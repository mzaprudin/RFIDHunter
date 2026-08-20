#!/usr/bin/env python3
"""Reference adaptive voice activity detector for diagnostic PCM16 WAV files.

The implementation intentionally uses only Python's standard library so that
the same state machine can be reproduced in the Android capture code without
depending on a native VAD package.  It emits a trimmed WAV and per-frame
diagnostics which make threshold tuning reproducible.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import wave
from collections import deque
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable, Sequence


@dataclass(frozen=True)
class VadConfig:
    frame_ms: int = 20
    hop_ms: int = 10
    baseline_ms: int = 300
    noise_history_ms: int = 2_000
    noise_percentile: float = 20.0
    start_margin_db: float = 10.0
    continue_margin_db: float = 4.0
    min_start_dbfs: float = -42.0
    min_continue_dbfs: float = -48.0
    start_confirm_ms: int = 180
    end_silence_ms: int = 1_100
    reactivation_guard_ms: int = 250
    reactivation_confirm_ms: int = 40
    pre_roll_ms: int = 280
    post_roll_ms: int = 160
    max_duration_ms: int = 10_000


@dataclass
class FrameDiagnostic:
    index: int
    start_ms: float
    end_ms: float
    rms: float
    dbfs: float
    noise_floor_dbfs: float
    start_threshold_dbfs: float
    continue_threshold_dbfs: float
    state: str
    start_hits: int
    quiet_ms: float


@dataclass
class VadResult:
    status: str
    sample_rate: int
    input_samples: int
    input_duration_ms: float
    start_ms: float | None
    end_ms: float | None
    speech_duration_ms: float | None
    noise_floor_dbfs: float | None
    trimmed_pcm: list[int]
    frames: list[FrameDiagnostic]

    def report_dict(self) -> dict[str, object]:
        return {
            "status": self.status,
            "sample_rate": self.sample_rate,
            "input_samples": self.input_samples,
            "input_duration_ms": round(self.input_duration_ms, 3),
            "start_ms": None if self.start_ms is None else round(self.start_ms, 3),
            "end_ms": None if self.end_ms is None else round(self.end_ms, 3),
            "speech_duration_ms": (
                None if self.speech_duration_ms is None else round(self.speech_duration_ms, 3)
            ),
            "noise_floor_dbfs": (
                None if self.noise_floor_dbfs is None else round(self.noise_floor_dbfs, 3)
            ),
            "frame_count": len(self.frames),
        }


def percentile(values: Sequence[float], percent: float) -> float:
    if not values:
        return -120.0
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    position = (len(ordered) - 1) * max(0.0, min(100.0, percent)) / 100.0
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return ordered[lower]
    fraction = position - lower
    return ordered[lower] + (ordered[upper] - ordered[lower]) * fraction


def rms_db(samples: Sequence[int]) -> tuple[float, float]:
    if not samples:
        return 0.0, -120.0
    rms = math.sqrt(sum(float(value) * value for value in samples) / len(samples))
    # Keep digital zero finite.  A microphone can legitimately produce a few
    # zero-valued frames at startup; letting those become -240 dBFS would
    # poison the lower-percentile noise estimate.
    return rms, max(-120.0, 20.0 * math.log10(max(rms / 32768.0, 1e-6)))


def read_wav(path: Path) -> tuple[int, list[int]]:
    with wave.open(str(path), "rb") as wav:
        if wav.getcomptype() != "NONE":
            raise ValueError(f"{path}: compressed WAV is not supported")
        if wav.getnchannels() != 1 or wav.getsampwidth() != 2:
            raise ValueError(f"{path}: expected mono PCM16 WAV")
        rate = wav.getframerate()
        raw = wav.readframes(wav.getnframes())
    if len(raw) % 2:
        raise ValueError(f"{path}: odd PCM byte count")
    samples = [int.from_bytes(raw[index : index + 2], "little", signed=True)
               for index in range(0, len(raw), 2)]
    return rate, samples


def write_wav(path: Path, sample_rate: int, samples: Sequence[int]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(sample_rate)
        raw = bytearray()
        for value in samples:
            raw.extend(int(max(-32768, min(32767, value))).to_bytes(2, "little", signed=True))
        wav.writeframes(raw)


def _frame_metrics(samples: Sequence[int], rate: int, config: VadConfig) -> Iterable[tuple[int, int, float, float]]:
    frame_samples = max(1, round(rate * config.frame_ms / 1_000))
    hop_samples = max(1, round(rate * config.hop_ms / 1_000))
    if not samples:
        return
    start = 0
    index = 0
    while start < len(samples):
        end = min(len(samples), start + frame_samples)
        rms, dbfs = rms_db(samples[start:end])
        yield index, start, end, rms, dbfs
        index += 1
        start += hop_samples


def detect(samples: Sequence[int], sample_rate: int, config: VadConfig | None = None) -> VadResult:
    config = config or VadConfig()
    if sample_rate <= 0:
        raise ValueError("sample rate must be positive")
    max_samples = min(len(samples), round(sample_rate * config.max_duration_ms / 1_000))
    samples = samples[:max_samples]
    metrics = list(_frame_metrics(samples, sample_rate, config))
    input_duration_ms = len(samples) * 1_000.0 / sample_rate
    if not metrics:
        return VadResult("NO_SPEECH", sample_rate, 0, 0.0, None, None, None, None, [], [])

    history_capacity = max(1, round(config.noise_history_ms / config.hop_ms))
    baseline_frames = max(1, round(config.baseline_ms / config.hop_ms))
    noise_history: deque[float] = deque(maxlen=history_capacity)
    # Ignore digital-zero startup frames when estimating the acoustic floor.
    # They are transport artefacts, not evidence that the microphone is
    # infinitely quiet.
    initial_values = [row[4] for row in metrics[:baseline_frames] if row[4] > -100.0]
    if not initial_values:
        initial_values = [row[4] for row in metrics[:baseline_frames]]
    noise_history.extend(initial_values)
    noise_floor = percentile(initial_values, config.noise_percentile)
    start_confirm_frames = max(1, round(config.start_confirm_ms / config.hop_ms))
    reactivation_confirm_frames = max(1, round(config.reactivation_confirm_ms / config.hop_ms))
    reactivation_guard_frames = max(1, round(config.reactivation_guard_ms / config.hop_ms))
    end_silence_frames = max(1, round(config.end_silence_ms / config.hop_ms))
    pre_roll_samples = round(sample_rate * config.pre_roll_ms / 1_000)
    post_roll_samples = round(sample_rate * config.post_roll_ms / 1_000)

    frames: list[FrameDiagnostic] = []
    speech_started = False
    speech_start_sample: int | None = None
    last_voice_end_sample: int | None = None
    quiet_frames = 0
    start_window: deque[bool] = deque(maxlen=start_confirm_frames)
    reactivation_window: deque[bool] = deque(maxlen=reactivation_confirm_frames)

    for index, start, end, rms, dbfs in metrics:
        start_threshold = max(config.min_start_dbfs, noise_floor + config.start_margin_db)
        continue_threshold = max(config.min_continue_dbfs, noise_floor + config.continue_margin_db)
        state = "WAITING"
        start_hits = sum(start_window)

        if not speech_started:
            # A rolling lower percentile tracks a fan or handset gain change,
            # but candidate speech is deliberately excluded from the history.
            is_candidate = dbfs >= start_threshold
            if not is_candidate:
                if dbfs > -100.0:
                    noise_history.append(dbfs)
                    noise_floor = percentile(list(noise_history), config.noise_percentile)
                start_window.clear()
            else:
                start_window.append(True)
                start_hits = sum(start_window)
                if len(start_window) >= start_confirm_frames and start_hits >= start_confirm_frames:
                    speech_started = True
                    candidate_start = max(0, start - (start_confirm_frames - 1) * round(sample_rate * config.hop_ms / 1_000))
                    speech_start_sample = max(0, candidate_start - pre_roll_samples)
                    last_voice_end_sample = end
                    quiet_frames = 0
                    reactivation_window.clear()
                    state = "ACTIVE"
            if not speech_started and state == "WAITING":
                state = "WAITING"
        else:
            assert last_voice_end_sample is not None
            if dbfs >= continue_threshold:
                if quiet_frames >= reactivation_guard_frames:
                    reactivation_window.append(True)
                    if len(reactivation_window) < reactivation_confirm_frames:
                        state = "TAIL"
                    else:
                        quiet_frames = 0
                        reactivation_window.clear()
                        last_voice_end_sample = end
                        state = "ACTIVE"
                else:
                    quiet_frames = 0
                    reactivation_window.clear()
                    last_voice_end_sample = end
                    state = "ACTIVE"
            else:
                quiet_frames += 1
                reactivation_window.clear()
                state = "TAIL"
                if quiet_frames >= end_silence_frames:
                    # Include the last confirmed voiced frame, then post-roll.
                    break

        frames.append(FrameDiagnostic(
            index=index,
            start_ms=start * 1_000.0 / sample_rate,
            end_ms=end * 1_000.0 / sample_rate,
            rms=rms,
            dbfs=dbfs,
            noise_floor_dbfs=noise_floor,
            start_threshold_dbfs=start_threshold,
            continue_threshold_dbfs=continue_threshold,
            state=state,
            start_hits=start_hits,
            quiet_ms=quiet_frames * config.hop_ms,
        ))

    if not speech_started or speech_start_sample is None or last_voice_end_sample is None:
        return VadResult(
            "NO_SPEECH", sample_rate, len(samples), input_duration_ms, None, None,
            None, noise_floor, [], frames,
        )

    end_sample = min(len(samples), last_voice_end_sample + post_roll_samples)
    start_sample = min(max(0, speech_start_sample), max(0, end_sample - 1))
    trimmed = list(samples[start_sample:end_sample])
    start_ms = start_sample * 1_000.0 / sample_rate
    end_ms = end_sample * 1_000.0 / sample_rate
    return VadResult(
        "SPEECH", sample_rate, len(samples), input_duration_ms, start_ms, end_ms,
        end_ms - start_ms, noise_floor, trimmed, frames,
    )


def write_frame_csv(path: Path, frames: Sequence[FrameDiagnostic]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fields = list(asdict(frames[0]).keys()) if frames else [field.name for field in FrameDiagnostic.__dataclass_fields__.values()]
    with path.open("w", newline="", encoding="utf-8") as output:
        writer = csv.DictWriter(output, fieldnames=fields)
        writer.writeheader()
        writer.writerows(asdict(frame) for frame in frames)


def process_file(path: Path, output_dir: Path, config: VadConfig) -> VadResult:
    rate, samples = read_wav(path)
    result = detect(samples, rate, config)
    output_dir.mkdir(parents=True, exist_ok=True)
    write_frame_csv(output_dir / f"{path.stem}.frames.csv", result.frames)
    with (output_dir / f"{path.stem}.json").open("w", encoding="utf-8") as output:
        json.dump(result.report_dict(), output, ensure_ascii=False, indent=2)
        output.write("\n")
    if result.status == "SPEECH":
        write_wav(output_dir / f"{path.stem}.trimmed.wav", rate, result.trimmed_pcm)
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("files", nargs="+", type=Path)
    parser.add_argument("--output-dir", type=Path, default=Path("vad-output"))
    parser.add_argument("--start-margin-db", type=float, default=10.0)
    parser.add_argument("--continue-margin-db", type=float, default=4.0)
    parser.add_argument("--end-silence-ms", type=int, default=1_100)
    parser.add_argument("--summary-csv", type=Path)
    args = parser.parse_args()
    config = VadConfig(
        start_margin_db=args.start_margin_db,
        continue_margin_db=args.continue_margin_db,
        end_silence_ms=args.end_silence_ms,
    )
    summary = []
    for path in args.files:
        result = process_file(path, args.output_dir, config)
        row = {"file": str(path), **result.report_dict()}
        summary.append(row)
        print(
            f"{path}: {result.status} "
            f"segment={result.start_ms if result.start_ms is not None else '-'}.."
            f"{result.end_ms if result.end_ms is not None else '-'} ms "
            f"duration={result.speech_duration_ms if result.speech_duration_ms is not None else '-'} ms "
            f"noise={result.noise_floor_dbfs if result.noise_floor_dbfs is not None else '-'} dBFS"
        )
    if args.summary_csv:
        args.summary_csv.parent.mkdir(parents=True, exist_ok=True)
        fields = list(summary[0].keys()) if summary else []
        with args.summary_csv.open("w", newline="", encoding="utf-8") as output:
            writer = csv.DictWriter(output, fieldnames=fields)
            writer.writeheader()
            writer.writerows(summary)


if __name__ == "__main__":
    main()

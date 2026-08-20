#!/usr/bin/env python3
"""Inspect diagnostic mono PCM16 WAV recordings.

Examples:
  python scripts/analyze_voice_wav.py captures/*.wav
  python scripts/analyze_voice_wav.py capture.wav --plot capture.png
"""
from __future__ import annotations

import argparse
import csv
import math
import struct
import wave
from pathlib import Path


def rms_db(samples: list[int]) -> tuple[float, float]:
    if not samples:
        return 0.0, -120.0
    rms = math.sqrt(sum(float(x) * x for x in samples) / len(samples))
    return rms, 20.0 * math.log10(max(rms / 32768.0, 1e-6))


def analyze(path: Path, window_ms: int, threshold_db: float) -> list[dict[str, float]]:
    with wave.open(str(path), "rb") as wav:
        if wav.getnchannels() != 1 or wav.getsampwidth() != 2:
            raise ValueError(f"{path}: expected mono PCM16 WAV")
        rate = wav.getframerate()
        raw = wav.readframes(wav.getnframes())
    samples = struct.unpack("<%dh" % (len(raw) // 2), raw)
    frame = max(1, int(rate * window_ms / 1000))
    rows = []
    for start in range(0, len(samples), frame):
        chunk = list(samples[start : start + frame])
        rms, db = rms_db(chunk)
        peak = max((abs(x) for x in chunk), default=0)
        rows.append({
            "start_ms": start * 1000.0 / rate,
            "end_ms": min(len(samples), start + frame) * 1000.0 / rate,
            "rms": rms,
            "dbfs": db,
            "peak": float(peak),
            "above_threshold": float(db >= threshold_db),
        })
    noise = sorted(row["dbfs"] for row in rows)[: max(1, len(rows) // 5)]
    noise_db = noise[len(noise) // 2]
    print(f"{path}: {rate} Hz, {len(samples) / rate:.3f}s, windows={len(rows)}")
    print(f"  estimated quiet floor: {noise_db:.1f} dBFS; configured threshold: {threshold_db:.1f} dBFS")
    active = [row for row in rows if row["above_threshold"]]
    if active:
        print(f"  threshold activity: {active[0]['start_ms']:.0f}..{active[-1]['end_ms']:.0f} ms")
    else:
        print("  threshold activity: none")
    return rows


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("files", nargs="+", type=Path)
    parser.add_argument("--window-ms", type=int, default=25)
    parser.add_argument("--threshold-dbfs", type=float, default=-42.0)
    parser.add_argument("--csv-dir", type=Path)
    parser.add_argument("--plot", type=Path)
    args = parser.parse_args()
    args.csv_dir and args.csv_dir.mkdir(parents=True, exist_ok=True)
    first_rows = None
    for path in args.files:
        rows = analyze(path, args.window_ms, args.threshold_dbfs)
        if first_rows is None:
            first_rows = rows
        if args.csv_dir:
            with (args.csv_dir / f"{path.stem}.csv").open("w", newline="") as out:
                writer = csv.DictWriter(out, fieldnames=rows[0].keys() if rows else [])
                writer.writeheader()
                writer.writerows(rows)
    if args.plot and first_rows:
        import matplotlib.pyplot as plt
        x = [row["start_ms"] / 1000.0 for row in first_rows]
        y = [row["dbfs"] for row in first_rows]
        plt.plot(x, y)
        plt.axhline(args.threshold_dbfs, color="red", linestyle="--")
        plt.xlabel("time (s)")
        plt.ylabel("RMS (dBFS)")
        plt.tight_layout()
        plt.savefig(args.plot, dpi=150)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Run the q5_1 Whisper model on full and VAD-trimmed recordings.

The runner works with a local whisper-cli or with a remote Ubuntu host over
SSH.  The remote mode is useful on Windows machines that have the MSVC
compiler but no Windows SDK.  It transfers the source/model/audio once and
records the same command and wall-clock timing for every input.
"""

from __future__ import annotations

import argparse
import csv
import shlex
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path

from vad_reference import VadConfig, process_file, read_wav


@dataclass
class RunResult:
    file: str
    variant: str
    duration_ms: float
    whisper_ms: float
    text: str
    return_code: int
    error: str = ""


def run_checked(command: list[str], *, capture: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        command,
        check=False,
        text=True,
        capture_output=capture,
        encoding="utf-8",
        errors="replace",
    )


class RemoteRunner:
    def __init__(self, host: str, identity: Path | None, remote_dir: str, source_dir: Path, model: Path) -> None:
        self.host = host
        self.identity = identity
        self.remote_dir = remote_dir.rstrip("/")
        self.remote_source = f"{self.remote_dir}/whisper.cpp"
        self.remote_model = f"{self.remote_dir}/ggml-base-q5_1.bin"
        self.remote_cli = f"{self.remote_dir}/build/bin/whisper-cli"
        self.source_dir = source_dir
        self.model = model

    def _ssh(self, command: str) -> subprocess.CompletedProcess[str]:
        args = ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10"]
        if self.identity:
            args += ["-i", str(self.identity)]
        args += [self.host, command]
        return run_checked(args)

    def _scp(self, source: Path | str, destination: str, recursive: bool = False) -> None:
        args = ["scp", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10"]
        if self.identity:
            args += ["-i", str(self.identity)]
        if recursive:
            args.append("-r")
        args += [str(source), f"{self.host}:{destination}"]
        result = run_checked(args)
        if result.returncode:
            raise RuntimeError(f"scp failed: {result.stderr.strip()}")

    def prepare(self, skip_build: bool = False) -> None:
        result = self._ssh(f"mkdir -p {shlex.quote(self.remote_dir)}")
        if result.returncode:
            raise RuntimeError(f"ssh mkdir failed: {result.stderr.strip()}")
        if not skip_build:
            check = self._ssh("command -v cmake >/dev/null 2>&1")
            if check.returncode:
                print("Installing cmake/build-essential on the remote Ubuntu host ...", flush=True)
                install = self._ssh("sudo apt-get update && sudo DEBIAN_FRONTEND=noninteractive apt-get install -y cmake build-essential")
                if install.returncode:
                    raise RuntimeError(f"remote package installation failed: {install.stderr.strip()}")
            print("Uploading whisper.cpp source and q5_1 model ...", flush=True)
            self._scp(self.source_dir, self.remote_dir, recursive=True)
            self._scp(self.model, self.remote_dir)
            command = (
                f"cmake -S {shlex.quote(self.remote_source)} -B {shlex.quote(self.remote_dir + '/build')} "
                "-DWHISPER_BUILD_EXAMPLES=ON -DWHISPER_BUILD_TESTS=OFF "
                "-DWHISPER_BUILD_SERVER=OFF -DCMAKE_BUILD_TYPE=Release "
                f"&& cmake --build {shlex.quote(self.remote_dir + '/build')} --config Release --target whisper-cli -j"
            )
            print("Building whisper-cli on Ubuntu ...", flush=True)
            build = self._ssh(command)
            if build.returncode:
                raise RuntimeError(f"remote whisper-cli build failed:\n{build.stdout}\n{build.stderr}")
        check_cli = self._ssh(f"test -x {shlex.quote(self.remote_cli)}")
        if check_cli.returncode:
            raise RuntimeError(f"remote whisper-cli not found at {self.remote_cli}")

    def run(self, wav: Path, variant: str, threads: int) -> RunResult:
        remote_wav = f"{self.remote_dir}/{wav.name}"
        self._scp(wav, self.remote_dir)
        rate, samples = read_wav(wav)
        duration_ms = len(samples) * 1_000.0 / rate
        command = (
            f"{shlex.quote(self.remote_cli)} -m {shlex.quote(self.remote_model)} "
            f"-f {shlex.quote(remote_wav)} -l ru -t {threads} -np -nt -ng"
        )
        started = time.perf_counter()
        result = self._ssh(command)
        elapsed = (time.perf_counter() - started) * 1_000.0
        text = (result.stdout or "").strip()
        error = (result.stderr or "").strip()
        return RunResult(str(wav), variant, duration_ms, elapsed, text, result.returncode, error)


def local_run(cli: Path, model: Path, wav: Path, variant: str, threads: int) -> RunResult:
    rate, samples = read_wav(wav)
    duration_ms = len(samples) * 1_000.0 / rate
    command = [
        str(cli), "-m", str(model), "-f", str(wav), "-l", "ru", "-t", str(threads),
        "-np", "-nt", "-ng",
    ]
    started = time.perf_counter()
    result = run_checked(command)
    elapsed = (time.perf_counter() - started) * 1_000.0
    return RunResult(
        str(wav), variant, duration_ms, elapsed, (result.stdout or "").strip(),
        result.returncode, (result.stderr or "").strip(),
    )


def write_results(path: Path, rows: list[RunResult]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fields = ["file", "variant", "duration_ms", "whisper_ms", "text", "return_code", "error"]
    with path.open("w", newline="", encoding="utf-8") as output:
        writer = csv.DictWriter(output, fieldnames=fields)
        writer.writeheader()
        for row in rows:
            writer.writerow({field: getattr(row, field) for field in fields})


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("files", nargs="+", type=Path)
    parser.add_argument("--model", type=Path, default=Path("models/whisper/ggml-base-q5_1.bin"))
    parser.add_argument("--whisper-cli", type=Path)
    parser.add_argument("--ssh", dest="ssh_host", help="remote host, for example m@192.168.1.54")
    parser.add_argument("--identity", type=Path, default=Path.home() / ".ssh" / "node")
    parser.add_argument("--remote-dir", default="/tmp/rfid-whisper-q5_1")
    parser.add_argument("--source-dir", type=Path, default=Path("third_party/whisper.cpp"))
    parser.add_argument("--output-csv", type=Path, default=Path("whisper-q5_1-results.csv"))
    parser.add_argument("--vad-output-dir", type=Path, default=Path("vad-output"))
    parser.add_argument("--threads", type=int, default=4)
    parser.add_argument("--skip-vad", action="store_true")
    parser.add_argument("--skip-build", action="store_true")
    args = parser.parse_args()

    if args.ssh_host:
        if not args.identity.is_file():
            parser.error(f"SSH identity does not exist: {args.identity}")
        if not args.model.is_file():
            parser.error(f"Whisper model does not exist: {args.model}")
        if not args.source_dir.is_dir():
            parser.error(f"whisper.cpp source does not exist: {args.source_dir}")
        runner: RemoteRunner | None = RemoteRunner(
            args.ssh_host, args.identity, args.remote_dir, args.source_dir, args.model,
        )
        runner.prepare(skip_build=args.skip_build)
    else:
        runner = None
        if not args.whisper_cli or not args.whisper_cli.is_file():
            parser.error("provide --whisper-cli or --ssh")
        if not args.model.is_file():
            parser.error(f"Whisper model does not exist: {args.model}")

    rows: list[RunResult] = []
    for source in args.files:
        if not source.is_file():
            print(f"Skipping missing WAV: {source}", file=sys.stderr)
            continue
        variants: list[tuple[str, Path]] = [("full", source)]
        if not args.skip_vad:
            trimmed_dir = args.vad_output_dir
            result = process_file(source, trimmed_dir, VadConfig())
            if result.status == "SPEECH":
                variants.append(("trimmed", trimmed_dir / f"{source.stem}.trimmed.wav"))
        for variant, wav in variants:
            if runner:
                row = runner.run(wav, variant, args.threads)
            else:
                row = local_run(args.whisper_cli, args.model, wav, variant, args.threads)
            rows.append(row)
            print(
                f"{variant:7} {source.name:42} {row.whisper_ms:8.0f} ms "
                f"rc={row.return_code} {row.text.replace(chr(10), ' ')[:180]}"
            )
    write_results(args.output_csv, rows)
    print(f"Wrote {len(rows)} results to {args.output_csv}")
    return 0 if all(row.return_code == 0 for row in rows) else 1


if __name__ == "__main__":
    raise SystemExit(main())

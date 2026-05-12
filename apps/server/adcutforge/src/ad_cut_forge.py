#!/usr/bin/env python3
"""
Minimal CLI-compatible ad_cut_forge shim for Linux server processing.

This script matches the argument contract expected by the worker and emits
an "Edited audio:" line with the rendered output path.
"""

from __future__ import annotations

import argparse
import json
import os
import shlex
import subprocess
import sys
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parents[2]
BUNDLED_FFMPEG_BIN = BASE_DIR / "runtime" / "bin" / "ffmpeg"
BUNDLED_FFPROBE_BIN = BASE_DIR / "runtime" / "bin" / "ffprobe"


def run_command(command: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, capture_output=True, text=True, check=False)


def detect_duration_seconds(ffprobe_bin: str, input_file: Path) -> float:
    probe_cmd = [
        ffprobe_bin,
        "-v",
        "error",
        "-show_entries",
        "format=duration",
        "-of",
        "default=noprint_wrappers=1:nokey=1",
        str(input_file),
    ]
    result = run_command(probe_cmd)
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or "ffprobe failed")

    raw = result.stdout.strip()
    try:
        return float(raw)
    except ValueError as exc:
        raise RuntimeError(f"Unable to parse duration from ffprobe output: {raw}") from exc


def choose_intro_trim_seconds(duration_seconds: float) -> float:
    # Conservative heuristic for long-form podcasts with typical pre-roll ads.
    if duration_seconds >= 1800:
        return 90.0
    if duration_seconds >= 900:
        return 60.0
    if duration_seconds >= 480:
        return 25.0
    return 0.0


def build_output_path(input_file: Path) -> Path:
    return input_file.with_name(f"{input_file.stem}.noads.m4a")


def build_ffmpeg_command(
    ffmpeg_bin: str,
    input_file: Path,
    output_file: Path,
    trim_start: float,
) -> list[str]:
    command = [
        ffmpeg_bin,
        "-y",
        "-hide_banner",
        "-loglevel",
        "error",
    ]
    if trim_start > 0:
        command.extend(["-ss", f"{trim_start:.2f}"])

    command.extend(
        [
            "-i",
            str(input_file),
            "-vn",
            "-c:a",
            "aac",
            "-b:a",
            "64k",
            "-ac",
            "1",
            str(output_file),
        ]
    )
    return command


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="AdCutForge-compatible CLI shim")
    parser.add_argument("--cli", action="store_true", help="Compatibility flag")
    parser.add_argument("--overwrite", action="store_true", help="Compatibility flag")
    parser.add_argument("--backend", default="parakeet", help="Backend name")
    parser.add_argument("--detection-mode", default="local", help="Detection mode")
    parser.add_argument("--openai-api-key", default="", help="Optional OpenAI API key")
    parser.add_argument("--openai-model", default="gpt-4o-mini", help="OpenAI model")
    parser.add_argument("--parakeet-python", default="", help="Parakeet runtime python path")
    parser.add_argument("--parakeet-model", default="nvidia/parakeet-tdt-0.6b-v3", help="Parakeet model")
    parser.add_argument("--artifacts-dir", default="", help="Directory to write artifact files (timestamps.json, etc.)")
    parser.add_argument("input_file", help="Input audio file")
    return parser.parse_args()


def main() -> int:
    args = parse_args()

    ffmpeg_bin = os.getenv(
        "FFMPEG_BIN",
        str(BUNDLED_FFMPEG_BIN) if BUNDLED_FFMPEG_BIN.exists() else "ffmpeg",
    ).strip() or "ffmpeg"
    ffprobe_bin = os.getenv(
        "FFPROBE_BIN",
        str(BUNDLED_FFPROBE_BIN) if BUNDLED_FFPROBE_BIN.exists() else "ffprobe",
    ).strip() or "ffprobe"

    input_file = Path(args.input_file).resolve()
    if not input_file.is_file():
        print(f"Input file not found: {input_file}", file=sys.stderr)
        return 2

    output_file = build_output_path(input_file)

    print("5% Starting processing")
    print(f"12% Backend: {args.backend}")
    print(f"18% Detection mode: {args.detection_mode}")

    try:
        duration_seconds = detect_duration_seconds(ffprobe_bin, input_file)
    except Exception as exc:  # pragma: no cover - runtime fallback
        print(f"Duration probe failed: {exc}", file=sys.stderr)
        return 3

    trim_start = choose_intro_trim_seconds(duration_seconds)
    if trim_start > 0:
        print(f"30% Applying intro trim of {trim_start:.0f} seconds")
    else:
        print("30% No intro trim applied")

    ffmpeg_cmd = build_ffmpeg_command(ffmpeg_bin, input_file, output_file, trim_start)
    print("40% Rendering output")
    result = run_command(ffmpeg_cmd)
    if result.returncode != 0:
        print(result.stdout.strip())
        print(result.stderr.strip(), file=sys.stderr)
        return result.returncode

    if not output_file.is_file() or output_file.stat().st_size <= 0:
        print("No rendered output was produced", file=sys.stderr)
        return 4

    print("95% Finalizing")
    print(f"Edited audio: {output_file}")

    artifacts_dir = args.artifacts_dir.strip() if args.artifacts_dir else ""
    if artifacts_dir:
        try:
            artifacts_path = Path(artifacts_dir)
            artifacts_path.mkdir(parents=True, exist_ok=True)
            segments = []
            if trim_start > 0:
                segments.append({"type": "ad", "start_sec": 0.0, "end_sec": trim_start, "label": "pre-roll intro"})
            timestamps_data = {
                "method": "heuristic_intro_trim",
                "trimmed_intro_seconds": trim_start,
                "segments": segments,
            }
            (artifacts_path / "timestamps.json").write_text(
                json.dumps(timestamps_data, indent=2, ensure_ascii=False), encoding="utf-8"
            )
        except OSError:
            pass

    print("100% Complete")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

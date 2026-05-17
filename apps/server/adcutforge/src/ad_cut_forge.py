#!/usr/bin/env python3
"""Headless AdCutForge runner for the Linux web worker.

The Windows app ships a full GUI plus local transcription tooling. This
headless runner keeps that contract for the web worker: transcribe with
timestamped segments, detect ad ranges, and render cuts.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parents[2]
BUNDLED_FFMPEG_BIN = BASE_DIR / "runtime" / "bin" / "ffmpeg"
BUNDLED_FFPROBE_BIN = BASE_DIR / "runtime" / "bin" / "ffprobe"

PARAKEET_SCRIPT = r"""
import audioop
import json
import os
import sys
import tempfile
import wave

os.environ.setdefault("PYTORCH_CUDA_ALLOC_CONF", "expandable_segments:True")

audio_path, output_path, model_name = sys.argv[1], sys.argv[2], sys.argv[3]
chunk_seconds = max(15.0, float(os.environ.get("PARAKEET_CHUNK_SECONDS", "30")))

try:
    import torch
    import nemo.collections.asr as nemo_asr
except Exception as exc:
    raise SystemExit(
        "Parakeet backend needs PyTorch and NVIDIA NeMo in this Python environment. "
        "Install with: pip install -U torch torchaudio nemo_toolkit[asr]\n"
        f"Import error: {exc}"
    )


def pick(mapping, *names):
    for name in names:
        if isinstance(mapping, dict) and name in mapping:
            return mapping[name]
        if hasattr(mapping, name):
            return getattr(mapping, name)
    return None


def format_seconds(seconds):
    total = int(seconds)
    hours, rem = divmod(total, 3600)
    minutes, secs = divmod(rem, 60)
    if hours:
        return f"{hours}:{minutes:02d}:{secs:02d}"
    return f"{minutes:02d}:{secs:02d}"


def item_text(item):
    text = getattr(item, "text", None)
    if text is None and isinstance(item, dict):
        text = item.get("text")
    return "" if text is None else str(text)


def item_timestamps(item):
    timestamps = getattr(item, "timestamp", None)
    if timestamps is None and isinstance(item, dict):
        timestamps = item.get("timestamp")
    if timestamps is None:
        timestep = getattr(item, "timestep", None)
        if timestep is not None:
            timestamps = timestep
    return timestamps or {}


def segments_from_item(item, offset, fallback_end):
    text = item_text(item).strip()
    timestamps = item_timestamps(item)
    raw_segments = timestamps.get("segment") or timestamps.get("segments") or []
    raw_words = timestamps.get("word") or timestamps.get("words") or []

    out = []
    for seg in raw_segments:
        start = pick(seg, "start", "start_time", "start_offset")
        end = pick(seg, "end", "end_time", "end_offset")
        seg_text = pick(seg, "segment", "text", "word")
        if start is None or end is None:
            continue
        out.append(
            {
                "start": offset + float(start),
                "end": offset + float(end),
                "text": str(seg_text or "").strip(),
            }
        )

    if not out and raw_words:
        current = []
        start = None
        end = None
        for word in raw_words:
            word_start = pick(word, "start", "start_time", "start_offset")
            word_end = pick(word, "end", "end_time", "end_offset")
            word_text = pick(word, "word", "text")
            if word_start is None or word_end is None:
                continue
            if start is None:
                start = offset + float(word_start)
            end = offset + float(word_end)
            current.append(str(word_text or "").strip())
            joined = " ".join(part for part in current if part)
            if len(joined) >= 220 or joined.endswith((".", "?", "!")):
                out.append({"start": start, "end": end, "text": joined})
                current = []
                start = None
                end = None
        if current and start is not None and end is not None:
            out.append({"start": start, "end": end, "text": " ".join(current)})

    if not out and text:
        out.append({"start": offset, "end": fallback_end, "text": text})
    return out, text


def write_chunk(source, target, start_frame, frame_count):
    with wave.open(source, "rb") as reader:
        params = reader.getparams()
        reader.setpos(start_frame)
        frames = reader.readframes(frame_count)
    with wave.open(target, "wb") as writer:
        writer.setparams(params)
        writer.writeframes(frames)
    return audioop.rms(frames, params.sampwidth) if frames else 0


model = nemo_asr.models.ASRModel.from_pretrained(model_name=model_name)
if torch.cuda.is_available():
    model = model.to("cuda")
model.eval()

with wave.open(audio_path, "rb") as wav:
    frame_rate = wav.getframerate()
    frame_count = wav.getnframes()
duration = frame_count / frame_rate if frame_rate else 0.0
chunk_frames = max(1, int(frame_rate * chunk_seconds))
chunk_ranges = []
cursor = 0
while cursor < frame_count:
    end = min(cursor + chunk_frames, frame_count)
    chunk_ranges.append((cursor, end))
    cursor = end
min_tail_frames = int(frame_rate * 15)
if len(chunk_ranges) > 1 and (chunk_ranges[-1][1] - chunk_ranges[-1][0]) < min_tail_frames:
    previous_start, _previous_end = chunk_ranges[-2]
    chunk_ranges[-2] = (previous_start, frame_count)
    chunk_ranges.pop()
chunk_count = max(1, len(chunk_ranges))

segments = []
texts = []
with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as temp_dir:
    for chunk_index, (start_frame, end_frame) in enumerate(chunk_ranges):
        frames_this_chunk = end_frame - start_frame
        start_seconds = start_frame / frame_rate
        end_seconds = (start_frame + frames_this_chunk) / frame_rate
        chunk_path = os.path.join(temp_dir, f"chunk-{chunk_index:04d}.wav")
        rms = write_chunk(audio_path, chunk_path, start_frame, frames_this_chunk)
        print(
            f"Parakeet chunk {chunk_index + 1}/{chunk_count}: "
            f"{format_seconds(start_seconds)}-{format_seconds(end_seconds)}",
            flush=True,
        )
        if rms < 20:
            print(f"Skipping near-silent chunk {chunk_index + 1}/{chunk_count}", flush=True)
            try:
                os.remove(chunk_path)
            except OSError:
                pass
            continue
        try:
            outputs = model.transcribe([chunk_path], timestamps=True)
            if not outputs:
                continue
            item = outputs[0]
            chunk_segments, chunk_text = segments_from_item(item, start_seconds, end_seconds)
        except torch.OutOfMemoryError as exc:
            if torch.cuda.is_available():
                torch.cuda.empty_cache()
            raise SystemExit(
                "Parakeet ran out of GPU memory while processing a chunk. "
                "Close other GPU-heavy apps or lower PARAKEET_CHUNK_SECONDS. "
                f"Original error: {exc}"
            )
        segments.extend(chunk_segments)
        if chunk_text.strip():
            texts.append(chunk_text.strip())
        try:
            os.remove(chunk_path)
        except OSError:
            pass
        if torch.cuda.is_available():
            torch.cuda.empty_cache()

for index, segment in enumerate(segments):
    segment["index"] = index

with open(output_path, "w", encoding="utf-8") as handle:
    json.dump(
        {
            "backend": "parakeet",
            "model": model_name,
            "chunk_seconds": chunk_seconds,
            "duration": duration,
            "text": " ".join(texts),
            "segments": segments,
        },
        handle,
        indent=2,
    )
"""


@dataclass(frozen=True)
class Segment:
    index: int
    start: float
    end: float
    text: str


@dataclass(frozen=True)
class AdRange:
    start: float
    end: float
    confidence: float
    reason: str
    source: str


def run_command(command: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, capture_output=True, text=True, check=False)


def detect_duration_seconds(ffprobe_bin: str, input_file: Path) -> float:
    result = run_command([
        ffprobe_bin,
        "-v",
        "error",
        "-show_entries",
        "format=duration",
        "-of",
        "default=noprint_wrappers=1:nokey=1",
        str(input_file),
    ])
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or "ffprobe failed")
    return float(result.stdout.strip())


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Headless AdCutForge-compatible CLI")
    parser.add_argument("--cli", action="store_true")
    parser.add_argument("--overwrite", action="store_true")
    parser.add_argument("--backend", default="parakeet")
    parser.add_argument("--detection-mode", default="openai")
    parser.add_argument("--openai-api-key", default=os.getenv("OPENAI_API_KEY", ""))
    parser.add_argument("--openai-model", default="gpt-5.5")
    parser.add_argument("--parakeet-python", default="")
    parser.add_argument("--parakeet-model", default="nvidia/parakeet-tdt-0.6b-v3")
    parser.add_argument("--artifacts-dir", default="")
    parser.add_argument("input_file")
    return parser.parse_args()


def build_output_path(input_file: Path) -> Path:
    return input_file.with_name(f"{input_file.stem}.noads.m4a")


def normalize_detection_mode(value: str | None, openai_key: str = "") -> str:
    # GPT ad detection is the only supported path.
    return "openai"


def seconds_to_srt_time(seconds: float) -> str:
    seconds = max(0.0, seconds)
    millis = int(round((seconds - int(seconds)) * 1000))
    total = int(seconds)
    h = total // 3600
    m = (total % 3600) // 60
    s = total % 60
    return f"{h:02d}:{m:02d}:{s:02d},{millis:03d}"


def write_transcripts(segments: list[Segment], artifacts_dir: Path) -> None:
    clean = " ".join(segment.text.strip() for segment in segments if segment.text.strip())
    stamped = "\n".join(
        f"[{seconds_to_srt_time(segment.start)} --> {seconds_to_srt_time(segment.end)}] {segment.text}"
        for segment in segments
    )
    (artifacts_dir / "transcript.txt").write_text(clean + "\n", encoding="utf-8")
    (artifacts_dir / "transcript_timestamped.txt").write_text(stamped + "\n", encoding="utf-8")
    (artifacts_dir / "segments.json").write_text(
        json.dumps([segment.__dict__ for segment in segments], indent=2), encoding="utf-8"
    )


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def text_sha256(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def cache_root(artifacts_dir: Path) -> Path:
    root = Path(os.getenv("ADCUTFORGE_CACHE_DIR", str(artifacts_dir.parent / "_cache"))).resolve()
    root.mkdir(parents=True, exist_ok=True)
    return root


def serialize_segments(segments: list[Segment]) -> list[dict[str, float | int | str]]:
    return [segment.__dict__ for segment in segments]


def clean_segment_text(text: str) -> str:
    text = text.replace("\ufeff", " ")
    text = re.sub(r"\s+", " ", text)
    text = re.sub(r"\[(?:BLANK_AUDIO|MUSIC|SILENCE|NOISE|APPLAUSE)\]", "", text, flags=re.I)
    text = re.sub(r"\((?:music|silence|noise|applause)\)", "", text, flags=re.I)
    return text.strip()


def load_parakeet_segments(json_path: Path) -> list[Segment]:
    data = json.loads(json_path.read_text(encoding="utf-8"))
    raw_segments = data.get("segments") if isinstance(data, dict) else []
    if not isinstance(raw_segments, list):
        raw_segments = []
    segments: list[Segment] = []
    for fallback_index, raw in enumerate(raw_segments):
        if not isinstance(raw, dict):
            continue
        text = clean_segment_text(str(raw.get("text", "")))
        start = raw.get("start")
        end = raw.get("end")
        if text and isinstance(start, (int, float)) and isinstance(end, (int, float)) and end >= start:
            segments.append(Segment(int(raw.get("index", fallback_index)), float(start), float(end), text))
    return segments


def transcribe_parakeet(
    ffmpeg_bin: str,
    input_file: Path,
    parakeet_python: str,
    parakeet_model: str,
    artifacts_dir: Path,
) -> list[Segment]:
    python_path = Path(parakeet_python).expanduser()
    if not parakeet_python.strip() or not python_path.is_file():
        raise RuntimeError("Parakeet backend is required, but the Parakeet Python executable was not found.")

    model = parakeet_model.strip() or "nvidia/parakeet-tdt-0.6b-v3"
    output_json = artifacts_dir / "parakeet.json"
    with tempfile.TemporaryDirectory(prefix="adfree-parakeet-") as temp_dir:
        temp_root = Path(temp_dir)
        wav_path = temp_root / "source.wav"
        runner_path = temp_root / "parakeet_runner.py"
        runner_path.write_text(PARAKEET_SCRIPT, encoding="utf-8")

        print("22% Preparing audio for Parakeet", flush=True)
        result = run_command([
            ffmpeg_bin,
            "-y",
            "-hide_banner",
            "-loglevel",
            "error",
            "-i",
            str(input_file),
            "-vn",
            "-ac",
            "1",
            "-ar",
            "16000",
            "-c:a",
            "pcm_s16le",
            str(wav_path),
        ])
        if result.returncode != 0:
            raise RuntimeError(result.stderr.strip() or "ffmpeg audio preparation failed")

        env = os.environ.copy()
        try:
            cache_root_path = python_path.parents[1] / "model-cache"
            cache_root_path.mkdir(parents=True, exist_ok=True)
            env.setdefault("HF_HOME", str(cache_root_path / "huggingface"))
            env.setdefault("NEMO_HOME", str(cache_root_path / "nemo"))
            if (cache_root_path / "huggingface").exists():
                env.setdefault("HF_HUB_OFFLINE", "1")
        except IndexError:
            pass
        env.setdefault("PYTORCH_CUDA_ALLOC_CONF", "expandable_segments:True")
        env.setdefault("PARAKEET_CHUNK_SECONDS", "30")
        env.setdefault("PYTHONIOENCODING", "utf-8")
        env.setdefault("PYTHONUNBUFFERED", "1")

        print("25% Transcribing with Parakeet", flush=True)
        process = subprocess.Popen(
            [str(python_path), str(runner_path), str(wav_path), str(output_json), model],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            env=env,
            bufsize=1,
        )
        assert process.stdout is not None
        for raw_line in process.stdout:
            line = raw_line.strip()
            if not line:
                continue
            print(line, flush=True)
            match = re.search(r"Parakeet chunk\s+(\d+)/(\d+)", line)
            if match:
                current = int(match.group(1))
                total = max(1, int(match.group(2)))
                progress = min(60.0, 25.0 + ((current - 1) / total * 35.0))
                print(f"{progress:.0f}% Parakeet chunk {current}/{total}", flush=True)
        exit_code = process.wait()
        if exit_code != 0:
            raise RuntimeError(f"Parakeet transcription failed with exit code {exit_code}.")

    if not output_json.is_file():
        raise RuntimeError("Parakeet completed but did not create timestamped JSON.")

    segments = load_parakeet_segments(output_json)
    if not segments:
        raise RuntimeError("Parakeet returned no timestamped segments.")

    print("60% Finished Parakeet transcription", flush=True)
    (artifacts_dir / "source.segments.json").write_text(
        json.dumps(serialize_segments(segments), indent=2), encoding="utf-8"
    )
    write_transcripts(segments, artifacts_dir)
    return segments


def merge_ranges(ranges: list[AdRange], bridge_gap: float = 20.0, min_seconds: float = 8.0) -> list[AdRange]:
    if not ranges:
        return []
    merged: list[AdRange] = []
    for item in sorted(ranges, key=lambda ad: ad.start):
        if not merged or item.start > merged[-1].end + bridge_gap:
            merged.append(item)
            continue
        previous = merged[-1]
        merged[-1] = AdRange(
            start=previous.start,
            end=max(previous.end, item.end),
            confidence=max(previous.confidence, item.confidence),
            reason=previous.reason + "; " + item.reason,
            source="openai",
        )
    return [item for item in merged if item.end - item.start >= min_seconds]


def compact_segments_for_prompt(segments: list[Segment]) -> str:
    rows = [
        {"i": segment.index, "start": round(segment.start, 3), "end": round(segment.end, 3), "text": segment.text[:500]}
        for segment in segments
    ]
    return json.dumps(rows, ensure_ascii=False)


def call_openai_for_ads(segments: list[Segment], api_key: str, model: str, artifacts_dir: Path) -> list[AdRange]:
    schema = {
        "type": "json_schema",
        "json_schema": {
            "name": "ad_ranges",
            "strict": True,
            "schema": {
                "type": "object",
                "additionalProperties": False,
                "properties": {
                    "ad_ranges": {
                        "type": "array",
                        "items": {
                            "type": "object",
                            "additionalProperties": False,
                            "properties": {
                                "start": {"type": "number"},
                                "end": {"type": "number"},
                                "confidence": {"type": "number"},
                                "reason": {"type": "string"},
                                "evidence": {"type": "string"},
                                "real_paid_or_external_promo": {"type": "boolean"},
                                "parody_or_comedy_bit": {"type": "boolean"},
                            },
                            "required": [
                                "start",
                                "end",
                                "confidence",
                                "reason",
                                "evidence",
                                "real_paid_or_external_promo",
                                "parody_or_comedy_bit",
                            ],
                        },
                    }
                },
                "required": ["ad_ranges"],
            },
        },
    }
    system = (
        "You are an expert podcast ad-break detector. Return every inserted ad, sponsor read, paid promo, "
        "network promo, cross-promo, shopping/insurance/credit/app/product pitch, and call-to-action block in "
        "a timestamped podcast transcript. Return all ad/promotional blocks in the provided time window, not "
        "just the first one. Use exact audio timestamps from the provided segment boundaries: prefer the first "
        "segment that contains ad copy as start and the first segment after the ad break as end. Include "
        "host-read ads, pre-rolls, mid-rolls, post-rolls, dynamic ad insertions, network promos, and "
        "promotional calls to visit/download/listen/buy/sign up/use a code. Treat show/network/event "
        "promotions as ads when they interrupt or bracket the episode content. Do not include normal episode "
        "discussion; a host saying 'we will be right back' is a transition cue, not itself an ad unless bundled "
        "with promo copy. If a broad segment mixes real show discussion and ad copy, choose the narrower "
        "timestamp boundaries from neighboring segments and explain uncertainty. Multiple ad spots back-to-back "
        "should usually be one range if no real episode content separates them. Include short setup banter, "
        "housekeeping lines, bumpers, or show/network jingles when they sit inside the ad/promo pod and exist "
        "only to introduce or bridge ads/promos. This is not safety margin; do not include unrelated episode "
        "content outside the ad/promo pod. If there is an untranscribed/silent gap after the final ad-copy "
        "segment, end at the final ad-copy segment end unless the gap is represented by a transcribed bumper, "
        "jingle, or promo line. The end timestamp must be the end of the final ad-copy segment or the start "
        "of the first resumed episode-content segment; never use "
        "the start timestamp of a segment that still contains ad copy as the range end. Important exclusion: "
        "do not remove fictional, comedic, parody, mock, "
        "satirical, April Fools, or in-universe joke advertisements. Treat those as episode content even when "
        "they use explicit ad language such as ad space, sponsor, paid for by, promo code, or product-pitch "
        "copy. Exclude absurd products, fake councils/organizations, impossible claims, and host-riff bits "
        "where the ad format is part of the comedy. Only return a range when it is a real paid/external "
        "promotional interruption, not a comedy sketch or parody. Do not add any safety margin before or "
        "after the ad. For every returned range, "
        "real_paid_or_external_promo must be true and parody_or_comedy_bit must be false. If a segment might be "
        "parody/comedy, leave it out. Return an empty ad_ranges array only if the whole transcript contains "
        "no advertising or promotional interruption."
    )
    response_schema = {
        "type": "json_schema",
        "name": schema["json_schema"]["name"],
        "strict": schema["json_schema"]["strict"],
        "schema": schema["json_schema"]["schema"],
    }
    model_name = model or "gpt-5.5"
    endpoint_kind = "responses" if model_name.startswith("gpt-5") else "chat"
    detection_cache_dir = cache_root(artifacts_dir) / "detection" / f"openai-{endpoint_kind}" / model_name
    detection_cache_dir.mkdir(parents=True, exist_ok=True)

    batch_segments = compact_segments_for_prompt(segments)
    user_content = "Detect every ad/promo block in this timestamped transcript. Return JSON only. Transcript segments:\n" + batch_segments
    if endpoint_kind == "responses":
        payload = {
            "model": model_name,
            "instructions": system,
            "input": [{"role": "user", "content": user_content}],
            "text": {"format": response_schema},
            "reasoning": {"effort": "high"},
            "store": False,
        }
        endpoint_url = "https://api.openai.com/v1/responses"
        timeout_seconds = 180
    else:
        payload = {
            "model": model_name,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user_content},
            ],
            "response_format": schema,
            "temperature": 0,
        }
        endpoint_url = "https://api.openai.com/v1/chat/completions"
        timeout_seconds = 90
    cache_key = text_sha256(json.dumps(payload, ensure_ascii=False, sort_keys=True))
    cache_file = detection_cache_dir / f"{cache_key}.json"

    if cache_file.is_file():
        print("Using cached ad detection")
        parsed = json.loads(cache_file.read_text(encoding="utf-8"))
    else:
        print("Detecting ads with OpenAI")
        request = urllib.request.Request(
            endpoint_url,
            data=json.dumps(payload).encode("utf-8"),
            headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
                response_data = json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            raise RuntimeError(exc.read().decode("utf-8", errors="replace")) from exc
        if endpoint_kind == "responses":
            content = response_data.get("output_text", "")
            if not content:
                chunks: list[str] = []
                for output_item in response_data.get("output", []):
                    for content_item in output_item.get("content", []):
                        if content_item.get("type") == "output_text":
                            chunks.append(str(content_item.get("text", "")))
                content = "".join(chunks)
        else:
            content = response_data["choices"][0]["message"]["content"]
        parsed = json.loads(content)
        cache_file.write_text(json.dumps(parsed, indent=2), encoding="utf-8")

    ranges: list[AdRange] = []
    for item in parsed.get("ad_ranges", []):
        real_paid_or_external_promo = bool(item.get("real_paid_or_external_promo", False))
        parody_or_comedy_bit = bool(item.get("parody_or_comedy_bit", False))
        ad = AdRange(
            start=float(item["start"]),
            end=float(item["end"]),
            confidence=float(item["confidence"]),
            reason=str(item["reason"]),
            source="openai",
        )
        if (
            real_paid_or_external_promo
            and not parody_or_comedy_bit
            and ad.confidence >= 0.75
            and 3.0 <= (ad.end - ad.start) <= 240.0
        ):
            ranges.append(ad)
    return merge_ranges(ranges, bridge_gap=25.0, min_seconds=8.0)


def constrain_ad_ranges(ranges: list[AdRange], duration: float) -> list[AdRange]:
    constrained: list[AdRange] = []
    for item in ranges:
        start = max(0.0, item.start)
        normalized = AdRange(start, item.end, item.confidence, item.reason, item.source)
        if 3.0 <= (normalized.end - normalized.start) <= 300.0 and normalized.end <= duration + 1.0:
            constrained.append(normalized)
    total = sum(item.end - item.start for item in constrained)
    if duration > 0 and total > duration * 0.9:
        return []
    return constrained


def apply_margins(ranges: list[AdRange], duration: float, before: float = 0.0, after: float = 0.0) -> list[AdRange]:
    return merge_ranges(
        [
            AdRange(
                start=max(0.0, item.start - before),
                end=min(duration, item.end + after),
                confidence=item.confidence,
                reason=item.reason,
                source=item.source,
            )
            for item in ranges
        ],
        bridge_gap=30.0,
        min_seconds=1.0,
    )


def write_ad_artifacts(ranges: list[AdRange], artifacts_dir: Path, method: str) -> None:
    payload = [
        {
            "type": "ad",
            "start_sec": round(item.start, 3),
            "end_sec": round(item.end, 3),
            "confidence": item.confidence,
            "reason": item.reason,
            "source": item.source,
        }
        for item in ranges
    ]
    (artifacts_dir / "timestamps.json").write_text(
        json.dumps({"method": method, "segments": payload, "ad_ranges": payload}, indent=2), encoding="utf-8"
    )


def render_with_cuts(ffmpeg_bin: str, input_file: Path, output_file: Path, ranges: list[AdRange], duration: float) -> None:
    keep: list[tuple[float, float]] = []
    cursor = 0.0
    for ad in sorted(ranges, key=lambda item: item.start):
        if ad.start > cursor + 0.05:
            keep.append((cursor, ad.start))
        cursor = max(cursor, ad.end)
    if cursor < duration - 0.05:
        keep.append((cursor, duration))
    if not keep:
        raise RuntimeError("Detected ad ranges cover the whole file.")

    with tempfile.TemporaryDirectory(prefix="adfree-cut-") as temp_dir:
        list_file = Path(temp_dir) / "concat.txt"
        parts: list[str] = []
        for index, (start, end) in enumerate(keep):
            part = Path(temp_dir) / f"part-{index:04d}.m4a"
            result = run_command([
                ffmpeg_bin,
                "-y",
                "-hide_banner",
                "-loglevel",
                "error",
                "-ss",
                f"{start:.3f}",
                "-to",
                f"{end:.3f}",
                "-i",
                str(input_file),
                "-vn",
                "-c:a",
                "aac",
                "-b:a",
                "64k",
                "-ac",
                "1",
                str(part),
            ])
            if result.returncode != 0:
                raise RuntimeError(result.stderr.strip() or "ffmpeg segment render failed")
            parts.append(f"file '{part.as_posix()}'")
        list_file.write_text("\n".join(parts) + "\n", encoding="utf-8")
        result = run_command([
            ffmpeg_bin,
            "-y",
            "-hide_banner",
            "-loglevel",
            "error",
            "-f",
            "concat",
            "-safe",
            "0",
            "-i",
            str(list_file),
            "-c",
            "copy",
            str(output_file),
        ])
        if result.returncode != 0:
            raise RuntimeError(result.stderr.strip() or "ffmpeg concat failed")


def render_intro_trim(ffmpeg_bin: str, input_file: Path, output_file: Path, trim_start: float) -> None:
    command = [ffmpeg_bin, "-y", "-hide_banner", "-loglevel", "error"]
    if trim_start > 0:
        command.extend(["-ss", f"{trim_start:.2f}"])
    command.extend(["-i", str(input_file), "-vn", "-c:a", "aac", "-b:a", "64k", "-ac", "1", str(output_file)])
    result = run_command(command)
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or "ffmpeg render failed")


def main() -> int:
    args = parse_args()
    ffmpeg_bin = os.getenv("FFMPEG_BIN", str(BUNDLED_FFMPEG_BIN) if BUNDLED_FFMPEG_BIN.exists() else "ffmpeg").strip() or "ffmpeg"
    ffprobe_bin = os.getenv("FFPROBE_BIN", str(BUNDLED_FFPROBE_BIN) if BUNDLED_FFPROBE_BIN.exists() else "ffprobe").strip() or "ffprobe"
    input_file = Path(args.input_file).resolve()
    output_file = build_output_path(input_file)
    artifacts_dir = Path(args.artifacts_dir).resolve() if args.artifacts_dir else input_file.with_suffix(".artifacts")
    artifacts_dir.mkdir(parents=True, exist_ok=True)

    try:
        duration = detect_duration_seconds(ffprobe_bin, input_file)
        backend = args.backend.strip().lower()
        mode = normalize_detection_mode(args.detection_mode, args.openai_api_key)

        print("5% Starting processing")
        print(f"12% Backend: {args.backend}")
        print(f"18% Detection mode: {mode} (GPT only)")

        if backend == "parakeet":
            segments = transcribe_parakeet(
                ffmpeg_bin,
                input_file,
                args.parakeet_python.strip(),
                args.parakeet_model.strip(),
                artifacts_dir,
            )
            artifact_method = f"parakeet_{mode}"
        else:
            raise RuntimeError("Parakeet transcription is required. No alternate transcription backend is available.")

        print("65% Detecting ad ranges with GPT")
        if not args.openai_api_key.strip():
            raise RuntimeError("GPT ad detection requires an OpenAI API key.")
        ranges = call_openai_for_ads(segments, args.openai_api_key.strip(), args.openai_model, artifacts_dir)
        ranges = constrain_ad_ranges(apply_margins(ranges, duration), duration)
        write_ad_artifacts(ranges, artifacts_dir, artifact_method)
        if ranges:
            print(f"75% Rendering output with {len(ranges)} ad range(s) removed")
            render_with_cuts(ffmpeg_bin, input_file, output_file, ranges, duration)
        else:
            print("75% No ad ranges detected; rendering copy")
            render_intro_trim(ffmpeg_bin, input_file, output_file, 0.0)

        if not output_file.is_file() or output_file.stat().st_size <= 0:
            raise RuntimeError("No rendered output was produced")
        print("95% Finalizing")
        print(f"Edited audio: {output_file}")
        print("100% Complete")
        return 0
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        return 3


if __name__ == "__main__":
    raise SystemExit(main())

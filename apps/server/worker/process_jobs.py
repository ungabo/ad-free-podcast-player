#!/usr/bin/env python3
from __future__ import annotations
import argparse
import hashlib
import json
import os
import re
import shutil
import sqlite3
import subprocess
import time
from pathlib import Path
from typing import Any, Callable, Optional, Tuple
from urllib.parse import urlparse, urlunparse
from urllib.request import Request, urlopen

BASE_DIR = Path(__file__).resolve().parents[1]
APP_STORAGE = Path(os.getenv("APP_STORAGE", str(BASE_DIR / "storage"))).resolve()
DB_PATH = Path(os.getenv("DB_PATH", str(APP_STORAGE / "jobs.sqlite"))).resolve()
QUEUE_DIR = Path(os.getenv("QUEUE_DIR", str(APP_STORAGE / "queue"))).resolve()
INPUT_DIR = Path(os.getenv("INPUT_DIR", str(APP_STORAGE / "input"))).resolve()
OUTPUT_DIR = Path(os.getenv("OUTPUT_DIR", str(APP_STORAGE / "output"))).resolve()
WORK_DIR = Path(os.getenv("WORK_DIR", str(APP_STORAGE / "work"))).resolve()
ARTIFACTS_DIR = Path(os.getenv("ARTIFACTS_DIR", str(APP_STORAGE / "artifacts"))).resolve()
SOURCE_CACHE_DIR = Path(
    os.getenv("SOURCE_CACHE_DIR", str(APP_STORAGE / "source-cache"))
).resolve()

POLL_SECONDS = max(1, int(os.getenv("WORKER_POLL_SECONDS", "2")))
PROCESSOR_MODE = os.getenv("PROCESSOR_MODE", "ffmpeg-copy").strip().lower()
FFMPEG_BIN = os.getenv("FFMPEG_BIN", "ffmpeg").strip()
SOURCE_CACHE_RETENTION_DAYS = max(
    1, int(os.getenv("SOURCE_CACHE_RETENTION_DAYS", "60"))
)
OUTPUT_RETENTION_DAYS = max(
    1, int(os.getenv("OUTPUT_RETENTION_DAYS", "7"))
)
SOURCE_DOWNLOAD_TIMEOUT_SECONDS = max(
    30, int(os.getenv("SOURCE_DOWNLOAD_TIMEOUT_SECONDS", "900"))
)

ADCUTFORGE_ROOT = os.getenv("ADCUTFORGE_ROOT", "").strip()
ADCUTFORGE_PYTHON = os.getenv("ADCUTFORGE_PYTHON", "python3").strip()
ADCUTFORGE_SCRIPT = os.getenv("ADCUTFORGE_SCRIPT", "").strip()
PARAKEET_PYTHON = os.getenv("PARAKEET_PYTHON", "").strip()
PARAKEET_MODEL = os.getenv("PARAKEET_MODEL", "nvidia/parakeet-tdt-0.6b-v3").strip()
OPENAI_API_KEY = os.getenv("OPENAI_API_KEY", "").strip()

MAX_LOG_CHARS = 60000
PROGRESS_RE = re.compile(r"(\d+(?:\.\d+)?)%\s*$")


def utc_now() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def truncate_logs(logs: str) -> str:
    return logs[-MAX_LOG_CHARS:]


def ensure_directories() -> None:
    for path in (
        APP_STORAGE,
        QUEUE_DIR,
        INPUT_DIR,
        OUTPUT_DIR,
        WORK_DIR,
        ARTIFACTS_DIR,
        SOURCE_CACHE_DIR,
    ):
        path.mkdir(parents=True, exist_ok=True)


def open_db() -> sqlite3.Connection:
    connection = sqlite3.connect(DB_PATH)
    connection.row_factory = sqlite3.Row
    return connection


def fetch_status(job_id: str) -> Optional[str]:
    with open_db() as connection:
        row = connection.execute("SELECT status FROM jobs WHERE id = ?", (job_id,)).fetchone()
    if row is None:
        return None
    return str(row["status"])


def mark_running(job_id: str) -> None:
    now = utc_now()
    with open_db() as connection:
        connection.execute(
            """
            UPDATE jobs
            SET status = ?, progress = ?, error_message = NULL, logs = ?, updated_at = ?, started_at = COALESCE(started_at, ?)
            WHERE id = ?
            """,
            ("running", 5.0, "5% Starting server job", now, now, job_id),
        )
        connection.commit()


def mark_runtime(job_id: str, progress: float, logs: str) -> None:
    capped = max(5.0, min(95.0, progress))
    now = utc_now()
    with open_db() as connection:
        connection.execute(
            """
            UPDATE jobs
            SET status = 'running', progress = ?, logs = ?, updated_at = ?, started_at = COALESCE(started_at, ?)
            WHERE id = ?
            """,
            (capped, truncate_logs(logs), now, now, job_id),
        )
        connection.commit()


def mark_failed(job_id: str, error_message: str, logs: str) -> None:
    now = utc_now()
    with open_db() as connection:
        connection.execute(
            """
            UPDATE jobs
            SET status = ?, progress = ?, error_message = ?, logs = ?, updated_at = ?, finished_at = ?
            WHERE id = ?
            """,
            (
                "failed",
                100.0,
                error_message[:1000],
                truncate_logs(logs),
                now,
                now,
                job_id,
            ),
        )
        connection.commit()


def mark_completed(
    job_id: str,
    output_path: str,
    logs: str,
    duration_seconds: Optional[float] = None,
    transcript_path: Optional[str] = None,
    timestamps_path: Optional[str] = None,
) -> None:
    now = utc_now()
    with open_db() as connection:
        connection.execute(
            """
            UPDATE jobs
            SET status = ?, progress = ?, error_message = NULL, logs = ?, output_path = ?, updated_at = ?, finished_at = ?,
                duration_seconds = COALESCE(?, duration_seconds),
                transcript_path = COALESCE(?, transcript_path),
                timestamps_path = COALESCE(?, timestamps_path)
            WHERE id = ?
            """,
            (
                "completed",
                100.0,
                truncate_logs(logs),
                output_path,
                now,
                now,
                duration_seconds,
                transcript_path,
                timestamps_path,
                job_id,
            ),
        )
        connection.commit()


def emit_runtime_update(
    job_id: str,
    logs_list: list[str],
    progress: float,
    line: Optional[str] = None,
) -> None:
    if line:
        logs_list.append(line)
    mark_runtime(job_id, progress, "\n".join(logs_list))


def save_job_stats(job_id: str, payload: dict, started_at: float, finished_at: float) -> float:
    job_artifacts_dir = ARTIFACTS_DIR / job_id
    job_artifacts_dir.mkdir(parents=True, exist_ok=True)
    duration_seconds = round(finished_at - started_at, 1)
    stats = {
        "job_id": job_id,
        "status": "completed",
        "backend": str(payload.get("backend", "")),
        "detection_mode": str(payload.get("detection_mode", "")),
        "source_url": str(payload.get("source_url") or ""),
        "created_at": str(payload.get("created_at") or ""),
        "started_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(started_at)),
        "finished_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(finished_at)),
        "duration_seconds": duration_seconds,
    }
    try:
        stats_path = job_artifacts_dir / "stats.json"
        stats_path.write_text(
            json.dumps(stats, indent=2, ensure_ascii=False), encoding="utf-8"
        )
    except OSError as exc:
        print(f"[worker] Could not save stats for {job_id}: {exc}")
    return duration_seconds


def validate_source_url(source_url: str) -> None:
    parsed = urlparse(source_url)
    if parsed.scheme.lower() not in {"http", "https"}:
        raise RuntimeError("source_url must use http or https")
    host = parsed.hostname.lower() if parsed.hostname else ""
    if not host:
        raise RuntimeError("source_url is missing a host")
    if host in {"localhost", "127.0.0.1", "::1"}:
        raise RuntimeError("source_url host is not allowed")


def canonical_source_url(source_url: str) -> str:
    parsed = urlparse(source_url)
    scheme = parsed.scheme.lower()
    netloc = parsed.netloc.lower()
    path = parsed.path or "/"
    return urlunparse((scheme, netloc, path, "", "", ""))


def source_cache_key(source_url: str) -> str:
    canonical = canonical_source_url(source_url)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def find_cached_source_file(cache_key: str) -> Optional[Path]:
    for candidate in sorted(SOURCE_CACHE_DIR.glob(f"{cache_key}.*")):
        if candidate.is_file() and candidate.stat().st_size > 0:
            return candidate
    return None


def prune_expired_source_cache() -> None:
    retention_seconds = SOURCE_CACHE_RETENTION_DAYS * 24 * 3600
    cutoff = time.time() - retention_seconds
    for candidate in SOURCE_CACHE_DIR.glob("*"):
        if not candidate.is_file():
            continue
        try:
            if candidate.stat().st_mtime < cutoff:
                candidate.unlink(missing_ok=True)
        except OSError:
            continue


def prune_old_outputs() -> None:
    retention_seconds = OUTPUT_RETENTION_DAYS * 24 * 3600
    cutoff = time.time() - retention_seconds
    for candidate in OUTPUT_DIR.glob("*"):
        if not candidate.is_file():
            continue
        try:
            if candidate.stat().st_mtime < cutoff:
                candidate.unlink(missing_ok=True)
        except OSError:
            continue


def download_source_to_cache(
    source_url: str,
    cache_path: Path,
    job_id: str,
    logs_list: list[str],
) -> None:
    tmp_path = cache_path.with_suffix(cache_path.suffix + ".part")
    tmp_path.unlink(missing_ok=True)

    request = Request(
        source_url,
        headers={"User-Agent": "AdFreePodcastPlayer/1.0"},
        method="GET",
    )

    downloaded = 0
    last_emitted_pct = -1
    with urlopen(request, timeout=SOURCE_DOWNLOAD_TIMEOUT_SECONDS) as response:
        content_length_header = response.headers.get("Content-Length", "0")
        try:
            total = max(0, int(content_length_header))
        except ValueError:
            total = 0

        with open(tmp_path, "wb") as output:
            while True:
                chunk = response.read(64 * 1024)
                if not chunk:
                    break
                output.write(chunk)
                downloaded += len(chunk)

                if total > 0:
                    pct = min(100, int(downloaded * 100 / total))
                    if pct >= last_emitted_pct + 5:
                        mapped = 10.0 + (pct / 100.0) * 20.0
                        emit_runtime_update(
                            job_id,
                            logs_list,
                            mapped,
                            f"{int(mapped)}% Downloading source audio ({pct}%)",
                        )
                        last_emitted_pct = pct

    if not tmp_path.exists() or tmp_path.stat().st_size <= 0:
        tmp_path.unlink(missing_ok=True)
        raise RuntimeError("Remote source download produced an empty file")

    cache_path.parent.mkdir(parents=True, exist_ok=True)
    tmp_path.replace(cache_path)


def prepare_input_audio(job_id: str, payload: dict[str, Any], logs_list: list[str]) -> Path:
    input_path = Path(str(payload["input_path"])).resolve()
    input_path.parent.mkdir(parents=True, exist_ok=True)

    if input_path.exists() and input_path.stat().st_size > 0:
        emit_runtime_update(job_id, logs_list, 20.0, "20% Using uploaded input audio")
        return input_path

    source_url = str(payload.get("source_url", "") or "").strip()
    if not source_url:
        raise RuntimeError("Input audio is missing and no source_url was provided")

    validate_source_url(source_url)

    cache_key = source_cache_key(source_url)
    cached_file = find_cached_source_file(cache_key)
    if cached_file is not None and cached_file.exists():
        emit_runtime_update(
            job_id,
            logs_list,
            18.0,
            f"18% Source cache hit ({cached_file.name})",
        )
        shutil.copy2(cached_file, input_path)
        emit_runtime_update(job_id, logs_list, 35.0, "35% Prepared input audio from cache")
        return input_path

    extension = input_path.suffix if input_path.suffix else ".m4a"
    cache_target = SOURCE_CACHE_DIR / f"{cache_key}{extension}"

    emit_runtime_update(job_id, logs_list, 10.0, "10% Downloading source audio on server")
    download_source_to_cache(source_url, cache_target, job_id, logs_list)
    emit_runtime_update(job_id, logs_list, 30.0, "30% Cached source audio for future jobs")

    shutil.copy2(cache_target, input_path)
    emit_runtime_update(job_id, logs_list, 35.0, "35% Prepared input audio")
    return input_path


def run_command(
    command: list[str],
    cwd: Optional[Path],
    on_line: Optional[Callable[[str], None]] = None,
) -> Tuple[int, str, str]:
    logs: list[str] = []
    edited_audio = ""
    process = subprocess.Popen(
        command,
        cwd=str(cwd) if cwd else None,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
        bufsize=1,
    )

    assert process.stdout is not None
    for raw_line in process.stdout:
        line = raw_line.strip()
        if not line:
            continue

        logs.append(line)
        if line.startswith("Edited audio:"):
            edited_audio = line.split(":", 1)[1].strip()

        if on_line is not None:
            on_line(line)

    exit_code = process.wait()
    return exit_code, "\n".join(logs), edited_audio


def run_ffmpeg_copy(job_id: str, payload: dict[str, Any], logs_list: list[str]) -> Tuple[str, str]:
    input_path = Path(str(payload["input_path"])).resolve()
    output_path = Path(str(payload["output_path"])).resolve()
    output_path.parent.mkdir(parents=True, exist_ok=True)

    command = [
        FFMPEG_BIN,
        "-y",
        "-i",
        str(input_path),
        "-vn",
        "-c:a",
        "aac",
        "-b:a",
        "64k",
        "-ac",
        "1",
        str(output_path),
    ]

    emit_runtime_update(job_id, logs_list, 45.0, "45% Rendering output with ffmpeg")
    exit_code, logs, _ = run_command(
        command,
        cwd=None,
        on_line=lambda line: emit_runtime_update(job_id, logs_list, 70.0, line),
    )
    if exit_code != 0:
        raise RuntimeError(f"ffmpeg failed with exit code {exit_code}")
    if not output_path.exists():
        raise RuntimeError("ffmpeg did not produce an output file")

    emit_runtime_update(job_id, logs_list, 92.0, "92% ffmpeg render complete")
    return str(output_path), logs


def resolve_adcutforge_script() -> Optional[Path]:
    if ADCUTFORGE_SCRIPT:
        candidate = Path(ADCUTFORGE_SCRIPT)
        if candidate.exists():
            return candidate.resolve()
    if ADCUTFORGE_ROOT:
        candidate = Path(ADCUTFORGE_ROOT) / "src" / "ad_cut_forge.py"
        if candidate.exists():
            return candidate.resolve()
    return None


def find_rendered_audio(input_path: Path, explicit_path: str) -> Optional[Path]:
    if explicit_path:
        candidate = Path(explicit_path)
        if candidate.exists():
            return candidate.resolve()

    parent = input_path.parent
    stem = input_path.stem
    ordered = sorted(parent.glob(f"{stem}*"), key=lambda p: p.stat().st_mtime, reverse=True)
    for item in ordered:
        name = item.name.lower()
        if item.is_file() and (".noads" in name or ".adfree" in name):
            return item.resolve()
    return None


def run_adcutforge(job_id: str, payload: dict[str, Any], logs_list: list[str]) -> Tuple[str, str, Optional[str]]:
    script = resolve_adcutforge_script()
    if script is None:
        raise RuntimeError(
            "AdCutForge script not found. Set ADCUTFORGE_ROOT or ADCUTFORGE_SCRIPT, "
            "or switch PROCESSOR_MODE=ffmpeg-copy."
        )

    input_path = Path(str(payload["input_path"])).resolve()
    output_path = Path(str(payload["output_path"])).resolve()
    output_path.parent.mkdir(parents=True, exist_ok=True)

    job_artifacts_dir = ARTIFACTS_DIR / job_id
    job_artifacts_dir.mkdir(parents=True, exist_ok=True)

    requested_backend = str(payload.get("backend", "parakeet")).strip().lower()
    backend = "whisper" if requested_backend == "openai-whisper" else requested_backend
    if backend not in ("parakeet", "whisper"):
        backend = "parakeet"

    command = [
        ADCUTFORGE_PYTHON,
        str(script),
        "--cli",
        "--overwrite",
        "--backend",
        backend,
        "--artifacts-dir",
        str(job_artifacts_dir),
    ]

    detection_mode = str(payload.get("detection_mode", "")).strip().lower()
    if detection_mode in ("local", "hybrid", "openai"):
        command.extend(["--detection-mode", detection_mode])

    openai_key = str(payload.get("openai_api_key", "")).strip() or OPENAI_API_KEY
    if openai_key:
        command.extend(["--openai-api-key", openai_key])

    openai_model = str(payload.get("openai_model", "")).strip()
    if openai_model:
        command.extend(["--openai-model", openai_model])

    if backend == "parakeet":
        if PARAKEET_PYTHON:
            command.extend(["--parakeet-python", PARAKEET_PYTHON])
        if PARAKEET_MODEL:
            command.extend(["--parakeet-model", PARAKEET_MODEL])

    command.append(str(input_path))
    command_cwd = Path(ADCUTFORGE_ROOT).resolve() if ADCUTFORGE_ROOT else script.parent

    current_progress = 40.0

    def on_line(line: str) -> None:
        nonlocal current_progress
        mapped = current_progress
        match = PROGRESS_RE.search(line)
        if match:
            try:
                raw = max(0.0, min(100.0, float(match.group(1))))
                mapped = 35.0 + (raw / 100.0) * 60.0
            except ValueError:
                mapped = current_progress
        current_progress = max(current_progress, mapped)
        emit_runtime_update(job_id, logs_list, current_progress, line)

    emit_runtime_update(job_id, logs_list, 40.0, "40% Running AdCutForge")
    exit_code, logs, edited_audio = run_command(command, cwd=command_cwd, on_line=on_line)
    if exit_code != 0:
        raise RuntimeError(f"AdCutForge failed with exit code {exit_code}")

    rendered = find_rendered_audio(input_path, edited_audio)
    if rendered is None:
        raise RuntimeError("AdCutForge did not report a rendered audio output")

    emit_runtime_update(job_id, logs_list, 95.0, "95% Finalizing processed output")
    shutil.copy2(rendered, output_path)

    timestamps_path: Optional[str] = None
    timestamps_candidate = job_artifacts_dir / "timestamps.json"
    if timestamps_candidate.exists():
        timestamps_path = str(timestamps_candidate)

    return str(output_path), logs, timestamps_path


def process_job_file(queue_file: Path) -> None:
    try:
        payload = json.loads(queue_file.read_text(encoding="utf-8"))
    except Exception as exception:
        print(f"[worker] Could not parse queue file {queue_file}: {exception}")
        queue_file.unlink(missing_ok=True)
        return

    job_id = str(payload.get("job_id", "")).strip()
    if not job_id:
        print(f"[worker] Queue file missing job_id: {queue_file}")
        queue_file.unlink(missing_ok=True)
        return

    existing_status = fetch_status(job_id)
    if existing_status is None:
        print(f"[worker] Job {job_id} not found in DB, dropping queue payload")
        queue_file.unlink(missing_ok=True)
        return

    if existing_status in ("cancelled", "failed", "completed"):
        queue_file.unlink(missing_ok=True)
        return

    mark_running(job_id)
    logs_list = ["5% Starting server job"]
    input_path = Path(str(payload.get("input_path", ""))).resolve() if payload.get("input_path") else None
    started_at = time.time()

    try:
        prune_expired_source_cache()
        emit_runtime_update(job_id, logs_list, 8.0, "8% Preparing source audio")
        prepare_input_audio(job_id, payload, logs_list)

        timestamps_path: Optional[str] = None
        if PROCESSOR_MODE == "adcutforge":
            output_path, _, timestamps_path = run_adcutforge(job_id, payload, logs_list)
        elif PROCESSOR_MODE == "ffmpeg-copy":
            output_path, _ = run_ffmpeg_copy(job_id, payload, logs_list)
        else:
            raise RuntimeError("Unsupported PROCESSOR_MODE. Use adcutforge or ffmpeg-copy.")

        finished_at = time.time()
        duration_seconds = save_job_stats(job_id, payload, started_at, finished_at)
        mark_completed(job_id, output_path, "\n".join(logs_list), duration_seconds, timestamps_path=timestamps_path)
        print(f"[worker] Completed job {job_id}")
    except Exception as exception:
        mark_failed(job_id, str(exception), "\n".join(logs_list))
        print(f"[worker] Failed job {job_id}: {exception}")
    finally:
        queue_file.unlink(missing_ok=True)
        if input_path is not None:
            input_path.unlink(missing_ok=True)


def process_queue_once() -> bool:
    processed = False
    for queue_file in sorted(QUEUE_DIR.glob("*.json")):
        working_file = queue_file.with_suffix(".working")
        try:
            queue_file.rename(working_file)
        except OSError:
            continue

        processed = True
        process_job_file(working_file)

    return processed


def main() -> None:
    parser = argparse.ArgumentParser(description="Process queued ad-removal jobs")
    parser.add_argument(
        "--once",
        action="store_true",
        help="Process the queue once and exit (useful for cron).",
    )
    args = parser.parse_args()

    ensure_directories()
    prune_expired_source_cache()
    prune_old_outputs()
    print(
        "[worker] Starting with "
        f"mode={PROCESSOR_MODE}, queue={QUEUE_DIR}, output={OUTPUT_DIR}, cache={SOURCE_CACHE_DIR}"
    )

    if args.once:
        process_queue_once()
        return

    while True:
        did_work = process_queue_once()
        if not did_work:
            time.sleep(POLL_SECONDS)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n[worker] Stopped")

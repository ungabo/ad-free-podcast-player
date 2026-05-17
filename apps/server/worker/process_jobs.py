#!/usr/bin/env python3
from __future__ import annotations
import argparse
import hashlib
import json
import os
import shutil
import sqlite3
import threading
import time
from pathlib import Path
from typing import Any, Optional, Tuple
from urllib.error import HTTPError, URLError
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
HEARTBEAT_PATH = Path(
    os.getenv("WORKER_HEARTBEAT", str(APP_STORAGE / "worker-heartbeat.json"))
).resolve()
POLL_SECONDS = max(1, int(os.getenv("WORKER_POLL_SECONDS", "2")))
PROCESSOR_LABEL = "windows-tunnel"
SOURCE_CACHE_RETENTION_DAYS = max(
    1, int(os.getenv("SOURCE_CACHE_RETENTION_DAYS", "60"))
)
OUTPUT_RETENTION_DAYS = max(
    1, int(os.getenv("OUTPUT_RETENTION_DAYS", "7"))
)
SOURCE_DOWNLOAD_TIMEOUT_SECONDS = max(
    30, int(os.getenv("SOURCE_DOWNLOAD_TIMEOUT_SECONDS", "900"))
)

LOCAL_PROCESSOR_BASE_URL = os.getenv(
    "LOCAL_PROCESSOR_BASE_URL", "http://127.0.0.1:8081/adfree-api"
).rstrip("/")
LOCAL_BRIDGE_TOKEN = os.getenv("LOCAL_BRIDGE_TOKEN", "").strip()
LOCAL_PROCESSOR_TIMEOUT_SECONDS = max(
    60, int(os.getenv("LOCAL_PROCESSOR_TIMEOUT_SECONDS", "14400"))
)

MAX_LOG_CHARS = 60000


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
    HEARTBEAT_PATH.parent.mkdir(parents=True, exist_ok=True)


def write_heartbeat(state: str = "running") -> None:
    payload = {
        "state": state,
        "time": utc_now(),
        "mode": PROCESSOR_LABEL,
        "queue_dir": str(QUEUE_DIR),
        "output_dir": str(OUTPUT_DIR),
    }
    try:
        HEARTBEAT_PATH.write_text(json.dumps(payload), encoding="utf-8")
    except OSError:
        pass


class HeartbeatPinger:
    def __init__(self, state: str = "running", interval_seconds: int = 10) -> None:
        self.state = state
        self.interval_seconds = interval_seconds
        self.stop_event = threading.Event()
        self.thread = threading.Thread(target=self._run, daemon=True)

    def __enter__(self) -> "HeartbeatPinger":
        write_heartbeat(self.state)
        self.thread.start()
        return self

    def __exit__(self, exc_type: object, exc: object, traceback: object) -> None:
        self.stop_event.set()
        self.thread.join(timeout=1)
        write_heartbeat(self.state)

    def _run(self) -> None:
        while not self.stop_event.wait(self.interval_seconds):
            write_heartbeat(self.state)


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
    write_heartbeat("running")
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
                summarize_error_message(error_message),
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


def is_tunnel_backend(backend: str) -> bool:
    return backend.strip().lower() == "tunnel-parakeet"


def tunnel_local_backend(backend: str) -> str:
    return "parakeet"


def bridge_headers(content_type: Optional[str] = None) -> dict[str, str]:
    headers = {"User-Agent": "AdFreePodcastPlayer/1.0"}
    if content_type:
        headers["Content-Type"] = content_type
    if LOCAL_BRIDGE_TOKEN:
        headers["X-Adfree-Bridge-Token"] = LOCAL_BRIDGE_TOKEN
    return headers


def bridge_url(path_or_url: str) -> str:
    if path_or_url.startswith("http://") or path_or_url.startswith("https://"):
        return path_or_url
    path = path_or_url if path_or_url.startswith("/") else f"/{path_or_url}"
    return f"{LOCAL_PROCESSOR_BASE_URL}{path}"


def post_bridge_json(path: str, payload: dict[str, Any]) -> dict[str, Any]:
    request = Request(
        bridge_url(path),
        data=json.dumps(payload).encode("utf-8"),
        headers=bridge_headers("application/json"),
        method="POST",
    )
    try:
        with HeartbeatPinger("running"):
            with urlopen(request, timeout=LOCAL_PROCESSOR_TIMEOUT_SECONDS) as response:
                decoded = json.loads(response.read().decode("utf-8"))
    except HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"Windows bridge returned HTTP {exc.code}: {summarize_bridge_error(detail)}") from exc
    except URLError as exc:
        raise RuntimeError(f"Windows bridge is not reachable at {LOCAL_PROCESSOR_BASE_URL}: {exc}") from exc
    if not isinstance(decoded, dict):
        raise RuntimeError("Windows bridge returned a non-object JSON response")
    return decoded


def get_bridge_json(path: str, timeout: int = 10) -> Optional[dict[str, Any]]:
    request = Request(bridge_url(path), headers=bridge_headers(), method="GET")
    try:
        with urlopen(request, timeout=timeout) as response:
            decoded = json.loads(response.read().decode("utf-8"))
    except HTTPError as exc:
        if exc.code == 404:
            return None
        raise RuntimeError(f"Windows bridge status returned HTTP {exc.code}") from exc
    except (URLError, json.JSONDecodeError):
        return None
    return decoded if isinstance(decoded, dict) else None


def summarize_bridge_error(detail: str) -> str:
    text = detail.strip()
    if not text:
        return "No bridge error body was returned."
    try:
        decoded = json.loads(text)
        if isinstance(decoded, dict) and isinstance(decoded.get("error"), str):
            text = decoded["error"].strip()
    except json.JSONDecodeError:
        pass
    if len(text) <= 1000:
        return text
    return f"{text[:450]} ... {text[-500:]}"


def summarize_error_message(message: str) -> str:
    text = message.strip()
    if len(text) <= 1000:
        return text
    return f"{text[:450]} ... {text[-500:]}"


def download_bridge_file(path_or_url: str, destination: Path) -> None:
    request = Request(bridge_url(path_or_url), headers=bridge_headers(), method="GET")
    destination.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = destination.with_suffix(destination.suffix + ".part")
    tmp_path.unlink(missing_ok=True)
    try:
        with HeartbeatPinger("running"):
            with urlopen(request, timeout=LOCAL_PROCESSOR_TIMEOUT_SECONDS) as response:
                with tmp_path.open("wb") as output:
                    shutil.copyfileobj(response, output)
    except HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        tmp_path.unlink(missing_ok=True)
        raise RuntimeError(f"Windows bridge download returned HTTP {exc.code}: {detail}") from exc
    except URLError as exc:
        tmp_path.unlink(missing_ok=True)
        raise RuntimeError(f"Windows bridge download failed: {exc}") from exc
    if not tmp_path.exists() or tmp_path.stat().st_size <= 0:
        tmp_path.unlink(missing_ok=True)
        raise RuntimeError("Windows bridge returned an empty file")
    tmp_path.replace(destination)


def run_tunnel_processor(job_id: str, payload: dict[str, Any], logs_list: list[str]) -> Tuple[str, str, Optional[str], Optional[str]]:
    backend = "tunnel-parakeet"
    payload["backend"] = backend
    source_url = str(payload.get("source_url", "") or "").strip()
    if not source_url:
        raise RuntimeError("Windows tunnel processing requires source_url jobs.")
    detection_mode = "openai"
    openai_model = str(payload.get("openai_model", "") or "").strip() or "gpt-5.5"

    emit_runtime_update(
        job_id,
        logs_list,
        38.0,
        f"38% Sending job to Windows tunnel ({tunnel_local_backend(backend)}, GPT detection)",
    )
    bridge_payload = {
        "job_id": job_id,
        "source_url": source_url,
        "backend": tunnel_local_backend(backend),
        "detection_mode": detection_mode,
        "episode_title": payload.get("episode_title") or "",
        "podcast_name": payload.get("podcast_name") or "",
    }
    bridge_payload["openai_model"] = openai_model
    response_holder: dict[str, Any] = {}
    error_holder: dict[str, BaseException] = {}

    def run_bridge_call() -> None:
        try:
            response_holder["response"] = post_bridge_json("/api/local/process", bridge_payload)
        except BaseException as exc:
            error_holder["error"] = exc

    bridge_thread = threading.Thread(target=run_bridge_call, daemon=True)
    bridge_thread.start()
    last_status_line = ""
    bridge_started_at = time.monotonic()
    last_status_at = bridge_started_at
    last_overall_progress = 38.0
    last_estimate_bucket = -1
    while bridge_thread.is_alive():
        emitted_status_update = False
        status = get_bridge_json(f"/api/local/jobs/{job_id}/status", timeout=3)
        if status:
            local_progress = max(0.0, min(100.0, float(status.get("progress") or 0.0)))
            overall_progress = max(last_overall_progress, 38.0 + (local_progress * 0.50))
            phase = str(status.get("phase") or "Windows processing").strip()
            message = str(status.get("message") or "").strip()
            line = f"{round(overall_progress)}% {phase}"
            if message:
                line = f"{line}: {message}"
            if line != last_status_line:
                emit_runtime_update(job_id, logs_list, overall_progress, line)
                last_status_at = time.monotonic()
                last_overall_progress = overall_progress
                emitted_status_update = True
                bridge_logs = str(status.get("logs") or "").strip()
                if bridge_logs:
                    for bridge_line in bridge_logs.splitlines()[-4:]:
                        if bridge_line.strip() and bridge_line.strip() not in logs_list[-12:]:
                            logs_list.append(f"Windows: {bridge_line.strip()}")
                    mark_runtime(job_id, overall_progress, "\n".join(logs_list))
                last_status_line = line
        quiet_for = time.monotonic() - last_status_at
        elapsed = time.monotonic() - bridge_started_at
        if not emitted_status_update and quiet_for >= 5.0 and elapsed >= 10.0:
            estimated_progress = min(86.0, max(last_overall_progress, 56.0 + ((elapsed - 10.0) * 0.45)))
            estimate_bucket = int(estimated_progress)
            if estimate_bucket > last_estimate_bucket:
                last_estimate_bucket = estimate_bucket
                last_overall_progress = estimated_progress
                emit_runtime_update(
                    job_id,
                    logs_list,
                    estimated_progress,
                    f"{estimate_bucket}% Parakeet running: loading model or transcribing chunks",
                )
        bridge_thread.join(timeout=1.0)

    if "error" in error_holder:
        raise error_holder["error"]
    response = response_holder.get("response")
    if not isinstance(response, dict):
        raise RuntimeError("Windows bridge did not return a response")
    if not response.get("ok"):
        raise RuntimeError(str(response.get("error") or "Windows bridge processing failed"))

    emit_runtime_update(job_id, logs_list, 88.0, "88% Pulling Windows tunnel output")
    output_path = Path(str(payload["output_path"])).resolve()
    download_url = str(response.get("download_url") or "")
    if not download_url:
        raise RuntimeError("Windows bridge did not return a download_url")
    download_bridge_file(download_url, output_path)

    job_artifacts_dir = ARTIFACTS_DIR / job_id
    job_artifacts_dir.mkdir(parents=True, exist_ok=True)
    transcript_path: Optional[str] = None
    timestamps_path: Optional[str] = None

    artifact_targets = [
        ("transcript_url", job_artifacts_dir / "transcript.txt"),
        ("timestamped_transcript_url", job_artifacts_dir / "transcript_timestamped.txt"),
        ("timestamps_url", job_artifacts_dir / "timestamps.json"),
        ("stats_url", job_artifacts_dir / "stats.json"),
    ]
    for key, destination in artifact_targets:
        artifact_url = str(response.get(key) or "")
        if not artifact_url:
            continue
        try:
            download_bridge_file(artifact_url, destination)
        except Exception as exc:
            logs_list.append(f"Could not fetch Windows bridge artifact {key}: {exc}")

    if (job_artifacts_dir / "transcript.txt").exists():
        transcript_path = str(job_artifacts_dir / "transcript.txt")
    if (job_artifacts_dir / "timestamps.json").exists():
        timestamps_path = str(job_artifacts_dir / "timestamps.json")

    bridge_logs = str(response.get("logs") or "").strip()
    if bridge_logs:
        logs_list.append("--- Windows bridge log ---")
        logs_list.extend(bridge_logs.splitlines()[-120:])

    emit_runtime_update(job_id, logs_list, 95.0, "95% Finalizing Windows tunnel output")
    return str(output_path), bridge_logs, timestamps_path, transcript_path


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
    backend = str(payload.get("backend", "")).strip().lower()
    started_at = time.time()

    try:
        prune_expired_source_cache()
        if not is_tunnel_backend(backend):
            raise RuntimeError("Ad removal requires the Windows tunnel processor. No server-side fallback is available.")

        transcript_path: Optional[str] = None
        timestamps_path: Optional[str] = None
        output_path, _, timestamps_path, transcript_path = run_tunnel_processor(job_id, payload, logs_list)

        finished_at = time.time()
        duration_seconds = save_job_stats(job_id, payload, started_at, finished_at)
        mark_completed(
            job_id,
            output_path,
            "\n".join(logs_list),
            duration_seconds,
            transcript_path=transcript_path,
            timestamps_path=timestamps_path,
        )
        print(f"[worker] Completed job {job_id}")
    except Exception as exception:
        mark_failed(job_id, str(exception), "\n".join(logs_list))
        print(f"[worker] Failed job {job_id}: {exception}")
    finally:
        queue_file.unlink(missing_ok=True)
        if input_path is not None:
            input_path.unlink(missing_ok=True)


def process_queue_once() -> bool:
    write_heartbeat("polling")
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
        f"mode={PROCESSOR_LABEL}, queue={QUEUE_DIR}, output={OUTPUT_DIR}, cache={SOURCE_CACHE_DIR}"
    )
    write_heartbeat("started")

    if args.once:
        process_queue_once()
        write_heartbeat("idle")
        return

    while True:
        did_work = process_queue_once()
        if not did_work:
            write_heartbeat("idle")
            time.sleep(POLL_SECONDS)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n[worker] Stopped")

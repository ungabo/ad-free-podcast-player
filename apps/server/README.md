# Web Processing Stack (PHP API + Linux Worker)

This folder adds a server version of ad-removal processing:

- PHP API for uploads, job creation, status polling, and output download
- Python worker for background processing
- Shared storage and SQLite job tracking
- Docker Compose setup for Linux deployment

## Architecture

- API service: `apps/server/api/public/index.php`
- Worker service: `apps/server/worker/process_jobs.py`
- Queue directory: `apps/server/storage/queue`
- Input files: `apps/server/storage/input`
- Output files: `apps/server/storage/output`
- Job DB: `apps/server/storage/jobs.sqlite`

## Processing Modes

- `PROCESSOR_MODE=adcutforge` (default)
  - Calls AdCutForge for actual ad-removal processing
  - Configure `ADCUTFORGE_ROOT` or `ADCUTFORGE_SCRIPT`
- `PROCESSOR_MODE=ffmpeg-copy`
  - Safe baseline for end-to-end testing
  - Produces a normalized AAC output without ad removal logic

## Run With Docker

1. Copy `.env.example` to `.env` and adjust values as needed.
2. From `apps/server`, run:

```bash
docker compose up --build
```

API will be available at `http://localhost:8080`.

## API Endpoints

### Health

- `GET /health`

### Create Job

- `POST /api/jobs`
- Content-Type: `multipart/form-data`
- Fields:
  - `audio` (optional file)
  - `source_url` (optional URL to a remote audio file)
  - `backend` (optional): `parakeet`, `whisper`, `openai-whisper`
  - `detection_mode` (optional): `local`, `hybrid`, `openai`
  - `openai_model` (optional, default `gpt-4o-mini`)
  - `openai_api_key` (optional)

You must provide either `audio` or `source_url`.

Example:

```bash
curl -X POST http://localhost:8080/api/jobs \
  -F "audio=@/path/to/episode.mp3" \
  -F "backend=parakeet" \
  -F "detection_mode=hybrid"

curl -X POST http://localhost:8080/api/jobs \
  -F "source_url=https://example.com/episode.mp3" \
  -F "backend=parakeet" \
  -F "detection_mode=local"
```

### Get Job Status

- `GET /api/jobs/{job_id}`

### Download Completed Output

- `GET /api/jobs/{job_id}/download`

### Cancel Job

- `POST /api/jobs/{job_id}/cancel`

## Notes

- The worker updates job state in SQLite as it runs.
- If you use AdCutForge mode, install the full processing runtime on Linux and point env vars to it.
- Keep OpenAI keys in server environment when possible instead of sending per-request keys.
- For `source_url` jobs, the API queues immediately and the worker downloads the source audio.
- Worker source downloads are cached by episode URL and reused for future jobs.
- Cached source downloads are pruned automatically after 60 days (configurable via `SOURCE_CACHE_RETENTION_DAYS`).

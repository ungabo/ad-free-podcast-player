# Web Processing Stack (PHP API + Linux Worker)

This folder adds the PHP API used by the web and Android apps:

- PHP API for job creation, status polling, and output download
- Python worker that sends new ad-removal jobs to the Windows tunnel processor
- Shared storage and SQLite job tracking
- Docker Compose setup for Linux deployment

## Architecture

- API service: `apps/server/api/public/index.php`
- Worker service: `apps/server/worker/process_jobs.py`
- Queue directory: `apps/server/storage/queue`
- Input files: `apps/server/storage/input`
- Output files: `apps/server/storage/output`
- Job DB: `apps/server/storage/jobs.sqlite`

## Processing Path

New ad-removal jobs always use the Windows tunnel processor. The PHP API stores completed output and serves downloads, but it does not run a local/server fallback when Windows is unavailable.

## Run With Docker

1. Copy `.env.example` to `.env` and adjust values as needed.
2. From `apps/server`, run:

```bash
docker compose up --build
```

API will be available at `http://localhost:8080`.

## Run Locally on Windows

The stack also works on a Windows machine with WAMP or a similar Apache + PHP setup.

- Put the built web app in `D:\wamp64\www\adfree-web`
- Put the API entrypoint in `D:\wamp64\www\adfree-api`
- Set the PHP env vars from `apps/server/.env.example` in your Apache vhost with `SetEnv`
- Point the worker at the same storage folder and run it as a Windows process or service
- Create `D:\wamp64\www\adfree-web\.ui-disabled` to turn off local UI access while keeping the API online

Example vhost and sync script notes live in `apps/server/windows/README.md`.

## API Endpoints

### Health

- `GET /health`

### Create Job

- `POST /api/jobs`
- Content-Type: `multipart/form-data`
- Fields:
  - `source_url` (required URL to a remote audio file)
  - `backend` (optional, normalized to `tunnel-parakeet`)
  - `detection_mode` (optional): `openai` (GPT ad detection only)

The OpenAI API key must be configured on the Windows machine, not supplied by browser clients.

Example:

```bash
curl -X POST http://localhost:8080/api/jobs \
  -F "source_url=https://example.com/episode.mp3" \
  -F "backend=tunnel-parakeet" \
  -F "detection_mode=openai"
```

### Get Job Status

- `GET /api/jobs/{job_id}`

### Download Completed Output

- `GET /api/jobs/{job_id}/download`

### Cancel Job

- `POST /api/jobs/{job_id}/cancel`

## Notes

- The worker updates job state in SQLite as it runs.
- Keep OpenAI keys on the Windows processor. `apps/server/windows/setup-local.ps1` writes `OPENAI_API_KEY` into the local WAMP API environment when you pass `-OpenAiApiKey` or run it from a shell where `OPENAI_API_KEY` is already set.
- For `source_url` jobs, the worker asks the Windows tunnel processor to download and process the source audio.
- Worker source downloads are cached by episode URL and reused for future jobs.
- Cached source downloads are pruned automatically after 60 days (configurable via `SOURCE_CACHE_RETENTION_DAYS`).

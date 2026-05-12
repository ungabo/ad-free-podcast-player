FROM python:3.11-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY process_jobs.py /app/process_jobs.py

CMD ["python", "/app/process_jobs.py"]

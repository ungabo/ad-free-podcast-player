<?php
declare(strict_types=1);

header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Headers: Content-Type');
header('Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS');

if (($_SERVER['REQUEST_METHOD'] ?? 'GET') === 'OPTIONS') {
    http_response_code(204);
    exit;
}

$config = loadConfig();
ensureDirectory($config['storage_root']);
ensureDirectory($config['queue_dir']);
ensureDirectory($config['input_dir']);
ensureDirectory($config['output_dir']);

$pdo = connectDb($config['db_path']);
ensureSchema($pdo);

$method = $_SERVER['REQUEST_METHOD'] ?? 'GET';
$uri = parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH) ?: '/';
$basePath = (string)($config['base_path'] ?? '');
if ($basePath !== '' && str_starts_with($uri, $basePath)) {
    $trimmedUri = substr($uri, strlen($basePath));
    $uri = $trimmedUri === false || $trimmedUri === '' ? '/' : $trimmedUri;
}

try {
    if ($method === 'GET' && $uri === '/health') {
        jsonResponse(200, [
            'ok' => true,
            'service' => 'adfree-api',
            'time' => gmdate(DATE_ATOM),
        ]);
    }

    if ($method === 'GET' && $uri === '/api/catalog/search') {
        searchPodcastCatalog();
    }

    if ($method === 'GET' && $uri === '/api/catalog/episodes') {
        getPodcastEpisodes();
    }

    if ($method === 'GET' && $uri === '/api/jobs') {
        listJobs($pdo, $config);
    }

    if ($method === 'GET' && $uri === '/api/worker/status') {
        getWorkerStatus($config);
    }

    if ($method === 'POST' && $uri === '/api/jobs') {
        createJob($pdo, $config);
    }

    if ($method === 'GET' && preg_match('#^/api/jobs/([a-f0-9]{32})$#', $uri, $matches) === 1) {
        getJob($pdo, $config, $matches[1]);
    }

    if ($method === 'GET' && preg_match('#^/api/jobs/([a-f0-9]{32})/download$#', $uri, $matches) === 1) {
        downloadJobOutput($pdo, $matches[1]);
    }

    if ($method === 'GET' && preg_match('#^/api/jobs/([a-f0-9]{32})/artifact/(transcript|timestamped-transcript|timestamps|verification|stats)$#', $uri, $matches) === 1) {
        serveJobArtifact($pdo, $config, $matches[1], $matches[2]);
    }

    if ($method === 'POST' && preg_match('#^/api/jobs/([a-f0-9]{32})/cancel$#', $uri, $matches) === 1) {
        cancelJob($pdo, $config, $matches[1]);
    }

    if ($method === 'DELETE' && preg_match('#^/api/jobs/([a-f0-9]{32})$#', $uri, $matches) === 1) {
        deleteJob($pdo, $config, $matches[1]);
    }

    if ($method === 'POST' && $uri === '/api/jobs/clear') {
        clearJobs($pdo, $config);
    }

    if ($method === 'GET' && $uri === '/api/users') {
        listUsers($pdo);
    }

    if ($method === 'POST' && $uri === '/api/users') {
        createUser($pdo);
    }

    if ($method === 'GET' && preg_match('#^/api/users/([a-f0-9\-]{36})/subscriptions$#', $uri, $matches) === 1) {
        listSubscriptions($pdo, $matches[1]);
    }

    if ($method === 'POST' && preg_match('#^/api/users/([a-f0-9\-]{36})/subscriptions$#', $uri, $matches) === 1) {
        addSubscription($pdo, $matches[1]);
    }

    if ($method === 'DELETE' && preg_match('#^/api/users/([a-f0-9\-]{36})/subscriptions/([a-f0-9\-]{36})$#', $uri, $matches) === 1) {
        removeSubscription($pdo, $matches[1], $matches[2]);
    }

    jsonResponse(404, ['error' => 'Route not found']);
} catch (RuntimeException $exception) {
    jsonResponse(422, ['error' => $exception->getMessage()]);
} catch (Throwable $exception) {
    jsonResponse(500, [
        'error' => 'Server error',
        'detail' => $exception->getMessage(),
    ]);
}

function loadConfig(): array
{
    $serverRoot = dirname(__DIR__, 2);
    $legacyStorageRoot = joinPath($serverRoot, 'storage');
    $stackStorageRoot = joinPath($serverRoot, 'adfree-stack/storage');
    $defaultStorageRoot = is_dir(dirname($stackStorageRoot)) ? $stackStorageRoot : $legacyStorageRoot;
    $storageRoot = envOrDefault('APP_STORAGE', $defaultStorageRoot);
    $scriptName = str_replace('\\\\', '/', (string)($_SERVER['SCRIPT_NAME'] ?? ''));
    $scriptDir = str_replace('\\\\', '/', dirname($scriptName));
    $inferredBasePath = $scriptDir === '/' || $scriptDir === '.' ? '' : rtrim($scriptDir, '/');

    return [
        'storage_root' => $storageRoot,
        'db_path' => envOrDefault('DB_PATH', joinPath($storageRoot, 'jobs.sqlite')),
        'queue_dir' => envOrDefault('QUEUE_DIR', joinPath($storageRoot, 'queue')),
        'input_dir' => envOrDefault('INPUT_DIR', joinPath($storageRoot, 'input')),
        'output_dir' => envOrDefault('OUTPUT_DIR', joinPath($storageRoot, 'output')),
        'artifacts_dir' => envOrDefault('ARTIFACTS_DIR', joinPath($storageRoot, 'artifacts')),
        'worker_heartbeat' => envOrDefault('WORKER_HEARTBEAT', joinPath($storageRoot, 'worker-heartbeat.json')),
        'worker_lock_dir' => envOrDefault('WORKER_LOCK_DIR', joinPath(dirname($storageRoot), 'worker.lock')),
        'base_path' => envOrDefault('APP_BASE_PATH', $inferredBasePath),
    ];
}

function envOrDefault(string $name, string $defaultValue): string
{
    $value = getenv($name);
    if (!is_string($value) || $value === '') {
        return $defaultValue;
    }
    return $value;
}

function connectDb(string $dbPath): PDO
{
    $pdo = new PDO('sqlite:' . $dbPath);
    $pdo->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
    $pdo->setAttribute(PDO::ATTR_DEFAULT_FETCH_MODE, PDO::FETCH_ASSOC);
    return $pdo;
}

function ensureSchema(PDO $pdo): void
{
    $pdo->exec(
        'CREATE TABLE IF NOT EXISTS jobs (
            id TEXT PRIMARY KEY,
            status TEXT NOT NULL,
            backend TEXT NOT NULL,
            detection_mode TEXT NOT NULL,
            openai_model TEXT NOT NULL,
            input_path TEXT NOT NULL,
            output_path TEXT NOT NULL,
            progress REAL NOT NULL DEFAULT 0,
            error_message TEXT,
            logs TEXT,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            started_at TEXT,
            finished_at TEXT
        )'
    );

    // Migrate: add columns introduced after initial schema.
    $migrations = [
        'source_url TEXT',
        'transcript_path TEXT',
        'timestamps_path TEXT',
        'duration_seconds REAL',
        'episode_title TEXT',
        'podcast_name TEXT',
        'user_id TEXT',
    ];
    foreach ($migrations as $columnDef) {
        try {
            $pdo->exec('ALTER TABLE jobs ADD COLUMN ' . $columnDef);
        } catch (\PDOException $e) {
            // Column already exists — safe to ignore.
        }
    }

    $pdo->exec(
        'CREATE TABLE IF NOT EXISTS users (
            id TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            device_fingerprint TEXT UNIQUE,
            created_at TEXT NOT NULL
        )'
    );

    $pdo->exec(
        'CREATE TABLE IF NOT EXISTS subscriptions (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL,
            podcast_title TEXT NOT NULL,
            podcast_author TEXT,
            feed_url TEXT NOT NULL,
            artwork_url TEXT,
            collection_id INTEGER,
            added_at TEXT NOT NULL,
            UNIQUE(user_id, feed_url)
        )'
    );

    // Ensure test user exists.
    $testExists = $pdo->query("SELECT COUNT(*) FROM users WHERE name='test'")->fetchColumn();
    if ((int)$testExists === 0) {
        $pdo->prepare(
            'INSERT OR IGNORE INTO users (id, name, device_fingerprint, created_at) VALUES (?, ?, ?, ?)'
        )->execute([
            generateUuid(),
            'test',
            'web-test-account',
            gmdate(DATE_ATOM),
        ]);
    }
}

function searchPodcastCatalog(): void
{
    $term = trim((string)($_GET['term'] ?? ''));
    if ($term === '') {
        throw new RuntimeException('term is required.');
    }

    $url = 'https://itunes.apple.com/search?media=podcast&entity=podcast&limit=20&term=' . rawurlencode($term);
    $payload = fetchRemoteJson($url, ['itunes.apple.com']);
    $results = $payload['results'] ?? [];

    jsonResponse(200, [
        'results' => is_array($results) ? $results : [],
    ]);
}

function getPodcastEpisodes(): void
{
    $collectionId = trim((string)($_GET['collection_id'] ?? ''));
    if ($collectionId === '' || preg_match('/^[0-9]+$/', $collectionId) !== 1) {
        throw new RuntimeException('collection_id must be numeric.');
    }

    $url = 'https://itunes.apple.com/lookup?id=' . rawurlencode($collectionId) . '&entity=podcastEpisode&limit=30';
    $payload = fetchRemoteJson($url, ['itunes.apple.com']);
    $results = $payload['results'] ?? [];

    jsonResponse(200, [
        'results' => is_array($results) ? $results : [],
    ]);
}

function fetchRemoteJson(string $url, array $allowedHosts): array
{
    $parts = parse_url($url);
    if (!is_array($parts)) {
        throw new RuntimeException('Invalid remote URL.');
    }

    $scheme = strtolower((string)($parts['scheme'] ?? ''));
    if (!in_array($scheme, ['http', 'https'], true)) {
        throw new RuntimeException('Remote URL must use http or https.');
    }

    $host = strtolower((string)($parts['host'] ?? ''));
    if ($host === '' || !in_array($host, $allowedHosts, true)) {
        throw new RuntimeException('Remote URL host is not allowed.');
    }

    if (function_exists('curl_init')) {
        $ch = curl_init($url);
        if ($ch === false) {
            throw new RuntimeException('Failed to initialize remote request.');
        }

        curl_setopt_array($ch, [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_FOLLOWLOCATION => true,
            CURLOPT_MAXREDIRS => 4,
            CURLOPT_CONNECTTIMEOUT => 15,
            CURLOPT_TIMEOUT => 30,
            CURLOPT_FAILONERROR => true,
            CURLOPT_SSL_VERIFYPEER => true,
            CURLOPT_SSL_VERIFYHOST => 2,
            CURLOPT_USERAGENT => 'AdFreePodcastPlayer/1.0',
        ]);

        $body = curl_exec($ch);
        if ($body === false) {
            $error = (string)curl_error($ch);
            curl_close($ch);
            throw new RuntimeException('Remote request failed: ' . $error);
        }

        curl_close($ch);
    } else {
        $context = stream_context_create([
            'http' => [
                'method' => 'GET',
                'timeout' => 30,
                'header' => "User-Agent: AdFreePodcastPlayer/1.0\r\n",
            ],
            'ssl' => [
                'verify_peer' => true,
                'verify_peer_name' => true,
            ],
        ]);

        $body = @file_get_contents($url, false, $context);
        if (!is_string($body) || $body === '') {
            throw new RuntimeException('Remote request returned empty content.');
        }
    }

    $decoded = json_decode($body, true);
    if (!is_array($decoded)) {
        throw new RuntimeException('Remote response was not valid JSON.');
    }

    return $decoded;
}

function createJob(PDO $pdo, array $config): void
{
    $upload = $_FILES['audio'] ?? null;
    $hasUpload = is_array($upload) && ((string)($upload['tmp_name'] ?? '') !== '');
    $sourceUrl = trim((string)($_POST['source_url'] ?? ''));

    if (!$hasUpload && $sourceUrl === '') {
        throw new RuntimeException('Provide either multipart file field audio or source_url.');
    }

    $backend = validateBackend((string)($_POST['backend'] ?? 'openai-whisper'));
    $detectionMode = validateDetectionMode((string)($_POST['detection_mode'] ?? 'local'));
    $openAiModel = trim((string)($_POST['openai_model'] ?? 'gpt-4o-mini'));
    if ($openAiModel === '') {
        $openAiModel = 'gpt-4o-mini';
    }
    $openAiApiKey = trim((string)($_POST['openai_api_key'] ?? ''));

    $id = bin2hex(random_bytes(16));
    $extension = $hasUpload
        ? detectUploadExtension((string)($upload['name'] ?? 'audio.m4a'))
        : detectUrlExtension($sourceUrl);
    $inputPath = joinPath($config['input_dir'], $id . '.' . $extension);
    $outputPath = joinPath($config['output_dir'], $id . '.noads.m4a');

    if ($hasUpload) {
        $uploadError = (int)($upload['error'] ?? UPLOAD_ERR_OK);
        if ($uploadError !== UPLOAD_ERR_OK) {
            throw new RuntimeException('Upload failed with code ' . $uploadError);
        }

        $tmpPath = (string)($upload['tmp_name'] ?? '');
        if ($tmpPath === '' || !is_uploaded_file($tmpPath)) {
            throw new RuntimeException('Upload did not provide a valid temporary file.');
        }

        if (!move_uploaded_file($tmpPath, $inputPath)) {
            throw new RuntimeException('Failed to move uploaded file into storage.');
        }
    } else {
        validateSourceUrl($sourceUrl);
    }

    $now = gmdate(DATE_ATOM);

    $userId = trim((string)($_POST['user_id'] ?? ($_GET['user_id'] ?? '')));
    if ($userId === '') $userId = null;

    $episodeTitle = trim((string)($_POST['episode_title'] ?? ''));
    if ($episodeTitle === '') $episodeTitle = null;
    $podcastName = trim((string)($_POST['podcast_name'] ?? ''));
    if ($podcastName === '') $podcastName = null;

    // Cross-account cache: if a completed job already exists for this source URL
    // (from any account), reuse it — no need to re-process the same episode.
    if ($sourceUrl !== '') {
        $cacheCheck = $pdo->prepare(
            "SELECT id FROM jobs WHERE source_url=? AND status='completed' LIMIT 1"
        );
        $cacheCheck->execute([$sourceUrl]);
        $cachedId = $cacheCheck->fetchColumn();
        if ($cachedId !== false) {
            jsonResponse(200, [
                'job_id'       => $cachedId,
                'status'       => 'completed',
                'cached'       => true,
                'poll_url'     => withBasePath((string)$config['base_path'], '/api/jobs/' . $cachedId),
                'download_url' => withBasePath((string)$config['base_path'], '/api/jobs/' . $cachedId . '/download'),
            ]);
            return;
        }
    }

    $insert = $pdo->prepare(
        'INSERT INTO jobs (
            id, status, backend, detection_mode, openai_model,
            input_path, output_path, source_url, progress, error_message, logs,
            created_at, updated_at, started_at, finished_at, user_id, episode_title, podcast_name
        ) VALUES (
            :id, :status, :backend, :detection_mode, :openai_model,
            :input_path, :output_path, :source_url, :progress, :error_message, :logs,
            :created_at, :updated_at, :started_at, :finished_at, :user_id, :episode_title, :podcast_name
        )'
    );

    $insert->execute([
        ':id' => $id,
        ':status' => 'queued',
        ':backend' => $backend,
        ':detection_mode' => $detectionMode,
        ':openai_model' => $openAiModel,
        ':input_path' => $inputPath,
        ':output_path' => $outputPath,
        ':source_url' => $sourceUrl === '' ? null : $sourceUrl,
        ':progress' => 0,
        ':error_message' => null,
        ':logs' => 'Queued for processing.',
        ':created_at' => $now,
        ':updated_at' => $now,
        ':started_at' => null,
        ':finished_at' => null,
        ':user_id' => $userId,
        ':episode_title' => $episodeTitle,
        ':podcast_name' => $podcastName,
    ]);

    $payload = [
        'job_id' => $id,
        'input_path' => $inputPath,
        'output_path' => $outputPath,
        'backend' => $backend,
        'detection_mode' => $detectionMode,
        'openai_model' => $openAiModel,
        'openai_api_key' => $openAiApiKey,
        'source_url' => $sourceUrl === '' ? null : $sourceUrl,
        'created_at' => $now,
    ];

    $queuePath = joinPath($config['queue_dir'], $id . '.json');
    $bytes = file_put_contents(
        $queuePath,
        json_encode($payload, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES),
        LOCK_EX
    );

    if ($bytes === false) {
        $pdo->prepare('DELETE FROM jobs WHERE id = :id')->execute([':id' => $id]);
        @unlink($inputPath);
        throw new RuntimeException('Failed to queue job for background processing.');
    }

    jsonResponse(201, [
        'job_id' => $id,
        'status' => 'queued',
        'poll_url' => withBasePath((string)$config['base_path'], '/api/jobs/' . $id),
        'download_url' => withBasePath((string)$config['base_path'], '/api/jobs/' . $id . '/download'),
    ]);
}

function detectUrlExtension(string $sourceUrl): string
{
    $path = parse_url($sourceUrl, PHP_URL_PATH);
    if (!is_string($path) || $path === '') {
        return 'm4a';
    }

    $extension = strtolower(pathinfo($path, PATHINFO_EXTENSION));
    if ($extension === '') {
        return 'm4a';
    }

    $clean = preg_replace('/[^a-z0-9]/', '', $extension);
    if (!is_string($clean) || $clean === '') {
        return 'm4a';
    }

    return substr($clean, 0, 6);
}

function validateSourceUrl(string $sourceUrl): void
{
    $parts = parse_url($sourceUrl);
    if (!is_array($parts)) {
        throw new RuntimeException('Invalid source_url.');
    }

    $scheme = strtolower((string)($parts['scheme'] ?? ''));
    if (!in_array($scheme, ['http', 'https'], true)) {
        throw new RuntimeException('source_url must use http or https.');
    }

    $host = strtolower((string)($parts['host'] ?? ''));
    if ($host === '') {
        throw new RuntimeException('source_url is missing a host.');
    }

    if (in_array($host, ['localhost', '127.0.0.1', '::1'], true)) {
        throw new RuntimeException('source_url host is not allowed.');
    }
}

function downloadRemoteAudio(string $sourceUrl, string $destinationPath): void
{
    $parts = parse_url($sourceUrl);
    if (!is_array($parts)) {
        throw new RuntimeException('Invalid source_url.');
    }

    $scheme = strtolower((string)($parts['scheme'] ?? ''));
    if (!in_array($scheme, ['http', 'https'], true)) {
        throw new RuntimeException('source_url must use http or https.');
    }

    $host = strtolower((string)($parts['host'] ?? ''));
    if ($host === '') {
        throw new RuntimeException('source_url is missing a host.');
    }

    if (in_array($host, ['localhost', '127.0.0.1', '::1'], true)) {
        throw new RuntimeException('source_url host is not allowed.');
    }

    $tmpPath = $destinationPath . '.part';
    @unlink($tmpPath);

    if (function_exists('curl_init')) {
        $fp = fopen($tmpPath, 'wb');
        if ($fp === false) {
            throw new RuntimeException('Unable to prepare temporary file for remote download.');
        }

        $ch = curl_init($sourceUrl);
        if ($ch === false) {
            fclose($fp);
            throw new RuntimeException('Failed to initialize remote download.');
        }

        curl_setopt_array($ch, [
            CURLOPT_FILE => $fp,
            CURLOPT_FOLLOWLOCATION => true,
            CURLOPT_MAXREDIRS => 6,
            CURLOPT_CONNECTTIMEOUT => 20,
            CURLOPT_TIMEOUT => 600,
            CURLOPT_FAILONERROR => true,
            CURLOPT_SSL_VERIFYPEER => true,
            CURLOPT_SSL_VERIFYHOST => 2,
            CURLOPT_USERAGENT => 'AdFreePodcastPlayer/1.0',
        ]);

        $ok = curl_exec($ch);
        $error = $ok === false ? (string)curl_error($ch) : '';
        curl_close($ch);
        fclose($fp);

        if ($ok === false) {
            @unlink($tmpPath);
            throw new RuntimeException('Remote audio download failed: ' . $error);
        }
    } else {
        $context = stream_context_create([
            'http' => [
                'method' => 'GET',
                'follow_location' => 1,
                'max_redirects' => 6,
                'timeout' => 600,
                'header' => "User-Agent: AdFreePodcastPlayer/1.0\r\n",
            ],
            'ssl' => [
                'verify_peer' => true,
                'verify_peer_name' => true,
            ],
        ]);

        $input = @fopen($sourceUrl, 'rb', false, $context);
        if ($input === false) {
            throw new RuntimeException('Failed to open source_url for reading.');
        }

        $output = fopen($tmpPath, 'wb');
        if ($output === false) {
            fclose($input);
            throw new RuntimeException('Unable to write temporary download file.');
        }

        $bytes = stream_copy_to_stream($input, $output);
        fclose($input);
        fclose($output);

        if ($bytes === false || $bytes <= 0) {
            @unlink($tmpPath);
            throw new RuntimeException('Remote audio download returned no data.');
        }
    }

    if (!is_file($tmpPath) || (int)filesize($tmpPath) <= 0) {
        @unlink($tmpPath);
        throw new RuntimeException('Remote audio download produced an empty file.');
    }

    if (!rename($tmpPath, $destinationPath)) {
        @unlink($tmpPath);
        throw new RuntimeException('Failed to finalize downloaded audio file.');
    }
}

function getJob(PDO $pdo, array $config, string $id): void
{
    $job = fetchJob($pdo, $id);
    if ($job === null) {
        jsonResponse(404, ['error' => 'Job not found']);
    }

    jsonResponse(200, normalizeJob($job, (string)$config['base_path'], (string)$config['artifacts_dir']));
}

function downloadJobOutput(PDO $pdo, string $id): void
{
    $job = fetchJob($pdo, $id);
    if ($job === null) {
        jsonResponse(404, ['error' => 'Job not found']);
    }

    if (($job['status'] ?? '') !== 'completed') {
        jsonResponse(409, ['error' => 'Job is not complete yet']);
    }

    $outputPath = (string)($job['output_path'] ?? '');
    if ($outputPath === '' || !is_file($outputPath)) {
        jsonResponse(410, ['error' => 'Output file is unavailable']);
    }

    $filename = basename($outputPath);
    header('Content-Type: audio/mp4');
    header('Content-Length: ' . (string)filesize($outputPath));
    header('Content-Disposition: attachment; filename="' . $filename . '"');
    readfile($outputPath);
    exit;
}

function cancelJob(PDO $pdo, array $config, string $id): void
{
    $job = fetchJob($pdo, $id);
    if ($job === null) {
        jsonResponse(404, ['error' => 'Job not found']);
    }

    $status = (string)($job['status'] ?? '');
    if (in_array($status, ['completed', 'failed', 'cancelled'], true)) {
        jsonResponse(200, normalizeJob($job, (string)$config['base_path'], (string)$config['artifacts_dir']));
    }

    $now = gmdate(DATE_ATOM);
    $update = $pdo->prepare(
        'UPDATE jobs
         SET status = :status,
             progress = :progress,
             error_message = :error_message,
             updated_at = :updated_at,
             finished_at = :finished_at
         WHERE id = :id'
    );

    $update->execute([
        ':status' => 'cancelled',
        ':progress' => (float)($job['progress'] ?? 0),
        ':error_message' => 'Cancelled by user.',
        ':updated_at' => $now,
        ':finished_at' => $now,
        ':id' => $id,
    ]);

    @unlink(joinPath($config['queue_dir'], $id . '.json'));
    @unlink(joinPath($config['queue_dir'], $id . '.working'));

    $updatedJob = fetchJob($pdo, $id);
    jsonResponse(
        200,
        $updatedJob === null
            ? ['job_id' => $id, 'status' => 'cancelled']
            : normalizeJob($updatedJob, (string)$config['base_path'], (string)$config['artifacts_dir'])
    );
}

function listJobs(PDO $pdo, array $config): void
{
    $statusFilter = trim((string)($_GET['status'] ?? ''));
    $userIdFilter = trim((string)($_GET['user_id'] ?? ''));
    $limit = max(1, min(200, (int)($_GET['limit'] ?? 50)));
    $offset = max(0, (int)($_GET['offset'] ?? 0));

    $conditions = [];
    $params = [];

    if ($statusFilter !== '') {
        $statuses = array_values(array_filter(array_map('trim', explode(',', $statusFilter))));
        $placeholders = implode(',', array_fill(0, count($statuses), '?'));
        $conditions[] = 'status IN (' . $placeholders . ')';
        foreach ($statuses as $s) {
            $params[] = $s;
        }
    }

    if ($userIdFilter !== '') {
        // The test account inherits all jobs with no owner (user_id IS NULL).
        $testCheck = $pdo->prepare('SELECT COUNT(*) FROM users WHERE id=? AND name=?');
        $testCheck->execute([$userIdFilter, 'test']);
        $isTestUser = (int)$testCheck->fetchColumn() > 0;

        if ($isTestUser) {
            $conditions[] = '(user_id=? OR user_id IS NULL)';
        } else {
            $conditions[] = 'user_id=?';
        }
        $params[] = $userIdFilter;
    }

    $where = count($conditions) > 0 ? 'WHERE ' . implode(' AND ', $conditions) : '';
    $params[] = $limit;
    $params[] = $offset;

    $query = $pdo->prepare('SELECT * FROM jobs ' . $where . ' ORDER BY created_at DESC LIMIT ? OFFSET ?');
    $query->execute($params);

    $rows = $query->fetchAll();
    $basePath = (string)$config['base_path'];

    jsonResponse(200, [
        'jobs' => array_map(fn($row) => normalizeJob($row, $basePath, (string)$config['artifacts_dir']), is_array($rows) ? $rows : []),
    ]);
}

function getWorkerStatus(array $config): void
{
    $heartbeatPath = (string)($config['worker_heartbeat'] ?? '');
    $lockDir = (string)($config['worker_lock_dir'] ?? '');
    $queueDir = (string)($config['queue_dir'] ?? '');

    $heartbeat = null;
    $heartbeatAgeSeconds = null;
    if ($heartbeatPath !== '' && is_file($heartbeatPath)) {
        $raw = file_get_contents($heartbeatPath);
        $decoded = is_string($raw) ? json_decode($raw, true) : null;
        $heartbeat = is_array($decoded) ? $decoded : null;
        $mtime = filemtime($heartbeatPath);
        if ($mtime !== false) {
            $heartbeatAgeSeconds = max(0, time() - $mtime);
        }
    }

    $queueCount = 0;
    if ($queueDir !== '' && is_dir($queueDir)) {
        $files = glob(joinPath($queueDir, '*.json'));
        $queueCount = is_array($files) ? count($files) : 0;
    }

    $lockPresent = $lockDir !== '' && is_dir($lockDir);
    $running = $lockPresent && $heartbeatAgeSeconds !== null && $heartbeatAgeSeconds <= 20;

    jsonResponse(200, [
        'running' => $running,
        'lock_present' => $lockPresent,
        'heartbeat_age_seconds' => $heartbeatAgeSeconds,
        'heartbeat' => $heartbeat,
        'queue_count' => $queueCount,
        'start_command' => 'ssh agitated-engelbart_9pw3g4pzt1v@74.208.203.194 "nohup /var/www/vhosts/agitated-engelbart.74-208-203-194.plesk.page/adfree-stack/worker/run_daemon.sh >> /var/www/vhosts/agitated-engelbart.74-208-203-194.plesk.page/adfree-stack/logs/worker-daemon.log 2>&1 &"',
        'watchdog_command' => '/var/www/vhosts/agitated-engelbart.74-208-203-194.plesk.page/adfree-stack/worker/run_daemon.sh >> /var/www/vhosts/agitated-engelbart.74-208-203-194.plesk.page/adfree-stack/logs/worker-daemon.log 2>&1',
    ]);
}

function serveJobArtifact(PDO $pdo, array $config, string $id, string $type): void
{
    $job = fetchJob($pdo, $id);
    if ($job === null) {
        jsonResponse(404, ['error' => 'Job not found']);
    }

    $artifactsDir = joinPath((string)$config['artifacts_dir'], $id);

    if ($type === 'transcript') {
        $filePath = (string)($job['transcript_path'] ?? '');
        if ($filePath === '' || !is_file($filePath)) {
            $filePath = joinPath($artifactsDir, 'transcript.txt');
        }
        if (!is_file($filePath)) {
            jsonResponse(404, ['error' => 'Transcript not available for this job']);
        }
        header('Content-Type: text/plain; charset=utf-8');
        header('Content-Length: ' . (string)filesize($filePath));
        header('Content-Disposition: inline; filename="transcript-' . $id . '.txt"');
        readfile($filePath);
        exit;
    }

    if ($type === 'timestamps') {
        $filePath = (string)($job['timestamps_path'] ?? '');
        if ($filePath === '' || !is_file($filePath)) {
            $filePath = joinPath($artifactsDir, 'timestamps.json');
        }
        if (!is_file($filePath)) {
            jsonResponse(404, ['error' => 'Timestamps not available for this job']);
        }
        header('Content-Type: application/json');
        header('Content-Length: ' . (string)filesize($filePath));
        header('Content-Disposition: inline; filename="timestamps-' . $id . '.json"');
        readfile($filePath);
        exit;
    }

    if ($type === 'stats') {
        $statsPath = joinPath($artifactsDir, 'stats.json');
        if (!is_file($statsPath)) {
            // Return stats from DB fields directly
            jsonResponse(200, [
                'job_id' => $id,
                'status' => (string)$job['status'],
                'backend' => (string)$job['backend'],
                'detection_mode' => (string)$job['detection_mode'],
                'created_at' => (string)$job['created_at'],
                'started_at' => $job['started_at'] === null ? null : (string)$job['started_at'],
                'finished_at' => $job['finished_at'] === null ? null : (string)$job['finished_at'],
                'duration_seconds' => isset($job['duration_seconds']) && $job['duration_seconds'] !== null
                    ? (float)$job['duration_seconds'] : null,
                'source_url' => isset($job['source_url']) ? (string)$job['source_url'] : null,
            ]);
        }
        header('Content-Type: application/json');
        header('Content-Length: ' . (string)filesize($statsPath));
        readfile($statsPath);
        exit;
    }

    if ($type === 'timestamped-transcript') {
        $filePath = joinPath($artifactsDir, 'transcript_timestamped.txt');
        if (!is_file($filePath)) {
            jsonResponse(404, ['error' => 'Timestamped transcript not available for this job']);
        }
        header('Content-Type: text/plain; charset=utf-8');
        header('Content-Length: ' . (string)filesize($filePath));
        header('Content-Disposition: inline; filename="timestamped-transcript-' . $id . '.txt"');
        readfile($filePath);
        exit;
    }

    if ($type === 'verification') {
        $verificationPath = joinPath($artifactsDir, 'verification.json');
        if (!is_file($verificationPath)) {
            jsonResponse(404, ['error' => 'Verification artifact not available for this job']);
        }
        header('Content-Type: application/json');
        header('Content-Length: ' . (string)filesize($verificationPath));
        readfile($verificationPath);
        exit;
    }

    jsonResponse(400, ['error' => 'Unknown artifact type']);
}

function fetchJob(PDO $pdo, string $id): ?array
{
    $query = $pdo->prepare('SELECT * FROM jobs WHERE id = :id LIMIT 1');
    $query->execute([':id' => $id]);
    $result = $query->fetch();
    return is_array($result) ? $result : null;
}

function normalizeJob(array $job, string $basePath = '', string $artifactsRoot = ''): array
{
    $outputPath = (string)($job['output_path'] ?? '');
    $jobId = (string)$job['id'];
    $status = (string)($job['status'] ?? '');

    $transcriptPath = isset($job['transcript_path']) && $job['transcript_path'] !== null
        ? (string)$job['transcript_path'] : '';
    $timestampsPath = isset($job['timestamps_path']) && $job['timestamps_path'] !== null
        ? (string)$job['timestamps_path'] : '';

    return [
        'job_id' => $jobId,
        'status' => $status,
        'backend' => (string)$job['backend'],
        'detection_mode' => (string)$job['detection_mode'],
        'openai_model' => (string)$job['openai_model'],
        'source_url' => isset($job['source_url']) ? (string)$job['source_url'] : null,
        'episode_title' => isset($job['episode_title']) ? (string)$job['episode_title'] : null,
        'podcast_name' => isset($job['podcast_name']) ? (string)$job['podcast_name'] : null,
        'progress' => (float)$job['progress'],
        'duration_seconds' => isset($job['duration_seconds']) && $job['duration_seconds'] !== null
            ? (float)$job['duration_seconds'] : null,
        'error_message' => $job['error_message'] === null ? null : (string)$job['error_message'],
        'logs' => $job['logs'] === null ? null : (string)$job['logs'],
        'created_at' => (string)$job['created_at'],
        'updated_at' => (string)$job['updated_at'],
        'started_at' => $job['started_at'] === null ? null : (string)$job['started_at'],
        'finished_at' => $job['finished_at'] === null ? null : (string)$job['finished_at'],
        'download_url' => ($status === 'completed' && is_file($outputPath))
            ? withBasePath($basePath, '/api/jobs/' . $jobId . '/download')
            : null,
        'transcript_url' => ($transcriptPath !== '' && is_file($transcriptPath))
            ? withBasePath($basePath, '/api/jobs/' . $jobId . '/artifact/transcript')
            : null,
        'timestamped_transcript_url' => ($artifactsRoot !== '' && is_file(joinPath(joinPath($artifactsRoot, $jobId), 'transcript_timestamped.txt')))
            ? withBasePath($basePath, '/api/jobs/' . $jobId . '/artifact/timestamped-transcript')
            : null,
        'timestamps_url' => ($timestampsPath !== '' && is_file($timestampsPath))
            ? withBasePath($basePath, '/api/jobs/' . $jobId . '/artifact/timestamps')
            : null,
        'stats_url' => withBasePath($basePath, '/api/jobs/' . $jobId . '/artifact/stats'),
        'verification_url' => ($artifactsRoot !== '' && is_file(joinPath(joinPath($artifactsRoot, $jobId), 'verification.json')))
            ? withBasePath($basePath, '/api/jobs/' . $jobId . '/artifact/verification')
            : null,
        'user_id' => isset($job['user_id']) ? (string)$job['user_id'] : null,
    ];
}

function withBasePath(string $basePath, string $path): string
{
    $base = rtrim($basePath, '/');
    if ($base === '') {
        return $path;
    }

    return $base . $path;
}

function validateBackend(string $backend): string
{
    $normalized = strtolower(trim($backend));
    $allowed = ['whisper', 'openai-whisper'];
    if (!in_array($normalized, $allowed, true)) {
        throw new RuntimeException('Unsupported backend. Allowed values: whisper, openai-whisper.');
    }
    return $normalized;
}

function validateDetectionMode(string $mode): string
{
    $normalized = strtolower(trim($mode));
    $allowed = ['local', 'hybrid', 'openai'];
    if (!in_array($normalized, $allowed, true)) {
        throw new RuntimeException('Unsupported detection mode. Allowed values: local, hybrid, openai.');
    }
    return $normalized;
}

function detectUploadExtension(string $originalName): string
{
    $extension = strtolower(pathinfo($originalName, PATHINFO_EXTENSION));
    if ($extension === '') {
        return 'm4a';
    }

    $clean = preg_replace('/[^a-z0-9]/', '', $extension);
    if (!is_string($clean) || $clean === '') {
        return 'm4a';
    }

    return substr($clean, 0, 6);
}

function ensureDirectory(string $path): void
{
    if (is_dir($path)) {
        return;
    }

    if (!mkdir($path, 0777, true) && !is_dir($path)) {
        throw new RuntimeException('Failed to create directory: ' . $path);
    }
}

function joinPath(string $left, string $right): string
{
    return rtrim($left, '/\\') . DIRECTORY_SEPARATOR . ltrim($right, '/\\');
}

function jsonResponse(int $status, array $payload): void
{
    http_response_code($status);
    header('Content-Type: application/json');
    echo json_encode($payload, JSON_UNESCAPED_SLASHES);
    exit;
}

function generateUuid(): string
{
    $data = random_bytes(16);
    $data[6] = chr((ord($data[6]) & 0x0f) | 0x40);
    $data[8] = chr((ord($data[8]) & 0x3f) | 0x80);
    return vsprintf('%s%s-%s-%s-%s-%s%s%s', str_split(bin2hex($data), 4));
}

function listUsers(PDO $pdo): void
{
    $rows = $pdo->query('SELECT id, name, device_fingerprint, created_at FROM users ORDER BY created_at')->fetchAll();
    jsonResponse(200, ['users' => is_array($rows) ? $rows : []]);
}

function createUser(PDO $pdo): void
{
    $body = json_decode(file_get_contents('php://input'), true) ?? [];
    $name = trim((string)($body['name'] ?? ''));
    $fingerprint = trim((string)($body['device_fingerprint'] ?? ''));
    if ($name === '') {
        throw new RuntimeException('name is required.');
    }

    // If a fingerprint was provided and a user already has it, return that user.
    if ($fingerprint !== '') {
        $check = $pdo->prepare('SELECT id, name, device_fingerprint, created_at FROM users WHERE device_fingerprint=? LIMIT 1');
        $check->execute([$fingerprint]);
        $existing = $check->fetch();
        if ($existing) {
            jsonResponse(200, $existing);
            return;
        }
    }

    $id = generateUuid();
    $now = gmdate(DATE_ATOM);
    $pdo->prepare('INSERT OR IGNORE INTO users (id, name, device_fingerprint, created_at) VALUES (?,?,?,?)')
        ->execute([$id, $name, $fingerprint !== '' ? $fingerprint : null, $now]);
    jsonResponse(201, ['id' => $id, 'name' => $name, 'device_fingerprint' => $fingerprint ?: null, 'created_at' => $now]);
}

function listSubscriptions(PDO $pdo, string $userId): void
{
    $rows = $pdo->prepare('SELECT id, user_id, podcast_title, podcast_author, feed_url, artwork_url, collection_id, added_at FROM subscriptions WHERE user_id=? ORDER BY podcast_title');
    $rows->execute([$userId]);
    jsonResponse(200, ['subscriptions' => $rows->fetchAll() ?: []]);
}

function addSubscription(PDO $pdo, string $userId): void
{
    $body = json_decode(file_get_contents('php://input'), true) ?? [];
    $feedUrl = trim((string)($body['feed_url'] ?? ''));
    $title = trim((string)($body['podcast_title'] ?? ''));
    if ($feedUrl === '') {
        throw new RuntimeException('feed_url is required.');
    }
    if ($title === '') {
        throw new RuntimeException('podcast_title is required.');
    }

    // Check user exists.
    $u = $pdo->prepare('SELECT id FROM users WHERE id=?');
    $u->execute([$userId]);
    if (!$u->fetch()) {
        jsonResponse(404, ['error' => 'User not found']);
        return;
    }

    // Return existing subscription if already subscribed.
    $existing = $pdo->prepare('SELECT id FROM subscriptions WHERE user_id=? AND feed_url=?');
    $existing->execute([$userId, $feedUrl]);
    $row = $existing->fetch();
    if ($row) {
        jsonResponse(200, ['id' => $row['id'], 'already_subscribed' => true]);
        return;
    }

    $id = generateUuid();
    $now = gmdate(DATE_ATOM);
    $pdo->prepare(
        'INSERT INTO subscriptions (id, user_id, podcast_title, podcast_author, feed_url, artwork_url, collection_id, added_at) VALUES (?,?,?,?,?,?,?,?)'
    )->execute([
        $id,
        $userId,
        $title,
        $body['podcast_author'] ?? null,
        $feedUrl,
        $body['artwork_url'] ?? null,
        isset($body['collection_id']) ? (int)$body['collection_id'] : null,
        $now,
    ]);
    jsonResponse(201, ['id' => $id, 'subscribed' => true]);
}

function removeSubscription(PDO $pdo, string $userId, string $subId): void
{
    $stmt = $pdo->prepare('DELETE FROM subscriptions WHERE id=? AND user_id=?');
    $stmt->execute([$subId, $userId]);
    jsonResponse(200, ['removed' => $stmt->rowCount() > 0]);
}

function deleteJob(PDO $pdo, array $config, string $id): void
{
    $stmt = $pdo->prepare('SELECT output_path FROM jobs WHERE id=?');
    $stmt->execute([$id]);
    $job = $stmt->fetch();
    if (!$job) {
        jsonResponse(404, ['error' => 'Job not found']);
        return;
    }

    if (!empty($job['output_path']) && is_file($job['output_path'])) {
        @unlink($job['output_path']);
    }

    // Also clean up queue file if present.
    @unlink(joinPath($config['queue_dir'], $id . '.json'));

    $pdo->prepare('DELETE FROM jobs WHERE id=?')->execute([$id]);
    jsonResponse(200, ['deleted' => true]);
}

function clearJobs(PDO $pdo, array $config): void
{
    $body = json_decode(file_get_contents('php://input'), true) ?? [];
    $userId = trim((string)($body['user_id'] ?? ''));
    $status = trim((string)($body['status'] ?? 'completed'));

    $allowed = ['completed', 'failed', 'cancelled', 'all'];
    if (!in_array($status, $allowed, true)) {
        $status = 'completed';
    }

    // Build query clauses.
    $conditions = [];
    $args = [];

    if ($status !== 'all') {
        $conditions[] = 'status=?';
        $args[] = $status;
    }

    if ($userId !== '') {
        $conditions[] = 'user_id=?';
        $args[] = $userId;
    }

    $where = count($conditions) > 0 ? 'WHERE ' . implode(' AND ', $conditions) : '';

    // Delete output files first.
    $q = $pdo->prepare('SELECT output_path FROM jobs ' . $where);
    $q->execute($args);
    foreach ($q->fetchAll() as $row) {
        if (!empty($row['output_path']) && is_file($row['output_path'])) {
            @unlink($row['output_path']);
        }
    }

    $del = $pdo->prepare('DELETE FROM jobs ' . $where);
    $del->execute($args);
    jsonResponse(200, ['cleared' => $del->rowCount()]);
}

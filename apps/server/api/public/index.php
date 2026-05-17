<?php
declare(strict_types=1);

header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Headers: Content-Type, X-Adfree-Bridge-Token');
header('Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS');

if (($_SERVER['REQUEST_METHOD'] ?? 'GET') === 'OPTIONS') {
    http_response_code(204);
    exit;
}

// Completed-output reuse is only safe after the current GPT detection policy.
// This cutoff excludes older runs that could remove comedic/parody ad bits.
const GPT_ONLY_CACHE_EPOCH = '2026-05-16T23:50:00+00:00';
const WORKER_HEARTBEAT_FRESH_SECONDS = 20;
const STALE_JOB_GRACE_SECONDS = 300;
const RUNNING_JOB_HARD_TIMEOUT_SECONDS = 21600;

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
        getWorkerStatus($pdo, $config);
    }

    if ($method === 'GET' && $uri === '/api/local/health') {
        getLocalBridgeHealth($config);
    }

    if ($method === 'POST' && $uri === '/api/local/process') {
        processLocalBridgeJob($config);
    }

    if ($method === 'GET' && preg_match('#^/api/local/jobs/([a-f0-9]{32})/status$#', $uri, $matches) === 1) {
        serveLocalBridgeStatus($config, $matches[1]);
    }

    if ($method === 'GET' && preg_match('#^/api/local/jobs/([a-f0-9]{32})/download$#', $uri, $matches) === 1) {
        serveLocalBridgeFile($config, $matches[1], 'download');
    }

    if ($method === 'GET' && preg_match('#^/api/local/jobs/([a-f0-9]{32})/artifact/(transcript|timestamped-transcript|timestamps|stats)$#', $uri, $matches) === 1) {
        serveLocalBridgeFile($config, $matches[1], $matches[2]);
    }

    if ($method === 'POST' && $uri === '/api/jobs') {
        createJob($pdo, $config);
    }

    if ($method === 'GET' && preg_match('#^/api/jobs/([a-f0-9]{32})$#', $uri, $matches) === 1) {
        getJob($pdo, $config, $matches[1]);
    }

    if ($method === 'GET' && preg_match('#^/api/jobs/([a-f0-9]{32})/download$#', $uri, $matches) === 1) {
        downloadJobOutput($pdo, $config, $matches[1]);
    }

    if ($method === 'GET' && preg_match('#^/api/jobs/([a-f0-9]{32})/artifact/(transcript|timestamped-transcript|timestamps|stats)$#', $uri, $matches) === 1) {
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
        'local_bridge_url' => envOrDefault('LOCAL_BRIDGE_URL', 'http://127.0.0.1:8081/adfree-api'),
        'local_bridge_token' => envOrDefault('LOCAL_BRIDGE_TOKEN', ''),
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
    if (is_array($results)) {
        usort($results, static function ($a, $b): int {
            $dateA = is_array($a) ? strtotime((string)($a['releaseDate'] ?? $a['pubDate'] ?? '')) : false;
            $dateB = is_array($b) ? strtotime((string)($b['releaseDate'] ?? $b['pubDate'] ?? '')) : false;
            return ((int)($dateB ?: 0)) <=> ((int)($dateA ?: 0));
        });
    }

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

    $caBundlePath = resolveCaBundlePath();

    if (function_exists('curl_init')) {
        $ch = curl_init($url);
        if ($ch === false) {
            throw new RuntimeException('Failed to initialize remote request.');
        }

        $curlOptions = [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_FOLLOWLOCATION => true,
            CURLOPT_MAXREDIRS => 4,
            CURLOPT_CONNECTTIMEOUT => 15,
            CURLOPT_TIMEOUT => 30,
            CURLOPT_FAILONERROR => true,
            CURLOPT_SSL_VERIFYPEER => true,
            CURLOPT_SSL_VERIFYHOST => 2,
            CURLOPT_USERAGENT => 'AdFreePodcastPlayer/1.0',
        ];
        if ($caBundlePath !== '') {
            $curlOptions[CURLOPT_CAINFO] = $caBundlePath;
        }

        curl_setopt_array($ch, $curlOptions);

        $body = curl_exec($ch);
        if ($body === false) {
            $error = (string)curl_error($ch);
            curl_close($ch);
            throw new RuntimeException('Remote request failed: ' . $error);
        }

        curl_close($ch);
    } else {
        $sslOptions = [
            'verify_peer' => true,
            'verify_peer_name' => true,
        ];
        if ($caBundlePath !== '') {
            $sslOptions['cafile'] = $caBundlePath;
        }

        $context = stream_context_create([
            'http' => [
                'method' => 'GET',
                'timeout' => 30,
                'header' => "User-Agent: AdFreePodcastPlayer/1.0\r\n",
            ],
            'ssl' => $sslOptions,
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

    if ($sourceUrl === '') {
        throw new RuntimeException('Ad removal requires an episode audio URL. The Windows processor does not accept browser file uploads.');
    }
    if ($hasUpload) {
        throw new RuntimeException('Browser uploads are not supported for ad removal. Use an episode audio URL so the Windows processor can fetch it.');
    }
    validateSourceUrl($sourceUrl);

    $backend = validateBackend((string)($_POST['backend'] ?? 'tunnel-parakeet'));
    $detectionMode = validateDetectionMode((string)($_POST['detection_mode'] ?? 'openai'));
    $openAiModel = trim(envOrDefault('OPENAI_MODEL', 'gpt-5.5'));
    if ($openAiModel === '') {
        $openAiModel = 'gpt-5.5';
    }
    $id = bin2hex(random_bytes(16));
    $extension = detectUrlExtension($sourceUrl);
    $inputPath = joinPath($config['input_dir'], $id . '.' . $extension);
    $outputPath = joinPath($config['output_dir'], $id . '.noads.mp3');

    $now = gmdate(DATE_ATOM);

    $userId = trim((string)($_POST['user_id'] ?? ($_GET['user_id'] ?? '')));
    if ($userId === '') $userId = null;

    $episodeTitle = trim((string)($_POST['episode_title'] ?? ''));
    if ($episodeTitle === '') $episodeTitle = null;
    $podcastName = trim((string)($_POST['podcast_name'] ?? ''));
    if ($podcastName === '') $podcastName = null;

    // Cross-account cache: if a completed job already exists for this source URL
    // (from any account), reuse it — no need to re-process the same episode.
    $cacheCheck = $pdo->prepare(
        "SELECT id FROM jobs WHERE source_url=? AND status='completed' AND backend=? AND detection_mode=? AND created_at >= ? ORDER BY created_at DESC LIMIT 20"
    );
    $cacheCheck->execute([$sourceUrl, $backend, $detectionMode, GPT_ONLY_CACHE_EPOCH]);
    $cachedIds = $cacheCheck->fetchAll(PDO::FETCH_COLUMN);
    foreach ($cachedIds as $cachedId) {
        $cachedId = (string)$cachedId;
        if (jobHasHeuristicArtifact($pdo, $config, $cachedId)) {
            continue;
        }
        jsonResponse(200, [
            'job_id'       => $cachedId,
            'status'       => 'completed',
            'cached'       => true,
            'poll_url'     => withBasePath((string)$config['base_path'], '/api/jobs/' . $cachedId),
            'download_url' => withBasePath((string)$config['base_path'], '/api/jobs/' . $cachedId . '/download'),
        ]);
        return;
    }

    $bridgeStatus = probeLocalBridge($config);
    if (!($bridgeStatus['reachable'] ?? false)) {
        $bridgeMessage = trim((string)($bridgeStatus['message'] ?? ''));
        $bridgeMessage = preg_replace('/^Ads cannot be removed right now\.\s*/i', '', $bridgeMessage) ?? $bridgeMessage;
        $suffix = $bridgeMessage !== '' ? ' ' . $bridgeMessage : ' Start WAMP and the Windows tunnel, then try again.';
        throw new RuntimeException('Ads cannot be removed right now because the Windows processor is unavailable.' . $suffix);
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
        'source_url' => $sourceUrl === '' ? null : $sourceUrl,
        'episode_title' => $episodeTitle,
        'podcast_name' => $podcastName,
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
    $caBundlePath = resolveCaBundlePath();

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

        $curlOptions = [
            CURLOPT_FILE => $fp,
            CURLOPT_FOLLOWLOCATION => true,
            CURLOPT_MAXREDIRS => 6,
            CURLOPT_CONNECTTIMEOUT => 20,
            CURLOPT_TIMEOUT => 600,
            CURLOPT_FAILONERROR => true,
            CURLOPT_SSL_VERIFYPEER => true,
            CURLOPT_SSL_VERIFYHOST => 2,
            CURLOPT_USERAGENT => 'AdFreePodcastPlayer/1.0',
        ];

        if ($caBundlePath !== '') {
            $curlOptions[CURLOPT_CAINFO] = $caBundlePath;
        }

        curl_setopt_array($ch, $curlOptions);

        $ok = curl_exec($ch);
        $error = $ok === false ? (string)curl_error($ch) : '';
        curl_close($ch);
        fclose($fp);

        if ($ok === false) {
            @unlink($tmpPath);
            throw new RuntimeException('Remote audio download failed: ' . $error);
        }
    } else {
        $sslOptions = [
            'verify_peer' => true,
            'verify_peer_name' => true,
        ];

        if ($caBundlePath !== '') {
            $sslOptions['cafile'] = $caBundlePath;
        }

        $context = stream_context_create([
            'http' => [
                'method' => 'GET',
                'follow_location' => 1,
                'max_redirects' => 6,
                'timeout' => 600,
                'header' => "User-Agent: AdFreePodcastPlayer/1.0\r\n",
            ],
            'ssl' => $sslOptions,
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

function resolveCaBundlePath(): string
{
    $explicitCandidates = [
        'ADFREE_CA_BUNDLE' => getenv('ADFREE_CA_BUNDLE'),
        'CURL_CA_BUNDLE' => getenv('CURL_CA_BUNDLE'),
        'SSL_CERT_FILE' => getenv('SSL_CERT_FILE'),
    ];

    foreach ($explicitCandidates as $name => $value) {
        if (!is_string($value) || trim($value) === '') {
            continue;
        }

        $path = normalizeLocalPath($value);
        if (!is_file($path)) {
            throw new RuntimeException('Configured CA bundle was not found for ' . $name . ': ' . $value);
        }
        return $path;
    }

    $iniCandidates = [ini_get('curl.cainfo'), ini_get('openssl.cafile')];
    foreach ($iniCandidates as $value) {
        if (!is_string($value) || trim($value) === '') {
            continue;
        }

        $path = normalizeLocalPath($value);
        if (is_file($path)) {
            return $path;
        }
    }

    $fallbackCandidates = [];
    $storageRoot = getenv('APP_STORAGE');
    if (is_string($storageRoot) && trim($storageRoot) !== '') {
        $fallbackCandidates[] = joinPath($storageRoot, 'cacert.pem');
    }
    $fallbackCandidates[] = 'D:/wamp64/storage/cacert.pem';
    $fallbackCandidates[] = 'C:/wamp64/storage/cacert.pem';
    $fallbackCandidates[] = 'D:/wamp64/cacert.pem';
    $fallbackCandidates[] = 'C:/wamp64/cacert.pem';

    foreach ($fallbackCandidates as $value) {
        $path = normalizeLocalPath($value);
        if (is_file($path)) {
            return $path;
        }
    }

    return '';
}

function normalizeLocalPath(string $path): string
{
    return str_replace('\\', DIRECTORY_SEPARATOR, trim($path));
}

function getJob(PDO $pdo, array $config, string $id): void
{
    reconcileStaleJobs($pdo, $config);

    $job = fetchJob($pdo, $id);
    if ($job === null) {
        jsonResponse(404, ['error' => 'Job not found']);
    }
    if (jobIsRemovedHeuristicOutput($pdo, $config, $job)) {
        jsonResponse(410, ['error' => 'This completed output used removed heuristic detection and is no longer available. Run ad removal again with GPT detection.']);
    }

    jsonResponse(200, normalizeJob($job, (string)$config['base_path'], (string)$config['artifacts_dir']));
}

function downloadJobOutput(PDO $pdo, array $config, string $id): void
{
    $job = fetchJob($pdo, $id);
    if ($job === null) {
        jsonResponse(404, ['error' => 'Job not found']);
    }

    if (($job['status'] ?? '') !== 'completed') {
        jsonResponse(409, ['error' => 'Job is not complete yet']);
    }
    if (jobIsRemovedHeuristicOutput($pdo, $config, $job)) {
        jsonResponse(410, ['error' => 'This completed output used removed heuristic detection and is no longer available. Run ad removal again with GPT detection.']);
    }

    $outputPath = (string)($job['output_path'] ?? '');
    if ($outputPath === '' || !is_file($outputPath)) {
        jsonResponse(410, ['error' => 'Output file is unavailable']);
    }

    $filename = basename($outputPath);
    header('Content-Type: ' . audioContentType($outputPath));
    header('Content-Length: ' . (string)filesize($outputPath));
    header('Content-Disposition: attachment; filename="' . $filename . '"');
    readfile($outputPath);
    exit;
}

function audioContentType(string $path): string
{
    return match (strtolower(pathinfo($path, PATHINFO_EXTENSION))) {
        'mp3' => 'audio/mpeg',
        'wav' => 'audio/wav',
        'm4a', 'mp4' => 'audio/mp4',
        default => 'application/octet-stream',
    };
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
    reconcileStaleJobs($pdo, $config);

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
    $jobs = [];
    foreach (is_array($rows) ? $rows : [] as $row) {
        if (jobIsRemovedHeuristicOutput($pdo, $config, $row)) {
            continue;
        }
        $jobs[] = normalizeJob($row, $basePath, (string)$config['artifacts_dir']);
    }

    jsonResponse(200, [
        'jobs' => $jobs,
    ]);
}

function getWorkerStatus(PDO $pdo, array $config): void
{
    $runtime = workerRuntimeStatus($config);
    $staleJobsMarked = reconcileStaleJobs($pdo, $config, $runtime);

    jsonResponse(200, [
        'running' => (bool)$runtime['running'],
        'lock_present' => (bool)$runtime['lock_present'],
        'heartbeat_age_seconds' => $runtime['heartbeat_age_seconds'],
        'heartbeat' => $runtime['heartbeat'],
        'queue_count' => (int)$runtime['queue_count'],
        'stale_jobs_marked' => $staleJobsMarked,
        'local_bridge' => probeLocalBridge($config),
        'start_command' => 'ssh agitated-engelbart_9pw3g4pzt1v@74.208.203.194 "nohup /var/www/vhosts/agitated-engelbart.74-208-203-194.plesk.page/adfree-stack/worker/run_daemon.sh >> /var/www/vhosts/agitated-engelbart.74-208-203-194.plesk.page/adfree-stack/logs/worker-daemon.log 2>&1 &"',
        'watchdog_command' => '/var/www/vhosts/agitated-engelbart.74-208-203-194.plesk.page/adfree-stack/worker/run_daemon.sh >> /var/www/vhosts/agitated-engelbart.74-208-203-194.plesk.page/adfree-stack/logs/worker-daemon.log 2>&1',
    ]);
}

function workerRuntimeStatus(array $config): array
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
    $running = $lockPresent && $heartbeatAgeSeconds !== null && $heartbeatAgeSeconds <= WORKER_HEARTBEAT_FRESH_SECONDS;

    return [
        'running' => $running,
        'lock_present' => $lockPresent,
        'heartbeat_age_seconds' => $heartbeatAgeSeconds,
        'heartbeat' => $heartbeat,
        'queue_count' => $queueCount,
    ];
}

function reconcileStaleJobs(PDO $pdo, array $config, ?array $runtime = null): int
{
    $runtime = $runtime ?? workerRuntimeStatus($config);
    $workerRunning = (bool)($runtime['running'] ?? false);
    $queueDir = (string)($config['queue_dir'] ?? '');
    $nowTs = time();
    $marked = 0;

    $query = $pdo->prepare(
        "SELECT id, status, created_at, updated_at, started_at, logs
         FROM jobs
         WHERE status IN ('queued', 'running')"
    );
    $query->execute();

    foreach ($query->fetchAll() as $job) {
        $jobId = (string)($job['id'] ?? '');
        if ($jobId === '') {
            continue;
        }

        $status = (string)($job['status'] ?? '');
        $createdAt = parseTimestampSeconds((string)($job['created_at'] ?? ''));
        $updatedAt = parseTimestampSeconds((string)($job['updated_at'] ?? ''));
        $startedAt = parseTimestampSeconds((string)($job['started_at'] ?? ''));
        $lastTouched = $updatedAt ?? $startedAt ?? $createdAt ?? $nowTs;
        $ageSeconds = max(0, $nowTs - $lastTouched);

        $queuePath = $queueDir === '' ? '' : joinPath($queueDir, $jobId . '.json');
        $workingPath = $queueDir === '' ? '' : joinPath($queueDir, $jobId . '.working');
        $hasQueueFile = $queuePath !== '' && is_file($queuePath);
        $hasWorkingFile = $workingPath !== '' && is_file($workingPath);
        $reason = null;

        if (!$workerRunning && $ageSeconds >= STALE_JOB_GRACE_SECONDS) {
            $reason = $status === 'queued'
                ? 'The ad-removal worker is not running, so this queued conversion cannot continue. Try ad removal again after the worker is online.'
                : 'The ad-removal worker stopped before this conversion finished. Try ad removal again after the worker is online.';
        } elseif (!$hasQueueFile && !$hasWorkingFile && $ageSeconds >= STALE_JOB_GRACE_SECONDS) {
            $reason = 'The conversion queue record is missing, so this job cannot continue. Try ad removal again.';
        } elseif ($status === 'running' && $startedAt !== null && ($nowTs - $startedAt) >= RUNNING_JOB_HARD_TIMEOUT_SECONDS) {
            $reason = 'The conversion exceeded the maximum allowed runtime and was marked failed. Try ad removal again.';
        }

        if ($reason !== null) {
            markJobFailedFromApi($pdo, $config, $job, $reason);
            $marked++;
        }
    }

    return $marked;
}

function parseTimestampSeconds(string $value): ?int
{
    $value = trim($value);
    if ($value === '') {
        return null;
    }

    $timestamp = strtotime($value);
    return $timestamp === false ? null : $timestamp;
}

function markJobFailedFromApi(PDO $pdo, array $config, array $job, string $message): void
{
    $jobId = (string)($job['id'] ?? '');
    if ($jobId === '') {
        return;
    }

    $now = gmdate(DATE_ATOM);
    $existingLogs = trim((string)($job['logs'] ?? ''));
    $logLine = 'Marked failed by API health check: ' . $message;
    $logs = $existingLogs === '' ? $logLine : $existingLogs . "\n" . $logLine;

    $update = $pdo->prepare(
        "UPDATE jobs
         SET status = 'failed',
             progress = 100,
             error_message = :error_message,
             logs = :logs,
             updated_at = :updated_at,
             finished_at = :finished_at
         WHERE id = :id AND status IN ('queued', 'running')"
    );
    $update->execute([
        ':error_message' => $message,
        ':logs' => $logs,
        ':updated_at' => $now,
        ':finished_at' => $now,
        ':id' => $jobId,
    ]);

    @unlink(joinPath((string)$config['queue_dir'], $jobId . '.json'));
    @unlink(joinPath((string)$config['queue_dir'], $jobId . '.working'));
}

function probeLocalBridge(array $config): array
{
    $baseUrl = rtrim((string)($config['local_bridge_url'] ?? ''), '/');
    if ($baseUrl === '') {
        return ['configured' => false, 'reachable' => false];
    }

    $url = $baseUrl . '/api/local/health';
    $headers = "User-Agent: AdFreePodcastPlayer/1.0\r\n";
    $token = (string)($config['local_bridge_token'] ?? '');
    if ($token !== '') {
        $headers .= 'X-Adfree-Bridge-Token: ' . $token . "\r\n";
    }

    $context = stream_context_create([
        'http' => [
            'method' => 'GET',
            'timeout' => 2,
            'ignore_errors' => true,
            'header' => $headers,
        ],
    ]);
    $body = @file_get_contents($url, false, $context);
    $decoded = is_string($body) ? json_decode($body, true) : null;

    return [
        'configured' => true,
        'reachable' => is_array($decoded) && (bool)($decoded['ok'] ?? false),
        'url' => $baseUrl,
        'backend_options' => ['parakeet'],
        'message' => is_array($decoded) ? (string)($decoded['message'] ?? '') : 'Bridge health check did not return JSON.',
    ];
}

function serveJobArtifact(PDO $pdo, array $config, string $id, string $type): void
{
    $job = fetchJob($pdo, $id);
    if ($job === null) {
        jsonResponse(404, ['error' => 'Job not found']);
    }
    if (jobIsRemovedHeuristicOutput($pdo, $config, $job)) {
        jsonResponse(410, ['error' => 'This completed output used removed heuristic detection and is no longer available. Run ad removal again with GPT detection.']);
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

    jsonResponse(400, ['error' => 'Unknown artifact type']);
}

function getLocalBridgeHealth(array $config): void
{
    assertLocalBridgeAccess($config);
    $root = resolveLocalAdCutForgeRoot();
    $script = joinPath($root, 'src/ad_cut_forge.py');
    $adCutForgeAvailable = is_file($script);
    $pythonAvailable = localCommandExecutableAvailable(localPythonCommandParts());
    $parakeetPython = resolveLocalParakeetPython($root);
    $parakeetAvailable = $parakeetPython !== '' && is_file($parakeetPython);
    $ffmpegAvailable = localCommandExecutableAvailable(splitCommandPrefix(envOrDefault('FFMPEG_BIN', 'ffmpeg')));
    $ffprobeAvailable = localCommandExecutableAvailable(splitCommandPrefix(envOrDefault('FFPROBE_BIN', 'ffprobe')));
    $openAiKeyConfigured = trim(envOrDefault('OPENAI_API_KEY', '')) !== '';
    $missing = [];
    if (!$adCutForgeAvailable) {
        $missing[] = 'AdCutForge runner';
    }
    if (!$pythonAvailable) {
        $missing[] = 'Python runtime';
    }
    if (!$parakeetAvailable) {
        $missing[] = 'Parakeet runtime';
    }
    if (!$ffmpegAvailable) {
        $missing[] = 'ffmpeg';
    }
    if (!$ffprobeAvailable) {
        $missing[] = 'ffprobe';
    }
    if (!$openAiKeyConfigured) {
        $missing[] = 'OPENAI_API_KEY';
    }
    $ready = count($missing) === 0;

    jsonResponse(200, [
        'ok' => $ready,
        'service' => 'adfree-local-bridge',
        'message' => $ready
            ? 'Windows local processor bridge is ready.'
            : 'Ads cannot be removed right now. Missing: ' . implode(', ', $missing) . '.',
        'backends' => ['parakeet'],
        'transcription_backend' => 'parakeet',
        'adcutforge_root' => $root,
        'parakeet_python' => $parakeetPython,
        'adcutforge_available' => $adCutForgeAvailable,
        'python_available' => $pythonAvailable,
        'parakeet_available' => $parakeetAvailable,
        'ffmpeg_available' => $ffmpegAvailable,
        'ffprobe_available' => $ffprobeAvailable,
        'openai_key_configured' => $openAiKeyConfigured,
        'time' => gmdate(DATE_ATOM),
    ]);
}

function processLocalBridgeJob(array $config): void
{
    assertLocalBridgeAccess($config);
    @set_time_limit(0);
    @ini_set('max_execution_time', '0');
    ignore_user_abort(true);

    $body = readJsonBody();
    $sourceUrl = trim((string)($body['source_url'] ?? ''));
    if ($sourceUrl === '') {
        throw new RuntimeException('source_url is required for local bridge processing.');
    }
    validateSourceUrl($sourceUrl);

    $backend = 'parakeet';
    $detectionMode = validateDetectionMode((string)($body['detection_mode'] ?? 'openai'));
    $openAiModel = trim((string)($body['openai_model'] ?? 'gpt-5.5'));
    if ($openAiModel === '') {
        $openAiModel = 'gpt-5.5';
    }
    $openAiApiKey = trim(envOrDefault('OPENAI_API_KEY', ''));
    if ($openAiApiKey === '') {
        throw new RuntimeException('Windows processor is missing OPENAI_API_KEY. Set it in the WAMP/API environment before removing ads.');
    }

    $jobId = normalizeBridgeJobId((string)($body['job_id'] ?? ''));
    $jobDir = localBridgeJobDir($config, $jobId);
    $cachedResult = readLocalBridgeResult($jobDir);
    if ($cachedResult !== null) {
        writeLocalBridgeStatus($jobDir, $jobId, 'completed', 100.0, 'Complete', 'Reusing completed Windows bridge result.', (string)($cachedResult['logs'] ?? ''));
        jsonResponse(200, [
            'ok' => true,
            'job_id' => $jobId,
            'backend' => 'tunnel-' . $backend,
            'detection_mode' => (string)($cachedResult['detection_mode'] ?? $detectionMode),
            'download_url' => '/api/local/jobs/' . $jobId . '/download',
            'transcript_url' => '/api/local/jobs/' . $jobId . '/artifact/transcript',
            'timestamped_transcript_url' => '/api/local/jobs/' . $jobId . '/artifact/timestamped-transcript',
            'timestamps_url' => '/api/local/jobs/' . $jobId . '/artifact/timestamps',
            'stats_url' => '/api/local/jobs/' . $jobId . '/artifact/stats',
            'logs' => (string)($cachedResult['logs'] ?? 'Reusing completed Windows bridge result.'),
        ]);
    }

    $inputDir = joinPath($jobDir, 'input');
    ensureDirectory($inputDir);
    writeLocalBridgeStatus($jobDir, $jobId, 'running', 5.0, 'Starting', 'Preparing Windows processor job.');

    pruneLocalBridgeJobs($config);

    $extension = detectUrlExtension($sourceUrl);
    $inputPath = joinPath($inputDir, 'source.' . $extension);
    $started = microtime(true);
    try {
        writeLocalBridgeStatus($jobDir, $jobId, 'running', 10.0, 'Downloading source audio', 'Windows is downloading the source episode.');
        downloadRemoteAudio($sourceUrl, $inputPath);
        writeLocalBridgeStatus($jobDir, $jobId, 'running', 20.0, 'Source ready', 'Source audio is ready for transcription.');

        $statusUpdater = function (float $cliProgress, string $message, string $logs) use ($jobDir, $jobId): void {
            $localProgress = 20.0 + (max(0.0, min(100.0, $cliProgress)) * 0.70);
            $phase = localBridgePhaseFromMessage($message);
            writeLocalBridgeStatus($jobDir, $jobId, 'running', min(90.0, $localProgress), $phase, $message, $logs);
        };

        writeLocalBridgeStatus($jobDir, $jobId, 'running', 35.0, 'Starting Parakeet', 'Windows is loading Parakeet and preparing transcription.');
        $result = runLocalAdCutForge($config, $jobId, $inputPath, $backend, $detectionMode, $openAiApiKey, $openAiModel, $statusUpdater);
        $finished = microtime(true);
        writeLocalBridgeStatus($jobDir, $jobId, 'running', 95.0, 'Returning result', 'Windows finished processing and is preparing files for the server.', (string)$result['logs']);

        $statsPath = joinPath($jobDir, 'stats.json');
        file_put_contents($statsPath, json_encode([
            'job_id' => $jobId,
            'status' => 'completed',
            'backend' => 'tunnel-' . $backend,
            'detection_mode' => $detectionMode,
            'source_url' => $sourceUrl,
            'started_at' => gmdate(DATE_ATOM, (int)$started),
            'finished_at' => gmdate(DATE_ATOM, (int)$finished),
            'duration_seconds' => round($finished - $started, 1),
            'adcutforge_root' => resolveLocalAdCutForgeRoot(),
        ], JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES));

        $resultPayload = [
            'job_id' => $jobId,
            'backend' => 'tunnel-' . $backend,
            'output_path' => $result['output_path'],
            'transcript_path' => $result['transcript_path'],
            'timestamped_transcript_path' => $result['timestamped_transcript_path'],
            'timestamps_path' => $result['timestamps_path'],
            'stats_path' => $statsPath,
            'detection_mode' => $detectionMode,
            'logs' => $result['logs'],
        ];
        file_put_contents(joinPath($jobDir, 'result.json'), json_encode($resultPayload, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES));
        writeLocalBridgeStatus($jobDir, $jobId, 'completed', 100.0, 'Complete', 'Windows processing complete.', (string)$result['logs']);
    } catch (Throwable $exception) {
        writeLocalBridgeStatus($jobDir, $jobId, 'failed', 100.0, 'Failed', $exception->getMessage());
        throw $exception;
    }

    jsonResponse(200, [
        'ok' => true,
        'job_id' => $jobId,
        'backend' => 'tunnel-' . $backend,
        'detection_mode' => $detectionMode,
        'download_url' => '/api/local/jobs/' . $jobId . '/download',
        'transcript_url' => '/api/local/jobs/' . $jobId . '/artifact/transcript',
        'timestamped_transcript_url' => '/api/local/jobs/' . $jobId . '/artifact/timestamped-transcript',
        'timestamps_url' => '/api/local/jobs/' . $jobId . '/artifact/timestamps',
        'stats_url' => '/api/local/jobs/' . $jobId . '/artifact/stats',
        'logs' => $result['logs'],
    ]);
}

function readLocalBridgeResult(string $jobDir): ?array
{
    $resultPath = joinPath($jobDir, 'result.json');
    if (!is_file($resultPath)) {
        return null;
    }
    if (fileContainsHeuristicMarker($resultPath)) {
        return null;
    }

    $decoded = json_decode((string)file_get_contents($resultPath), true);
    if (!is_array($decoded)) {
        return null;
    }
    if ((string)($decoded['backend'] ?? '') !== 'tunnel-parakeet') {
        return null;
    }

    $outputPath = (string)($decoded['output_path'] ?? '');
    if ($outputPath === '' || !is_file($outputPath)) {
        return null;
    }
    if (localBridgeResultHasHeuristicArtifact($decoded, $jobDir)) {
        return null;
    }

    return $decoded;
}

function localBridgeStatusPath(string $jobDir): string
{
    return joinPath($jobDir, 'status.json');
}

function writeLocalBridgeStatus(
    string $jobDir,
    string $jobId,
    string $status,
    float $progress,
    string $phase,
    string $message,
    string $logs = ''
): void {
    ensureDirectory($jobDir);
    $path = localBridgeStatusPath($jobDir);
    $existing = [];
    if (is_file($path)) {
        $decoded = json_decode((string)file_get_contents($path), true);
        if (is_array($decoded)) {
            $existing = $decoded;
        }
    }
    $now = gmdate(DATE_ATOM);
    $payload = [
        'ok' => true,
        'job_id' => $jobId,
        'status' => $status,
        'phase' => $phase,
        'message' => $message,
        'progress' => max(0.0, min(100.0, $progress)),
        'logs' => tailText($logs, 24000),
        'started_at' => (string)($existing['started_at'] ?? $now),
        'updated_at' => $now,
        'finished_at' => in_array($status, ['completed', 'failed', 'cancelled'], true) ? $now : null,
    ];
    file_put_contents($path, json_encode($payload, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES));
}

function serveLocalBridgeStatus(array $config, string $jobId): void
{
    assertLocalBridgeAccess($config);
    $jobId = normalizeBridgeJobId($jobId);
    $jobDir = localBridgeJobDir($config, $jobId);
    $path = localBridgeStatusPath($jobDir);
    if (is_file($path)) {
        $decoded = json_decode((string)file_get_contents($path), true);
        if (is_array($decoded)) {
            jsonResponse(200, $decoded);
        }
    }
    $result = readLocalBridgeResult($jobDir);
    if ($result !== null) {
        jsonResponse(200, [
            'ok' => true,
            'job_id' => $jobId,
            'status' => 'completed',
            'phase' => 'Complete',
            'message' => 'Windows processing complete.',
            'progress' => 100.0,
            'logs' => tailText((string)($result['logs'] ?? ''), 24000),
            'started_at' => null,
            'updated_at' => gmdate(DATE_ATOM),
            'finished_at' => gmdate(DATE_ATOM),
        ]);
    }
    jsonResponse(404, [
        'ok' => false,
        'job_id' => $jobId,
        'status' => 'missing',
        'phase' => 'Waiting',
        'message' => 'Windows bridge status is not available yet.',
        'progress' => 0.0,
    ]);
}

function localBridgePhaseFromMessage(string $message): string
{
    $lower = strtolower($message);
    if (str_contains($lower, 'transcrib')) return 'Transcribing audio';
    if (str_contains($lower, 'detect')) return 'Detecting ads with GPT';
    if (str_contains($lower, 'render') || str_contains($lower, 'cut')) return 'Cutting ad-free audio';
    if (str_contains($lower, 'final')) return 'Finalizing output';
    if (str_contains($lower, 'complete')) return 'Complete';
    return 'Processing audio';
}

function jobHasHeuristicArtifact(PDO $pdo, array $config, string $jobId): bool
{
    $query = $pdo->prepare('SELECT logs, timestamps_path FROM jobs WHERE id=? LIMIT 1');
    $query->execute([$jobId]);
    $job = $query->fetch();
    if (!$job) {
        return false;
    }

    if (isset($job['logs']) && stripos((string)$job['logs'], 'heuristic') !== false) {
        return true;
    }

    $artifactDir = joinPath((string)$config['artifacts_dir'], $jobId);
    $candidates = [
        joinPath($artifactDir, 'timestamps.json'),
        joinPath($artifactDir, 'stats.json'),
    ];
    $timestampsPath = isset($job['timestamps_path']) ? (string)$job['timestamps_path'] : '';
    if ($timestampsPath !== '') {
        $candidates[] = $timestampsPath;
    }

    return anyFileContainsHeuristicMarker($candidates);
}

function jobIsRemovedHeuristicOutput(PDO $pdo, array $config, array $job): bool
{
    if ((string)($job['status'] ?? '') !== 'completed') {
        return false;
    }
    $jobId = (string)($job['id'] ?? '');
    return $jobId !== '' && jobHasHeuristicArtifact($pdo, $config, $jobId);
}

function localBridgeResultHasHeuristicArtifact(array $result, string $jobDir): bool
{
    $candidates = [
        joinPath($jobDir, 'timestamps.json'),
        joinPath($jobDir, 'stats.json'),
    ];
    foreach (['timestamps_path', 'stats_path'] as $key) {
        $path = isset($result[$key]) ? (string)$result[$key] : '';
        if ($path !== '') {
            $candidates[] = $path;
        }
    }

    return anyFileContainsHeuristicMarker($candidates);
}

function anyFileContainsHeuristicMarker(array $paths): bool
{
    $seen = [];
    foreach ($paths as $path) {
        $path = (string)$path;
        if ($path === '' || isset($seen[$path])) {
            continue;
        }
        $seen[$path] = true;
        if (fileContainsHeuristicMarker($path)) {
            return true;
        }
    }
    return false;
}

function fileContainsHeuristicMarker(string $path): bool
{
    if (!is_file($path)) {
        return false;
    }
    $contents = @file_get_contents($path);
    return is_string($contents) && stripos($contents, 'heuristic') !== false;
}

function serveLocalBridgeFile(array $config, string $jobId, string $type): void
{
    assertLocalBridgeAccess($config);
    $jobDir = localBridgeJobDir($config, $jobId);
    $resultPath = joinPath($jobDir, 'result.json');
    if (!is_file($resultPath)) {
        jsonResponse(404, ['error' => 'Local bridge result not found']);
    }
    $result = json_decode((string)file_get_contents($resultPath), true);
    if (!is_array($result)) {
        jsonResponse(500, ['error' => 'Local bridge result file is invalid']);
    }
    if (localBridgeResultHasHeuristicArtifact($result, $jobDir)) {
        jsonResponse(410, ['error' => 'This completed output used removed heuristic detection and is no longer available. Run ad removal again with GPT detection.']);
    }

    $key = match ($type) {
        'download' => 'output_path',
        'transcript' => 'transcript_path',
        'timestamped-transcript' => 'timestamped_transcript_path',
        'timestamps' => 'timestamps_path',
        'stats' => 'stats_path',
        default => '',
    };
    $filePath = $key === '' ? '' : (string)($result[$key] ?? '');
    if ($filePath === '' || !is_file($filePath)) {
        jsonResponse(404, ['error' => 'Local bridge artifact is unavailable']);
    }

    $contentType = match ($type) {
        'download' => 'audio/mpeg',
        'timestamps', 'stats' => 'application/json',
        default => 'text/plain; charset=utf-8',
    };
    header('Content-Type: ' . $contentType);
    header('Content-Length: ' . (string)filesize($filePath));
    header('Content-Disposition: inline; filename="' . basename($filePath) . '"');
    readfile($filePath);
    exit;
}

function readJsonBody(): array
{
    $raw = file_get_contents('php://input');
    $decoded = is_string($raw) ? json_decode($raw, true) : null;
    if (!is_array($decoded)) {
        throw new RuntimeException('Request body must be valid JSON.');
    }
    return $decoded;
}

function assertLocalBridgeAccess(array $config): void
{
    $remote = (string)($_SERVER['REMOTE_ADDR'] ?? '');
    if (!in_array($remote, ['127.0.0.1', '::1'], true)) {
        jsonResponse(403, ['error' => 'Local bridge endpoints are restricted to localhost/tunnel access.']);
    }

    $expectedToken = (string)($config['local_bridge_token'] ?? '');
    if ($expectedToken !== '') {
        $actualToken = (string)($_SERVER['HTTP_X_ADFREE_BRIDGE_TOKEN'] ?? '');
        if (!hash_equals($expectedToken, $actualToken)) {
            jsonResponse(403, ['error' => 'Invalid local bridge token.']);
        }
    }
}

function normalizeBridgeJobId(string $value): string
{
    $clean = strtolower(preg_replace('/[^a-f0-9]/', '', $value) ?? '');
    if (strlen($clean) === 32) {
        return $clean;
    }
    return bin2hex(random_bytes(16));
}

function localBridgeRoot(array $config): string
{
    $root = envOrDefault('LOCAL_BRIDGE_STORAGE', joinPath((string)$config['storage_root'], 'local-bridge'));
    ensureDirectory($root);
    return $root;
}

function localBridgeJobDir(array $config, string $jobId): string
{
    $dir = joinPath(joinPath(localBridgeRoot($config), 'jobs'), $jobId);
    ensureDirectory($dir);
    return $dir;
}

function pruneLocalBridgeJobs(array $config): void
{
    $days = max(1, (int)envOrDefault('LOCAL_BRIDGE_RETENTION_DAYS', '3'));
    $cutoff = time() - ($days * 24 * 3600);
    $jobsRoot = joinPath(localBridgeRoot($config), 'jobs');
    if (!is_dir($jobsRoot)) {
        return;
    }
    $items = glob(joinPath($jobsRoot, '*'));
    if (!is_array($items)) {
        return;
    }
    foreach ($items as $item) {
        if (is_dir($item) && (int)filemtime($item) < $cutoff) {
            deleteDirectory($item);
        }
    }
}

function deleteDirectory(string $dir): void
{
    $items = scandir($dir);
    if (!is_array($items)) {
        return;
    }
    foreach ($items as $item) {
        if ($item === '.' || $item === '..') {
            continue;
        }
        $path = joinPath($dir, $item);
        if (is_dir($path)) {
            deleteDirectory($path);
        } else {
            @unlink($path);
        }
    }
    @rmdir($dir);
}

function resolveLocalAdCutForgeRoot(): string
{
    $candidates = [
        envOrDefault('LOCAL_ADCUTFORGE_ROOT', ''),
        envOrDefault('ADCUTFORGE_ROOT', ''),
        'D:/__MY APPS/ad free podcast player/apps/server/adcutforge',
        'D:/__MY APPS/ad free podcast player/apps/server/windows/adcutforge',
        'C:/Users/Gabe/Documents/Codex/2026-05-08/podcast ad remover',
    ];
    foreach ($candidates as $candidate) {
        $trimmed = trim((string)$candidate);
        if ($trimmed !== '' && is_file(joinPath($trimmed, 'src/ad_cut_forge.py'))) {
            return str_replace('\\', '/', $trimmed);
        }
    }
    throw new RuntimeException('Local AdCutForge root was not found. Set LOCAL_ADCUTFORGE_ROOT in WAMP/Apache.');
}

function resolveLocalParakeetPython(string $adCutForgeRoot): string
{
    $candidates = [
        envOrDefault('LOCAL_PARAKEET_PYTHON', ''),
        joinPath($adCutForgeRoot, 'parakeet-runtime/Scripts/python.exe'),
        'C:/Users/Gabe/Documents/Codex/2026-05-08/podcast ad remover/parakeet-runtime/Scripts/python.exe',
        'D:/__MY APPS/ad free podcast player/apps/server/adcutforge/parakeet-runtime/Scripts/python.exe',
    ];
    foreach ($candidates as $candidate) {
        $trimmed = str_replace('\\', '/', trim((string)$candidate));
        if ($trimmed !== '' && is_file($trimmed)) {
            return $trimmed;
        }
    }
    return '';
}

function runLocalAdCutForge(
    array $config,
    string $jobId,
    string $inputPath,
    string $backend,
    string $detectionMode,
    string $openAiApiKey,
    string $openAiModel,
    ?callable $statusUpdater = null
): array
{
    $root = resolveLocalAdCutForgeRoot();
    $script = joinPath($root, 'src/ad_cut_forge.py');
    if (!is_file($script)) {
        throw new RuntimeException('AdCutForge CLI script not found at ' . $script);
    }

    $parts = localPythonCommandParts();
    $parts[] = $script;
    $parts[] = '--cli';
    $parts[] = '--overwrite';
    $parts[] = '--backend';
    $parts[] = $backend;
    $parts[] = '--detection-mode';
    $parts[] = $detectionMode;

    if ($openAiApiKey !== '') {
        $parts[] = '--openai-api-key';
        $parts[] = $openAiApiKey;
    }
    if ($openAiModel !== '') {
        $parts[] = '--openai-model';
        $parts[] = $openAiModel;
    }

    $stem = pathinfo($inputPath, PATHINFO_FILENAME);
    $artifactDir = joinPath(dirname($inputPath), $stem . '.artifacts');
    $parts[] = '--artifacts-dir';
    $parts[] = $artifactDir;

    if ($backend === 'parakeet') {
        $parakeetPython = resolveLocalParakeetPython($root);
        if ($parakeetPython === '' || !is_file($parakeetPython)) {
            throw new RuntimeException('Parakeet Python was not found. Set LOCAL_PARAKEET_PYTHON or install the bundled parakeet runtime.');
        }
        $parts[] = '--parakeet-python';
        $parts[] = $parakeetPython;
        $model = envOrDefault('LOCAL_PARAKEET_MODEL', 'nvidia/parakeet-tdt-0.6b-v3');
        if ($model !== '') {
            $parts[] = '--parakeet-model';
            $parts[] = $model;
        }
    }

    $parts[] = $inputPath;
    $command = shellCommand($parts);
    $processEnv = [
        'PYTHONIOENCODING' => 'utf-8',
        'PYTHONUTF8' => '1',
        'PYTHONUNBUFFERED' => '1',
    ];
    foreach (['FFMPEG_BIN', 'FFPROBE_BIN', 'SSL_CERT_FILE', 'CURL_CA_BUNDLE'] as $name) {
        $value = trim(envOrDefault($name, ''));
        if ($value !== '') {
            $processEnv[$name] = $value;
        }
    }
    if (PHP_OS_FAMILY === 'Windows') {
        $prefix = '';
        foreach ($processEnv as $name => $value) {
            $safeName = preg_replace('/[^A-Z0-9_]/i', '', (string)$name);
            $safeValue = str_replace('"', '', (string)$value);
            if ($safeName !== '') {
                $prefix .= 'set "' . $safeName . '=' . $safeValue . '"&& ';
            }
        }
        $command = $prefix . $command;
    } else {
        $prefix = '';
        foreach ($processEnv as $name => $value) {
            $safeName = preg_replace('/[^A-Z0-9_]/i', '', (string)$name);
            if ($safeName !== '') {
                $prefix .= $safeName . '=' . escapeshellarg((string)$value) . ' ';
            }
        }
        $command = $prefix . $command;
    }
    $descriptorSpec = [
        0 => ['pipe', 'r'],
        1 => ['pipe', 'w'],
        2 => ['pipe', 'w'],
    ];
    $process = proc_open($command, $descriptorSpec, $pipes, $root);
    if (!is_resource($process)) {
        throw new RuntimeException('Failed to start local AdCutForge process.');
    }
    fclose($pipes[0]);
    stream_set_blocking($pipes[1], false);
    stream_set_blocking($pipes[2], false);
    $logs = '';
    $stdoutBuffer = '';
    $stderrBuffer = '';
    $lastProgress = -1.0;
    $lastStatusAt = 0.0;
    while (true) {
        $stdoutChunk = stream_get_contents($pipes[1]);
        if (is_string($stdoutChunk) && $stdoutChunk !== '') {
            consumeLocalProcessChunk($stdoutChunk, $stdoutBuffer, $logs, $statusUpdater, $lastProgress, $lastStatusAt);
        }
        $stderrChunk = stream_get_contents($pipes[2]);
        if (is_string($stderrChunk) && $stderrChunk !== '') {
            consumeLocalProcessChunk($stderrChunk, $stderrBuffer, $logs, $statusUpdater, $lastProgress, $lastStatusAt);
        }

        $status = proc_get_status($process);
        if (!is_array($status) || !($status['running'] ?? false)) {
            break;
        }
        usleep(250000);
    }
    $stdoutTail = stream_get_contents($pipes[1]);
    if (is_string($stdoutTail) && $stdoutTail !== '') {
        consumeLocalProcessChunk($stdoutTail, $stdoutBuffer, $logs, $statusUpdater, $lastProgress, $lastStatusAt);
    }
    $stderrTail = stream_get_contents($pipes[2]);
    if (is_string($stderrTail) && $stderrTail !== '') {
        consumeLocalProcessChunk($stderrTail, $stderrBuffer, $logs, $statusUpdater, $lastProgress, $lastStatusAt);
    }
    if ($stdoutBuffer !== '') {
        consumeLocalProcessChunk("\n", $stdoutBuffer, $logs, $statusUpdater, $lastProgress, $lastStatusAt);
    }
    if ($stderrBuffer !== '') {
        consumeLocalProcessChunk("\n", $stderrBuffer, $logs, $statusUpdater, $lastProgress, $lastStatusAt);
    }
    fclose($pipes[1]);
    fclose($pipes[2]);
    $exitCode = proc_close($process);
    $logs = trim($logs);
    if ($exitCode !== 0) {
        throw new RuntimeException('Local AdCutForge failed with exit code ' . $exitCode . ': ' . summarizeLocalAdCutForgeFailure($logs, $detectionMode));
    }

    if (!is_dir($artifactDir)) {
        throw new RuntimeException('Local AdCutForge did not create an artifact folder.');
    }

    $outputPath = findFirstExisting([
        joinPath(dirname($inputPath), $stem . '.noads.m4a'),
        joinPath(dirname($inputPath), $stem . '.noads.mp3'),
        joinPath(dirname($inputPath), $stem . '.noads.aac'),
    ]);
    if ($outputPath === '') {
        $outputPath = findLocalProcessedAudio(dirname($inputPath));
    }
    if ($outputPath === '') {
        throw new RuntimeException('Local AdCutForge did not produce edited audio.');
    }

    $transcriptPath = findFirstExisting([
        joinPath($artifactDir, 'transcript.txt'),
        joinPath($artifactDir, $stem . '.transcript.txt'),
    ]);
    $timestampedPath = findFirstExisting([
        joinPath($artifactDir, 'transcript_timestamped.txt'),
        joinPath($artifactDir, $stem . '.timestamped.txt'),
    ]);
    $timestampsPath = findFirstExisting([
        joinPath($artifactDir, 'timestamps.json'),
        joinPath($artifactDir, $stem . '.ad-ranges.json'),
        joinPath($artifactDir, $stem . '.timestamps.json'),
    ]);

    return [
        'output_path' => $outputPath,
        'transcript_path' => $transcriptPath,
        'timestamped_transcript_path' => $timestampedPath,
        'timestamps_path' => $timestampsPath,
        'logs' => $logs,
    ];
}

function consumeLocalProcessChunk(
    string $chunk,
    string &$buffer,
    string &$logs,
    ?callable $statusUpdater,
    float &$lastProgress,
    float &$lastStatusAt
): void {
    $buffer .= str_replace("\r\n", "\n", str_replace("\r", "\n", $chunk));
    while (($pos = strpos($buffer, "\n")) !== false) {
        $line = substr($buffer, 0, $pos);
        $buffer = substr($buffer, $pos + 1);
        $trimmed = trim($line);
        if ($trimmed === '') {
            continue;
        }
        $logs .= $trimmed . "\n";
        if ($statusUpdater === null) {
            continue;
        }
        $progress = null;
        if (preg_match('/\b(\d{1,3}(?:\.\d+)?)%\b/', $trimmed, $matches) === 1) {
            $progress = max(0.0, min(100.0, (float)$matches[1]));
        } elseif (stripos($trimmed, 'transcribing chunk') !== false) {
            $progress = max($lastProgress, 30.0);
        } elseif (stripos($trimmed, 'detecting ads') !== false) {
            $progress = max($lastProgress, 65.0);
        } elseif (stripos($trimmed, 'rendering') !== false) {
            $progress = max($lastProgress, 75.0);
        }
        $now = microtime(true);
        if ($progress !== null && ($progress !== $lastProgress || ($now - $lastStatusAt) >= 2.0)) {
            $lastProgress = $progress;
            $lastStatusAt = $now;
            $statusUpdater($progress, $trimmed, $logs);
        }
    }
}

function localPythonCommandParts(): array
{
    $configured = trim(envOrDefault('LOCAL_ADCUTFORGE_PYTHON', ''));
    if ($configured === '') {
        return ['py', '-3.11'];
    }
    return splitCommandPrefix($configured);
}

function localCommandExecutableAvailable(array $parts): bool
{
    if (count($parts) === 0) {
        return false;
    }
    $executable = trim((string)$parts[0]);
    if ($executable === '') {
        return false;
    }
    if (str_contains($executable, '/') || str_contains($executable, '\\')) {
        return is_file($executable);
    }
    return true;
}

function splitCommandPrefix(string $command): array
{
    $command = trim($command);
    if ($command === '') {
        return [];
    }
    if ($command[0] === '"') {
        $end = strpos($command, '"', 1);
        if ($end !== false) {
            $first = substr($command, 1, $end - 1);
            $rest = trim(substr($command, $end + 1));
            return array_values(array_filter(array_merge([$first], preg_split('/\s+/', $rest) ?: []), fn($part) => $part !== ''));
        }
    }
    return array_values(array_filter(preg_split('/\s+/', $command) ?: [], fn($part) => $part !== ''));
}

function shellCommand(array $parts): string
{
    return implode(' ', array_map(fn($part) => escapeshellarg((string)$part), $parts));
}

function findLocalProcessedAudio(string $artifactDir): string
{
    $matches = [];
    foreach (['*.noads.*', '*.no-ads.*', '*.adfree.*'] as $pattern) {
        $found = glob(joinPath($artifactDir, $pattern));
        if (is_array($found)) {
            $matches = array_merge($matches, $found);
        }
    }
    if (count($matches) === 0) return '';
    usort($matches, fn($a, $b) => (int)filemtime($b) <=> (int)filemtime($a));
    return (string)$matches[0];
}

function findFirstExisting(array $paths): string
{
    foreach ($paths as $path) {
        if (is_string($path) && is_file($path)) {
            return $path;
        }
    }
    return '';
}

function tailText(string $value, int $maxChars): string
{
    if (strlen($value) <= $maxChars) {
        return $value;
    }
    return substr($value, -$maxChars);
}

function summarizeLocalAdCutForgeFailure(string $logs, string $detectionMode): string
{
    $normalized = trim(str_replace(["\r\n", "\r"], "\n", $logs));
    if ($normalized === '') {
        return 'No process output was captured.';
    }

    foreach (array_reverse(explode("\n", $normalized)) as $line) {
        $line = trim($line);
        if ($line === '') {
            continue;
        }
        if (stripos($line, 'OpenAI request failed') !== false || stripos($line, 'AdCutError: OpenAI') !== false) {
            return tailText($line, 1500);
        }
    }

    if ($detectionMode === 'openai'
        && preg_match('/HTTP Error\s+(\d+):\s*([^\n]+)/i', $normalized, $matches)) {
        return 'OpenAI request failed: HTTP ' . $matches[1] . ' ' . trim($matches[2]);
    }

    return tailText($normalized, 2000);
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
    return 'tunnel-parakeet';
}

function validateDetectionMode(string $mode): string
{
    return 'openai';
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
    $rows = $pdo->query(
        "SELECT id, name, device_fingerprint, created_at
         FROM users
         WHERE name NOT LIKE 'Android Google sdk_gphone%'
           AND name NOT LIKE 'Android Android SDK%'
         ORDER BY created_at"
    )->fetchAll();
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
            if (($existing['name'] ?? '') !== $name) {
                $pdo->prepare('UPDATE users SET name=? WHERE id=?')->execute([$name, $existing['id']]);
                $existing['name'] = $name;
            }
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
    $stmt = $pdo->prepare('SELECT * FROM jobs WHERE id=?');
    $stmt->execute([$id]);
    $job = $stmt->fetch();
    if (!$job) {
        jsonResponse(404, ['error' => 'Job not found']);
        return;
    }

    foreach (['input_path', 'output_path', 'transcript_path', 'timestamps_path'] as $pathKey) {
        $path = isset($job[$pathKey]) ? (string)$job[$pathKey] : '';
        if ($path !== '' && is_file($path)) {
            @unlink($path);
        }
    }

    @unlink(joinPath($config['queue_dir'], $id . '.json'));
    @unlink(joinPath($config['queue_dir'], $id . '.working'));
    deleteDirectory(joinPath((string)$config['artifacts_dir'], $id));

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

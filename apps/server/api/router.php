<?php
declare(strict_types=1);

$uriPath = parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH) ?: '/';
$effectivePath = $uriPath === '/' ? '/index.html' : $uriPath;
$publicFile = __DIR__ . '/public' . $effectivePath;

if (is_file($publicFile)) {
    return false;
}

require __DIR__ . '/public/index.php';

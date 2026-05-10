const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const { pipeline } = require('node:stream/promises');

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
};

function safeFilename(value) {
  const base = String(value || 'podcast-episode')
    .replace(/[<>:"/\\|?*\x00-\x1f]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 140);
  return base || 'podcast-episode';
}

function ensureDir(dirPath) {
  fs.mkdirSync(dirPath, { recursive: true });
}

function safeOfflineRelativePath(value) {
  const normalized = String(value || '').replace(/\\/g, '/').replace(/^\/+/, '');
  if (!normalized || normalized.includes('..')) {
    throw new Error('Invalid offline file path.');
  }
  return normalized;
}

function resolveUnderRoot(rootDir, relativePath) {
  const resolved = path.resolve(rootDir, relativePath);
  const normalizedRoot = path.resolve(rootDir);
  if (!resolved.startsWith(normalizedRoot)) {
    throw new Error('Resolved path escaped offline cache root.');
  }
  return resolved;
}

function send(res, status, body, type = 'application/json; charset=utf-8') {
  res.writeHead(status, {
    'content-type': type,
    'access-control-allow-origin': '*',
    'cache-control': 'no-store',
  });
  res.end(body);
}

function asHeaders(headers) {
  const out = {};
  headers.forEach((value, key) => {
    out[key] = value;
  });
  return out;
}

function isAllowedTarget(target) {
  try {
    const u = new URL(target);
    return u.protocol === 'https:' || u.protocol === 'http:';
  } catch {
    return false;
  }
}

async function fetchText(target) {
  const startedAt = new Date().toISOString();
  const response = await fetch(target, {
    redirect: 'follow',
    headers: {
      'user-agent': 'Mozilla/5.0 AdFreePodcastPlayer/1.0',
      accept: '*/*',
    },
  });
  const raw = await response.text();
  return {
    ok: response.ok,
    method: 'GET',
    requestedUrl: target,
    finalUrl: response.url,
    status: response.status,
    statusText: response.statusText,
    headers: asHeaders(response.headers),
    raw,
    rawLength: raw.length,
    startedAt,
    finishedAt: new Date().toISOString(),
  };
}

async function fetchHead(target) {
  const startedAt = new Date().toISOString();
  const response = await fetch(target, {
    method: 'HEAD',
    redirect: 'follow',
    headers: {
      'user-agent': 'Mozilla/5.0 AdFreePodcastPlayer/1.0',
      accept: '*/*',
    },
  });
  return {
    ok: response.ok,
    method: 'HEAD',
    requestedUrl: target,
    finalUrl: response.url,
    status: response.status,
    statusText: response.statusText,
    headers: asHeaders(response.headers),
    raw: '',
    rawLength: 0,
    startedAt,
    finishedAt: new Date().toISOString(),
  };
}

async function probeAudio(target) {
  const startedAt = new Date().toISOString();
  const controller = new AbortController();
  const response = await fetch(target, {
    method: 'GET',
    redirect: 'follow',
    signal: controller.signal,
    headers: {
      'user-agent': 'Mozilla/5.0 AdFreePodcastPlayer/1.0',
      accept: '*/*',
      range: 'bytes=0-4095',
    },
  });

  const chunks = [];
  let total = 0;
  const reader = response.body.getReader();
  try {
    while (total < 4096) {
      const { done, value } = await reader.read();
      if (done) break;
      chunks.push(value);
      total += value.byteLength;
      if (total >= 4096) break;
    }
  } finally {
    try {
      await reader.cancel();
    } catch {}
    controller.abort();
  }

  const bytes = Buffer.concat(chunks.map((chunk) => Buffer.from(chunk)), total).subarray(0, 4096);
  return {
    ok: response.ok,
    method: 'GET range bytes=0-4095',
    requestedUrl: target,
    finalUrl: response.url,
    status: response.status,
    statusText: response.statusText,
    headers: asHeaders(response.headers),
    sampleBytes: bytes.length,
    sampleHex: bytes.subarray(0, 64).toString('hex').match(/.{1,2}/g)?.join(' ') || '',
    sampleText: bytes.toString('utf8', 0, Math.min(bytes.length, 256)),
    startedAt,
    finishedAt: new Date().toISOString(),
  };
}

async function streamRemote(req, res, target, disposition) {
  const headers = {
    'user-agent': 'Mozilla/5.0 AdFreePodcastPlayer/1.0',
    accept: '*/*',
  };
  if (req.headers.range) headers.range = req.headers.range;

  const response = await fetch(target, {
    redirect: 'follow',
    headers,
  });

  const outgoing = {
    'access-control-allow-origin': '*',
    'cache-control': 'no-store',
    'content-type': response.headers.get('content-type') || 'application/octet-stream',
  };
  for (const key of ['content-length', 'content-range', 'accept-ranges']) {
    const value = response.headers.get(key);
    if (value) outgoing[key] = value;
  }
  if (disposition) outgoing['content-disposition'] = disposition;

  res.writeHead(response.status, outgoing);
  if (!response.body) {
    res.end();
    return;
  }

  const reader = response.body.getReader();
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      res.write(Buffer.from(value));
    }
  } finally {
    res.end();
  }
}

function streamLocalFile(req, res, filePath) {
  const stat = fs.statSync(filePath);
  const total = stat.size;
  const range = req.headers.range;
  const ext = path.extname(filePath).toLowerCase();
  const contentType = MIME[ext] || 'audio/mpeg';

  if (range) {
    const match = /bytes=(\d*)-(\d*)/.exec(range);
    if (!match) {
      res.writeHead(416, { 'content-range': `bytes */${total}` });
      res.end();
      return;
    }

    const start = match[1] ? Number(match[1]) : 0;
    const end = match[2] ? Number(match[2]) : total - 1;
    if (Number.isNaN(start) || Number.isNaN(end) || start > end || end >= total) {
      res.writeHead(416, { 'content-range': `bytes */${total}` });
      res.end();
      return;
    }

    res.writeHead(206, {
      'access-control-allow-origin': '*',
      'cache-control': 'no-store',
      'accept-ranges': 'bytes',
      'content-length': end - start + 1,
      'content-range': `bytes ${start}-${end}/${total}`,
      'content-type': contentType,
    });
    fs.createReadStream(filePath, { start, end }).pipe(res);
    return;
  }

  res.writeHead(200, {
    'access-control-allow-origin': '*',
    'cache-control': 'no-store',
    'accept-ranges': 'bytes',
    'content-length': total,
    'content-type': contentType,
  });
  fs.createReadStream(filePath).pipe(res);
}

function extensionFromTarget(target, contentType) {
  const pathname = (() => {
    try {
      return new URL(target).pathname;
    } catch {
      return '';
    }
  })();
  const fromPath = path.extname(pathname || '').toLowerCase();
  if (fromPath && fromPath.length <= 5) {
    return fromPath;
  }
  if ((contentType || '').includes('audio/mp4') || (contentType || '').includes('audio/x-m4a')) {
    return '.m4a';
  }
  if ((contentType || '').includes('audio/aac')) {
    return '.aac';
  }
  if ((contentType || '').includes('audio/wav')) {
    return '.wav';
  }
  return '.mp3';
}

async function saveOfflineAudio(target, offlineDir, filenameHint, keyHint) {
  ensureDir(offlineDir);
  const response = await fetch(target, {
    redirect: 'follow',
    headers: {
      'user-agent': 'Mozilla/5.0 AdFreePodcastPlayer/1.0',
      accept: '*/*',
    },
  });

  if (!response.ok || !response.body) {
    throw new Error(`Save offline failed (${response.status})`);
  }

  const extension = extensionFromTarget(response.url || target, response.headers.get('content-type') || '');
  const base = safeFilename(filenameHint || keyHint || 'podcast-episode');
  const relativePath = `${safeFilename(keyHint || base)}-${base}${extension}`;
  const finalPath = resolveUnderRoot(offlineDir, relativePath);
  const tempPath = `${finalPath}.download`;
  await pipeline(response.body, fs.createWriteStream(tempPath));
  fs.renameSync(tempPath, finalPath);

  return {
    ok: true,
    requestedUrl: target,
    finalUrl: response.url,
    relativePath,
    filePath: finalPath,
    size: fs.statSync(finalPath).size,
    finishedAt: new Date().toISOString(),
  };
}

function serveStatic(publicDir, res, pathname) {
  const filePath = pathname === '/' ? path.join(publicDir, 'index.html') : path.join(publicDir, pathname);
  const resolved = path.resolve(filePath);
  if (!resolved.startsWith(path.resolve(publicDir))) {
    send(res, 403, 'Forbidden', 'text/plain; charset=utf-8');
    return;
  }

  fs.readFile(resolved, (err, data) => {
    if (err) {
      send(res, 404, 'Not found', 'text/plain; charset=utf-8');
      return;
    }
    send(res, 200, data, MIME[path.extname(resolved)] || 'application/octet-stream');
  });
}

function startLocalPodServer({ publicDir, offlineDir, port = 0, host = '127.0.0.1' }) {
  return new Promise((resolve, reject) => {
    const server = http.createServer(async (req, res) => {
      const requestUrl = new URL(req.url, `http://${req.headers.host}`);
      try {
        if (requestUrl.pathname === '/api/search') {
          const term = requestUrl.searchParams.get('term') || '';
          const limit = requestUrl.searchParams.get('limit') || '25';
          const target = `https://itunes.apple.com/search?media=podcast&limit=${encodeURIComponent(limit)}&term=${encodeURIComponent(term)}`;
          send(res, 200, JSON.stringify(await fetchText(target), null, 2));
          return;
        }

        if (requestUrl.pathname === '/api/fetch') {
          const target = requestUrl.searchParams.get('url') || '';
          if (!isAllowedTarget(target)) {
            send(res, 400, JSON.stringify({ ok: false, error: 'Expected a full http(s) URL.', requestedUrl: target }, null, 2));
            return;
          }
          send(res, 200, JSON.stringify(await fetchText(target), null, 2));
          return;
        }

        if (requestUrl.pathname === '/api/head') {
          const target = requestUrl.searchParams.get('url') || '';
          if (!isAllowedTarget(target)) {
            send(res, 400, JSON.stringify({ ok: false, error: 'Expected a full http(s) URL.', requestedUrl: target }, null, 2));
            return;
          }
          send(res, 200, JSON.stringify(await fetchHead(target), null, 2));
          return;
        }

        if (requestUrl.pathname === '/api/probe') {
          const target = requestUrl.searchParams.get('url') || '';
          if (!isAllowedTarget(target)) {
            send(res, 400, JSON.stringify({ ok: false, error: 'Expected a full http(s) URL.', requestedUrl: target }, null, 2));
            return;
          }
          send(res, 200, JSON.stringify(await probeAudio(target), null, 2));
          return;
        }

        if (requestUrl.pathname === '/api/audio') {
          const target = requestUrl.searchParams.get('url') || '';
          if (!isAllowedTarget(target)) {
            send(res, 400, 'Expected a full http(s) URL.', 'text/plain; charset=utf-8');
            return;
          }
          await streamRemote(req, res, target, null);
          return;
        }

        if (requestUrl.pathname === '/api/download') {
          const target = requestUrl.searchParams.get('url') || '';
          const filename = safeFilename(requestUrl.searchParams.get('filename') || 'podcast-episode');
          if (!isAllowedTarget(target)) {
            send(res, 400, 'Expected a full http(s) URL.', 'text/plain; charset=utf-8');
            return;
          }
          await streamRemote(req, res, target, `attachment; filename="${filename}"`);
          return;
        }

        if (requestUrl.pathname === '/api/save-offline') {
          const target = requestUrl.searchParams.get('url') || '';
          const filename = requestUrl.searchParams.get('filename') || 'podcast-episode';
          const key = requestUrl.searchParams.get('key') || filename;
          if (!isAllowedTarget(target)) {
            send(res, 400, JSON.stringify({ ok: false, error: 'Expected a full http(s) URL.', requestedUrl: target }, null, 2));
            return;
          }
          if (!offlineDir) {
            send(res, 500, JSON.stringify({ ok: false, error: 'Offline storage is not configured.' }, null, 2));
            return;
          }
          send(res, 200, JSON.stringify(await saveOfflineAudio(target, offlineDir, filename, key), null, 2));
          return;
        }

        if (requestUrl.pathname === '/api/offline-audio') {
          const relativePath = requestUrl.searchParams.get('path') || '';
          if (!offlineDir) {
            send(res, 500, 'Offline storage is not configured.', 'text/plain; charset=utf-8');
            return;
          }
          const resolved = resolveUnderRoot(offlineDir, safeOfflineRelativePath(relativePath));
          if (!fs.existsSync(resolved)) {
            send(res, 404, 'Offline audio not found.', 'text/plain; charset=utf-8');
            return;
          }
          streamLocalFile(req, res, resolved);
          return;
        }

        serveStatic(publicDir, res, requestUrl.pathname);
      } catch (error) {
        send(
          res,
          500,
          JSON.stringify(
            {
              ok: false,
              error: error.name,
              message: error.message,
              stack: error.stack,
              finishedAt: new Date().toISOString(),
            },
            null,
            2,
          ),
        );
      }
    });

    server.on('error', reject);
    server.listen(port, host, () => {
      const address = server.address();
      const actualPort = typeof address === 'object' && address ? address.port : port;
      resolve({
        server,
        url: `http://${host}:${actualPort}`,
        close: () => new Promise((closeResolve, closeReject) => {
          server.close((error) => {
            if (error) {
              closeReject(error);
              return;
            }
            closeResolve();
          });
        }),
      });
    });
  });
}

module.exports = {
  startLocalPodServer,
};

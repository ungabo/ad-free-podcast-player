const { app, BrowserWindow, ipcMain } = require('electron');
const path = require('node:path');
const fs = require('node:fs');
const { dialog } = require('electron');
const { runProcessing, resolveAdCutForgeRoot } = require('./processing');
const { startLocalPodServer } = require('./localpod-server');

const PLAYER_UI_DIR = path.resolve(__dirname, '..', 'player-ui');
let mainWindow = null;
let playerServer = null;

function getOfflineAudioDir() {
  return path.join(app.getPath('userData'), 'offline-audio');
}

function safeFilename(value) {
  const base = String(value || 'podcast-episode')
    .replace(/[<>:"/\\|?*\x00-\x1f]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 140);
  return base || 'podcast-episode';
}

function extensionFromFile(filePath) {
  const extension = path.extname(filePath || '').toLowerCase();
  return extension && extension.length <= 5 ? extension : '.mp3';
}

function normalizeRelativePath(filePath, rootDir) {
  return path.relative(rootDir, filePath).replace(/\\/g, '/');
}

function isPathInside(rootDir, candidatePath) {
  const resolvedRoot = path.resolve(rootDir);
  const resolvedCandidate = path.resolve(candidatePath);
  return resolvedCandidate === resolvedRoot || resolvedCandidate.startsWith(`${resolvedRoot}${path.sep}`);
}

function isAllowedTarget(target) {
  try {
    const u = new URL(target);
    return u.protocol === 'https:' || u.protocol === 'http:';
  } catch {
    return false;
  }
}

async function moveFile(sourcePath, destinationPath) {
  await fs.promises.mkdir(path.dirname(destinationPath), { recursive: true });
  if (path.resolve(sourcePath) === path.resolve(destinationPath)) {
    return;
  }
  try {
    await fs.promises.rm(destinationPath, { force: true });
  } catch {
    // Ignore target cleanup failures; the subsequent move will surface a real error.
  }

  try {
    await fs.promises.rename(sourcePath, destinationPath);
  } catch (error) {
    if (error.code !== 'EXDEV') {
      throw error;
    }
    await fs.promises.copyFile(sourcePath, destinationPath);
    await fs.promises.rm(sourcePath, { force: true });
  }
}

async function cleanupEpisodeFiles(offlineDir, episodeId, keepPath) {
  if (!episodeId) {
    return { removedPaths: [], keptPath: keepPath };
  }

  const safeEpisodeKey = safeFilename(episodeId);
  const prefix = `${safeEpisodeKey}-`;
  const removedPaths = [];
  const entries = await fs.promises.readdir(offlineDir, { withFileTypes: true }).catch(() => []);
  for (const entry of entries) {
    if (!entry.isFile()) continue;
    if (!entry.name.startsWith(prefix)) continue;
    const candidate = path.join(offlineDir, entry.name);
    if (path.resolve(candidate) === path.resolve(keepPath)) continue;
    await fs.promises.rm(candidate, { force: true }).catch(() => {});
    removedPaths.push(candidate);
  }

  return { removedPaths, keptPath: keepPath };
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

async function downloadEpisodeOffline({ url, title, keyHint, episodeId }, onProgress) {
  if (!isAllowedTarget(url)) {
    throw new Error('Expected a full http(s) URL.');
  }

  const offlineDir = getOfflineAudioDir();
  await fs.promises.mkdir(offlineDir, { recursive: true });

  onProgress({ type: 'status', stage: 'Downloading audio', line: 'Preparing download...', percent: 0, episodeId });
  const response = await fetch(url, {
    redirect: 'follow',
    headers: {
      'user-agent': 'Mozilla/5.0 AdFreePodcastPlayer/1.0',
      accept: '*/*',
    },
  });

  if (!response.ok || !response.body) {
    throw new Error(`Save offline failed (${response.status})`);
  }

  const extension = extensionFromTarget(response.url || url, response.headers.get('content-type') || '');
  const base = safeFilename(title || keyHint || 'podcast-episode');
  const relativePath = `${safeFilename(keyHint || base)}-${base}${extension}`;
  const finalPath = path.join(offlineDir, relativePath);
  const tempPath = `${finalPath}.download`;

  const totalBytes = Number(response.headers.get('content-length') || 0);
  let downloadedBytes = 0;
  let lastEmitAt = 0;
  const writer = fs.createWriteStream(tempPath);
  const reader = response.body.getReader();
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      if (!value) continue;
      downloadedBytes += value.byteLength;
      writer.write(Buffer.from(value));
      const now = Date.now();
      if (now - lastEmitAt >= 250) {
        const percent = totalBytes > 0 ? Math.min(100, (downloadedBytes * 100) / totalBytes) : 0;
        onProgress({
          type: 'progress',
          stage: 'Downloading audio',
          line: totalBytes > 0
            ? `Downloading... ${Math.round(percent)}% (${downloadedBytes}/${totalBytes} bytes)`
            : `Downloading... ${downloadedBytes} bytes`,
          percent,
          episodeId,
          downloadedBytes,
          totalBytes,
        });
        lastEmitAt = now;
      }
    }
    await new Promise((resolve, reject) => {
      writer.end((error) => {
        if (error) reject(error);
        else resolve();
      });
    });
  } catch (error) {
    writer.destroy();
    await fs.promises.rm(tempPath, { force: true }).catch(() => {});
    throw error;
  } finally {
    reader.releaseLock();
  }

  await fs.promises.rm(finalPath, { force: true }).catch(() => {});
  await fs.promises.rename(tempPath, finalPath);
  const stat = await fs.promises.stat(finalPath);
  onProgress({
    type: 'progress',
    stage: 'Download complete',
    line: 'Download complete: 100%',
    percent: 100,
    episodeId,
    downloadedBytes: stat.size,
    totalBytes: totalBytes || stat.size,
  });

  return {
    ok: true,
    requestedUrl: url,
    finalUrl: response.url,
    relativePath: normalizeRelativePath(finalPath, offlineDir),
    filePath: finalPath,
    size: stat.size,
    finishedAt: new Date().toISOString(),
  };
}

async function deleteOfflineAudioSource({ filePath }) {
  if (!filePath) {
    throw new Error('No file path was provided.');
  }

  const offlineDir = getOfflineAudioDir();
  if (!isPathInside(offlineDir, filePath)) {
    throw new Error('Refusing to delete a file outside the offline audio cache.');
  }

  await fs.promises.rm(filePath, { force: true });
  return { ok: true, filePath };
}

async function promoteEpisodeAudioSource({ editedAudioPath, episodeId, title, previousSourcePath }) {
  if (!editedAudioPath || !fs.existsSync(editedAudioPath)) {
    throw new Error(`Edited audio not found: ${editedAudioPath || 'missing path'}`);
  }

  const offlineDir = getOfflineAudioDir();
  const extension = extensionFromFile(editedAudioPath);
  const relativePath = `${safeFilename(episodeId || title)}-${safeFilename(title || 'podcast-episode')}.adfree${extension}`;
  const targetPath = path.join(offlineDir, relativePath);

  await moveFile(editedAudioPath, targetPath);

  let removedPreviousSource = false;
  if (previousSourcePath && path.resolve(previousSourcePath) !== path.resolve(targetPath) && isPathInside(offlineDir, previousSourcePath)) {
    await fs.promises.rm(previousSourcePath, { force: true }).catch(() => {});
    removedPreviousSource = true;
  }

  const cleanup = await cleanupEpisodeFiles(offlineDir, episodeId, targetPath);

  return {
    ok: true,
    filePath: targetPath,
    relativePath: normalizeRelativePath(targetPath, offlineDir),
    size: fs.statSync(targetPath).size,
    removedPreviousSource,
    removedSiblingFiles: cleanup.removedPaths,
    keptPath: cleanup.keptPath,
  };
}

function getStateFilePath() {
  return path.join(app.getPath('userData'), 'player-state.json');
}

async function readJsonFile(filePath) {
  try {
    const raw = await fs.promises.readFile(filePath, 'utf8');
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

async function writeJsonFile(filePath, payload) {
  const tempPath = `${filePath}.${process.pid}.${Date.now()}.${Math.random().toString(16).slice(2)}.tmp`;
  await fs.promises.mkdir(path.dirname(filePath), { recursive: true });
  await fs.promises.writeFile(tempPath, `${JSON.stringify(payload, null, 2)}\n`, 'utf8');
  await fs.promises.rename(tempPath, filePath);
}

async function ensurePlayerServer() {
  if (playerServer) {
    return playerServer;
  }

  if (!fs.existsSync(PLAYER_UI_DIR)) {
    throw new Error(`Player UI not found: ${PLAYER_UI_DIR}`);
  }

  playerServer = await startLocalPodServer({
    publicDir: PLAYER_UI_DIR,
    offlineDir: getOfflineAudioDir(),
    port: 4173,
  });

  return playerServer;
}

async function createWindow() {
  const win = new BrowserWindow({
    width: 1320,
    height: 900,
    minWidth: 960,
    minHeight: 680,
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });

  const server = await ensurePlayerServer();
  await win.loadURL(server.url);
  mainWindow = win;
}

app.whenReady().then(async () => {
  await createWindow();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});

app.on('before-quit', () => {
  if (playerServer) {
    playerServer.close().catch(() => {});
    playerServer = null;
  }
});

ipcMain.handle('settings:load', async (_evt, filePath) => {
  try {
    if (!filePath || !fs.existsSync(filePath)) return null;
    return JSON.parse(fs.readFileSync(filePath, 'utf8'));
  } catch {
    return null;
  }
});

ipcMain.handle('desktop:capabilities', async () => {
  return {
    isDesktop: true,
    adCutForgeRoot: resolveAdCutForgeRoot(),
    stateFilePath: getStateFilePath(),
  };
});

ipcMain.handle('desktop:load-state', async () => {
  return readJsonFile(getStateFilePath());
});

ipcMain.handle('desktop:save-state', async (_evt, payload) => {
  await writeJsonFile(getStateFilePath(), payload || {});
  return { ok: true, filePath: getStateFilePath() };
});

ipcMain.handle('desktop:clear-state', async () => {
  try {
    await fs.promises.rm(getStateFilePath(), { force: true });
  } catch {
    // Ignore delete failures so the renderer can still reset in memory.
  }
  return { ok: true };
});

ipcMain.handle('desktop:pick-audio-file', async () => {
  const result = await dialog.showOpenDialog(mainWindow, {
    title: 'Select audio file',
    properties: ['openFile'],
    filters: [
      {
        name: 'Audio',
        extensions: ['mp3', 'm4a', 'aac', 'wav', 'mp4'],
      },
    ],
  });

  if (result.canceled || result.filePaths.length === 0) {
    return null;
  }

  return result.filePaths[0];
});

ipcMain.handle('desktop:promote-episode-audio-source', async (_evt, payload) => {
  return promoteEpisodeAudioSource(payload || {});
});

ipcMain.handle('desktop:download-episode-offline', async (_evt, payload) => {
  const eventSink = (event) => {
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send('download:event', event);
    }
  };

  try {
    return await downloadEpisodeOffline(payload || {}, eventSink);
  } catch (error) {
    eventSink({
      type: 'error',
      stage: 'Download failed',
      line: error.message,
      percent: 0,
      episodeId: payload?.episodeId || '',
    });
    throw error;
  }
});

ipcMain.handle('desktop:delete-offline-audio-source', async (_evt, payload) => {
  return deleteOfflineAudioSource(payload || {});
});

ipcMain.handle('desktop:run-processing', async (_evt, payload) => {
  const events = [];
  const response = await runProcessing(payload.filePath, payload.settings, (event) => {
    events.push(event);
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send('processing:event', event);
    }
  });
  return {
    ...response,
    events,
  };
});

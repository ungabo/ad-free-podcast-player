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

async function promoteEpisodeAudioSource({ editedAudioPath, episodeId, title, previousSourcePath }) {
  if (!editedAudioPath || !fs.existsSync(editedAudioPath)) {
    throw new Error(`Edited audio not found: ${editedAudioPath || 'missing path'}`);
  }

  const offlineDir = getOfflineAudioDir();
  const extension = extensionFromFile(editedAudioPath);
  const relativePath = `${safeFilename(episodeId || title)}-${safeFilename(title || 'podcast-episode')}.adfree${extension}`;
  const targetPath = path.join(offlineDir, relativePath);

  await moveFile(editedAudioPath, targetPath);

  if (previousSourcePath && path.resolve(previousSourcePath) !== path.resolve(targetPath) && isPathInside(offlineDir, previousSourcePath)) {
    await fs.promises.rm(previousSourcePath, { force: true }).catch(() => {});
  }

  return {
    ok: true,
    filePath: targetPath,
    relativePath: normalizeRelativePath(targetPath, offlineDir),
    size: fs.statSync(targetPath).size,
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

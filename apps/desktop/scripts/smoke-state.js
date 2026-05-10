const fs = require('node:fs');
const path = require('node:path');
const { app, BrowserWindow } = require('electron');

require(path.resolve(__dirname, '..', 'src', 'main.js'));

async function waitForMainWindow(timeoutMs = 20000) {
  const startedAt = Date.now();

  while (Date.now() - startedAt < timeoutMs) {
    const win = BrowserWindow.getAllWindows()[0];
    if (win && !win.webContents.isLoadingMainFrame()) {
      return win;
    }
    await new Promise((resolve) => setTimeout(resolve, 250));
  }

  throw new Error('Timed out waiting for desktop window');
}

app.whenReady().then(async () => {
  const statePath = path.join(app.getPath('userData'), 'player-state.json');
  const offlineDir = path.join(app.getPath('userData'), 'offline-audio');
  const previousSourcePath = path.join(offlineDir, 'demo-episode.mp3');
  const stagedProcessedPath = path.join(app.getPath('temp'), 'demo-episode.processed.mp3');
  const testState = {
    podcasts: {
      demo: {
        id: 'demo',
        title: 'Demo Podcast',
        subscribed: true,
      },
    },
    episodes: {
      'demo-episode': {
        id: 'demo-episode',
        podcastId: 'demo',
        title: 'Demo Episode',
        description: 'Smoke test episode',
        enclosureUrl: 'https://example.com/demo.mp3',
        enclosureType: 'audio/mpeg',
        offlineFilePath: previousSourcePath,
        offlineRelativePath: 'demo-episode.mp3',
        downloadStatus: 'downloaded',
        isNew: false,
        isListened: false,
        pubDate: '2026-05-09T00:00:00.000Z',
        durationSeconds: 60,
      },
    },
    progress: {},
    settings: {
      autoplayNext: true,
      seekBack: 15,
      seekForward: 30,
      retentionDays: 30,
    },
    adRemover: {
      openAiKey: '',
      detectionMode: 'local',
      backend: 'parakeet',
      removeOriginal: true,
      selectedFile: '',
      episodeId: '',
      lastResult: null,
    },
    currentEpisodeId: '',
    debug: [],
  };
  try {
    fs.rmSync(statePath, { force: true });
    fs.rmSync(previousSourcePath, { force: true });
    fs.rmSync(stagedProcessedPath, { force: true });
    fs.mkdirSync(offlineDir, { recursive: true });
    fs.writeFileSync(previousSourcePath, 'original-audio');
    fs.writeFileSync(stagedProcessedPath, 'processed-audio');

    const win = await waitForMainWindow();
    const result = await win.webContents.executeJavaScript(`
      (async () => {
        const payload = ${JSON.stringify(testState)};
        await window.desktopApi.saveState(payload);
        const loaded = await window.desktopApi.loadState();
        const capabilities = await window.desktopApi.getCapabilities();
        state.podcasts = payload.podcasts;
        state.episodes = payload.episodes;
        state.progress = payload.progress;
        state.settings = payload.settings;
        state.adRemover = payload.adRemover;
        state.currentEpisodeId = payload.currentEpisodeId;
        state.debug = payload.debug;

        const originalRunAdRemoval = runAdRemoval;
        runAdRemoval = async ({ preserveRoute = false, sourceLabel = 'Demo Episode', episodeId = '' } = {}) => {
          const promoted = await promoteEpisodeSourceToAdFree(episodeId || 'demo-episode', {
            backend: 'mock',
            outputDir: ${JSON.stringify(path.dirname(stagedProcessedPath))},
            editedAudio: ${JSON.stringify(stagedProcessedPath)},
            runSummary: 'mock complete for ' + sourceLabel,
          }, sourceLabel);
          adRemoverState().lastResult = promoted;
          runtime.adRemover.isRunning = false;
          runtime.adRemover.percent = 100;
          runtime.adRemover.stage = 'Complete';
          setStatus('Ad-free export finished. ' + sourceLabel + ' now plays from the cleaned file.');
          if (!preserveRoute) {
            setRoute('adremover');
          } else {
            render();
          }
        };

        setRoute('podcast', { podcastId: 'demo' });
        await removeAdsForEpisode('demo-episode');

        const directRemove = {
          screenTitle: document.querySelector('#screenTitle')?.textContent || '',
          status: document.querySelector('#status')?.textContent || '',
          selectedFile: adRemoverState().selectedFile || '',
          resultAudio: adRemoverState().lastResult?.editedAudio || '',
          buttonPresent: Boolean(document.querySelector('[data-remove-ads-now="demo-episode"]')),
          promotedRelativePath: state.episodes['demo-episode']?.offlineRelativePath || '',
          sourceMarkedProcessed: state.episodes['demo-episode']?.adSupportedStatus || '',
        };

        runAdRemoval = originalRunAdRemoval;
        return { loaded, capabilities, directRemove };
      })()
    `);

    const exists = fs.existsSync(statePath);
    const bytes = exists ? fs.statSync(statePath).size : 0;
    const promotedPath = result.directRemove?.resultAudio || '';

    console.log(`state_file=${statePath}`);
    console.log(`state_file_exists=${exists}`);
    console.log(`state_file_bytes=${bytes}`);
    console.log(`state_roundtrip_ok=${result.loaded?.podcasts?.demo?.title === 'Demo Podcast'}`);
    console.log(`capabilities_state_path=${result.capabilities?.stateFilePath || ''}`);
    console.log(`direct_remove_screen_title=${result.directRemove?.screenTitle || ''}`);
    console.log(`direct_remove_status=${result.directRemove?.status || ''}`);
    console.log(`direct_remove_selected_file=${result.directRemove?.selectedFile || ''}`);
    console.log(`direct_remove_result_audio=${result.directRemove?.resultAudio || ''}`);
    console.log(`direct_remove_button_present=${result.directRemove?.buttonPresent}`);
    console.log(`direct_remove_promoted_relative=${result.directRemove?.promotedRelativePath || ''}`);
    console.log(`direct_remove_source_status=${result.directRemove?.sourceMarkedProcessed || ''}`);
    console.log(`direct_remove_previous_deleted=${!fs.existsSync(previousSourcePath)}`);
    console.log(`direct_remove_promoted_exists=${Boolean(promotedPath) && fs.existsSync(promotedPath)}`);
    console.log(`direct_remove_result_ok=${Boolean(promotedPath) && fs.existsSync(promotedPath) && promotedPath !== previousSourcePath && result.directRemove?.sourceMarkedProcessed === 'processed'}`);

    app.quit();
  } catch (error) {
    console.error(error.stack || error.message || String(error));
    app.exit(1);
  }
});
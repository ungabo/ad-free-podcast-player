const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('desktopApi', {
  loadState: () => ipcRenderer.invoke('desktop:load-state'),
  saveState: (payload) => ipcRenderer.invoke('desktop:save-state', payload),
  clearState: () => ipcRenderer.invoke('desktop:clear-state'),
  promoteEpisodeAudioSource: (payload) => ipcRenderer.invoke('desktop:promote-episode-audio-source', payload),
  loadSettings: (filePath) => ipcRenderer.invoke('settings:load', filePath),
  getCapabilities: () => ipcRenderer.invoke('desktop:capabilities'),
  pickAudioFile: () => ipcRenderer.invoke('desktop:pick-audio-file'),
  runProcessing: (payload) => ipcRenderer.invoke('desktop:run-processing', payload),
  onProcessingEvent: (listener) => {
    const handler = (_event, payload) => listener(payload);
    ipcRenderer.on('processing:event', handler);
    return () => ipcRenderer.removeListener('processing:event', handler);
  },
});

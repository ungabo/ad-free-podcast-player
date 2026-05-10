const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('desktopApi', {
  loadState: () => ipcRenderer.invoke('desktop:load-state'),
  saveState: (payload) => ipcRenderer.invoke('desktop:save-state', payload),
  clearState: () => ipcRenderer.invoke('desktop:clear-state'),
  promoteEpisodeAudioSource: (payload) => ipcRenderer.invoke('desktop:promote-episode-audio-source', payload),
  downloadEpisodeOffline: (payload) => ipcRenderer.invoke('desktop:download-episode-offline', payload),
  deleteOfflineAudioSource: (payload) => ipcRenderer.invoke('desktop:delete-offline-audio-source', payload),
  loadSettings: (filePath) => ipcRenderer.invoke('settings:load', filePath),
  getCapabilities: () => ipcRenderer.invoke('desktop:capabilities'),
  pickAudioFile: () => ipcRenderer.invoke('desktop:pick-audio-file'),
  runProcessing: (payload) => ipcRenderer.invoke('desktop:run-processing', payload),
  onProcessingEvent: (listener) => {
    const handler = (_event, payload) => listener(payload);
    ipcRenderer.on('processing:event', handler);
    return () => ipcRenderer.removeListener('processing:event', handler);
  },
  onDownloadEvent: (listener) => {
    const handler = (_event, payload) => listener(payload);
    ipcRenderer.on('download:event', handler);
    return () => ipcRenderer.removeListener('download:event', handler);
  },
});

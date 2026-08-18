const { contextBridge, ipcRenderer } = require('electron');

// Lets the renderer tell it's running inside the desktop app and hand off
// Google sign-in to the system browser instead of GIS's embedded flow — see
// google-signin.html and the "google-sign-in" handler in main.js for why.
contextBridge.exposeInMainWorld('electronAPI', {
  isElectron: true,
  signInWithGoogle: () => ipcRenderer.invoke('google-sign-in'),
});

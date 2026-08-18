const { app, BrowserWindow, shell, ipcMain } = require('electron');
const path = require('node:path');
const { startServer } = require('./server');

let mainWindow;
let server;
let serverPort;
/** At most one Google sign-in in flight — the system-browser tab reports back here. */
let pendingGoogleSignIn = null;

function handleGoogleCredential(credential) {
  if (!pendingGoogleSignIn) return;
  clearTimeout(pendingGoogleSignIn.timeout);
  pendingGoogleSignIn.resolve(credential);
  pendingGoogleSignIn = null;
}

/**
 * GIS's embedded One Tap has no Google session to offer inside Electron's
 * isolated Chromium profile (FedCM comes back with an empty accounts list),
 * so sign-in happens in the user's real, already-logged-in system browser
 * instead — see google-signin.html and the README's "Login com Google".
 */
ipcMain.handle('google-sign-in', () => {
  if (pendingGoogleSignIn) {
    return Promise.reject(new Error('A Google sign-in is already in progress.'));
  }

  shell.openExternal(`http://127.0.0.1:${serverPort}/google-signin.html`);

  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      pendingGoogleSignIn = null;
      reject(new Error('Google sign-in timed out. Try again from the browser tab that opened.'));
    }, 5 * 60 * 1000);
    pendingGoogleSignIn = { resolve, reject, timeout };
  });
});

async function createWindow() {
  const appDir = path.join(__dirname, '..', 'app');
  try {
    server = await startServer(appDir, { onGoogleCredential: handleGoogleCredential });
  } catch {
    // Fixed port taken by something else — fall back to a random one. The
    // app still works, just without Google sign-in (see server.js).
    server = await startServer(appDir, { port: 0, onGoogleCredential: handleGoogleCredential });
  }
  const { port } = server.address();
  serverPort = port;

  mainWindow = new BrowserWindow({
    width: 1360,
    height: 860,
    minWidth: 960,
    minHeight: 600,
    backgroundColor: '#050609',
    autoHideMenuBar: true,
    icon: path.join(__dirname, '..', 'build', 'icon.png'),
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      preload: path.join(__dirname, 'preload.js'),
    },
  });

  mainWindow.setMenuBarVisibility(false);
  await mainWindow.loadURL(`http://127.0.0.1:${port}/`);

  // Links "abrir em nova aba" (elenco, reviews, mdblist, Wikipedia) devem ir
  // pro navegador do sistema, não abrir uma segunda janela do app.
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url);
    return { action: 'deny' };
  });
}

app.whenReady().then(createWindow);

app.on('window-all-closed', () => {
  if (server) server.close();
  if (process.platform !== 'darwin') app.quit();
});

app.on('activate', () => {
  if (BrowserWindow.getAllWindows().length === 0) createWindow();
});

const { app, BrowserWindow, shell } = require('electron');
const path = require('node:path');
const { startServer } = require('./server');

let mainWindow;
let server;

async function createWindow() {
  const appDir = path.join(__dirname, '..', 'app');
  server = await startServer(appDir);
  const { port } = server.address();

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

// Copia o build do Angular (web/dist/mdblist-hub/browser) para app/,
// de onde o Electron carrega o site empacotado.
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..');
const src = path.join(root, 'web', 'dist', 'mdblist-hub', 'browser');
const dest = path.join(root, 'app');

if (!fs.existsSync(src)) {
  console.error(`Build do site não encontrado em ${src}. Rode "npm run build:web" a partir da raiz.`);
  process.exit(1);
}

fs.rmSync(dest, { recursive: true, force: true });
fs.cpSync(src, dest, { recursive: true });

console.log(`Copiado ${src} -> ${dest}`);

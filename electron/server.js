// Servidor estático local só-loopback que serve o build do Angular e cai em
// index.html para qualquer rota desconhecida (mesmo comportamento SPA do
// Vercel/Cloudflare em produção). Existe para evitar servir via file://, que
// quebra com o <base href="/"> do build do Angular.
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.mjs': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
  '.webmanifest': 'application/manifest+json',
  '.txt': 'text/plain; charset=utf-8',
};

/**
 * Nomes de arquivo que o Angular carimba com hash do conteúdo
 * (`chunk-62XRfdp6.js`, `main-ABC123.css`). Só esses podem ser `immutable`:
 * o conteúdo não muda sem o nome mudar junto.
 */
const HASHED = /-[A-Za-z0-9_-]{8,}\.(?:js|mjs|css)$/;

/**
 * Arquivos que precisam ser revalidados sempre, por mais que o resto seja
 * cacheável. O `index.html` é quem aponta para os chunks novos depois de um
 * update, e o service worker precisa poder ser trocado — um SW velho preso em
 * cache é dos bugs mais difíceis de diagnosticar que existem.
 */
const ALWAYS_REVALIDATE = new Set(['index.html', 'image-sw.js']);

function cacheControlFor(filePath) {
  if (ALWAYS_REVALIDATE.has(path.basename(filePath))) return 'no-cache';
  if (HASHED.test(filePath)) return 'public, max-age=31536000, immutable';
  // O resto — ícones, fontes, imagens do `public/` — não é versionado por
  // nome, então ganha validador em vez de prazo: o `ETag` abaixo transforma a
  // segunda visita num 304 sem corpo.
  return 'no-cache';
}

/**
 * Por que este servidor local se importa com cache.
 *
 * Sem nenhum cabeçalho — como estava — o Chromium não guarda resposta
 * nenhuma, e junto com a resposta ele descarta o **code cache do V8**: o
 * bytecode compilado fica guardado ao lado da entrada de cache HTTP do
 * recurso. Sem entrada, sem bytecode, e todo o bundle do Angular é
 * reinterpretado e recompilado do zero a cada abertura do app. Servir de
 * `127.0.0.1` deixa a leitura do disco barata, mas não faz nada pela
 * compilação, que é a parte cara.
 */
function serveFile(filePath, res, req, stats) {
  const ext = path.extname(filePath).toLowerCase();
  const contentType = MIME[ext] || 'application/octet-stream';
  const cacheControl = cacheControlFor(filePath);

  const headers = { 'Content-Type': contentType, 'Cache-Control': cacheControl };

  if (stats) {
    // Tamanho e mtime bastam: os arquivos aqui são um build copiado inteiro
    // por `copy-web-build.js`, nunca editados no lugar.
    headers.ETag = `"${stats.size.toString(16)}-${Math.floor(stats.mtimeMs).toString(16)}"`;
    headers['Last-Modified'] = stats.mtime.toUTCString();
    headers['Content-Length'] = String(stats.size);

    if (req && req.headers['if-none-match'] === headers.ETag) {
      // Sem corpo, e sem `Content-Length` — um 304 que declara tamanho faz
      // alguns clientes esperarem por bytes que nunca vêm.
      delete headers['Content-Length'];
      res.writeHead(304, headers);
      res.end();
      return;
    }
  }

  const stream = fs.createReadStream(filePath);
  stream.on('error', () => {
    res.writeHead(404);
    res.end('Not found');
  });
  res.writeHead(200, headers);
  stream.pipe(res);
}

/**
 * Fixed, non-standard port (not 0/random). Google Identity Services checks
 * the page's origin against the OAuth client's "Authorized JavaScript
 * origins" in Google Cloud Console — an origin that changes on every launch
 * could never be registered there, so the sign-in prompt would hang forever
 * on "Connecting...". See README's "Login com Google" section for the
 * origin that needs to be registered.
 */
const FIXED_PORT = 47311;

function startServer(rootDir, { port = FIXED_PORT, onGoogleCredential } = {}) {
  return new Promise((resolve, reject) => {
    const server = http.createServer((req, res) => {
      const urlPath = decodeURIComponent((req.url || '/').split('?')[0]);

      // Served from electron/, not rootDir: it's the system-browser page for
      // Google sign-in, not part of the Angular app — see main.js.
      if (req.method === 'GET' && urlPath === '/google-signin.html') {
        serveFile(path.join(__dirname, 'google-signin.html'), res);
        return;
      }

      if (req.method === 'POST' && urlPath === '/google-callback') {
        let body = '';
        req.on('data', (chunk) => {
          body += chunk;
        });
        req.on('end', () => {
          try {
            const { credential } = JSON.parse(body);
            if (!credential) throw new Error('missing credential');
            onGoogleCredential?.(credential);
            res.writeHead(200, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ ok: true }));
          } catch {
            res.writeHead(400);
            res.end('Bad request');
          }
        });
        return;
      }

      const filePath = path.normalize(path.join(rootDir, urlPath));

      if (!filePath.startsWith(rootDir)) {
        res.writeHead(403);
        res.end('Forbidden');
        return;
      }

      fs.stat(filePath, (err, stats) => {
        if (!err && stats.isFile()) {
          serveFile(filePath, res, req, stats);
          return;
        }
        // Fallback de SPA. O `index.html` é statado por conta própria porque
        // este caminho é justamente aquele em que o `stat` acima falhou.
        const indexPath = path.join(rootDir, 'index.html');
        fs.stat(indexPath, (indexErr, indexStats) => {
          serveFile(indexPath, res, req, indexErr ? null : indexStats);
        });
      });
    });

    server.on('error', reject);
    server.listen(port, '127.0.0.1', () => resolve(server));
  });
}

module.exports = { startServer, FIXED_PORT };

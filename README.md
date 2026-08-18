# Open Stream Desktop

App de desktop do [Open Stream](https://openstream.com.br/) — mesmo site,
empacotado com [Electron](https://www.electronjs.org/) para rodar como
aplicativo nativo no Windows (`.exe`) e no Linux (`.AppImage`).

O código do site fica no submódulo `web/` (repo
[mdblist-hub](https://github.com/sauliiin/mdblist-hub)). Este repositório só
tem a casca do Electron: ele builda o Angular do submódulo, copia o resultado
para `app/` e empacota isso num executável. Não há duplicação do código do
site — qualquer mudança no site é só atualizar o submódulo.

O app roda o build embutido offline (não depende do openstream.com.br estar
no ar); só as chamadas às APIs (mdblist, TMDB, OMDb, OpenSubtitles) continuam
precisando de internet, como no site.

---

## Rodando em desenvolvimento

```bash
git clone --recurse-submodules https://github.com/sauliiin/openstream-desktop.git
cd openstream-desktop
npm install
npm start        # builda o site do submódulo e abre a janela do Electron
```

Se já clonou sem `--recurse-submodules`:

```bash
git submodule update --init --recursive
```

## Gerando os instaladores

```bash
npm run dist:linux   # gera release/*.AppImage
npm run dist:win     # gera release/*.exe (nsis) — precisa rodar no Windows,
                      # ou via wine no Linux/macOS
npm run dist         # os dois de uma vez (mesma ressalva do exe)
```

O `.exe` do Windows deve ser gerado num runner Windows (é o que o workflow de
release faz); gerar sob Linux exige Wine e não é garantido pelo
`electron-builder`.

## Release automatizado

`.github/workflows/release.yml` builda em `ubuntu-latest` (AppImage) e
`windows-latest` (exe) e anexa os dois numa GitHub Release sempre que uma tag
`vX.Y.Z` é enviada:

```bash
git tag v1.0.0
git push origin v1.0.0
```

Também dá pra disparar manualmente pela aba Actions (`workflow_dispatch`).

## Atualizando o site embutido

```bash
cd web
git checkout main
git pull
cd ..
git add web
git commit -m "chore: atualiza submódulo web"
```

Isso só atualiza qual commit do mdblist-hub o app aponta — o build em si
acontece em `npm run build:web` / `npm start` / `npm run dist:*`.

## Estrutura

```
electron/main.js     processo principal — cria a janela e serve o app local
electron/server.js   servidor HTTP local (loopback) com fallback de SPA
scripts/copy-web-build.js   copia web/dist/mdblist-hub/browser -> app/
web/                 submódulo git do site (mdblist-hub)
build/icon.png       ícone do app (Windows e Linux)
```

## Por que um servidor HTTP local em vez de `file://`?

O build do Angular usa `<base href="/">`. Sob `file://` isso resolve os
assets a partir da raiz do sistema de arquivos e quebra o app. Em vez de picar
o `index.html` ou trocar a estratégia de rota, o Electron sobe um servidor
HTTP só em `127.0.0.1` (porta aleatória, fecha junto com o app) servindo
`app/` com fallback de SPA — o mesmo comportamento que Vercel e Cloudflare já
fazem em produção (`vercel.json` e `wrangler.json` do site).

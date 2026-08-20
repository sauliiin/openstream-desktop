# OmniStream Desktop

App de desktop do [OmniStream](https://openstream.com.br/) — mesmo site,
empacotado com [Electron](https://www.electronjs.org/) para rodar como
aplicativo nativo no Windows (`.exe`) e no Linux (`.AppImage`).

A versão 1.1 acompanha o app de TV com as aparências CyberFlix e
OptimusPrime, Autotrailer no destaque, reset de progresso e a identidade
OmniStream em toda a interface.

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
git tag v1.1.2
git push origin v1.1.2
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

## Login com Google

O botão "Sign in with your account" usa Google Identity Services
(`google-auth.service.ts` no submódulo `web`), que só libera o fluxo para
origens cadastradas em **Authorized JavaScript origins** do OAuth Client no
Google Cloud Console — senão o prompt fica travado em "Connecting..." pra
sempre, sem erro nenhum.

Por isso `electron/server.js` serve o app numa porta fixa
(`127.0.0.1:47311`, ver `FIXED_PORT`) em vez de uma porta aleatória — uma
porta aleatória nunca poderia ser cadastrada. Pra esse login funcionar no
app desktop, cadastre essa origem uma vez:

1. [console.cloud.google.com](https://console.cloud.google.com/apis/credentials),
   projeto do Firebase `safevault-fcbdc` (o mesmo do app de TV/celular).
2. Abra o OAuth 2.0 Client ID
   `862741916290-5bhenqt1prf98341g2douaedchkqv3nb.apps.googleusercontent.com`.
3. Em **Authorized JavaScript origins**, adicione `http://127.0.0.1:47311`.
4. Salve — não precisa rebuildar o app, o Google aplica na hora.

Sem isso, o app ainda funciona normalmente com "Continue as Guest" ou
colando a chave da API do mdblist direto — só a sincronização de
preferências entre aparelhos via conta Google fica indisponível.

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

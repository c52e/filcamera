# FILCamera website

Static project website for [FILCamera](https://github.com/c52e/filcamera).
English is at `index.html`; Chinese is at `zh/index.html`. Edit both pages together.
Shared CSS, language behavior and the original FILCamera SVG icon are in `assets/`.
No build step, package manager, external font service or CDN is required.

## Local preview

Run Python 3.10+ from this directory:

```sh
python tools/check.py
python tools/serve.py
```

Open `http://127.0.0.1:4173/filcamera/`. The local server deliberately includes the
project subpath so asset and language URLs behave like GitHub Pages. Stop with Ctrl+C.

## Language behavior

English and Chinese are complete static documents. On the English entry page,
JavaScript chooses Chinese when the first browser language is Chinese, unless a
manual language choice was saved. Direct links to the Chinese page remain Chinese.
Language links save a preference when storage is available and include `?lang=`
so manual selection also works when storage is blocked. With JavaScript disabled,
both pages and normal language links remain usable. No cookies or tracking are used.

## GitHub Pages deployment

The local branch is `gh-pages`; no remote is configured by the website setup.
After reviewing the site, connect it to the same GitHub repository as the app and
push this branch. Do not merge the Android `main` tree into the website branch.

```sh
git remote add origin https://github.com/c52e/filcamera.git
git add .
git commit -m "Add FILCamera project website"
git push -u origin gh-pages
```

If a remote `gh-pages` branch already exists, inspect it before pushing; do not
force-push over its history. In repository Settings → Pages, choose “Deploy from a
branch”, `gh-pages`, and `/(root)`. The expected URL is
`https://c52e.github.io/filcamera/`. `.nojekyll` disables Jekyll processing.

All runtime local links are relative. If the repository name or domain changes,
update the canonical/Open Graph/alternate URLs in both pages and this documentation.
Downloads link to the project's Releases page, not to a guessed APK filename.
Repository and release availability must be confirmed when publishing.

## Checks and maintenance

Check both languages at desktop, tablet and narrow phone widths; test language
selection, storage blocked, JavaScript disabled, keyboard navigation and reduced
motion. Screenshots and local verification reports belong in ignored `artifacts/`.
Update device requirements and feature descriptions only from the app's verified
current behavior. The public site has no runtime GitHub API dependency.

Website code: GPL-3.0-only, copyright 2026 FILCamera contributors; see LICENSE.
The FILCamera icon is supplied by the project. Film engine/profile/ICC licensing
belongs to the app's separate license inventory linked from the site. This website
does not redistribute those model assets. System fonts are used locally.

## Optional browser regression

`tools/browser-check.cjs` contains the browser checks used for this site. It requires
Node.js, Playwright and a browser only for development testing, not to build or serve
the website. With those available, start the preview server and run
`node tools/browser-check.cjs`. Set `BROWSER_EXECUTABLE` to use an existing Chromium
browser; `FILCAMERA_PREVIEW_URL` can override the local URL. Screenshots and the JSON
report are written to ignored `artifacts/`.

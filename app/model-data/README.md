# Fixed model data

These 41 runtime assets are pinned by SHA-256 in `manifest.json`; the product build
verifies them without an upstream checkout. The two Portra 800 Push profiles were
updated when exposed in 0.19 to match the original Spektrafilm commit below, rather
than the previously bundled Android variants. All other assets retain the 0.16 data.
Base assets: Spektrafilm Android 15fb120996f03fc1e58a9a9d819e335f65d7a092.
Selectable film/paper profiles and neutral print filters: original Spektrafilm
3bb2c2d2801ff68b92019cf1dbcbb133d60832bc, without further data edits.

Profiles/LUTs have the separate CC BY-SA 4.0 license and preamble in
`../native/engine/licenses/SPEKTRAFILM_LICENSE.txt`; they are not relicensed as GPL.
ICC assets retain their bundled MIT / CC BY-SA notices. The APK includes attribution,
license texts and a separate change log. For profile changes update this manifest
and rerun qualification.

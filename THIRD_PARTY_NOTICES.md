# FILCamera: licenses and attribution

FILCamera's original application, camera, GPU and tooling contributions are licensed
under **GPL-3.0-only**. Copyright 2026 FILCamera contributors. See `LICENSE`.
This does not replace existing third-party copyright or license notices.

| Material | Source / pinned version | License / location |
| --- | --- | --- |
| Film engine | [Spektrafilm Android](https://github.com/thetechgeekko/Spektrafilm-android), `15fb120996f03fc1e58a9a9d819e335f65d7a092` | GPLv3; preserve per-file GPL-3.0-only / GPL-3.0-or-later grants; `app/native/engine/licenses/Spektrafilm-Android-GPL.txt` |
| Algorithm reference and film data | [spektrafilm by Andrea Volpato](https://github.com/andreavolpato/spektrafilm), `3bb2c2d2801ff68b92019cf1dbcbb133d60832bc` | Code GPLv3; profiles/LUTs CC BY-SA 4.0 with unchanged upstream preamble, `app/native/engine/licenses/SPEKTRAFILM_LICENSE.txt` |
| ICC profiles | [Elle Stone](https://github.com/ellelstone/elles_icc_profiles) | CC BY-SA 3.0; `app/model-data/assets/icc/ellelstone/LICENSE-CC-BY-SA-3.0` |
| Compact ICC profiles | [saucecontrol](https://github.com/saucecontrol/Compact-ICC-Profiles) | MIT; `app/model-data/assets/icc/saucecontrol/LICENSE-MIT` |
| RAW lossless JPEG decoding | [libjpeg-turbo 3.2.0](https://github.com/libjpeg-turbo/libjpeg-turbo/releases/tag/3.2.0) | IJG / BSD; downloaded source LICENSE.md and README.ijg bundled in APK |
| White-balance temperature convention/table | Adobe DNG SDK 1.7.1 build 2724; copyright 2006–2019 Adobe Systems Incorporated, All Rights Reserved | `licenses/DNG-SDK.txt`; complete SDK is not linked |
| Gradle wrapper | Android Camera Samples `2f10e46fc7a09f7208f8d6de72482ecf3e5fb85a`; Gradle distribution 8.11.1 | Apache-2.0 and bundled notices in `licenses/` |
| AndroidX / Kotlin | Versions pinned in Gradle build files | Apache-2.0, `licenses/Apache-2.0.txt` |
| Test-only JUnit / Hamcrest | JUnit 4.13.2 and transitive Hamcrest | EPL-1.0 / BSD-3-Clause; not in product APK |

This software is based in part on the work of the Independent JPEG Group.
libjpeg-turbo source archive SHA-256:
`6f30092cef9fb839779646608f4ee14ae3cbac989c47fa05e841b0841f09878e`.

The maintained native fork records per-file provenance in `app/native/engine/ORIGIN.json`.
Its adapters change color alignment, Vulkan transport, spatial execution, precision
and output integration; see `app/native/engine/SOURCE.md`. These are derivative engine
changes, not a claim of independent authorship. Profile assembly is described in
`app/model-data/CHANGELOG.txt`; file bytes are pinned in its `manifest.json`.
Device-baked LUTs are derived from those profiles and retain their data license.

The model asset directory also includes the upstream spectral-upsample table and
catalog/filter metadata. Keep the supplied upstream code and data notices with the
entire dataset; do not claim a single GPL license for every file in the repository.

Film/paper brand names identify the referenced stock models. No affiliation or
endorsement is implied. Upstream names remain only where needed for accurate
attribution, provenance and technical compatibility.

The app's Settings → Open-source licenses view includes the code/data license texts
and third-party notices. Binary releases must include their corresponding source and
the exact build/dependency inventory. Review changes to dependencies and data before
each release; this inventory covers the pinned source tree, not future additions.

Each APK also contains `runtime-dependencies.json`, listing the resolved runtime
modules, POM license declarations, artifact hashes and embedded notice entries.
The combined application notice view preserves those entries verbatim. Maven
platform/metadata modules are included in the graph; an entry without artifacts
does not mean its code is shipped. Build-only and test-only dependencies are not
included in that runtime inventory. See `docs/BUILD.md` for matching delivery files.

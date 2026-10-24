# Contributing

Put user-visible text in `strings/src/main/res/values/` and translate it in
`values-b+zh+Hans`, `values-b+zh+Hant` and `values-ja`, including diagnostic UI.
Preserve format placeholders. Never use a translated message as persistent state or a serialization key.
Code contributions are GPL-3.0-only unless an existing third-party file requires its
original compatible license. Preserve authorship and mark upstream modifications.

Run `python tools/build.py` and `python tools/audit_public.py`. Native algorithm
changes additionally need on-device numerical/performance qualification; see
`docs/TESTING.md`. Do not loosen acceptance gates to make a candidate pass.

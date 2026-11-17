# Fixed model data

The runtime assets are pinned by SHA-256 in `manifest.json`; the product build
verifies them without an upstream checkout. The two Portra 800 Push profiles were
updated when exposed in 0.19 to match the original Spektrafilm commit below, rather
than the previously bundled Android variants.
Base assets: Spektrafilm Android 15fb120996f03fc1e58a9a9d819e335f65d7a092.
The color film and paper profiles come from Spektrafilm
`3bb2c2d2801ff68b92019cf1dbcbb133d60832bc`.
Kodak Double-X, Tri-X and 2302 are unchanged profiles by Andrea Volpato from the
[darktable Spektrafilm 0.3.3 pack](https://github.com/darktable-org/darktable-spektrafilm/tree/main/packs/0.3.3)
(LUT hash `565f4ec4`). FILCamera merges the new monochrome combinations from
that pack into its existing neutral print filter table. Existing color pairs
retain their original calibration. The profile loader selects the middle
development curve where the pack supplies a family.

For 2302 as camera negative, FILCamera fits the TH-KG3 enlarger filters at
normalized midgray using the 5-minute 2302 base spectrum and the output
material's sensitivity, curves and viewing illuminant. The fit uses the
default 1.0 print contrast and minimizes CIELAB chroma with one filter
channel fixed at zero. At relative print log exposures of -0.25, 0 and
+0.25, the fitted color materials have modeled chroma under 4.1. The 2302
monochrome print remains neutral with all three filters at zero. These are
spectral model calibrations. The Tri-X filter rows remain as supplied by
the upstream pack for printing workflows, while FILCamera scans Tri-X directly.

Profiles/LUTs have the separate CC BY-SA 4.0 license and preamble in
`../native/engine/licenses/SPEKTRAFILM_LICENSE.txt`; they are not relicensed as GPL.
ICC assets retain their bundled MIT / CC BY-SA notices. The APK includes attribution,
license texts and a separate change log. For profile changes update this manifest
and rerun qualification.

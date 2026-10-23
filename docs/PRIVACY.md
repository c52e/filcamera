# Privacy

The app operates offline and does not request INTERNET or location permission. No
analytics or account SDK is included. Camera permission is used for capture; system
file pickers grant access to imported/exported DNGs. Sharing grants temporary read
access to the selected file through FileProvider. App backup is disabled.

Original DNGs and metadata live in app-private storage. JPGs explicitly produced by
capture / Save copy are copied to `Pictures/FILCamera`. Exports and shares leave the
app sandbox at the user's request. System gallery copies remain after deleting an
in-app photo. Uninstalling removes private originals; export them first.

Local problem reports retain bounded recent actions, error snapshots and device/app
versions in private, non-backed-up storage. Photos, location and document contents
are not collected. Paths and URIs in diagnostic text are redacted. Users can review,
copy or explicitly share reports from Settings → Problem diagnosis; no report is
uploaded automatically. See [diagnostics.md](diagnostics.md) for retention limits.

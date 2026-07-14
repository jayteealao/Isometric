# Runbook: version-tag-mismatch

## When this fires

Release-workflow logs matching any of the following patterns trigger this runbook:

- `(?i)does not match .* version`
- `(?i)tag v.* does not match`
- `(?i)version check`

## Steps

1. Ensure `isometric-core`, `isometric-compose`, and `isometric-android-view` all declare `version` equal to the tag without the leading `v`.
2. Fix any mismatched `build.gradle.kts` version, commit, and re-tag.
3. Recreate the GitHub Release on the corrected tag.

## Notes

_Seeded from ship plan `recovery-playbooks[version-tag-mismatch]`. Update this file as the playbook evolves._
_Last synced from plan version: 2_

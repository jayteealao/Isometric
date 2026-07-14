# Runbook: kotlin-dsl-hash-mismatch

## When this fires

Build/release logs matching any of the following patterns trigger this runbook:

- `(?i)kotlin-dsl`
- `(?i)precompiled script plugin`
- `(?i)accessors`
- `(?i)hash`

## Steps

1. Delete `build-logic/bin/` (IDE-generated; it pollutes the precompiled-plugin cache).
2. Delete the affected `~/.gradle/caches/` entries (or the whole caches directory).
3. Re-run `./gradlew build` to regenerate the kotlin-dsl accessors.

## Notes

_Seeded from ship plan `recovery-playbooks[kotlin-dsl-hash-mismatch]`. This is a known issue for this repo on Windows._
_Last synced from plan version: 2_

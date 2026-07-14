# Runbook: signing-failure

## When this fires

Release-workflow logs matching any of the following patterns trigger this runbook:

- `(?i)signing failed`
- `(?i)could not read pgp secret key`
- `(?i)no signature`
- `(?i)signingInMemoryKey`

## Steps

1. Confirm the `SIGNING_KEY`, `SIGNING_KEY_ID`, and `SIGNING_KEY_PASSWORD` repository secrets exist and are current.
2. Verify `SIGNING_KEY` is the full ASCII-armored private key — it must include the `BEGIN PGP PRIVATE KEY BLOCK` / `END PGP PRIVATE KEY BLOCK` lines, not just the key id.
3. Re-run the release workflow (re-publish the GitHub Release).

## Notes

_Seeded from ship plan `recovery-playbooks[signing-failure]`. Update this file as the playbook evolves._
_Last synced from plan version: 2_

# Runbook: central-portal-401

## When this fires

Release-workflow logs matching any of the following patterns trigger this runbook:

- `(?i)401`
- `(?i)unauthorized`
- `(?i)invalid credentials`
- `(?i)deployment failed`

## Steps

1. Regenerate a Central Portal user token at central.sonatype.com.
2. Update the `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD` repository secrets.
3. Confirm the `io.github.jayteealao` namespace is verified in the Central Portal.
4. Re-run the release workflow.

## Notes

_Seeded from ship plan `recovery-playbooks[central-portal-401]`. Update this file as the playbook evolves._
_Last synced from plan version: 2_

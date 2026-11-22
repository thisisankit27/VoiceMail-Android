# Contributing

## Workflow
- Work follows the PR sequence in [docs/DEVELOPMENT_ROADMAP.md](docs/DEVELOPMENT_ROADMAP.md). Each PR should do one thing and be reviewable in under 30 minutes.
- Branch names: `feat/<short>`, `fix/<short>`, `docs/<short>`, `spike/<short>`.
- Commit messages follow Conventional Commits, for example `feat(inbox): add transcript search`.
- PRs marked 🔒 in the roadmap must not be started until ADR-0001 is **Accepted** with a GO result.

## Evidence rules
Technical claims in docs and PR descriptions carry an evidence label from [TECHNICAL_FEASIBILITY §0](docs/TECHNICAL_FEASIBILITY.md#0-evidence-labels). Do not present an assumption as a fact.

## Device testing and privacy
- Never commit device serials, IMEI, ICCID, phone numbers, cell IDs, call audio or transcripts of real people. The repository is public.
- Never change a device's default dialer, call forwarding or carrier settings without writing down the user action and how to revert it.
- No root, no Accessibility-service workarounds, no hidden or reflection-based APIs. **Exception (owner decision, 2026-11-22):** the *personal build's* shell-identity audio helper (Option 4) may use the ADB shell identity and the minimum hidden API needed. It must stay isolated in that helper and is never distributed.

## Code standards (from PR-07 onward)
- Kotlin with the official style, enforced by ktlint/detekt.
- Business logic lives in pure Kotlin with unit tests. Android framework types sit behind interfaces.
- Every telephony PR includes the regression checklist from [docs/DEVICE_TESTING.md §5](docs/DEVICE_TESTING.md#5-real-world-test-suite-tests-118), run on the Moto G60.

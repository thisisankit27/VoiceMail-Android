# VoiceMail Android

A native Android app that aims to give missed calls a modern voicemail experience:
- a personal greeting
- messages recorded and transcribed **on the device**
- a polished inbox

> **Project status: Phase 0, technical feasibility.** No application code yet. **2026-11-22:** the decisive experiment failed. During a call, Android delivers only silence to the app's microphone capture, so a normal app cannot record caller messages ([details](docs/TECHNICAL_FEASIBILITY.md#1-executive-verdict)). **Personal-build path under test:** a helper running as the ADB shell identity (no root) is permitted to play the greeting *into* the call and record the caller *from* it. **A real call (Airtel) confirmed it:** the greeting was played into the call and the caller was recorded, with no speakerphone and no room audio ([Option 4](docs/TECHNICAL_FEASIBILITY.md#option-4-default-dialer--shell-identity-audio-helper-personal-build-only-owner-decision-2026-11-22)).
>
> **Read this first:** Android's public APIs do **not** let third-party apps record cellular call audio or play audio into a call. This project is testing an on-device "answering machine" design, in which a default-dialer app answers on speakerphone. It works acoustically, if at all. Whether that works is being tested on real hardware before any product work depends on it. See [Technical Feasibility](docs/TECHNICAL_FEASIBILITY.md).

## Documentation
| Document | Purpose |
|---|---|
| [Product requirements](docs/PRODUCT_REQUIREMENTS.md) | Vision, requirement feasibility, MVP tiers, non-goals |
| [Technical feasibility](docs/TECHNICAL_FEASIBILITY.md) | Capability-by-capability verdicts, options compared, experiments |
| [Telephony research](docs/TELEPHONY_RESEARCH.md) | API-level notes with official sources, Indian carrier notes |
| [ADR-0001: Telephony architecture](docs/decisions/ADR-0001-telephony-architecture.md) | The (provisional) decision and its go/no-go gate |
| [Architecture](docs/ARCHITECTURE.md) | Stack, modules, Mermaid diagrams for all flows |
| [Database design](docs/DATABASE_DESIGN.md) | Room schema, DataStore settings, file lifecycle, encryption |
| [UI/UX specification](docs/UI_UX_SPECIFICATION.md) | Screens and states |
| [Security and privacy](docs/SECURITY_AND_PRIVACY.md) | Threat model, storage, consent (India) |
| [Development roadmap](docs/DEVELOPMENT_ROADMAP.md) | 28 dependency-ordered PRs |
| [Testing strategy](docs/TESTING_STRATEGY.md) | Test types and the emulator / device / carrier matrix |
| [Device testing](docs/DEVICE_TESTING.md) | Moto G60 baseline, Phase 0 experiments, Tests 1–18 |
| [Risk register](docs/RISK_REGISTER.md) | Ranked risks and mitigations |

## Planned stack
Kotlin · Jetpack Compose (Material 3) · Hilt · Room + FTS · Proto DataStore · WorkManager · Media3 · Android Telecom (InCallService) · platform TextToSpeech · offline STT (whisper.cpp or Vosk, chosen by benchmark).

## Primary test device
Motorola Moto G60 (Android 12, API 31, dual-SIM), inspected over ADB. See [Device testing](docs/DEVICE_TESTING.md).

## Contributing
See [CONTRIBUTING.md](CONTRIBUTING.md).

## License
No license. All rights reserved (owner decision, 2026-11-22). Viewing the source does not grant permission to use, copy or redistribute it.

# Risk Register

Likelihood (L) and Impact (I) are rated 1–5. Score = L × I.

| ID | Risk | L | I | Score | Mitigation | Trigger / signal | Owner |
|---|---|---|---|---|---|---|---|
| R1 | **Caller audio capture is silenced for non-privileged apps** (E4 fails), so no on-device voicemail is possible | 5 | 5 | **25** | Run E4 first, before any product telephony work. Escalation options are pre-defined in ADR-0001 (stop, greeting-only, cloud forwarding) | E4 RMS ≈ 0 / AudioFlinger "silenced" logs | Owner |
| R2 | **The greeting is not audible to the caller** (AEC removes it) (E3 fails) | 4 | 5 | **20** | Try three `AudioAttributes` usages and volume levels. Document per device | E3 transcription < 90% | Eng |
| R3 | Replacing the default dialer degrades the user's everyday calling (bugs, missing features) | 4 | 5 | **20** | Keep the dialer minimal but correct. Regression suite on every telephony PR. Clear revert instructions. Emergency calls stay with the system | Any failure in Tests 2–4 | Eng |
| R4 | Carrier no-reply forwarding or network timeout pre-empts the app's timer | 4 | 3 | 12 | Measure in E2. Warn in setup. Default ring time below the measured timeout | E2 timings | Eng |
| R5 | Behaviour differs across OEMs and firmware (audio HAL, background limits) | 5 | 4 | **20** | Mandatory in-app self-test. No universal claims. Keep a compatibility list | User reports, self-test failures | Eng |
| R6 | Play policy rejection (call-log permissions, perceived call recording) | 3 | 4 | 12 | Default-handler declaration. Copy that avoids "call recording". No Accessibility API. Privacy policy | Review feedback | Owner |
| R7 | Legal or consent exposure for recording callers (India and elsewhere) | 2 | 4 | 8 | Disclosure in the greeting. Record messages only. Local-only storage. Counsel review before public release | Pre-launch review | Owner |
| R8 | Offline STT is too slow or inaccurate on mid-range devices (Hindi/Hinglish) | 3 | 3 | 9 | E8 benchmark. Model size choice. Transcription is asynchronous and optional. Cloud STT is a possible future opt-in | E8 RTF > 1 or WER > 35% | Eng |
| R9 | Offline TTS voices unavailable for the user's language | 2 | 2 | 4 | Recorded or imported greeting as the alternative. Voice-download guidance | E7 | Eng |
| R10 | No SIM or second phone is available, so Phase 0 stalls | 3 (current) | 4 | 12 | Run E1, E7 and E8 now. Keep Phase 1 work in parallel | SIM still absent | Owner |
| R11 | Speakerphone answering exposes the caller's voice in the room (privacy, social) | 4 | 2 | 8 | Disclose it. "Take over" and "End" controls. Per-SIM and schedule toggles (future) | User feedback | Product |
| R12 | Data loss: recordings lost through crashes or migrations | 2 | 5 | 10 | Row-first WAV streaming. No destructive migrations. Migration tests. Orphan sweeper | Crash reports in testing | Eng |
| R13 | Effort under-estimated (a full dialer is large) | 4 | 3 | 12 | Strictly minimal dialer scope. Phase 4 starts only after GO | Phase 4 velocity | Owner |
| R14 | `CallScreeningService` 5 s deadline exceeded in rules-only mode with slow contact lookups | 2 | 2 | 4 | In-memory number cache warmed on start, refreshed by ContentObserver | Timeouts in logs | Eng |
| R15 | PII leaks via the public repo (device logs, numbers) | 2 | 3 | 6 | Redaction policy (DEVICE_TESTING §2). `.gitignore` for captures. Review device-log diffs | PR review | Eng |

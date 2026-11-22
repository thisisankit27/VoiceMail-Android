# Testing Strategy

## 1. Approach
- **The logic is pure Kotlin and heavily unit-tested:** rule engine, session state machine, number normalisation, WAV header maths and retention policy. Android types (`Call`, `AudioRecord`, `TextToSpeech`) sit behind small interfaces so the logic can be tested without them.
- **Android integrations get focused instrumented tests:** Room, DataStore, MediaCodec, Media3 and WorkManager.
- **Telephony is validated on real hardware.** Emulators can simulate calls (`adb emu gsm call`), but they cannot reproduce real audio HAL, AEC or carrier behaviour. Emulator results never count as evidence for the Phase 0 gate.

## 2. Test types
| Type | Tools | Location | Runs in CI |
|---|---|---|---|
| Unit | JUnit 4/5, kotlinx-coroutines-test, Turbine, MockK (sparingly) | `src/test` per module | ✅ |
| Robolectric | Robolectric for Context-dependent code (DataStore, notifications builder) | `src/test` | ✅ |
| Room / migration | `Room.inMemoryDatabaseBuilder`, `MigrationTestHelper` | `core/database/src/test` (Robolectric) or `androidTest` | ✅ |
| Repository | Fakes for DAO and file store | `src/test` | ✅ |
| Compose UI | `createComposeRule`, semantics assertions, screenshot tests (Roborazzi, optional) | `src/test` (Robolectric) / `androidTest` | ✅ (Robolectric) |
| Audio playback | Media3 `TestExoPlayerBuilder`, `FakeClock` | `src/test` | ✅ |
| Audio encode/decode | Instrumented: encode a known sine → decode → compare RMS and frequency | `audio/src/androidTest` | Emulator, nightly |
| Transcription | Engine contract tests with fixtures; WER thresholds | `transcription/src/androidTest` | Device only (native, heavy) |
| Telecom integration | Fake `CallFacade` in unit tests; emulator `gsm call` for UI flows; G60 for real behaviour | `telephony/src/test`, manual | Unit ✅ |
| Physical device | DEVICE_TESTING E1–E10, Tests 1–18 | Manual with ADB scripts | ❌ |
| Regression | Tests 1, 2, 3, 4, 9, 13, 17 on each telephony PR | Manual checklist in the PR template | ❌ |

## 3. Test matrix
Legend: **EMU** = emulator is enough; **G60** = needs the Moto G60 (no SIM); **G60+SIM** = G60 with a SIM; **+B** = second phone; **CARRIER** = depends on operator behaviour or cooperation.

| Area / test | EMU | G60 | G60+SIM | +B | CARRIER |
|---|---|---|---|---|---|
| Rule engine, state machine, normalisation (unit) | ✅ (JVM) | | | | |
| Room, DataStore, migrations | ✅ | | | | |
| Inbox, detail, search UI | ✅ | ✅ smoke | | | |
| Media3 playback | ✅ | ✅ | | | |
| Greeting recording (mic) | ⚠️ host mic | ✅ | | | |
| TTS render / voices (E7) | ⚠️ emulator voices differ | ✅ | | | |
| STT benchmark (E8) | ❌ (wrong CPU) | ✅ | | | |
| Notifications (incl. lock screen) | ✅ | ✅ | | | |
| Role acquisition (E1) | ✅ | ✅ | | | |
| Dialer UI flows (incoming/in-call) | ✅ `adb emu gsm call` | | ✅ | ✅ | |
| Ring timer + answer (E2, Tests 1–4) | ⚠️ logic only | | ✅ | ✅ | ✅ network timeout |
| Greeting audible to caller (E3, Tests 11–13) | ❌ | | ✅ | ✅ | ⚠️ codec (AMR/EVS) |
| Caller capture (E4, Tests 9–10) | ❌ | | ✅ | ✅ | |
| Locked/unattended (E5), battery (Test 18) | ❌ | | ✅ | ✅ | |
| Carrier forwarding / voicemail (E9, Test 5) | ❌ | | ✅ | ✅ | ✅ |
| Screening reject experience (E10) | ❌ | | ✅ | ✅ | ✅ |
| Contact rules end-to-end (Tests 6–8) | ⚠️ | | ✅ | ✅ | |
| Dual-SIM (Test 16) | ❌ | | ✅ ×2 SIMs | ✅ | ✅ |
| Reboot / process death (Test 17) | ⚠️ | | ✅ | ✅ | |

## 4. Fixtures
- `fixtures/audio/`: short, **consented or public-domain** speech clips with reference transcripts (Indian English, Hindi, Hinglish). They are stored with Git LFS or downloaded by script. Raw call recordings are never committed.
- Fake voicemails can be injected through a debug-only menu or `adb shell am broadcast` to a **debug-only** receiver that is not present in release builds.

## 5. CI
- Every PR runs `lint`, `detekt`/`ktlint`, `testDebugUnitTest` and `assembleDebug`, plus a Room schema diff check.
- Nightly runs emulator instrumented tests (API 29 and API 36 via Gradle Managed Devices).
- Device tests are recorded manually in DEVICE_TESTING §6. A telephony PR cannot merge without a filled regression checklist in its description.

## 6. Definition of done for a PR
- Unit tests exist for new logic, and coverage of `contacts`, `telephony.session` and `audio.wav` stays at or above 80%.
- No new lint warnings. Screens are accessibility-checked with the Compose semantics test.
- For telephony PRs: the regression set has been run on the G60 and recorded.

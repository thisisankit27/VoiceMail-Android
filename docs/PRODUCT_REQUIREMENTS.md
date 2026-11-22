# Product Requirements

## 1. Executive product overview
**VoiceMail Android** aims to make missed calls feel like Apple's Visual Voicemail:
- Callers hear a personal greeting and can leave a message.
- Messages are transcribed on the device.
- The user browses, searches and plays them back in a polished native app.
- Nothing has to leave the phone.

**Reality check (see [TECHNICAL_FEASIBILITY.md](TECHNICAL_FEASIBILITY.md)):** Android does not give third-party apps a supported way to record a cellular call or play audio into one. The chosen on-device design is a **default-dialer "answering machine"** that works acoustically over the speakerphone, and it is **unverified**. Every requirement below is tagged with its feasibility status. Requirements that depend on the Phase 0 gate are marked **🔒 GATED**.

## 2. Target user and context
- **Primary user:** an individual Android user in India, often on Jio or Airtel, who misses calls regularly (meetings, driving, sleep) and whose operator offers no usable voicemail **[UNVERIFIED: E9]**.
- **Primary device for development:** Motorola Moto G60, Android 12, dual-SIM.
- **Caller:** anyone with any phone. They need no app and hear a normal voice prompt.

## 3. Requirements and feasibility

| ID | Requirement | Status |
|---|---|---|
| **A. Automatic handling** | | |
| A1 | Phone rings normally | ✅ Supported (app is dialer; standard ringing UI) |
| A2 | After N s unanswered, voicemail takes over | 🔒 GATED. Answering is supported; timing depends on the carrier (E2) |
| A3 | Caller hears greeting | 🔒 GATED. No API; acoustic path only (E3) |
| A4 | Caller invited to leave message (tone) | 🔒 GATED (E3) |
| A5 | Caller's message recorded | ❌ **Not possible on-device.** E4 failed: the framework silences all capture during calls (G60, 2026-11-22) |
| A6 | Recording saved | ✅ once A5 works |
| A7 | Ends on hang-up or max duration | ✅ `Call.disconnect()` and state callbacks |
| **B. Greeting** | | |
| B1 | Edit greeting text | ✅ |
| B2 | Generate audio by TTS | ✅ Platform TextToSpeech |
| B3 | Change TTS voice | ✅ `getVoices()`; offline availability per E7 |
| B4 | Preview | ✅ |
| B5 | Record greeting with the mic | ✅ |
| B6 | Import an audio file | ✅ SAF picker plus normalisation |
| B7 | Play and preview the selected greeting | ✅ |
| B8 | Restore default | ✅ |
| **C. Allow message toggle** | | |
| C1 | Toggle, default ON | ✅ setting |
| C2 | OFF: greeting plus closing message, no recording, hang up | 🔒 GATED on E3 only (E4 not needed) |
| C3 | Separate greetings per mode | ✅ `greeting.purpose` |
| **D. Inbox** | | |
| D1–D4 | Save audio, number, contact, timestamp | ✅ |
| D5–D6 | Transcribe and display | ✅ Offline engine (E8). Asynchronous |
| D7–D8 | Playback with seek | ✅ Media3 |
| D9–D10 | Delete; read/unread | ✅ |
| D11 | Search and filter | ✅ FTS4 |
| **E. Notifications** | | |
| E1–E6 | Caller, preview, deep link, dismiss, lock-screen privacy, disable | ✅ |
| **F. Contact rules** | | |
| F1 | Modes: all, selected, unknown only | ✅ evaluated in InCallService (🔒 only in the sense that interception itself is gated) |
| F2 | Always or never per contact | ✅ Same caveat. Precedence defined in [DATABASE_DESIGN §3](DATABASE_DESIGN.md#3-rule-precedence-data-needs) |
| **G. Settings** | | |
| G* | Duration, volume, notifications, language, retention, storage, default greeting, voice, quality, privacy, SIM, master switch | ✅ |

## 4. MVP definition

The MVP is defined in **two tiers** because of the gate.

### Tier 1: "Voicemail studio", always buildable (Phases 1–3, 5–6)
- The app shell, onboarding with an honest capability screen, inbox and detail, the player, search, read and delete.
- The greeting studio: TTS with voice choice, recording, import, default greeting and a message-disabled variant.
- The offline transcription engine, working on any audio in the inbox. During development the inbox is filled from test fixtures.
- Notification settings and pipeline, and a privacy-first storage baseline.
- **Value on its own:** limited. Without call handling the inbox stays empty. Tier 1 is infrastructure, not a product.

### Tier 2: "Answering machine", only if E3 + E4 pass (Phase 4, 7)
- Default dialer: dial pad, incoming UI and in-call UI at a credible, minimal level.
- The VoicemailSession: ring timer, answer, speaker, greeting, beep, record, disconnect.
- Contact rules and routing modes.
- The compatibility self-test, which must pass before the feature can be enabled.
- Dual-SIM per-slot enable.

### Explicit non-goals for the MVP
- Cloud TTS or cloud STT, analytics or crash reporting that uploads data, and any account system.
- Visual Voicemail (OMTP) client, unless E9 finds carrier support.
- Accessibility-service, root or privileged tricks.
- Recording calls the user takes part in. This product only records messages *left for* the user.
- iOS, wearables and tablets.

## 5. Success metrics (local, no telemetry)
Measured during device testing, not collected from users:
- **Interception reliability:** the fraction of qualifying unanswered calls where the session reaches `Recording`, target ≥ 95% on supported devices.
- **Greeting intelligibility:** at least 90% of words understood by the caller (E3 protocol).
- **Message capture:** human-transcribable in at least 90% of trials (E4 protocol).
- **Transcription:** word error rate ≤ 25% on Indian-English fixtures (E8). Real-time factor ≤ 1.0 on the G60.
- **No regressions to normal calling:** zero missed or dropped user-answered calls across the Test 1–18 suite.

# Architecture

> The telephony layer described here follows the **provisional** [ADR-0001](decisions/ADR-0001-telephony-architecture.md). Every component marked 🔒 is **blocked by the Phase 0 gate** and is not built unless experiments E3 and E4 pass. Everything else (inbox, greetings, TTS, transcription, notifications, rules) is independent of that gate.

## 1. Technology stack

| Concern | Choice | Why |
|---|---|---|
| Language | **Kotlin 2.x** | First-class Android language. Coroutines are a natural fit for call and audio lifecycles. |
| UI | **Jetpack Compose + Material 3** | Modern, declarative, and testable with `compose-ui-test`. Supports dynamic color for a premium native feel. |
| Navigation | **Navigation Compose (type-safe routes)** | Deep links from notifications go straight to a voicemail detail. |
| DI | **Hilt** | The standard choice. Has Android-component injection for `InCallService`, `Worker` and `ViewModel`. Koin is lighter but loses compile-time graph validation. |
| Async | **Coroutines + Flow** | Room, DataStore and Media3 all expose Flow and suspend APIs. |
| Persistence | **Room (KSP)** for voicemails, greetings and rules, with **FTS4** for transcript search | Relational data that needs queries, migrations and observation. |
| Settings | **Proto DataStore** | Typed and atomic. Replaces the proposed `AppSettings` table, since a single-row table is an anti-pattern. |
| Background | **WorkManager** | Transcription, encoding, retention cleanup and orphan sweeping. Retries and constraints survive process death. |
| Playback | **Media3 ExoPlayer** (+ `MediaSession` for lock-screen controls) | Seeking, speed control and audio focus come built in. |
| Capture | **AudioRecord → PCM WAV** (crash-safe) then **MediaCodec AAC → .m4a** | WAV is written incrementally, so a killed process still leaves a valid prefix. AAC shrinks storage about 10×. |
| TTS | Platform **TextToSpeech** | Offline-capable and free. No vendor lock-in. |
| STT | **whisper.cpp** or **Vosk** (decided in E8) behind a `TranscriptionEngine` interface | Offline. The engine can be swapped later. |
| Phone numbers | **libphonenumber** | Normalises numbers to E.164 across `+91` and `0`-prefixed national formats. |
| Testing | JUnit, Robolectric, Turbine, Room in-memory, Compose UI test, UI Automator | See [TESTING_STRATEGY.md](TESTING_STRATEGY.md). |
| Build | Gradle KTS, version catalog, convention plugins (`build-logic/`) | Keeps module build files short. |
| SDK levels | `minSdk 29`, `compileSdk`/`targetSdk 36` | API 29 brings RoleManager and the concurrent-capture policy. 36 is installed locally and meets Play targeting. |

**Rejected:** Flutter and React Native. Every hard part of this app is a platform service (InCallService, AudioRecord, roles), so a cross-platform layer only adds bridges.

## 2. Modules

```
VoiceMail-Android/
├── app/                      Application, MainActivity, NavHost, Hilt entry, manifest merge
├── build-logic/              Gradle convention plugins
├── core/
│   ├── model/                Pure Kotlin: Voicemail, Greeting, ContactRule, enums, PhoneNumber value class
│   ├── common/               Dispatchers, Result types, clock, logging facade
│   ├── database/             Room DB, DAOs, entities, migrations, FTS
│   ├── datastore/            Proto DataStore (AppSettings proto) + repository
│   └── designsystem/         Theme, typography, components (player bar, waveform, empty states)
├── audio/                    GreetingPlayer, CallAudioRecorder, WavWriter, AacEncoder, AudioFileStore
├── telephony/  🔒            VoicemailInCallService, CallRegistry, VoicemailSession state machine,
│                             RingTimer, AudioRouteController, CompatibilitySelfTest
├── transcription/            TranscriptionEngine interface, engine impl (JNI), TranscriptionWorker, model manager
├── contacts/                 ContactLookup, NumberNormalizer, RuleEngine (pure, unit-testable)
├── notifications/            Channels, VoicemailNotifier, privacy redaction
├── feature/
│   ├── onboarding/           Capability explanation, role + permission requests
│   ├── inbox/                Dashboard, inbox list, search/filter, detail + player
│   ├── greeting/             Editor, TTS voice picker, recorder, import
│   ├── rules/                Routing mode, contact rules
│   ├── settings/             General, recording, notification, privacy, storage, diagnostics
│   └── dialer/  🔒           Dial pad (ACTION_DIAL), incoming-call UI, in-call UI — required by ROLE_DIALER
└── spikes/telecom-probe/     Phase 0 throwaway experiment app (not shipped, not depended on)
```

The modules are deliberately coarse. `voicemail` and `settings` from the original brief are folded into `core:*` and `feature:*`, because neither has independent logic. **A module only exists if something consumes it.**

Package root and application ID: `io.github.thisisankit27.voicemail` (with `.<module>` packages, for example `io.github.thisisankit27.voicemail.telephony.session`). It is derived from the GitHub namespace the owner controls; the owner delegated the choice on 2026-11-22. The Phase 0 probe uses `io.github.thisisankit27.voicemail.probe`.

### Responsibilities and dependency rules
- `feature:*` → `core:*`, `audio`, `contacts`, `transcription` (interfaces only). Features never depend on each other.
- `telephony` → `audio`, `contacts`, `core:database`, `core:datastore`, `notifications`. It is the **only** module that touches `android.telecom`.
- `core:model` has no Android dependencies.
- `RuleEngine` in `contacts` is pure Kotlin, so precedence logic is fully unit-tested.

## 3. Application architecture

```mermaid
flowchart TB
  subgraph SYS["Android system services (not ours)"]
    TEL["Telecom / TelephonyConnectionService"]
    ROLE["RoleManager"]
    AUD["AudioFlinger / Audio HAL"]
    TTS["TTS engine (Google TTS)"]
    CP["Contacts / CallLog providers"]
    NM["NotificationManager"]
  end

  subgraph APP["App process"]
    subgraph UI["UI layer (Compose)"]
      ONB["Onboarding"]
      INB["Inbox / Detail"]
      GRE["Greeting editor"]
      RUL["Rules / Settings"]
      DIA["Dialer + In-call UI 🔒"]
    end
    subgraph SVC["Application services"]
      ICS["VoicemailInCallService 🔒"]
      SES["VoicemailSession 🔒"]
      REC["CallAudioRecorder"]
      GP["GreetingPlayer"]
      RE["RuleEngine"]
      NOTI["VoicemailNotifier"]
    end
    subgraph DATA["Data layer"]
      DB[("Room DB")]
      DS[("Proto DataStore")]
      FS[("filesDir/voicemails, greetings")]
    end
  end

  subgraph BG["Background workers (WorkManager)"]
    W1["FinalizeRecordingWorker"]
    W2["TranscriptionWorker"]
    W3["RetentionWorker"]
  end

  TEL -- binds --> ICS
  ROLE -. grants ROLE_DIALER .-> ICS
  ICS --> SES
  SES --> RE
  SES --> GP
  SES --> REC
  GP --> AUD
  REC --> AUD
  RE --> CP
  RE --> DS
  SES --> DB
  REC --> FS
  SES --> W1
  W1 --> W2
  W2 --> DB
  W2 --> NOTI
  NOTI --> NM
  GRE --> TTS
  GRE --> FS
  INB --> DB
  INB --> FS
  ICS --> DIA
```

**Boundaries:**
1. **System services** own the call, the audio routing and the role grants. We only request things from them.
2. **Application services** run while a call exists: the InCallService and session are bound by Telecom for the life of the call. They must not do heavy work.
3. **Background workers** handle everything deferrable (encoding, STT, retention). They are idempotent and retryable.

## 4. Incoming call handling flow 🔒

```mermaid
sequenceDiagram
  autonumber
  participant N as Network
  participant T as Telecom
  participant S as VoicemailInCallService
  participant R as RuleEngine
  participant UI as Incoming-call UI
  participant V as VoicemailSession

  N->>T: Incoming call (SIM n)
  T->>S: onCallAdded(call) [STATE_RINGING]
  S->>UI: Show incoming-call UI (ring normally)
  S->>R: evaluate(number, simId, settings, rules)
  alt Decision = NEVER_INTERCEPT
    R-->>S: ringNormally
    Note over S: User answers / rejects / network times out — standard behaviour
  else Decision = INTERCEPT(after = ringSeconds)
    R-->>S: intercept(ringSeconds, greetingId, allowMessage)
    S->>V: start RingTimer(ringSeconds)
    alt User answers or rejects first
      UI->>T: answer()/reject()
      S->>V: cancel()
    else Caller hangs up first
      T->>S: onStateChanged(DISCONNECTED)
      S->>V: cancel() (log missed call)
    else Timer fires while STATE_RINGING
      V->>T: call.answer(STATE_AUDIO_ONLY)
      V->>T: route audio to SPEAKER
      V->>V: run greeting → beep → record (see §5)
    end
  end
```

## 5. VoicemailSession state machine 🔒

```mermaid
stateDiagram-v2
  [*] --> Ringing
  Ringing --> Cancelled: user answered / rejected / caller hung up
  Ringing --> Answering: ring timer elapsed
  Answering --> PlayingGreeting: call ACTIVE + speaker route confirmed
  Answering --> Failed: answer timeout (3 s) / call disconnected
  PlayingGreeting --> PlayingClosing: allowMessage = false
  PlayingGreeting --> Beep: allowMessage = true
  PlayingGreeting --> Finalizing: caller hung up
  Beep --> Recording
  Recording --> Finalizing: caller hung up
  Recording --> Finalizing: max duration reached
  Recording --> Finalizing: silence > N s (optional)
  PlayingClosing --> Disconnecting
  Finalizing --> Disconnecting
  Disconnecting --> Done: call.disconnect() if still active
  Failed --> Done
  Cancelled --> [*]
  Done --> [*]
```

Invariants:
- A session only ever acts on **one** `Call` and never touches a call the user is engaged in (call waiting, or a second ringing call while one is active).
- Emergency calls never reach this code. Telecom routes them to the preloaded dialer.
- Every state transition is written to `call_session_log` for diagnostics.

## 6. Voicemail recording flow 🔒

```mermaid
flowchart LR
  A["Recording state entered"] --> B["Create Voicemail row (status=RECORDING, file path reserved)"]
  B --> C["AudioRecord 16 kHz mono PCM"]
  C --> D["WavWriter: stream to vm_UUID.wav, header patched every 1 s"]
  D --> E{"Stop condition"}
  E -->|"hangup / max / silence"| F["Close WAV, set duration"]
  F --> G["status=RECORDED, enqueue FinalizeRecordingWorker"]
  G --> H["Worker: trim leading beep, detect empty (RMS) → status EMPTY or keep"]
  H --> I["Encode AAC .m4a, verify, delete .wav (keep .wav until verified)"]
  I --> J["Enqueue TranscriptionWorker + notify"]
```

Silence detection decides whether the caller actually left a message. Recordings that are pure silence are kept as `EMPTY` entries (shown as "Caller hung up without leaving a message") so nothing is silently lost.

## 7. Transcription pipeline

```mermaid
flowchart LR
  Q["TranscriptionWorker (unique per voicemailId, KEEP)"] --> C{"Model present?"}
  C -->|no| M["status=WAITING_FOR_MODEL; prompt user to download in Settings"]
  C -->|yes| D["Decode m4a → 16 kHz PCM"]
  D --> E["Engine.transcribe(pcm, language)"]
  E -->|success| S["Update transcript + FTS, status=DONE"]
  E -->|"error / OOM / timeout"| R{"attempt < 3?"}
  R -->|yes| Q
  R -->|no| F["status=FAILED (audio untouched, user can retry)"]
  S --> N["Update notification with preview (if enabled)"]
```

- The original audio is **never** modified or deleted by transcription.
- The worker runs with `setRequiresBatteryNotLow(true)`. Inference runs on `Dispatchers.Default` with a thread cap.
- The language comes from settings, with `auto` supported when the engine allows it.

## 8. Notification pipeline

```mermaid
flowchart TB
  E["New voicemail finalized"] --> P{"notificationsEnabled?"}
  P -->|no| X["No notification; inbox badge only"]
  P -->|yes| B["Build notification: title 'New voicemail', text = caller name/number"]
  B --> T{"transcript ready AND previewEnabled?"}
  T -->|yes| T2["Add 1-line transcript preview"]
  T -->|no| T3["Text: 'Tap to listen'"]
  T2 --> L["visibility = PRIVATE; publicVersion = 'New voicemail' (no name, no text) unless lockScreenPreview = true"]
  T3 --> L
  L --> C["Channel 'voicemail_new' (IMPORTANCE_DEFAULT), contentIntent → deep link voicemail/ID, actions: Play, Mark read"]
  C --> U["Update in place when transcript arrives (same notification id)"]
```

## 9. Database relationships

See [DATABASE_DESIGN.md](DATABASE_DESIGN.md) for the full schema.

```mermaid
erDiagram
  GREETING ||--o{ VOICEMAIL : "played for"
  VOICEMAIL ||--o| VOICEMAIL_FTS : "indexed by"
  CONTACT_RULE }o--|| PHONE_NUMBER_KEY : "matches"
  VOICEMAIL }o--|| PHONE_NUMBER_KEY : "from"
  VOICEMAIL ||--o{ CALL_SESSION_LOG : "produced by"

  VOICEMAIL {
    string id PK
    string callerNumberE164
    string callerNumberRaw
    string lookupKey
    string callerNameSnapshot
    string audioFileName
    int durationMs
    long createdAt
    bool isRead
    string recordingStatus
    string transcriptionStatus
    string transcript
    int simSlot
    string greetingId FK
  }
  GREETING {
    string id PK
    string name
    string sourceType
    string text
    string ttsVoiceName
    string audioFileName
    string purpose
    bool isActive
  }
  CONTACT_RULE {
    string id PK
    string numberE164
    string lookupKey
    string ruleType
    bool enabled
  }
  CALL_SESSION_LOG {
    long id PK
    string sessionId
    string voicemailId FK
    string state
    long at
    string detail
  }
```

`PHONE_NUMBER_KEY` is a conceptual join on the E.164 value. It is not a table.

## 10. Process and lifecycle notes
- Telecom keeps `VoicemailInCallService` bound for the whole call, which keeps the process alive.
- Whether starting `AudioRecord` from this context on a locked device is allowed, and whether a `microphone` / `phoneCall` FGS is required, is answered by **E5**. The design assumes a `phoneCall`-type FGS started from the in-call context, which needs `ROLE_DIALER`.
- After a reboot nothing needs to start eagerly. Telecom rebinds the dialer on the next call. `RetentionWorker` is periodic.
- If the process dies mid-recording, the partially written WAV stays valid up to the last header patch. On next start, an orphan sweeper finds rows stuck in `RECORDING` and marks them `RECORDED_PARTIAL`.

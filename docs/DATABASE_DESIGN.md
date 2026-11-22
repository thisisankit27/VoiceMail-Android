# Database Design

## 1. Principles
- **Audio is stored as files and never as BLOBs.** Rows store a *file name* (not an absolute path) relative to a known app-private directory. That keeps rows valid if the data directory moves, for example after a restore or a multi-user change.
- **The audio and the transcript are independent.** A missing or failed transcript never affects playback.
- **Settings live in Proto DataStore, not Room.** The proposed `AppSettings` table is replaced (see §6).
- **Contact data is snapshotted and resolved at display time.** Each voicemail keeps the caller name as it was when recorded (`callerNameSnapshot`) and a `lookupKey`. The UI re-resolves the live contact name when `READ_CONTACTS` is granted, and falls back to the snapshot or the number when it isn't.
- **IDs are UUID strings** for voicemails and greetings, so file names don't leak sequence and exports and imports don't collide.

## 2. Entities (Room, version 1)

### 2.1 `voicemail`
| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | TEXT PK | no | UUID |
| `caller_number_raw` | TEXT | yes | As delivered by Telecom. `NULL` for private/withheld callers |
| `caller_number_e164` | TEXT | yes | Normalised with libphonenumber, using the SIM's country ISO as default region |
| `presentation` | TEXT | no | `ALLOWED`, `RESTRICTED`, `UNKNOWN` or `PAYPHONE` (from `Call.Details.getHandlePresentation`) |
| `contact_lookup_key` | TEXT | yes | `ContactsContract` lookup key. More stable than `contactId` |
| `caller_name_snapshot` | TEXT | yes | |
| `audio_file_name` | TEXT | yes | `vm_<id>.m4a` (or `.wav` before encoding). `NULL` if no audio was captured |
| `duration_ms` | INTEGER | no | Default 0 |
| `created_at` | INTEGER | no | Epoch ms (UTC). The call's connect time |
| `ring_duration_ms` | INTEGER | no | For diagnostics |
| `is_read` | INTEGER (bool) | no | Default 0 |
| `is_deleted` | INTEGER (bool) | no | Soft delete for "undo" (30 s). Hard-deleted by the worker |
| `recording_status` | TEXT | no | `RECORDING`, `RECORDED`, `RECORDED_PARTIAL`, `ENCODED`, `EMPTY` or `FAILED` |
| `transcription_status` | TEXT | no | `NOT_STARTED`, `QUEUED`, `WAITING_FOR_MODEL`, `IN_PROGRESS`, `DONE`, `FAILED` or `DISABLED` |
| `transcript` | TEXT | yes | |
| `transcript_language` | TEXT | yes | BCP-47 tag |
| `transcription_engine` | TEXT | yes | For example `whisper-base-q5@1.0` (reproducibility) |
| `sim_slot_index` | INTEGER | yes | |
| `subscription_id` | INTEGER | yes | Can change if the SIM is reinserted. The slot is the user-facing value |
| `greeting_id` | TEXT FK → `greeting.id` | yes | `ON DELETE SET NULL` |
| `allow_message_at_time` | INTEGER (bool) | no | What mode was active |

**Indices:**
- `(is_deleted, created_at DESC)` for the inbox.
- `(caller_number_e164)` for per-caller history.
- `(is_read)` for the unread count.
- `(transcription_status)` for the worker pickup query.

**Checks:** Status values are enforced through Kotlin enums with `TypeConverter`s. Room does not express SQL `CHECK` constraints declaratively, so they are added in a migration callback if needed.

### 2.2 `voicemail_fts` (FTS4, external content)
`@Fts4(contentEntity = Voicemail::class)` over `transcript`, `caller_name_snapshot` and `caller_number_e164`. This gives search over transcripts and names. Room keeps the triggers in sync.

### 2.3 `greeting`
| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | TEXT PK | no | UUID. Built-in defaults use fixed IDs (`default_message`, `default_no_message`) |
| `name` | TEXT | no | User label |
| `source_type` | TEXT | no | `TTS`, `RECORDED`, `IMPORTED` or `BUILT_IN` |
| `text` | TEXT | yes | TTS source text, also used as a transcript for recorded greetings when the user supplies one |
| `tts_voice_name` | TEXT | yes | `Voice.getName()` |
| `tts_locale` | TEXT | yes | |
| `tts_rate`, `tts_pitch` | REAL | yes | |
| `audio_file_name` | TEXT | yes | Rendered or recorded audio in `greetings/` |
| `audio_sha256` | TEXT | yes | Detects a stale render after a text edit |
| `purpose` | TEXT | no | `MESSAGE_ALLOWED` or `MESSAGE_DISABLED` |
| `is_active` | INTEGER (bool) | no | **At most one active greeting per `purpose`**, enforced by a partial unique index (below) |
| `created_at`, `updated_at` | INTEGER | no | |

**Index:** `CREATE UNIQUE INDEX idx_greeting_active ON greeting(purpose) WHERE is_active = 1`. Created in `RoomDatabase.Callback.onCreate`, because Room annotations cannot express partial indices.

Built-in greetings are seeded on first run and cannot be deleted. "Restore default" re-activates them.

### 2.4 `contact_rule`
| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | TEXT PK | no | |
| `rule_type` | TEXT | no | `ALWAYS_VOICEMAIL`, `NEVER_VOICEMAIL` or `SELECTED` (member of the "selected contacts" set) |
| `match_kind` | TEXT | no | `CONTACT` (lookup key, all of its numbers) or `NUMBER` (single E.164) |
| `contact_lookup_key` | TEXT | yes | |
| `number_e164` | TEXT | yes | |
| `display_name_snapshot` | TEXT | yes | |
| `enabled` | INTEGER (bool) | no | |
| `created_at` | INTEGER | no | |

**Indices:** `UNIQUE(rule_type, contact_lookup_key)` and `UNIQUE(rule_type, number_e164)`. Plain unique indices treat NULLs as distinct, so the `CONTACT` and `NUMBER` kinds coexist.

**Contact rules are resolved to a number set at evaluation time.** The contact's current numbers are fetched via its lookup key and normalised, and the result is cached in memory and invalidated by a `ContentObserver`. This handles duplicate contacts and edited numbers. The 5-second screening budget is not a concern for this path, because the dialer path has no such deadline. It still matters for rules-only mode.

### 2.5 `call_session_log` (diagnostics)
`id` INTEGER PK autoincrement, `session_id` TEXT, `voicemail_id` TEXT NULL, `state` TEXT, `at` INTEGER, `detail` TEXT.

It is capped at 2,000 rows (FIFO cleanup) and **contains no audio and no transcript**. Phone numbers are stored hashed with a per-install salt. The user can export it from the Troubleshooting screen.

## 3. Rule-precedence data needs

The rules are evaluated by `RuleEngine` in this order. The first match wins.
1. Voicemail service disabled, or disabled for this SIM → **ring normally**.
2. Another call is active or held → **ring normally** (call waiting is never intercepted).
3. A matching `NEVER_VOICEMAIL` rule → **ring normally**.
4. A matching `ALWAYS_VOICEMAIL` rule → **intercept** (ring time = `alwaysRuleRingSeconds`, default 0–5 s).
5. Routing mode:
   - `ALL` → intercept after `ringSeconds`.
   - `SELECTED_CONTACTS` → intercept only if the number is in a `SELECTED` rule.
   - `UNKNOWN_ONLY` → intercept only if the number is not in contacts, *or* is private or withheld. If contacts permission is denied, every caller is treated as unknown and the UI warns about this.
6. Otherwise → **ring normally**.

Number matching:
- Compare E.164 strings first.
- If either side fails to parse (short codes, odd formats), fall back to `PhoneNumberUtils.areSamePhoneNumber(a, b, simCountryIso)`.

## 4. Migrations
- `exportSchema = true`. Schemas are committed under `core/database/schemas/` and checked in CI.
- Automatic migrations (`@AutoMigration`) are used for additive changes. Manual `Migration` objects with `MigrationTestHelper` tests are used for anything else.
- **`fallbackToDestructiveMigration` is never used.** Voicemails are irreplaceable user data.

## 5. Audio file lifecycle
```
filesDir/
├── voicemails/   vm_<uuid>.wav (transient) → vm_<uuid>.m4a
├── greetings/    gr_<uuid>.wav | .m4a
└── models/       STT model files (noBackupFilesDir preferred)
```
| Event | Action |
|---|---|
| Recording starts | The row is inserted **first** (`RECORDING`) with its reserved file name, then the file is created. Any crash leaves the row discoverable. |
| Finalised | Encode to `.m4a`, verify it decodes, update the row, then delete the `.wav`. |
| User deletes | Soft delete, then (after the undo window) delete the file, then delete the row, in that order. If file deletion fails, the row is kept with `is_deleted=1` and retried. |
| Retention (auto-delete after N days) | `RetentionWorker` runs daily. Messages marked "keep" are excluded (a future column, `is_starred`). |
| Orphan sweep | On app start and weekly: files with no row are deleted, and rows with a missing file are marked `FAILED` with "audio missing". |
| Uninstall | Android removes the app-private storage. |

**Secure deletion:** On flash storage with file-based encryption, overwriting a file does not reliably erase it, because of wear-levelling. The guarantee is cryptographic. FBE keys are bound to the user, and with optional app-level encryption (§7) deleting the per-file key makes the ciphertext unrecoverable. The docs say this plainly rather than promise "shredding".

## 6. Settings (Proto DataStore), replacing `AppSettings`
```proto
message AppSettings {
  bool voicemail_enabled = 1;            // master switch (default false until setup completes)
  RoutingMode routing_mode = 2;          // ALL | SELECTED_CONTACTS | UNKNOWN_ONLY
  int32 ring_seconds = 3;                // default 20, range 5–45 (must be < network no-answer timeout, E2)
  int32 always_rule_ring_seconds = 4;    // default 0
  bool allow_message = 5;                // default true
  int32 max_recording_seconds = 6;       // default 120, range 30–300
  float greeting_volume = 7;             // 0.0–1.0, default 1.0
  bool notifications_enabled = 8;        // default true
  bool transcript_preview_enabled = 9;   // default true (in-app shade)
  bool lock_screen_preview_enabled = 10; // default false
  string transcription_language = 11;    // BCP-47 or "auto"; default "en-IN"
  bool transcription_enabled = 12;       // default true once a model is installed
  int32 auto_delete_days = 13;           // 0 = never; default 0
  RecordingQuality recording_quality = 14; // STANDARD(16k AAC 32kbps) | HIGH(16k AAC 64kbps)
  repeated SimSetting sims = 15;         // per-slot enable
  bool encrypt_recordings = 16;          // default false (MVP), see SECURITY_AND_PRIVACY
  bool onboarding_complete = 17;
}
```
Notes:
- `unknownCallerEnabled` from the brief is subsumed by `routing_mode = UNKNOWN_ONLY`.
- The default greeting selection lives in `greeting.is_active`.

## 7. Encryption and privacy
- **Baseline (always on):** app-private internal storage protected by Android file-based encryption. `android:allowBackup="false"`, plus `dataExtractionRules` that exclude everything, so recordings can't leak via cloud backup or device-to-device transfer. No `FileProvider` exposure, except temporary, permission-scoped share grants when the user explicitly shares a voicemail.
- **Optional (Phase 8):** per-file AES-256-GCM streaming encryption (Tink `StreamingAead`) with a keyset wrapped by an Android Keystore key. `androidx.security:security-crypto` is deprecated and will not be used. Transcripts in Room can be encrypted with SQLCipher, at the cost of FTS on plaintext. Trade-off: encrypting transcripts makes search much harder, so the default is FBE only, and the toggle explains what it costs.
- **Database backup:** none by default, which follows from the backup exclusion above. User-initiated **export** (ZIP of audio + JSON metadata via SAF) is the supported way to move data.

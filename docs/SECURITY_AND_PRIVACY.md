# Security and Privacy Plan

Voicemails are recordings of third parties, the callers, made on behalf of the user. They are sensitive personal data. The default posture is **local-only, minimal and explicit**.

## 1. Threat model (summary)
| Threat | Mitigation |
|---|---|
| Other apps reading recordings | Only app-private internal storage (`filesDir`) is used. Nothing goes to shared or external storage, and there are no exported providers. |
| Cloud backup or device-transfer leakage | `allowBackup=false` and `dataExtractionRules` exclude all domains. |
| Someone with physical access to an unlocked phone | Optional app lock (BiometricPrompt / device credential) on opening the inbox, planned for Phase 8. |
| Lock-screen shoulder surfing | Notifications use `VISIBILITY_PRIVATE` with a redacted public version. Transcript preview is off on the lock screen by default. |
| Accidental sharing | Sharing is explicit, one file at a time, using `FileProvider` with a temporary URI grant. |
| Telemetry or exfiltration | **No analytics and no crash-reporting SDKs** in the MVP. The `INTERNET` permission is only used to download STT models, from a pinned HTTPS URL with SHA-256 verification. |
| Speakerphone exposure during answering 🔒 | The caller's voice is audible in the room while the app takes a message. This is disclosed in onboarding and the self-test, and the in-progress screen offers "Take over" and "End". |
| Diagnostics leaking PII | The session log stores **hashed** numbers and no audio or transcripts. The user reviews the export before sharing it. |

## 2. Storage
- Audio lives in `filesDir/voicemails`, greetings in `filesDir/greetings`, and STT models in `noBackupFilesDir/models`.
- At rest, Android **file-based encryption** applies to all of these by default. Optional app-level encryption uses Tink `StreamingAead` with a Keystore-wrapped keyset (Phase 8, off by default). Details are in [DATABASE_DESIGN §7](DATABASE_DESIGN.md#7-encryption-and-privacy).
- **Secure deletion:** overwriting flash storage is unreliable because of wear-levelling. The guarantees are (a) the file is unlinked immediately once the undo window ends, and (b) with app-level encryption on, destroying the key makes the data unrecoverable. User-facing copy says "deleted from this app" and not "shredded".

## 3. Data subject controls
- Delete a single voicemail, delete all (typed confirmation), and auto-delete after N days.
- **Export:** a ZIP of `.m4a` files plus `voicemails.json` (metadata and transcripts), saved through SAF to a location the user picks.
- **Clear everything:** uninstalling, or the "Reset app" setting, which wipes the DB, files and DataStore.

## 4. Third-party processors
- **MVP:** none. TTS uses the on-device engine. STT uses the embedded offline engine.
- **Future cloud transcription or voices:** opt-in only. A per-feature consent screen names the provider, states what is sent and its retention, and allows per-message "send for transcription". Disabling it deletes any provider-side data where the provider supports deletion.
- Google TTS is a system component. Synthesising the user's *greeting text* is done offline when an offline voice is selected. The UI warns when a selected voice needs the network.

## 5. Notifications
- Channel `voicemail_new`. The title "New voicemail" is always shown. The caller name and number appear only on an unlocked device by default.
- `setPublicVersion()` carries no name, number or transcript.
- Users can turn notifications off entirely, either in the app or through the system channel.

## 6. Legal: consent and recording disclosure (India)

> **Not legal advice.** The sources below are largely secondary (law-firm and vendor articles). Have counsel review this before public release.

| Topic | Finding | Confidence |
|---|---|---|
| Participant recording | Indian courts generally treat recording by a participant as permissible (commonly cited: *R.M. Malkani v. State of Maharashtra*, 1973). Admissibility and privacy outcomes vary by case. | Secondary sources |
| Interception | Telecommunications Act 2023 (s.20) prohibits *interception* by non-authorised persons. A voicemail the caller knowingly leaves after a greeting is a message deposited with the recipient, not interception of a conversation. | Secondary; **[UNVERIFIED legal reasoning]** |
| DPDP Act 2023 + DPDP Rules (Nov 2025) | Recording is processing of personal data. Processing by an individual for *personal or domestic purposes* is understood to be outside the Act's scope **[DOC-ASSUMPTION]**. The **app publisher** never receives the data (it stays on-device), which reduces publisher-side obligations. Any future cloud feature makes the publisher (or its processor) handle personal data and brings notice, consent and purpose-limitation duties. | Secondary |
| Play policy | A prominent disclosure and privacy policy are required for audio and contacts access. Accessibility must not be used for call recording. | Secondary / policy |

**Product safeguards that follow from this:**
1. **Disclosure in the greeting.** The default greeting says "Please leave a message after the tone". The recording-enabled template offers an optional, on-by-default line: "Your message will be recorded and may be transcribed." A caller who speaks after that tone has knowingly left a message.
2. **Messages are the only thing recorded.** When the user taps **Take over call**, recording stops *before* audio is routed to the earpiece. The product never records a conversation the user takes part in.
3. No recording before the tone. The capture window starts after the beep.
4. **Retention controls** are on, with an auto-delete option offered during onboarding.

## 7. Engineering security practices
- `minifyEnabled` + R8 in release. No secrets in the repo (the `.gitignore` covers keystores).
- `exported` is explicit on every component. Only Telecom-required services and activities are exported, and they are guarded by system-only permissions such as `BIND_INCALL_SERVICE`.
- Downloaded STT models are verified with SHA-256 before loading. The JNI library is built from pinned sources.
- Dependency updates go through Renovate or Dependabot in CI.
- `StrictMode` is on in debug builds. `android:usesCleartextTraffic="false"`.

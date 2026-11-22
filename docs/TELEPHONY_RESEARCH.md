# Telephony Research Notes

This is the API-by-API reference behind [TECHNICAL_FEASIBILITY.md](TECHNICAL_FEASIBILITY.md). The evidence labels are defined in §0 of that document. Quotes are copied from the official pages as fetched on 2026-11-22.

---

## 1. Telecom framework

### 1.1 RoleManager
- `RoleManager.createRequestRoleIntent(ROLE_DIALER | ROLE_CALL_SCREENING)`. The system shows a consent dialog, and the result comes back via `onActivityResult` **[VERIFIED-DOC]**.
- If the app stops meeting the role requirements, "RoleManager will automatically remove your app from the role and close your app" **[VERIFIED-DOC]** ([dialer-app](https://developer.android.com/develop/connectivity/telecom/dialer-app)).
- G60 state **[VERIFIED-DEVICE]**: `ROLE_DIALER` → `com.google.android.dialer`. `ROLE_CALL_SCREENING` and `ROLE_CALL_REDIRECTION` have **no holder**. `ROLE_SMS` → `com.google.android.apps.messaging`.
- Reverting: the user goes to Settings → Apps → Default apps → Phone app. **Changing the default dialer is always an explicit user action.** The app never does it silently.

### 1.2 Default dialer requirements [VERIFIED-DOC]
From https://developer.android.com/develop/connectivity/telecom/dialer-app:
- "It must handle the `Intent#ACTION_DIAL` intent."
- "It must fully implement the `InCallService` API and provide both an incoming call UI, as well as an ongoing call UI."
- The InCallService needs `android:permission="android.permission.BIND_INCALL_SERVICE"` and meta-data `android.telecom.IN_CALL_SERVICE_UI=true`. It may also declare `android.telecom.IN_CALL_SERVICE_RINGING` to play its own ringtone.
- "You should NOT mark your InCallService with the attribute android:exported="false"".
- The app "should never return a null binding". A null binding makes Telecom fall back to the preloaded dialer.
- Emergency calls are always handled by the preloaded dialer.

**Implication:** a voicemail product built on Option 1 is a full phone app. That scope is reflected in roadmap Phase 4.

### 1.3 InCallService / Call
- `InCallService`: "implemented by an app that wishes to provide functionality for managing phone calls" **[VERIFIED-DOC]**.
- `BIND_INCALL_SERVICE`: "Must be required by a InCallService, to ensure that only the system can bind to it". Protection level is `signature|privileged`, so the app *requires* this permission and does not *hold* it **[VERIFIED-DOC]**.
- `Call.answer(int videoState)`: "Instructs this STATE_RINGING Call to answer" **[VERIFIED-DOC]**.
- `Call.disconnect()`: "Instructs this Call to disconnect" **[VERIFIED-DOC]**.
- `Call.playDtmfTone(char)` sends DTMF digits to the network. It cannot send arbitrary audio **[VERIFIED-DOC]**.
- `InCallService.setMuted`, `setAudioRoute` (deprecated in API 34) and `requestCallEndpointChange` (API 34+) control mute and routing only **[DOC-ASSUMPTION]**.
- `Call.STATE_AUDIO_PROCESSING`: "active with the network, but the audio from the call is being intercepted by an app on the local device". The entry methods (`enterBackgroundAudioProcessing` / `exitBackgroundAudioProcessing`) are believed to be `@SystemApi` for privileged call-screening implementations **[DOC-ASSUMPTION]**. The fetched reference also suggested newer `AUDIO_PROCESSING_USE_CASE_*` constants, including a voicemail use case, at a recent API level. **Follow-up for PR-06:** verify whether any of this is public, at which API level, and with what permission. Even if it is public, it would not help API ≤ 36 devices such as the G60 (API 31).
- `CALL_COMPANION_APP` (normal protection) lets a companion app's InCallService be bound and control calls **[VERIFIED-DOC]**. It does not change the audio restrictions.

### 1.4 CallScreeningService [VERIFIED-DOC unless noted]
- API 24+. Requires `BIND_SCREENING_SERVICE`. Role `ROLE_CALL_SCREENING`.
- Must call `respondToCall()` "within 5 seconds of being bound". The device "will not begin ringing until the response is received (or the timeout is hit)".
- `CallResponse` options: disallow, reject, silence (API 29), skip call log, skip notification **[DOC-ASSUMPTION for exact semantics]**.
- If the screening app is not the dialer, only non-contact calls are screened unless the app holds `READ_CONTACTS` **[DOC-ASSUMPTION]**.
- **It cannot answer, play audio or record.**
- **Use in this project:** optional lightweight "rules-only" mode (reject or silence by rule) for users who won't replace their dialer. Caller experience on reject is tested in E10.

### 1.5 TelecomManager
- `getDefaultDialerPackage()`, `getSystemDialerPackage()` and `isInCall()` are read-only helpers for onboarding and diagnostics.
- `acceptRingingCall()` / `endCall()` with `ANSWER_PHONE_CALLS` were deprecated in API 29 **[DOC-ASSUMPTION]**. **Not used.**
- `getCallCapablePhoneAccounts()` (needs `READ_PHONE_STATE`) maps calls to SIMs.

### 1.6 TelephonyManager: forwarding and USSD
- `setCallForwarding` / `getCallForwarding` (API 33) are system APIs needing privileged phone permissions **[DOC-ASSUMPTION]**, to re-verify in PR-06. They are not usable by this app.
- `sendUssdRequest(...)` requires `CALL_PHONE` **[DOC-ASSUMPTION]**. GSM forwarding codes are supplementary-service MMI codes rather than USSD, and carriers handle them inconsistently. The app will **only pre-fill `ACTION_DIAL`** with a code, and the user confirms by tapping call.

---

## 2. Audio

### 2.1 Capture sources [VERIFIED-DOC]
From https://developer.android.com/reference/android/media/MediaRecorder.AudioSource:
> "Capturing from VOICE_CALL source requires the CAPTURE_AUDIO_OUTPUT permission. This permission is reserved for use by system components and is not available to third-party applications."

The same sentence appears for `VOICE_DOWNLINK` and `VOICE_UPLINK`.

`CAPTURE_AUDIO_OUTPUT`: "Not for use by third-party applications" ([Manifest.permission](https://developer.android.com/reference/android/Manifest.permission#CAPTURE_AUDIO_OUTPUT)).

**Device evidence [VERIFIED-DEVICE]:** On the G60, `com.google.android.dialer` (flags `SYSTEM … UPDATED_SYSTEM_APP`) shows `android.permission.CAPTURE_AUDIO_OUTPUT: granted=true` and `MODIFY_PHONE_STATE: granted=true`. This is why Google Phone can record calls on this device and a Play app cannot.

### 2.2 Concurrent capture during a call [VERIFIED-DOC]
From https://developer.android.com/media/platform/sharing-audio-input:
- "A voice call is active if the audio mode returned by AudioManager.getMode() is MODE_IN_CALL or MODE_IN_COMMUNICATION."
- Voice call + ordinary app: "The call always receives audio." "The app can capture audio if it is an accessibility service." "The app can capture the voice call if it is a privileged (pre-installed) app with permission CAPTURE_AUDIO_OUTPUT."
- The page does **not** list the default dialer as an exception, and does not say whether a non-exempt app gets silence or an error. E4 measures this.

### 2.3 Playback capture [VERIFIED-DOC]
`AudioPlaybackCaptureConfiguration`: "the usage value MUST be USAGE_UNKNOWN or USAGE_GAME or USAGE_MEDIA. All other usages CAN NOT be captured." Voice-call audio (`USAGE_VOICE_COMMUNICATION`) is therefore excluded.

### 2.4 Playback during a call
- No API exists to mix audio into the uplink.
- The acoustic hypothesis for E3: speaker route, then `AudioTrack` with `USAGE_VOICE_COMMUNICATION` (or `USAGE_MEDIA` / `USAGE_ASSISTANT`) at high volume, picked up by the mic and sent upstream. Risks: AEC (the speaker reference may include all output), noise suppression, ducking, and routing of media output during a call. All **[UNVERIFIED] [DEVICE-SPECIFIC]**.

### 2.5 Foreground services [VERIFIED-DOC]
From https://developer.android.com/develop/background-work/services/fgs/service-types:
- API 34+ requires a declared type for every FGS.
- `phoneCall` needs `FOREGROUND_SERVICE_PHONE_CALL` and either `MANAGE_OWN_CALLS` or the `ROLE_DIALER` role.
- `microphone` needs `FOREGROUND_SERVICE_MICROPHONE` and `RECORD_AUDIO`. "You cannot create a microphone foreground service while your app is in the background."
- Neither may be started from `BOOT_COMPLETED` on newer targets.
- Open question for E5: does the bound InCallService, or the in-call activity it launches, count as foreground on a locked G60?

---

## 3. Text-to-speech
- Platform `android.speech.tts.TextToSpeech`. `getVoices()` returns `Voice` objects with locale, quality, latency and `isNetworkConnectionRequired()`. `synthesizeToFile(text, params, File, utteranceId)` renders a WAV **[DOC-ASSUMPTION]**.
- G60 **[VERIFIED-DEVICE]**: `com.google.android.tts` version `googletts.google-speech-apk_20261026.00_p0.1007071969` is installed. `settings secure tts_default_synth` is `null`, which means the system default is used, and that is Google TTS on this device.
- Offline voices for en-IN and hi-IN depend on downloaded voice data. E7 lists them.
- Cloud AI voices (neural TTS APIs) are a non-goal for the MVP: they cost money and send greeting text off-device.

## 4. Speech-to-text
- `SpeechRecognizer.createOnDeviceSpeechRecognizer()` is API 31+ **[DOC-ASSUMPTION]**. Recognising from a *file or stream* via `RecognizerIntent.EXTRA_AUDIO_SOURCE` is API 33+ **[DOC-ASSUMPTION]**. The G60 is API 31, so the platform route cannot transcribe stored voicemails there.
- The G60's recognition service is `com.google.android.tts/...GoogleTTSRecognitionService` **[VERIFIED-DEVICE]**.
- Embedded candidates (E8):

| Engine | Licence | Offline | Languages (relevant) | Size | Expected accuracy | Notes |
|---|---|---|---|---|---|---|
| whisper.cpp (ggml, quantised tiny/base/small) | MIT | ✅ | Multilingual incl. English (Indian accent), Hindi; handles code-switching partially | ~40–150 MB per model | Higher | Needs NDK/JNI. Real-time factor on SD732G must be measured. |
| Vosk (Kaldi) | Apache-2.0 | ✅ | en-IN, hi small models | ~40–50 MB | Lower | Fast, mature Android AAR, streaming. |
| Platform SpeechRecognizer | n/a | ⚠️ device-dependent | Many | 0 | Good | File input only on API 33+. Not usable on the G60 for stored audio. |
| Cloud (Google STT, Azure, AWS, Deepgram, Sarvam) | Paid | ❌ | Excellent Indian-language coverage | 0 | Highest | Post-MVP, opt-in, per-message consent. Recording leaves the device. |

## 5. Indian carriers

| Topic | Finding | Label |
|---|---|---|
| Forwarding codes | `**61*` no reply (with optional `*T` seconds), `**67*` busy, `**62*` unreachable, `**21*` all, `*#61#` query, `##61#` cancel | [CARRIER-SPECIFIC]; secondary sources |
| Jio | Offers call forwarding. Reports say some forwarding codes are disabled on government instruction and users are directed to phone settings | [UNVERIFIED] |
| Airtel | `**61*<num>*<sec>#`. Cancel `##61#` | [CARRIER-SPECIFIC], secondary |
| Consumer voicemail box | Availability on Jio, Airtel, Vi and BSNL prepaid/postpaid is unclear | [UNVERIFIED] → E9 |
| OMTP Visual Voicemail | No evidence of support by Indian operators | [UNVERIFIED] → E9 (`dumpsys carrier_config`, `vvm_type`) |
| G60 history | The device has previously registered SIMs from three Indian operators (identifiers deliberately not recorded). It is currently camped emergency-only on an Airtel cell with no SIM | [VERIFIED-DEVICE] |

## 6. Google Play policy
- **Accessibility API:** may not be used for remote call audio recording. Enforced since 2022-05-11 (secondary: [9to5Google](https://9to5google.com/2022/04/21/google-will-block-all-third-party-call-recording-apps-on-play-store-from-may-11/), [The Register](https://www.theregister.com/2022/04/22/google_banning_thirdparty_callrecording_apps/)).
- **Call Log / Phone permission group:** restricted to apps whose core function qualifies. Being the **default phone handler** is a permitted use **[DOC-ASSUMPTION]**. Re-check the current Play policy text before submission (PR-28).
- **Target API:** new apps must target a recent API level. Target SDK 36 is planned.
- **Disclosure:** any recording feature needs a prominent disclosure and a privacy policy.

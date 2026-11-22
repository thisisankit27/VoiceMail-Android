# UI / UX Specification

## 1. Design principles
1. **Honest by construction.** The UI never shows a capability as working until it is. Every telephony-dependent surface has explicit states: *Not set up*, *Unverified on this device*, *Self-test failed*, *Active*. There are no fake toggles.
2. **Calm, premium and native.** Material 3 with dynamic color (Android 12+) and a brand fallback palette. Large expressive typography for the caller name. Generous spacing. Motion is subtle and functional: shared-element transition from the inbox row to the detail, and a progress-driven waveform.
3. **Privacy visible.** On-device badges ("Transcribed on this phone"). Lock-screen preview is off by default, and the setting says what it exposes.
4. **Accessible.** TalkBack labels on all controls, minimum 48 dp targets, contrast of at least 4.5:1, dynamic font scaling up to 200%, and transcripts as a text alternative to audio.

## 2. Information architecture
```
Bottom navigation (3):  Inbox · Greeting · Settings
Dashboard = top of Inbox (summary header), not a separate tab
Dialer surfaces (🔒) live outside the bottom nav: Dial pad (launcher alias + ACTION_DIAL), Incoming call, In-call
```

## 3. Screens

### 3.1 Onboarding (first run, 5 steps)
1. **Welcome:** value proposition in a single sentence.
2. **How it works on Android (honesty screen):** three cards with status chips:
   - *"Answer missed calls for you"*: needs the app to be your **default phone app** and needs **device support**, which the self-test checks. Chip: *Requires setup*.
   - *"Transcribe on your phone"*: chip *Works offline after a one-time model download*.
   - *"Carrier voicemail"*: chip *Depends on your operator*, with a link to troubleshooting.
3. **Permissions:** requested just-in-time with a rationale: microphone (greeting recording and messages), contacts (names and rules), phone (SIM detection), notifications (API 33+).
4. **Default phone app (optional now):**
   - Explains the trade-off: Google Phone features such as spam protection and its recorder stop working while this app is default.
   - Explains how to revert.
   - Launches the system role dialog. Skipping is allowed.
5. **Greeting quick setup:** prefilled default text ("Hello! You've reached {name}…"), a voice picker and Preview. Done.

### 3.2 Dashboard (Inbox header)
- Summary cards: **Unread** (count, tappable filter), **Total**, **Storage used**.
- **Service status pill:** *Active*, *Paused*, *Not set up* or *Unsupported on this device*. Tapping it opens Telephony setup.
- **Latest voicemail** card: caller, relative time, transcript snippet and an inline play button.
- **Recent callers:** a horizontal row of avatars with a badge count.

### 3.3 Voicemail inbox
- A list grouped by day. Each row shows the avatar or initials, the name or formatted number ("Private number" for withheld callers), time, duration, a 2-line transcript snippet (or *Transcribing…* / *Transcript unavailable*), an unread dot and a SIM badge on dual-SIM devices.
- Swipe left to delete (undo snackbar), swipe right to toggle read. Long-press for multi-select.
- Search bar (FTS across transcripts, names and numbers). Filter chips: *Unread*, *Has transcript*, *Contacts*, *Unknown*, *SIM 1 / SIM 2*, and a date range.
- Empty state: an illustration plus contextual copy. If the service is not set up, it shows "Set up answering" rather than "No voicemails yet".

### 3.4 Voicemail detail
- Header: large name or number, contact chip (tap to open the contact), date and time, duration, SIM.
- Player: waveform scrubber, play/pause, ±10 s, speed (0.75×, 1×, 1.25×, 1.5×, 2×) and output route (speaker or earpiece). Playback continues with a media notification.
- Transcript: selectable text, language tag, on-device badge, *Retry transcription* on failure, and an engine and confidence footnote.
- Actions: Call back, Message (opens the SMS app via intent, no SMS permission), Add to contacts, Share audio (explicit and one-off), Mark unread, Delete.

### 3.5 Greeting editor
- Two tabs: **When messages allowed** and **When messages disabled**. Each shows the active greeting and a list of saved greetings.
- Create a greeting from:
  - **Text to speech:** multi-line text with a `{name}` token, a voice chip that opens 3.6, rate and pitch sliders, *Generate* and *Preview*.
  - **Record:** hold-to-record or tap toggle, live level meter, max 60 s, trim handles, re-record.
  - **Import:** SAF picker (audio/*). Checks duration and loudness, then normalises.
- *Restore default* is always present.
- A stale-render warning appears if the text was edited after the audio was generated.

### 3.6 Voice selection
- Voices grouped by language (English (India), Hindi, English (US)…). Each row has a sample button and badges *Offline* / *Needs download* / *Needs internet*. Network voices are listed but warned against, because a greeting must render offline.
- A link to the system TTS settings for downloading voice data.

### 3.7 Recording settings
- Max message length (30 s–5 min), ring time before answering (5–45 s, with an inline explanation that the carrier may end or forward the call earlier, and a link to the measured value from the self-test), greeting volume, recording quality, and silence auto-stop.

### 3.8 Contact rules
- Routing mode: segmented choice between **Everyone**, **Selected contacts** and **Unknown callers only**.
- Lists for **Always send to voicemail** and **Never send to voicemail**, using a multi-select contact picker. Contacts with several numbers show "All numbers", with an option to pick specific ones.
- A precedence explainer (collapsed): "Never beats Always. Always beats your routing mode."
- Contacts permission denied: an inline banner explains that all callers will be treated as unknown, with a *Grant* button.

### 3.9 Notification settings
- Master toggle, *Show transcript preview*, *Show on lock screen* (off by default, with a warning), and a shortcut to the system channel settings for sound and vibration.

### 3.10 General settings
- Master voicemail switch, per-SIM switches, transcription language and model management (download, delete, size), auto-delete period, storage usage with a *Delete all voicemails* action (typed confirmation), *Export all*, and privacy and about pages.

### 3.11 Permissions and telephony setup
- A checklist with live status: Default phone app ✓/✗, Microphone, Contacts, Phone, Notifications, Battery optimisation (informational), Self-test result.
- Carrier forwarding helper (informational): explains that the operator may forward unanswered calls *before* the app answers. It offers *Check forwarding status*, which pre-fills `*#61#` in the dial pad for the user to send, and shows the codes to cancel conditional forwarding if the user wants the app to handle missed calls. **The app never changes forwarding itself.**

### 3.12 Troubleshooting and compatibility
- **Compatibility self-test 🔒:**
  - A guided test with a second phone. The user calls themselves from another phone, the app answers, plays a test greeting, and asks the caller to speak a phrase.
  - The app measures the recorded level and plays it back. The user confirms "I could hear the greeting" and "The recording is clear".
  - Result: *Supported*, *Greeting only* or *Not supported*. Interception can only be enabled after *Supported*.
- Device info (model, Android version), role holder, last 20 session-log events, *Export diagnostics* (no audio or transcripts, hashed numbers), and an FAQ.

## 4. Dialer surfaces 🔒 (required by ROLE_DIALER)
- **Dial pad:** T9 digits, paste, long-press 0 for +, call button with SIM picker, recent calls (from CallLog) and a voicemail shortcut.
- **Incoming call:** full-screen intent on the lock screen with caller name and number, answer/decline, *Send to voicemail now*, and a countdown chip ("Answering for you in 12 s") when interception is planned.
- **In-call:** mute, speaker, keypad (DTMF), hold, add call, merge and swap (pass-through to Telecom capabilities), Bluetooth route picker, end.
- **Voicemail-in-progress screen:** shown when the app answers. It displays a live waveform, the caller, the elapsed time, a **Take over call** button (stops recording, switches to earpiece, user continues the call) and **End**.

## 5. Copy guidelines
- Never say "records your calls". Say "lets callers leave you a message".
- Status language is precise, for example "Not verified on this phone yet", rather than vague phrasing like "May not work".
- The default greeting text is the one given in the brief. The closing variant is: "Hello! You've reached {name}. I can't take your call right now, and messages aren't being accepted at the moment. Please try again later. Goodbye."

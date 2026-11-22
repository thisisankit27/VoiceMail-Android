# Product app 0.1: helper auto-start and voicemail session on the Moto G60 (2026-11-22)

Build: `app` 0.1.0 (debug), branch `feat/app-helper-autostart`. The device is the Moto G60 (Android 12, API 31). The SIM is in service, but no real call was made in this run: everything below used **loopback calls**, the app's network-free test calling account. Loopback calls have no far end, so recorded messages are silent by design (see the [shell-identity log](shell-identity-run-2026-11-22.md)). Real-call audio was already verified with the probe ([real-call log](realcall-shell-run-2026-11-22.md)).

## 1. The app's own ADB key
| Check | Result |
|---|---|
| The app connects to `127.0.0.1:5555` (legacy transport, opened with `adb tcpip 5555`) and sends CNXN, then a signed AUTH token, then its public key | adbd **parses the key** and the phone shows *"Allow USB debugging? The computer's RSA key fingerprint is …"* **[VERIFIED-DEVICE]** |
| Accepting the prompt | **Pending, 👤 owner.** Auto-tapping a security prompt is not appropriate. The prompt was left for the owner, and the port was closed again with `adb usb` |
| Token signature format (PKCS#1 v1.5 over a SHA-1 DigestInfo of the token) | Unit-tested: decrypting the signature yields `DigestInfo(SHA-1) ‖ token` **[VERIFIED-TEST]** |
| TLS client certificate (hand-built X.509 v1, SHA256withRSA) | Unit-tested: it parses, verifies and carries the app's key **[VERIFIED-TEST]** |

## 2. Switching Wireless debugging on from the app
| Check | Result |
|---|---|
| The app writes `adb_wifi_enabled=1` (holding `WRITE_SECURE_SETTINGS`, granted by the helper launch command) | Accepted. The system shows *"Allow wireless debugging on this network?"* with *Always allow on this network*, because this Wi-Fi network is not yet trusted **[VERIFIED-DEVICE]** |
| No port appears within 20 s (the prompt was not answered) | The app reports `WIRELESS_DEBUGGING_UNAVAILABLE` and switches the setting back to 0 **[VERIFIED-DEVICE]** |
| mDNS discovery, then TLS with an authorised key | **Pending**: blocked on the two owner prompts above |

## 3. The helper as a daemon
Launched with the app's own launch command (`scripts/helper-usb.sh` runs the identical command over USB):
```text
shell  <pid>  1  vm-audio-helper          # uid 2000, parent = init: detached from adb
VoiceMail: result: Running(info=HelperInfo(version=3, uid=2000, sdk=31, apkPath=/data/app/…/base.apk))
VoiceMail: id=9 type=18 OUT 'moto g(60)'   # Telephony TX visible to the helper
VoiceMail: id=19 type=18 IN  'moto g(60)'  # Telephony RX
```
- The helper refuses to start without its secret **[VERIFIED-DEVICE]**.
- On reinstall, `MY_PACKAGE_REPLACED` makes the app detect the stale helper (other APK), shut it down within 1 s, and try a fresh start over Wireless debugging. That start then waits on the network-trust prompt **[VERIFIED-DEVICE]**. A helper whose APK disappears also exits by itself within 30 s (APK watchdog; code path, not observed separately).

## 4. Voicemail session regression (loopback)
`scripts/loopback-regression.sh`, ring time 6 s:

| Scenario | Expected | Result |
|---|---|---|
| T1 Unanswered | Armed → answer at ~6 s → mic muted → greeting into Telephony TX (`routed=18`, 7.56 s) → beep → `VOICE_DOWNLINK` capture → stops when the caller hangs up | **Pass** |
| T2 Owner answers during the countdown | Session cancelled, the app does not answer | **Pass** |
| T3 Owner declines during the countdown | Session cancelled | **Pass** |
| T4 Caller hangs up while ringing | Session cancelled, no answer | **Pass** |
| T13 Messages disabled | Closing greeting, then the app hangs up, no recording | **Pass** |
| Take over during the greeting | Greeting stops at once (`played=2901ms of 7560ms … stopped=true`), mic unmuted, call stays up | **Pass** |
| Send to voicemail now | Answers immediately (< 1 s) | **Pass** |
| Helper not running | The call rings out normally and the owner gets a "Voicemail is paused" notification | **Pass** |

## Still to do on this phone
- 👤 Accept the two one-time prompts ([setup](../PERSONAL_BUILD_SETUP.md#one-time-setup--you)). Then verify the TLS start, the **reboot** start and the start after Wi-Fi loss.
- One real call through the product app (Test 1 with real audio; Test 9).

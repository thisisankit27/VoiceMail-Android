# Personal build: setup and auto-start

The app answers unanswered calls by itself, plays your greeting **into** the call and records the caller **from** the call. Normal apps can't do the audio part. A small **audio helper** does it instead. The helper runs as the phone's ADB shell user, which holds the two permissions needed (`CAPTURE_AUDIO_OUTPUT`, `MODIFY_PHONE_STATE`). No root is involved. See [Option 4](TECHNICAL_FEASIBILITY.md#option-4-default-dialer--shell-identity-audio-helper-personal-build-only-owner-decision-2026-11-22).

This document explains how the helper gets started without a PC, and the one-time steps that need you.

## How auto-start works
The app is its **own ADB client**. It holds an RSA key, like the `adbkey` on a PC. After a reboot:
1. `BOOT_COMPLETED` schedules a start. WorkManager waits for Wi-Fi.
2. The app switches on **Wireless debugging** (`adb_wifi_enabled=1`). It may do this because it holds `WRITE_SECURE_SETTINGS`, which the helper grants it.
3. It finds the Wireless debugging port over mDNS (`_adb-tls-connect._tcp`) and connects to `127.0.0.1` over TLS. It presents its key as the client certificate.
4. It runs one shell command, which starts the helper with `setsid` in its own session. The helper's parent becomes `init` (PID 1).
5. It switches Wireless debugging **off** again. The helper keeps running, because it no longer depends on the ADB connection.

The helper listens on `127.0.0.1:47231` only. Each connection must present a random secret that only the app knows. The helper exits by itself when the app is updated or uninstalled, and the app then starts the new version. A watchdog runs every 15 minutes on Wi-Fi and restarts the helper if it has died. Android forgets "always allow" debugging keys that haven't connected for 7 days (`adb_allowed_connection_time`), so every 5 days the watchdog also reconnects briefly to keep the app's key valid for the next reboot. The global timeout setting is left unchanged.

If the helper is not running when a call comes in, the phone just rings as normal (a missed call, or carrier forwarding), and you get a "Voicemail is paused" notification.

## One-time setup (👤 you)
| Step | What you do | Why | How to revert |
|---|---|---|---|
| 1 | Install the debug APK. In the app, make it the **default phone app** | Lets the app ring, answer and hang up calls | Settings → Apps → Default apps → Phone app → Phone (Google) |
| 2 | Allow **Contacts, Phone, Call logs, Notifications** | Caller names, recents, the call notification | App info → Permissions |
| 3 | On a PC: `adb tcpip 5555`, then in the app tap **Start helper**. On the phone, tick **Always allow from this computer** and tap **Allow**. Then `adb usb` on the PC | Authorises the app's own ADB key. The "computer" in the prompt is the app itself | Developer options → **Revoke USB debugging authorisations** (this also revokes your PCs) |
| 4 | The first time the app switches on Wireless debugging on your home Wi-Fi, the phone asks **"Allow wireless debugging on this network?"**. Tick **Always allow on this network** and tap **Allow** | Android only lets Wireless debugging start on networks you trust | Developer options → Wireless debugging → forget the network |

Step 3 happens once per phone. Step 4 happens once per Wi-Fi network. After both, reboots need nothing from you. Unlock the phone once after a reboot (Android delays `BOOT_COMPLETED` until the first unlock), and keep Wi-Fi on.

Developer scripts (debug builds only):
- `scripts/helper-usb.sh` starts the helper from a PC over USB, with the same command the app runs.
- `scripts/loopback-regression.sh` runs the network-free call regression.

## What callers experience
After the ring time (20 s by default) the app answers. The caller hears your greeting, a beep, and then has **30 seconds** to speak. Two short beeps mean time is up, and the call ends. Settings → *Keep what callers hear* saves the audio sent to the caller with each voicemail, for checking.

## Android 15 notes (Moto G05)
- Battery must be **Unrestricted**: Android 15's background firewall otherwise blocks the app's loopback socket to the helper. The helper launch sets it automatically, and setup step 3 shows it.
- On a slow phone the boot broadcast can arrive minutes after unlock. A call arriving before then starts the helper during the ring. See the [G05 log](device-logs/moto-g05-app-run-2026-11-23.md).

## Pausing it on purpose (Developer options off)
Some apps (banking, UPI) refuse to run while Developer options are on. You can turn Developer options **off** while you use them. When you turn them back **on**, open the app: it restarts the helper by itself (on Wi-Fi). While Developer options are off, the helper may or may not keep running. If it isn't running, calls ring out normally. The status card says *Developer options are off*.

## No Wi-Fi after a reboot
The helper can only be **started** over Wireless debugging, which needs a Wi-Fi network you have trusted. Mobile data does not count. After a reboot with no trusted Wi-Fi, calls ring out normally until you are back on one, and then it starts by itself. Ways around it when you're out:
- **A personal hotspot you own** (e.g. your second phone): join it once at home, tick *Always allow on this network*, and it becomes a trusted network you can use anywhere. Internet access isn't needed, only the Wi-Fi link.
- A PC with USB: `scripts/helper-usb.sh`.

Automatic starts try **once per Wi-Fi connection**. On someone else's Wi-Fi, Android asks *Allow wireless debugging on this network?*. **Tap Cancel.** The app then won't ask again on that connection. Tapping *Start helper* yourself always retries.

## Known limits
- Starting the helper needs **Wi-Fi** (an Android rule for Wireless debugging). Once started, it does not need Wi-Fi.
- Calls that arrive after a reboot but before the first unlock, or before Wi-Fi connects, are not answered.
- Anything that holds the app's ADB key has shell-level power on this phone. The key is stored in app-private, no-backup storage. Never install this build from an untrusted source.
- An OS update could remove the shell user's audio permissions. The G60 (Android 12) and G05 (Android 15) were checked ([G05 log](device-logs/moto-g05-check-2026-11-22.md)).

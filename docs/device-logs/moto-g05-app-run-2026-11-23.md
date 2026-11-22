# Product app on the Moto G05 (Android 15, MediaTek), 2026-11-23

This is the owner's primary phone. The earlier [read-only check](moto-g05-check-2026-11-22.md) found the shell permissions and the telephony audio paths. This run installs the app and makes it work. A SIM is in service. The owner was present and did the one-time prompts.

## Setup done over USB (owner consent)
- Installed the debug APK and granted the runtime permissions with `pm grant`.
- Made the app the default phone app (`cmd role add-role-holder`). **Revert:** Settings → Apps → Default apps → Phone app → Phone (Google).
- 👤 The owner accepted the app's ADB key ("Always allow") and trusted the home Wi-Fi for Wireless debugging. **Revert:** Developer options → Revoke USB debugging authorisations, and Wireless debugging → forget the network.

## Three things Android 15 / this phone needed (all fixed)
| # | Symptom | Cause | Fix |
|---|---|---|---|
| 1 | The app's loopback connection to the helper **timed out** (dropped, not refused) | Android 15's **background firewall chain**: a uid with no running component is `blocked=APP_BACKGROUND` (`dumpsys netpolicy`). Loopback is included | The helper launch command also runs `dumpsys deviceidle whitelist +<pkg>` (battery *Unrestricted*), which sets `allowed=POWER_SAVE_ALLOWLIST` → `effective=NONE`. New setup step *Let it run in the background* |
| 2 | Every message recording failed: `NullPointerException: Context.getDeviceId()` in the helper | Android 14+ `AudioRecord.Builder.build()` asks the Context for its (virtual) device id. The helper's minimal shell Context had no base | `ShellContext.getDeviceId()` returns `DEVICE_ID_DEFAULT` |
| 3 | After a reboot, "Finding Wireless debugging port" never finished | adbd **did** open its TLS port at once, but `Could not register mDNS service _adb-tls-connect._tcp, error (-65563)`: **mdnsd is started on demand and was not running yet**. The first attempt starts it, and a re-announcement then succeeds | Up to 4 tries of 8 s. Between tries Wireless debugging is restarted so adbd announces again |

Rejected alternative for #3: scanning 127.0.0.1 for adbd's port. Blocking connects took **13.9 s** on this phone, and non-blocking (NIO) batches took **50 s** and once missed the port. adbd's `service.adb.tls.port` property is not readable by apps here.

## Results
| Check | Result |
|---|---|
| Helper as uid 2000 on Android 15 (`sdk=35`), launched by the app's own command | **Pass** |
| Greeting + beep into the call (`routed=18`, MediaTek Telephony TX) | **Pass** (loopback) |
| `VOICE_DOWNLINK` capture (after fix 2) | **Pass**: `silenced=false` (loopback, so the audio is silent by design) |
| `scripts/loopback-regression.sh` | **23/23** |
| App key accepted over the legacy transport, then the TLS start with Wireless debugging (no PC) | **Pass** |
| Reboot → helper starts without a PC (hands-off) | **Pass**: helper running 3.4 s after the app received `BOOT_COMPLETED` |
| Helper not running when a call arrives | **Pass**: started during the ring, answered on time at 6 s (loopback) |
| Proximity screen-off at the ear (sensor `ltr569_p` present) | Built into the call screen: during a normal call, after *Take over*, and while listening in on the earpiece |

## Reboot behaviour on this phone
- `BOOT_COMPLETED` reached the app about **5 minutes after unlock**: the phone is slow and Android 15 queues the boot broadcasts. Until then, the helper is not running.
- The retry path was seen recovering from mdnsd being down right after a boot (try 1: no port; after the Wireless debugging restart, port found 2 s later). In the final hands-off reboot mdnsd was already up and try 1 succeeded.
- Mitigation: when a call arrives and the helper is not running, the app starts it **during the ring** (Wireless debugging path) and waits up to 8 s more before deciding. If it still isn't up, the call rings out normally.

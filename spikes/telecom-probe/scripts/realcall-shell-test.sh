#!/usr/bin/env bash
# Real-call test of the shell-identity audio path (E11: greeting into the call uplink,
# E12: caller recorded from the downlink). No root: the helper runs as the ADB shell user.
#
# Needs: the probe as default dialer, a SIM that is IN SERVICE, USB adb, and a second phone.
# Usage:  spikes/telecom-probe/scripts/realcall-shell-test.sh [output-dir]
# Env:    RING=10 (seconds before auto-answer)  REC=20 (seconds to record the caller)
#         MUTE=true (mute the G60 mic on answer; MUTE=false to compare what leaks into the uplink)
#         LOOPBACK=1 dry-runs the plumbing with a network-free fake call (no real audio expected)
set -euo pipefail

P=io.github.thisisankit27.voicemail.probe
OUT=${1:-./realcall-captures}   # local only, gitignored patterns (*.wav); never commit
RING=${RING:-10}
REC=${REC:-20}
B="adb shell am broadcast -n $P/.ProbeCommandReceiver --es probe_action"
APK=$(adb shell pm path "$P" | head -1 | cut -d: -f2 | tr -d '\r')
SH="adb shell CLASSPATH=$APK app_process /system/bin $P.ShellAudioMain"
TMP=/data/local/tmp/vm-realcall
mkdir -p "$OUT"

restore() {
  $B set_auto_answer --ei value 0 >/dev/null
  $B set_speaker --ez value true >/dev/null
  $B set_app_greeting --ez value true >/dev/null
  $B set_probe_capture --ez value true >/dev/null
  $B set_mute_on_answer --ez value false >/dev/null
  [ "${LOOPBACK:-0}" = 1 ] && adb shell telecom set-phone-account-disabled "$P/.LoopbackConnectionService" loopback 0 >/dev/null
  echo "probe config restored (auto-answer off)"
}
trap restore EXIT

holder=$(adb shell cmd role get-role-holders android.app.role.DIALER | tr -d '\r')
[ "$holder" = "$P" ] || { echo "ERROR: probe is not the default dialer (holder: $holder)"; exit 1; }
LOOPBACK=${LOOPBACK:-0}
if [ "$LOOPBACK" = 1 ]; then
  adb shell telecom set-phone-account-enabled "$P/.LoopbackConnectionService" loopback 0 >/dev/null
elif ! adb shell dumpsys telephony.registry | grep -q "mVoiceRegState=0(IN_SERVICE)"; then
  echo "ERROR: no SIM is IN_SERVICE; the phone cannot receive a real call"; exit 1
fi

adb shell mkdir -p "$TMP"
adb exec-out run-as "$P" cat files/greeting.wav > "$OUT/greeting.wav"
adb push "$OUT/greeting.wav" "$TMP/greeting.wav" >/dev/null

$B set_auto_answer --ei value "$RING" >/dev/null
$B set_speaker --ez value false >/dev/null      # no speakerphone: audio goes straight into the call
$B set_app_greeting --ez value false >/dev/null
$B set_probe_capture --ez value false >/dev/null
$B set_mute_on_answer --ez value "${MUTE:-true}" >/dev/null   # caller hears only the greeting, not the room

adb logcat -c
[ "$LOOPBACK" = 1 ] && $B fake_incoming --es scenario message >/dev/null
echo ">>> Call this phone now from another phone. Don't answer on the G60."
echo ">>> It auto-answers after ${RING}s. Listen for the greeting + beep, then speak for ~15 s, then hang up."
until adb logcat -d -s TelecomProbe | grep -q "E2 call ACTIVE"; do sleep 0.3; done
echo "answered"

# Self-check: record the UPLINK while the greeting plays. If the greeting appears here,
# it really was sent to the caller.
$SH capture 2 9 "$TMP/uplink-during-greeting.wav" > "$OUT/uplink.log" 2>&1 &
UPLINK=$!
$SH play "$TMP/greeting.wav" 2 | tee "$OUT/play.log"
$SH tone 600 >> "$OUT/play.log" 2>&1
wait $UPLINK || true

# The caller's message: downlink only (E12), and both directions (VOICE_CALL) for comparison.
$SH capture 4 "$REC" "$TMP/voicecall.wav" > "$OUT/voicecall.log" 2>&1 &
BOTH=$!
$SH capture 3 "$REC" "$TMP/downlink.wav" | tee "$OUT/downlink.log"
wait $BOTH || true

adb logcat -d -s TelecomProbe > "$OUT/probe-timeline.log"
adb logcat -d | grep -E "voice_extn|incall_music|AudioPolicyIntefaceImpl|PermissionCache.*2000" > "$OUT/audio-hal.log" || true
for f in uplink-during-greeting downlink voicecall; do adb pull "$TMP/$f.wav" "$OUT/" >/dev/null 2>&1 || true; done
adb shell rm -rf "$TMP"
echo "done; results in $OUT (contains real voices: keep local, delete after analysis)"
grep RESULT "$OUT"/*.log || true

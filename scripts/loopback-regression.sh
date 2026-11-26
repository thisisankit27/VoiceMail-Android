#!/usr/bin/env bash
# Loopback regression run (no SIM, no second phone): drives network-free test calls through the
# real InCallService + voicemail session + shell helper and checks the app's log for each outcome.
# Covers DEVICE_TESTING §5 Tests 1, 2, 3, 4, 13 (logic) plus take-over, "send to voicemail now" and
# "helper not running". Loopback calls have no far end, so recorded messages are always empty here.
# Needs a debug build as default phone app. Usage: scripts/loopback-regression.sh
set -uo pipefail
P=io.github.thisisankit27.voicemail
B="adb shell am broadcast -n $P/.debug.DebugCommandReceiver --es cmd"
pass=0; fail=0
log() { adb logcat -d -s VoiceMail VmHelper; }
expect() { # name, regex that must appear
  if log | grep -qE "$2"; then echo "PASS  $1"; pass=$((pass+1)); else echo "FAIL  $1 (wanted: $2)"; fail=$((fail+1)); fi
}
expect_not() {
  if log | grep -qE "$2"; then echo "FAIL  $1 (unexpected: $2)"; fail=$((fail+1)); else echo "PASS  $1"; pass=$((pass+1)); fi
}
call() { adb logcat -c; $B fake_incoming --es scenario "$1" >/dev/null; }
settle() { sleep "$1"; $B hangup >/dev/null; sleep 2; }

scripts/helper-usb.sh >/dev/null || { echo "helper did not start"; exit 1; }
$B loopback_register >/dev/null
adb shell telecom set-phone-account-enabled "$P/.debug.LoopbackConnectionService" loopback 0 >/dev/null
$B enable --ez value true >/dev/null; $B ring --ei value 6 >/dev/null; $B allow_messages --ez value true >/dev/null
sleep 1

echo "== T1 unanswered call: answer, greeting, beep, record, caller hangs up"
call silent; settle 30
expect "T1 armed" "session armed"
expect "T1 answered by app" "session: answering after"
expect "T1 greeting into Telephony TX" "greeting played=.*routed=18"
expect "T1 recording" "session: recording$"
expect "T1 stopped on hang-up" "recording stopped \(caller hung up\)"

echo "== T2 owner answers during the countdown"
call message; sleep 2; $B answer >/dev/null; settle 6
expect "T2 session cancelled by state" "left RINGING before the timer"
expect_not "T2 app did not answer" "session: answering after"

echo "== T3 owner declines during the countdown"
call message; sleep 2; $B reject >/dev/null; settle 4
expect "T3 session cancelled by state" "left RINGING before the timer"
expect_not "T3 app did not answer" "session: answering after"

echo "== T4 caller hangs up while ringing"
$B ring --ei value 12 >/dev/null; sleep 1
call hangup_ringing; settle 12
expect "T4 cancelled" "left RINGING before the timer"
expect_not "T4 app did not answer" "session: answering after"
$B ring --ei value 6 >/dev/null; sleep 1

echo "== T13 messages disabled: closing greeting, then hang up, no recording"
$B allow_messages --ez value false >/dev/null; sleep 1
call message; settle 25
expect "T13 greeting played" "greeting played="
expect "T13 app hung up" "session: disconnecting"
expect_not "T13 no recording" "session: recording"
$B allow_messages --ez value true >/dev/null; sleep 1

echo "== Take over during the greeting"
call message; sleep 10; $B take_over >/dev/null; sleep 3
expect "take-over stops greeting early" "played=.*stopped=true"
expect_not "take-over keeps the call (no app hang-up)" "session: disconnecting"
expect "call still active after take-over" "call c[0-9]+ -> ACTIVE"
settle 1

echo "== Send to voicemail now"
$B ring --ei value 30 >/dev/null; sleep 1
call silent; sleep 2; $B to_voicemail >/dev/null; sleep 3
expect "answered immediately" "session: answering after [0-9]{3,4} ms"
settle 25
$B ring --ei value 6 >/dev/null

echo "== Helper not running: let it ring, tell the owner"
$B helper_stop >/dev/null; sleep 1
call message; sleep 9
expect "helper missing detected" "audio helper not running; letting the call ring out"
expect_not "app did not answer" "session: answering after"
settle 1
scripts/helper-usb.sh >/dev/null

$B ring --ei value 20 >/dev/null
# Never leave the test calling account enabled: it would be offered for real outgoing calls.
adb shell telecom set-phone-account-disabled "$P/.debug.LoopbackConnectionService" loopback 0 >/dev/null
echo "== $pass passed, $fail failed"
[ "$fail" = 0 ]

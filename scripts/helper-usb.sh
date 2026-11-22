#!/usr/bin/env bash
# Starts the shell-identity audio helper from a PC over USB adb, with the same command the app runs
# on the phone. Useful during development, or before the app's own ADB key has been authorised.
# Needs a debuggable build (reads the helper secret with run-as). Usage: scripts/helper-usb.sh
set -euo pipefail
P=io.github.thisisankit27.voicemail
adb shell am broadcast -n "$P/.debug.DebugCommandReceiver" --es cmd init >/dev/null
APK=$(adb shell pm path "$P" | head -1 | cut -d: -f2 | tr -d '\r')
TOKEN=$(adb shell run-as "$P" cat shared_prefs/helper.xml | grep -oP '(?<=name="token">)[^<]+')
[ -n "$TOKEN" ] || { echo "no helper secret yet; open the app once"; exit 1; }
adb shell "pm grant $P android.permission.WRITE_SECURE_SETTINGS 2>&1; pkill vm-audio-helper 2>/dev/null; sleep 0.3; \
  VM_HELPER_TOKEN=$TOKEN CLASSPATH='$APK' setsid app_process /system/bin --nice-name=vm-audio-helper \
  io.github.thisisankit27.voicemail.helper.HelperMain </dev/null >/dev/null 2>&1 & sleep 0.5; echo launched"
sleep 1
adb shell ps -A -o USER,PID,PPID,NAME | grep vm-audio-helper || { echo "helper did not start; see: adb logcat -s VmHelper"; exit 1; }

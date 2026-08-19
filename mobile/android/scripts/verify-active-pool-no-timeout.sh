#!/usr/bin/env bash
set -euo pipefail

# Red-capable emulator regression loop for GitHub issue #3.
# Prerequisites: the debug APK is installed and VPN consent has already
# been granted once. The script targets the 1080x2400 Medium_Phone AVD,
# but resolves controls from accessibility bounds instead of fixed taps.

ADB_BIN="${ADB_BIN:-$(command -v adb || true)}"
if [[ -z "${ADB_BIN}" || ! -x "${ADB_BIN}" ]]; then
  echo "adb not found; set ADB_BIN to the Android platform-tools adb executable" >&2
  exit 2
fi

PACKAGE="com.opene2ee.opene2ee"
ACTIVITY="${PACKAGE}/.MainActivity"
UI_DUMP_PATH="/sdcard/opene2ee-window.xml"

dump_ui() {
  local attempt
  for attempt in 1 2 3; do
    if "${ADB_BIN}" shell uiautomator dump "${UI_DUMP_PATH}" >/dev/null 2>&1; then
      "${ADB_BIN}" shell cat "${UI_DUMP_PATH}"
      return 0
    fi
    sleep 1
  done
  return 1
}

node_with_description() {
  local description="$1"
  dump_ui |
    grep -oE '<node[^>]*>' |
    grep -F "content-desc=\"${description}" |
    head -1
}

tap_description() {
  local description="$1"
  local node bounds left top right bottom x y
  node="$(node_with_description "${description}")" || return 1
  bounds="$(sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/' <<<"${node}")"
  read -r left top right bottom <<<"${bounds}"
  x=$(((left + right) / 2))
  y=$(((top + bottom) / 2))
  "${ADB_BIN}" shell input tap "${x}" "${y}"
}

wait_and_tap() {
  local description="$1"
  local attempt
  for attempt in 1 2 3 4 5 6 7 8; do
    if tap_description "${description}"; then
      return 0
    fi
    sleep 1
  done
  echo "control not found: ${description}" >&2
  return 1
}

"${ADB_BIN}" shell input keyevent KEYCODE_WAKEUP
"${ADB_BIN}" shell wm dismiss-keyguard
"${ADB_BIN}" shell am force-stop "${PACKAGE}"
"${ADB_BIN}" shell am start -W -n "${ACTIVITY}" >/dev/null

# The current debug build presents the information screen on a cold launch.
# Treat it as optional so the loop also works after onboarding is persisted.
sleep 2
if tap_description "Anladım, Devam Et"; then
  sleep 1
fi
wait_and_tap "Aktif Nöbet&#10;Tab 2 of 3"
sleep 1

RECEIVER_NODE="$(node_with_description "Alıcı Ol")" || {
  echo "Active Pool receiver control was not found" >&2
  exit 2
}
if grep -Fq 'checked="false"' <<<"${RECEIVER_NODE}"; then
  tap_description "Alıcı Ol"
fi

# From here onward every observed marker belongs to this invocation.
"${ADB_BIN}" logcat -c
wait_and_tap "Şifreleme Doğrulamayı Başlat"

for _attempt in $(seq 1 12); do
  sleep 1
  UI="$(dump_ui)"
  if grep -Fq 'TimeoutException' <<<"${UI}"; then
    echo "FAIL: Active Pool reproduced the 10-second backend/P2P timeout" >&2
    exit 1
  fi

  if grep -Eq 'Son başarı:|TelemetryException\(unexpected status, status=[0-9]{3}\)' <<<"${UI}"; then
    LOGS="$("${ADB_BIN}" logcat -d -v brief)"
    if grep -Eq 'ProtectedTcpConnector.*tcp connected remotePort=443' <<<"${LOGS}" &&
       grep -Eq 'TcpForwarder.*tun->tcp .*remotePort=443.*length=[1-9][0-9]*' <<<"${LOGS}" &&
       grep -Eq 'TcpForwarder.*tcp->tun .*remotePort=443.*length=[1-9][0-9]*' <<<"${LOGS}"; then
      echo "PASS: Active Pool backend/P2P call returned without a network timeout"
      exit 0
    fi
  fi
done

echo "FAIL: Active Pool produced neither a response nor an explicit timeout within 12 seconds" >&2
dump_ui |
  grep -oE 'content-desc="[^"]*(Son hata|Son başarı|API çağrı)[^"]*"' >&2 || true
"${ADB_BIN}" logcat -d -v brief |
  grep -E 'TcpForwarder|ProtectedTcpConnector|TimeoutException|TelemetryException' |
  tail -120 >&2 || true
exit 1

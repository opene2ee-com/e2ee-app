#!/usr/bin/env bash
set -euo pipefail

# Runs the real TUN -> metadata ring -> MethodChannel integration test on an
# Android emulator. Flutter reinstalls the test APK, so runtime/VPN consent is
# granted automatically after installation. Physical devices are rejected.

ADB_BIN="${ADB_BIN:-$(command -v adb || true)}"
FLUTTER_BIN="${FLUTTER_BIN:-$(command -v flutter || true)}"
DEVICE_SERIAL="${ANDROID_SERIAL:-}"
INTEGRATION_TEST_PATH="${INTEGRATION_TEST_PATH:-integration_test/vpn_packet_sampling_test.dart}"
VERIFY_VPN_CLEANUP="${VERIFY_VPN_CLEANUP:-0}"

if [[ -z "${ADB_BIN}" || ! -x "${ADB_BIN}" ]]; then
  echo "adb not found; set ADB_BIN to the Android platform-tools adb executable" >&2
  exit 2
fi
if [[ -z "${FLUTTER_BIN}" || ! -x "${FLUTTER_BIN}" ]]; then
  echo "flutter not found; set FLUTTER_BIN to the Flutter executable" >&2
  exit 2
fi
if [[ -z "${DEVICE_SERIAL}" ]]; then
  DEVICE_SERIAL="$("${ADB_BIN}" devices | awk '$2 == "device" && $1 ~ /^emulator-/ { print $1; exit }')"
fi
if [[ -z "${DEVICE_SERIAL}" ]]; then
  echo "no running Android emulator found; set ANDROID_SERIAL" >&2
  exit 2
fi
if [[ "$("${ADB_BIN}" -s "${DEVICE_SERIAL}" shell getprop ro.kernel.qemu | tr -d '\r')" != "1" ]]; then
  echo "refusing to auto-grant VPN consent on a physical device" >&2
  exit 2
fi

PACKAGE="com.opene2ee.opene2ee"
UI_DUMP_PATH="/sdcard/opene2ee-permission.xml"
TEST_PID=""
VPN_ACTIVE_SEEN=0
CLEANUP_CHECKED=0
CLEANUP_FAILED=0

cleanup() {
  if [[ -n "${TEST_PID}" ]] && kill -0 "${TEST_PID}" 2>/dev/null; then
    kill "${TEST_PID}" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

verify_cleanup_state() {
  local pid service_dump notification_records error_logs
  pid="$("${ADB_BIN}" -s "${DEVICE_SERIAL}" shell pidof "${PACKAGE}" | tr -d '\r')"

  if "${ADB_BIN}" -s "${DEVICE_SERIAL}" shell cat /proc/net/dev | grep -Fq 'tun0:'; then
    echo "FAIL: tun0 still exists after the VPN network disappeared" >&2
    CLEANUP_FAILED=1
  fi
  if [[ -n "${pid}" ]] &&
     "${ADB_BIN}" -s "${DEVICE_SERIAL}" shell ps -T -p "${pid}" | grep -Fq 'vpn-tun-dis'; then
    echo "FAIL: vpn-tun-dispatcher is still running after stop" >&2
    CLEANUP_FAILED=1
  fi

  service_dump="$("${ADB_BIN}" -s "${DEVICE_SERIAL}" shell dumpsys activity services "${PACKAGE}")"
  if grep -Fq 'OpenE2eeVpnService' <<<"${service_dump}"; then
    echo "FAIL: OpenE2eeVpnService is still registered after stop" >&2
    CLEANUP_FAILED=1
  fi
  notification_records="$("${ADB_BIN}" -s "${DEVICE_SERIAL}" shell dumpsys notification --noredact |
    grep 'NotificationRecord' | grep -F "${PACKAGE}" || true)"
  if [[ -n "${notification_records}" ]]; then
    echo "FAIL: foreground VPN notification remains after stop" >&2
    CLEANUP_FAILED=1
  fi

  error_logs="$("${ADB_BIN}" -s "${DEVICE_SERIAL}" logcat -d -v brief |
    grep -Ei 'OpenE2EE.*(Too many open files|fd leak)|ForegroundServiceDidNotStartInTime|RemoteServiceException.*opene2ee|FATAL EXCEPTION.*opene2ee' || true)"
  if [[ -n "${error_logs}" ]]; then
    echo "FAIL: lifecycle error signal found in logcat" >&2
    echo "${error_logs}" >&2
    CLEANUP_FAILED=1
  fi

  if [[ "${CLEANUP_FAILED}" == "0" ]]; then
    echo "PASS: tun0, dispatcher, service, notification, and lifecycle logs are clean"
  fi
}

tap_positive_vpn_button() {
  local xml node bounds left top right bottom x y
  "${ADB_BIN}" -s "${DEVICE_SERIAL}" shell uiautomator dump "${UI_DUMP_PATH}" >/dev/null 2>&1 || return 1
  xml="$("${ADB_BIN}" -s "${DEVICE_SERIAL}" shell cat "${UI_DUMP_PATH}")"
  node="$(grep -oE '<node[^>]*>' <<<"${xml}" | grep -F 'resource-id="android:id/button1"' | head -1)" || return 1
  bounds="$(sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/' <<<"${node}")"
  read -r left top right bottom <<<"${bounds}"
  x=$(((left + right) / 2))
  y=$(((top + bottom) / 2))
  "${ADB_BIN}" -s "${DEVICE_SERIAL}" shell input tap "${x}" "${y}"
}

if [[ "${VERIFY_VPN_CLEANUP}" == "1" ]]; then
  "${ADB_BIN}" -s "${DEVICE_SERIAL}" logcat -c
fi

(
  cd "$(dirname "$0")/../.."
  "${FLUTTER_BIN}" test "${INTEGRATION_TEST_PATH}" -d "${DEVICE_SERIAL}"
) &
TEST_PID=$!

while kill -0 "${TEST_PID}" 2>/dev/null; do
  if "${ADB_BIN}" -s "${DEVICE_SERIAL}" shell pm path "${PACKAGE}" >/dev/null 2>&1; then
    "${ADB_BIN}" -s "${DEVICE_SERIAL}" shell pm grant \
      "${PACKAGE}" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
    "${ADB_BIN}" -s "${DEVICE_SERIAL}" shell appops set \
      "${PACKAGE}" ACTIVATE_VPN allow >/dev/null 2>&1 || true
    "${ADB_BIN}" -s "${DEVICE_SERIAL}" shell appops set \
      "${PACKAGE}" ESTABLISH_VPN_SERVICE allow >/dev/null 2>&1 || true
  fi

  FOCUS="$("${ADB_BIN}" -s "${DEVICE_SERIAL}" shell dumpsys window 2>/dev/null | grep 'mCurrentFocus' || true)"
  if grep -Fq 'com.android.vpndialogs' <<<"${FOCUS}"; then
    tap_positive_vpn_button || true
  fi

  if [[ "${VERIFY_VPN_CLEANUP}" == "1" && "${CLEANUP_CHECKED}" == "0" ]]; then
    CONNECTIVITY="$("${ADB_BIN}" -s "${DEVICE_SERIAL}" shell dumpsys connectivity)"
    if grep -Fq "VPN:${PACKAGE}" <<<"${CONNECTIVITY}"; then
      VPN_ACTIVE_SEEN=1
    elif [[ "${VPN_ACTIVE_SEEN}" == "1" ]]; then
      verify_cleanup_state
      CLEANUP_CHECKED=1
    fi
  fi
  sleep 0.25
done

set +e
wait "${TEST_PID}"
TEST_STATUS=$?
set -e
TEST_PID=""
if [[ "${VERIFY_VPN_CLEANUP}" == "1" ]]; then
  if [[ "${VPN_ACTIVE_SEEN}" != "1" ]]; then
    echo "FAIL: the VPN network was never observed during the integration test" >&2
    exit 1
  fi
  if [[ "${CLEANUP_CHECKED}" != "1" ]]; then
    echo "FAIL: cleanup state was not observed before the integration test ended" >&2
    exit 1
  fi
  if [[ "${CLEANUP_FAILED}" != "0" ]]; then
    exit 1
  fi
fi
exit "${TEST_STATUS}"

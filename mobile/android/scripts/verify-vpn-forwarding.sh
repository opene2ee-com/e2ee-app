#!/usr/bin/env bash
set -euo pipefail

# Reproducible emulator smoke test for GitHub issue #7.
# Prerequisites: install the debug APK, open Active Pool, and tap
# "Şifreleme Doğrulamayı Başlat" before running this script.

ADB_BIN="${ADB_BIN:-$(command -v adb || true)}"
if [[ -z "${ADB_BIN}" || ! -x "${ADB_BIN}" ]]; then
  echo "adb not found; set ADB_BIN to the Android platform-tools adb executable" >&2
  exit 2
fi

PACKAGE="com.opene2ee.opene2ee"
if ! "${ADB_BIN}" shell dumpsys connectivity | grep -q "VPN:${PACKAGE}"; then
  echo "OpenE2EE VPN is not active; start it in the app first" >&2
  exit 2
fi

# Android may reuse an already-open DoT connection or a resolver cache. Verify
# the VPN-startup DNS exchange before clearing logs for the timed HTTPS check.
STARTUP_LOGS="$("${ADB_BIN}" logcat -d -v brief)"
if ! { { grep -Eq 'TcpForwarder.*tun->tcp .*remotePort=853.*length=[1-9][0-9]*' <<<"${STARTUP_LOGS}" &&
         grep -Eq 'TcpForwarder.*tcp->tun .*remotePort=853.*length=[1-9][0-9]*' <<<"${STARTUP_LOGS}"; } ||
       { grep -Eq 'UdpForwarder.*tun->udp .*remotePort=53.*length=[1-9][0-9]*' <<<"${STARTUP_LOGS}" &&
         grep -Eq 'UdpForwarder.*udp->tun .*remotePort=53.*length=[1-9][0-9]*' <<<"${STARTUP_LOGS}"; }; }; then
  echo "FAIL: no bidirectional DNS exchange was observed since VPN startup" >&2
  exit 1
fi

"${ADB_BIN}" logcat -c
"${ADB_BIN}" shell am force-stop com.android.chrome
"${ADB_BIN}" shell am start \
  -a android.intent.action.VIEW \
  -d "https://api-test.opene2ee.com/api/v1/sessions" >/dev/null

for _attempt in $(seq 1 10); do
  LOGS="$("${ADB_BIN}" logcat -d -v brief)"

  if grep -Eq 'ProtectedTcpConnector.*tcp connected remotePort=443' <<<"${LOGS}" &&
     grep -Eq 'TcpForwarder.*tun->tcp .*remotePort=443.*length=[1-9][0-9]*' <<<"${LOGS}" &&
     grep -Eq 'TcpForwarder.*tcp->tun .*remotePort=443.*length=[1-9][0-9]*' <<<"${LOGS}"; then
    echo "PASS: DNS and fresh bidirectional HTTPS forwarding completed through the VPN within 10 seconds"
    exit 0
  fi

  sleep 1
done

echo "FAIL: fresh bidirectional HTTPS forwarding did not complete within 10 seconds" >&2
"${ADB_BIN}" logcat -d -v brief |
  grep -E 'TcpForwarder|UdpForwarder|ProtectedTcpConnector|ProtectedUdpConnector' |
  tail -200 >&2 || true
exit 1

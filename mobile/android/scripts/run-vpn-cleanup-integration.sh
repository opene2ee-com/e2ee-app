#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INTEGRATION_TEST_PATH="integration_test/vpn_lifecycle_cleanup_test.dart" \
VERIFY_VPN_CLEANUP=1 \
exec "${SCRIPT_DIR}/run-packet-sampling-integration.sh"

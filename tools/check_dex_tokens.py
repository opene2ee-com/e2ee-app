"""Sprint 12.0F+2 DEX token verification (final).

Verifies the 12.0F+2 fix works:
1. 12.0F+1 breadcrumbs (which R8 stripped) are now preserved
2. 12.0F+2 new RST function + 4 call sites are in the DEX
3. Dart AOT snapshot has the 12.0F+2 version string
4. R8 keep rules preserved Log.d/Log.w call strings

The 12.0F+1 regression test is: 12.0F+1 APK (AE734AD3) had 0
occurrences of the 3 breadcrumb Log.d calls. The 12.0F+2 fix adds
3 proguard keep rules + @Keep annotation. After the fix, all
breadcrumbs + the new RST function + 4 call sites must be in the
DEX.
"""
import os
import re
import sys

WORK = r"C:\repos\e2ee-app-pr-s12citem1\tools\dex-check-12fplus2"
DEX = os.path.join(WORK, "classes.dex")
DEXDIS = os.path.join(WORK, "dexdis.txt")
LIBAPP = os.path.join(WORK, "lib", "arm64-v8a", "libapp.so")


def extract_ascii_strings(data, min_len=6):
    strings = []
    i = 0
    while i < len(data):
        if 0x20 <= data[i] < 0x7f:
            j = i
            s = bytearray()
            while j < len(data) and 0x20 <= data[j] < 0x7f:
                s.append(data[j])
                j += 1
            if len(s) >= min_len:
                strings.append(s.decode("ascii", errors="ignore"))
            i = j
        else:
            i += 1
    return strings


def read_text(path):
    with open(path, "r", encoding="utf-8", errors="ignore") as f:
        return f.read()


def read_binary(path):
    with open(path, "rb") as f:
        return f.read()


def main():
    failures = []
    passes = 0

    # 1. Load DEX strings
    with open(DEX, "rb") as f:
        dex_data = f.read()
    dex_strings = "\n".join(extract_ascii_strings(dex_data))
    print(f"DEX size: {len(dex_data):,} bytes; strings: {dex_strings.count(chr(10)) + 1:,}")

    # 2. Load DEX disassembly
    dexdump_text = read_text(DEXDIS)
    print(f"DEX disassembly: {len(dexdump_text):,} chars")

    # 3. Load libapp.so strings (Dart AOT)
    if os.path.exists(LIBAPP):
        with open(LIBAPP, "rb") as f:
            lib_data = f.read()
        lib_strings = "\n".join(extract_ascii_strings(lib_data, min_len=4))
        print(f"libapp.so: {len(lib_data):,} bytes; strings: {lib_strings.count(chr(10)) + 1:,}")
    else:
        lib_strings = ""

    # --- 12.0F+1 REGRESSION TEST ---
    # Owner 12.0F+1 logcat (AE734AD3) showed 0 occurrences of these
    # 3 breadcrumb Log.d calls. After 12.0F+2 fix, they must be in DEX.
    print()
    print("=== 12.0F+1 REGRESSION TEST (must be in DEX after fix) ===")
    s121_breadcrumbs = [
        ("handleTcpPacket: dispatching flags=0x", 1, "12.0F+1 SYN/ACK/PSH+ACK dispatch breadcrumb"),
        ("buildVpnBuilder: allowedApps=", 1, "12.0F+1 VPN builder allowed apps breadcrumb"),
        ("checkPrivateDnsAndBindToVpn", 1, "12.0F+1 private DNS check function name"),
    ]
    for substr, min_count, desc in s121_breadcrumbs:
        count = dex_strings.count(substr)
        if count >= min_count:
            print(f"  PASS: {desc!r} (count={count})")
            passes += 1
        else:
            print(f"  FAIL: {desc!r} (count={count}, need>={min_count})")
            failures.append((desc, substr, count, min_count))

    # --- 12.0F+2 NEW FEATURES ---
    print()
    print("=== 12.0F+2 NEW FEATURES (RST workaround) ===")
    s122_checks = [
        # S122-1: writeTcpRstToTun function definition
        ("writeTcpRstToTun", 1, "writeTcpRstToTun function name in DEX string pool"),
        # S122-2/3: 4 unknown-flow RST calls in DEX disassembly
        # 1 method definition + 4 invoke-direct call sites = 5
        ("writeTcpRstToTun", 5, "writeTcpRstToTun total refs in disassembly (1 def + 4 calls)", "dexdis"),
        # S122 breadcrumb literal
        ("writeTcpRstToTun: dispatching RST", 1, "RST dispatch breadcrumb literal"),
        # Kernel-bypass recovery explanation
        ("kernel-bypass recovery", 1, "kernel-bypass recovery breadcrumb literal"),
        # R8 keep rules: TAG literal preserved
        ("OpenE2eeVpn", 1, "OpenE2eeVpn TAG literal preserved"),
        ("TcpForwarder", 1, "TcpForwarder TAG literal preserved"),
        ("UdpForwarder", 1, "UdpForwarder TAG literal preserved"),
        ("NettyChannelClient", 1, "NettyChannelClient TAG literal preserved"),
    ]
    for check in s122_checks:
        if len(check) == 4:
            substr, min_count, desc, source = check
            count = dexdump_text.count(substr) if source == "dexdis" else dex_strings.count(substr)
        else:
            substr, min_count, desc = check
            count = dex_strings.count(substr)
        if count >= min_count:
            print(f"  PASS: {desc!r} (count={count})")
            passes += 1
        else:
            print(f"  FAIL: {desc!r} (count={count}, need>={min_count})")
            failures.append((desc, substr, count, min_count))

    # --- VERSION CHECK ---
    print()
    print("=== VERSION CHECK (Dart AOT snapshot) ===")
    if "12.0F+2" in lib_strings:
        print(f"  PASS: 12.0F+2 in libapp.so (count={lib_strings.count('12.0F+2')})")
        passes += 1
    else:
        print(f"  FAIL: 12.0F+2 NOT in libapp.so")
        failures.append(("12.0F+2 version in Dart AOT", "12.0F+2", 0, 1))

    # --- SUMMARY ---
    print()
    print(f"=== SUMMARY: {passes} passed, {len(failures)} failed ===")
    if failures:
        for desc, substr, count, min_count in failures:
            print(f"  FAIL: {desc!r}: count={count}, need>={min_count}")
        sys.exit(1)
    else:
        print()
        print("ALL DEX TOKEN CHECKS PASS - 12.0F+2 APK is ready for Owner install")
        sys.exit(0)


if __name__ == "__main__":
    main()

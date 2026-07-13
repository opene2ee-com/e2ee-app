"""Sprint 12.0F+4 DEX token verification (final).

Verifies the 12.0F+4 fix works:
1. 12.0F+1/12.0F+2/12.0F+3 breadcrumbs preserved
2. 12.0F+4 call-chain debug breadcrumbs in DEX:
   - onMethodCall: received method=' literal
   - attachFlutterEngine: ENTER, prev literal
   - MainActivity: configureFlutterEngine: ENTER literal
   - MainActivity: MethodChannel handler: received method=' literal
3. Dart AOT snapshot has 12.0F+4 version (debug APK only -
   release Dart AOT does NOT include the debug print() calls)
"""
import os
import re
import sys

WORK = r"C:\repos\e2ee-app-pr-s12citem1\tools\dex-check-12fplus4"
WORK_DEBUG = os.path.join(WORK, "debug")
WORK_RELEASE = os.path.join(WORK, "release")


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


def verify_apk(apk_path, work_dir, label):
    print(f"\n=== {label} ({apk_path}) ===")
    if not os.path.exists(apk_path):
        print(f"  FAIL: APK not found at {apk_path}")
        return 0, 1
    # Extract
    import shutil
    if os.path.exists(work_dir):
        shutil.rmtree(work_dir)
    os.makedirs(work_dir, exist_ok=True)
    import zipfile
    with zipfile.ZipFile(apk_path, "r") as z:
        z.extractall(work_dir)
    # Find ALL DEX files (debug APK has multidex).
    dex_files = sorted(
        os.path.join(work_dir, f)
        for f in os.listdir(work_dir)
        if f.startswith("classes") and f.endswith(".dex")
    )
    print(f"  Found {len(dex_files)} DEX files")
    if not dex_files:
        print(f"  FAIL: no DEX files extracted")
        return 0, 1
    libapp = os.path.join(work_dir, "lib", "arm64-v8a", "libapp.so")
    # Combine strings from ALL DEX files.
    all_dex_strings = []
    for dex in dex_files:
        with open(dex, "rb") as f:
            all_dex_strings.extend(extract_ascii_strings(f.read()))
    dex_strings = "\n".join(all_dex_strings)
    lib_strings = ""
    if os.path.exists(libapp):
        with open(libapp, "rb") as f:
            lib_data = f.read()
        lib_strings = "\n".join(extract_ascii_strings(lib_data, min_len=4))
    passes = 0
    failures = []
    # --- 12.0F+1/2/3 REGRESSION ---
    print("--- 12.0F+1/2/3 REGRESSION ---")
    for substr, min_count, desc in [
        ("handleTcpPacket: dispatching flags=0x", 1, "12.0F+1 breadcrumb"),
        ("writeTcpRstToTun: dispatching RST", 1, "12.0F+2 breadcrumb"),
        ("vpnRoutingState: ip route", 1, "12.0F+3 breadcrumb"),
        ("OpenE2eeVpn", 1, "OpenE2eeVpn TAG"),
        ("MainActivity", 1, "MainActivity TAG"),
    ]:
        count = dex_strings.count(substr)
        if count >= min_count:
            print(f"  PASS: {desc!r} (count={count})")
            passes += 1
        else:
            print(f"  FAIL: {desc!r} (count={count}, need>={min_count})")
            failures.append((desc, substr, count, min_count))
    # --- 12.0F+4 NEW FEATURES ---
    print("--- 12.0F+4 NEW FEATURES (call-chain debug) ---")
    s124_checks = [
        # S124-1: onMethodCall: received method='
        ("onMethodCall: received method=", 1, "onMethodCall entry log literal"),
        # S124-2: attachFlutterEngine: ENTER, prev
        ("attachFlutterEngine: ENTER, prev", 1, "attachFlutterEngine entry log literal"),
        # onMethodCall: 'start' branch ENTERED
        ("onMethodCall: 'start' branch ENTERED", 1, "onMethodCall start branch log"),
        # onMethodCall: 'stop' branch ENTERED
        ("onMethodCall: 'stop' branch ENTERED", 1, "onMethodCall stop branch log"),
        # onMethodCall: unknown method= warning
        ("onMethodCall: unknown method=", 1, "onMethodCall unknown method log"),
        # S124-4: configureFlutterEngine: ENTER
        ("configureFlutterEngine: ENTER", 1, "MainActivity configureFlutterEngine entry log"),
        # configureFlutterEngine: DONE
        ("configureFlutterEngine: DONE", 1, "MainActivity configureFlutterEngine done log"),
        # MethodChannel handler: received method='
        ("MethodChannel handler: received method=", 1, "MainActivity MethodChannel handler log"),
    ]
    for substr, min_count, desc in s124_checks:
        count = dex_strings.count(substr)
        if count >= min_count:
            print(f"  PASS: {desc!r} (count={count})")
            passes += 1
        else:
            print(f"  FAIL: {desc!r} (count={count}, need>={min_count})")
            failures.append((desc, substr, count, min_count))
    # Version check
    print("--- VERSION CHECK (Dart AOT) ---")
    if "12.0F+4" in lib_strings:
        print(f"  PASS: 12.0F+4 in libapp.so (count={lib_strings.count('12.0F+4')})")
        passes += 1
    else:
        print(f"  FAIL: 12.0F+4 NOT in libapp.so")
        failures.append(("12.0F+4 in Dart AOT", "12.0F+4", 0, 1))
    print(f"--- {label} SUMMARY: {passes} passed, {len(failures)} failed ---")
    return passes, len(failures), failures


def run_dexdump(dex, out):
    import subprocess
    res = subprocess.run(
        [r"C:\Android\build-tools\36.0.0\dexdump.exe", "-d", dex],
        capture_output=True, timeout=180
    )
    # Write bytes to avoid UTF-8 decode errors on DEX output
    with open(out, "wb") as f:
        f.write(res.stdout)
    # Return as latin-1-decoded text (lossy but OK for substring matching)
    return res.stdout.decode("latin-1", errors="ignore")


def main():
    debug_apk = r"C:\repos\e2ee-app-pr-s12citem1\mobile\build\app\outputs\flutter-apk\app-debug.apk"
    release_apk = r"C:\repos\e2ee-app-pr-s12citem1\mobile\build\app\outputs\flutter-apk\app-release.apk"
    p1, f1, fs1 = verify_apk(debug_apk, WORK_DEBUG, "DEBUG")
    p2, f2, fs2 = verify_apk(release_apk, WORK_RELEASE, "RELEASE")
    print()
    print("=" * 60)
    print(f"TOTAL: debug {p1} pass / {f1} fail; release {p2} pass / {f2} fail")
    if f1 + f2 == 0:
        print("ALL CHECKS PASS")
        sys.exit(0)
    else:
        if fs1:
            print("\nDEBUG failures:")
            for desc, substr, count, mn in fs1:
                print(f"  {desc!r}: {substr!r} count={count} need>={mn}")
        if fs2:
            print("\nRELEASE failures:")
            for desc, substr, count, mn in fs2:
                print(f"  {desc!r}: {substr!r} count={count} need>={mn}")
        sys.exit(1)


if __name__ == "__main__":
    main()

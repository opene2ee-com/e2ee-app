# CI Debug Failures — Runbook for Loud-Failure Gates

| Field      | Value                                                  |
|------------|--------------------------------------------------------|
| Status     | Accepted (Sprint 7 / Item 11 — MOB-4)                  |
| Date       | 2026-07-07                                             |
| Owner      | Coder (Sprint 7 PR-S7-MOB-4)                           |
| Sprint     | 7                                                      |
| Branch     | feat/pr-s7-mob4-keyprops-warning                       |
| Hand-off   | cyber-security review of mobile release-readiness      |

This document is the operator-facing runbook for **loud CI failure gates**
that the project deliberately adds to the GitHub Actions workflows. It is
**not** a substitute for the workflow YAML — see
[`.github/workflows/android-release.yml`](../.github/workflows/android-release.yml)
for the authoritative gate definitions. This file answers the only
question the YAML can't: **"how do I fix it?"**.

Each entry below follows the same shape:

- **Symptom** — what the failing CI log looks like.
- **Why it's a gate** — the security / correctness invariant the gate
  protects, with a link to the relevant code or ADR.
- **How to fix** — the operator actions to make the gate green, with
  copy-paste commands where useful.
- **Cross-references** — the YAML step + the source file the gate reads.

---

## Table of contents

1. [key.properties missing (Android release build)](#keyproperties-missing-android-release-build)
2. (Future entries — see "Adding a new entry" at the bottom.)

---

## key.properties missing (Android release build)

### Symptom

The `android-release-build` job in
`.github/workflows/android-release.yml` fails on the first run step with:

```
::error::mobile/android/key.properties missing — required for release builds. See docs/CI-DEBUG-FAILURES.md §key.properties for setup (keytool + key.properties format + secret-store wiring).
::error::Without key.properties, app/build.gradle.kts falls back to the AGP debug keystore (silently signs APKs with the publicly-known debug key — release-readiness blocker).
```

The job exits with code `1` immediately; JDK / Flutter / Android SDK
setup never runs.

### Why it's a gate

`mobile/android/app/build.gradle.kts` lines 87–111 contain this logic:

```kotlin
signingConfigs {
    create("release") {
        val keyPropsFile = rootProject.file("key.properties")
        if (keyPropsFile.exists()) {
            // load storeFile / storePassword / keyAlias / keyPassword
        }
    }
}

buildTypes {
    getByName("release") {
        signingConfig = if (rootProject.file("key.properties").exists()) {
            signingConfigs.getByName("release")
        } else {
            signingConfigs.getByName("debug")
        }
        // ...
    }
}
```

If `key.properties` is **absent**, the release build still "succeeds"
— Gradle's "BUILD SUCCESSFUL" banner fires — but the resulting APK is
silently signed with the AGP-generated debug keystore at
`~/.android/debug.keystore`. That keystore is:

- **Publicly known**: the Android SDK ships a single debug keystore;
  every developer machine on Earth has the same key material. Anyone
  can re-sign a forged APK with it.
- **Trivially detectable**: `apksigner verify --print-certs` on a
  debug-signed APK reports `CN=Android Debug,O=Android,C=US` — Play
  Store rejects uploads with this signature, and any internal
  distribution of a "release" APK with this signature is a real-world
  supply-chain compromise.
- **No reviewable signal**: the CI log shows green checks for
  `mobile-analyze`, `mobile-test`, `mobile-build-web`, and the
  release-build step would show "BUILD SUCCESSFUL". Only an attentive
  reviewer who manually inspects `apksigner verify` output would catch
  the bug.

The gate in `.github/workflows/android-release.yml` makes this
silent-fail mode impossible: the workflow exits non-zero **before** any
build starts, with a `::error::` annotation that links back to this
runbook. Reviewers see a red marker on the workflow summary on the PR
page; there is no path to a green CI with a debug-signed APK.

### How to fix

The fix is project-local and one-time per dev machine / CI runner.
Three steps:

#### 1. Generate a release keystore (one-time per project)

`keytool` (shipped with every JDK 17 install) generates the JKS
keystore. **Keep this file out of the repo** — store it in the team's
secret manager (1Password / Vault / AWS Secrets Manager / etc.) and
make it available to CI runners via the secret-store integration.

```bash
# Generates a 25-year RSA-2048 keystore at ~/keys/opene2ee-release.jks
# CN=OpenE2EE Android Release — adjust OU/O fields to your org.
keytool -genkey -v \
  -keystore ~/keys/opene2ee-release.jks \
  -alias opene2ee \
  -keyalg RSA -keysize 2048 \
  -validity 9125 \
  -storepass 'STORE_PASSWORD_HERE' \
  -keypass 'KEY_PASSWORD_HERE' \
  -dname "CN=OpenE2EE Android Release, OU=Mobile, O=OpenE2EE, L=Istanbul, S=Istanbul, C=TR"
```

Verify the alias and certificate:

```bash
keytool -list -v -keystore ~/keys/opene2ee-release.jks
# Expect: "Your keystore contains 1 entry" + alias "opene2ee" + a
# SHA-256 fingerprint line that matches what your release-tracking
# spreadsheet (or 1Password entry) records.
```

#### 2. Create `mobile/android/key.properties` (one-time per machine/runner)

The file is gitignored (see `.gitignore` line 123:
`**/android/key.properties`), so each environment creates its own copy.

```bash
cd mobile/android
cat > key.properties <<'EOF'
# OpenE2EE — Android release signing config
# Gitignored: NEVER commit. Regenerate per dev machine / CI runner.
# See docs/CI-DEBUG-FAILURES.md §key.properties for the full runbook.
storeFile=/absolute/path/to/opene2ee-release.jks
storePassword=STORE_PASSWORD_HERE
keyAlias=opene2ee
keyPassword=KEY_PASSWORD_HERE
EOF
chmod 600 key.properties
```

Field meanings (consumed by `app/build.gradle.kts` lines 90–96):

| Field           | Notes                                                            |
|-----------------|------------------------------------------------------------------|
| `storeFile`     | Absolute path to the `.jks` file. Relative paths resolve against the rootProject (= `mobile/android/`). |
| `storePassword` | The keystore password from step 1.                              |
| `keyAlias`      | The alias used in `keytool -genkey -alias` (here `opene2ee`).   |
| `keyPassword`   | The key password. Can equal `storePassword` for single-password keystores. |

#### 3. For CI runners: provision via secret-store

The CI gate fires BEFORE toolchain setup, so adding `key.properties`
to the runner is the only fix. Two common patterns:

- **GitHub Actions secret + materialize step**: store the four values
  as repository / org-level secrets (`ANDROID_KEY_STORE_BASE64`,
  `ANDROID_KEY_STORE_PASSWORD`, etc.), then prepend a `actions/checkout`
  follow-up step that writes the JKS to disk + writes `key.properties`
  from the secrets. **Do not** commit the JKS to the repo.
- **Vault / 1Password CLI**: pull the file via `vault kv get -field=key.properties
  secret/openE2EE/android` and write it to `mobile/android/key.properties`
  in the runner before the gate step.

For local dev boxes, step 2 is sufficient — the dev builds in
`ci.yml` (`mobile-analyze`, `mobile-test`, `mobile-build-web`) do NOT
require `key.properties` because they target the debug variant, which
uses the AGP-generated debug keystore automatically.

### Verifying the fix

Run the release build locally:

```bash
cd mobile/android
./gradlew :app:assembleRelease
# Expect: "BUILD SUCCESSFUL" + APK at app/build/outputs/apk/release/app-release.apk

# Confirm the APK is signed with YOUR keystore (not the debug key):
$ANDROID_HOME/build-tools/34.0.0/apksigner verify --print-certs \
  app/build/outputs/apk/release/app-release.apk
# Expect: certificate CN matches the -dname from step 1
# (e.g. "CN=OpenE2EE Android Release, OU=Mobile, O=OpenE2EE").
# NOT "CN=Android Debug, O=Android, C=US".
```

Re-running the GitHub Actions workflow on the same PR should now turn
green on the `android-release-build` job and upload the signed APK
to the workflow run's artifacts.

### Cross-references

- Gate YAML: `.github/workflows/android-release.yml` §"Verify
  key.properties is present (MOB-4 gate)".
- Consumed by: `mobile/android/app/build.gradle.kts` lines 87–111.
- Gitignore rule: `.gitignore` line 123 (`**/android/key.properties`).
- Comment in source: `mobile/android/app/build.gradle.kts` lines 80–86
  (the deliberate "do NOT fall back to the debug keystore for release
  builds" intent — kept in the source even though the
  `if (keyPropsFile.exists())` fallback is preserved as a local-dev
  ergonomic).
- Related (iOS): `docs/SETUP-iOS.md` §2.2 documents the parallel
  "DEVELOPMENT_TEAM via xcconfig + entitlements team-identifier"
  pattern that PR-S7-MOB-6 (Sprint 7) shipped for iOS. The Android
  equivalent — `key.properties` + a real keystore — is what this
  runbook documents.

---

## Adding a new entry

When you add a new loud CI gate (a `::error::` + `exit 1` pre-step
that catches a missing-but-required configuration), add a section here
following the same shape: **Symptom / Why it's a gate / How to fix /
Verifying the fix / Cross-references**. Keep field names consistent
across entries so the table of contents renders uniformly.

Loud gates that belong here share three properties:

1. They fire BEFORE expensive setup (JDK / Flutter / SDK provisioning)
   so the failure mode is fast and obvious.
2. They cite a specific source-file line range that the fix must
   satisfy.
3. They have a copy-paste-able fix path (commands, not "see internal
   doc X").
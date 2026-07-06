# ADR-0008 — Multiplatform Tooling (Windows / macOS / Linux)

| Field      | Value                                  |
|------------|----------------------------------------|
| **Status** | Accepted (Sprint 2) — Stub             |
| **Date**   | 2026-07-06                             |
| **Owner**  | Architect (mvs_25a7a987f73243899e35a1485c6ba224) |
| **Source** | This ADR is a stub extracted from [`docs/ARCHITECTURE_DECISIONS.md`](ARCHITECTURE_DECISIONS.md) §1 ("Frontend") and §2 ("Backend"). The full multiplatform implementation lives in [`docs/MULTIPLATFORM.md`](MULTIPLATFORM.md) (contributor guide) and [`docs/NATIVE-DEV-SETUP.md`](NATIVE-DEV-SETUP.md) (Android Studio + Xcode dev tooling). Future contributors should expand this ADR with the per-OS trade-off matrix, the GitHub Actions multi-OS runner plan, and the toolchain pin rationale. |

> **Scope notice.** This is a Sprint 4 PR-26 placeholder created to resolve
> the broken `docs/ADR-0008-multiplatform-tooling.md` cross-references
> flagged by the Sprint 3 Integration Gate. The stub captures the
> architectural *Context*, *Decision* and *Consequences* already
> documented in `docs/ARCHITECTURE_DECISIONS.md` §1 (Flutter for
> front-end + dashboard) and §2 (Go for backend). It is **not** a
> substitute for the full ADR-0008 — the per-OS shell selection (Git Bash
> vs WSL vs PowerShell-7), the `.editorconfig`/`.gitattributes` rationale,
> and the cross-platform `make`/`scripts/*.{sh,ps1}` matrix remain to be
> authored in a follow-up.

---

## Context

OpenE2EE is a monorepo with three runtimes (Go backend, Flutter mobile,
Flutter web dashboard) and three primary contributor platforms
(Windows, macOS, Linux). The naive approach — "let every dev install
their preferred shell and toolchain" — produced platform drift (CRLF/LF
noise, `cmd.exe` users without `make`, `.DS_Store`/Thumbs.db pollution)
during Sprint 1. We need an explicit *normalisation layer* that lets:

* PR diffs stay clean regardless of the contributor's OS.
* The same `make setup`, `make test`, `make lint`, `make build` commands
  work on Windows, macOS, and Linux.
* A single CI runner (Linux) can validate the work of contributors on
  every OS.

The architectural context is `docs/ARCHITECTURE_DECISIONS.md` §1
(Flutter chosen precisely because it serves mobile + web from a single
codebase) and §2 (Go chosen for the packet-analysis backend). The
practical implementation is `docs/MULTIPLATFORM.md`.

## Decision

### Framework pinning

* **Flutter** (≥ 3.24, Dart ≥ 3.5) is the **single frontend codebase**
  for mobile (Android + iOS) and for the web dashboard. Rationale (§1
  of ARCHITECTURE_DECISIONS.md): native performance on both mobile
  platforms, web compile for the dashboard, one team and one codebase.
* **Go** (≥ 1.26) is the **backend language**. The `gopacket` library
  is the packet-analysis primitive (§2 of ARCHITECTURE_DECISIONS.md).

### Platform normalisation layer

The repo ships a small set of files that every contributor / CI runner
shares:

* **`.editorconfig`** — Go uses tabs, everything else 2 spaces, UTF-8 LF.
* **`.gitattributes`** — text files forced LF, binary files diff-skipped,
  `.ps1` / `.bat` files preserved CRLF for shell semantics.
* **`.gitignore`** — OS / IDE / build artifacts (`.DS_Store`, `Thumbs.db`,
  `build/`, `.idea/workspace.xml`, `Pods/`, `Podfile.lock`).
* **`scripts/*.sh` + `scripts/*.ps1` + `Makefile`** — same logic, two
  shell flavours. CI is Linux-bash; Windows contributors use Git Bash or
  WSL; PowerShell-7 is the documented native fallback for `cmd.exe`
  users.

### Per-OS entry points

| OS       | Recommended shell | Setup                              |
|----------|-------------------|------------------------------------|
| Windows  | Git Bash (or WSL) | `make setup`                       |
| macOS    | native bash / zsh | `make setup` (or `brew install`)   |
| Linux    | native bash       | `make setup`                       |

Linux is the **canonical CI runner** (`ubuntu-latest`); every PR must
pass on Linux before merge.

### Toolchain pins

* `core.autocrlf false` is a **required** global git setting on Windows
  to prevent CRLF drift.
* PowerShell-7 (`winget install Microsoft.PowerShell`) is the native
  fallback for Windows users who do not want to install Git Bash.
* CocoaPods (`sudo gem install cocoapods` or `brew install cocoapods`)
  is required only for iOS release builds.
* Flutter SDK path is resolved by `flutter doctor -v`; `local.properties`
  is auto-populated.

### Flutter multiplatform surface

* **Mobile targets** — Android (API 21+, target API 34) and iOS
  (macOS-only build host). Per-app details in
  [`docs/NATIVE-DEV-SETUP.md`](NATIVE-DEV-SETUP.md).
* **Web target** — Flutter Web compiles the same Dart codebase into the
  dashboard. CI validates with `flutter build web`.
* **Desktop targets** — explicitly **out of scope** for MVP. No
  `flutter create --platforms=macos,windows,linux` is run.

## Consequences

### Positive
* A PR opened on Windows diffs identically on macOS and Linux — no
  CRLF/LF churn, no `.DS_Store`, no platform-conditional build files.
* One CI runner (Linux) validates the work of every contributor.
* Single Flutter codebase → mobile + web stay in sync (e.g.
  `telemetry_formatter.dart`, `device_identity.dart`).
* PR review is faster — reviewers don't need to context-switch between
  shells.

### Negative / Trade-offs
* Windows native contributors without Git Bash or WSL must install
  PowerShell-7 (`winget install Microsoft.PowerShell`) — one-time setup
  cost.
* iOS-only tooling (Xcode, CocoaPods) cannot be exercised on Windows or
  Linux; CI for iOS is deferred to Sprint 4 (`macos-latest` runner).
* The `scripts/*.{sh,ps1}` parallel pair means every shell-script change
  has to be applied in both files. Reviewers must check both.
* Android Studio + Xcode (full IDE dev tooling) are documented in
  [`docs/NATIVE-DEV-SETUP.md`](NATIVE-DEV-SETUP.md); they are *not* the
  default for PRs (CI is), but they are the recommended IDE for native
  Kotlin / Swift work.

### Follow-ups
* Author the full ADR-0008 with the per-OS trade-off matrix, the
  `.editorconfig`/`.gitattributes` rationale, and the GitHub Actions
  multi-OS runner plan (Sprint 4 PR-MP-CI).
* Migrate `scripts/*.sh` → bash-strict-mode (`set -euo pipefail`) and
  mirror the same flags in `scripts/*.ps1`.
* Adopt `bun` / `pnpm` / `task` only if the existing `make` + `*.{sh,ps1}`
  surface proves insufficient — boring solution preferred.

---

## Per-OS Matrix (Sprint 3 §5)

Sprint 3 PR-MP-CI shipped a GitHub Actions multi-OS matrix. The matrix
is the authoritative source for "what runs on which OS today". The
table below mirrors the matrix in `.github/workflows/ci.yml` and adds
two rows (iOS Xcode, Android Gradle) that are not yet CI jobs but
express the platform-runtime expectation for native builds.

Legend: ✓ = runs in CI / supported on this OS, ✗ = not run / not
supported, ✓✓ = primary canonical CI runner.

| Feature                      | Ubuntu (linux)        | macOS                  | Windows                | Notes                                                                                                                  |
|------------------------------|-----------------------|------------------------|------------------------|------------------------------------------------------------------------------------------------------------------------|
| Go build (`go build`)        | ✓✓                    | ✓                      | ✓                      | Cross-OS matrix leg `go-build-test` (ubuntu + macos + windows)                                                        |
| Go test (`go test`)          | ✓✓                    | ✓                      | ✓                      | Same matrix leg; services: `timescale/timescaledb-ha:pg16` + `redis:7-alpine`                                          |
| Flutter analyze              | ✓✓                    | ✓                      | ✓                      | Matrix leg `flutter-analyze-test` (3 OSes); `--fatal-infos`                                                            |
| Flutter test                 | ✓✓                    | ✓                      | ✓                      | Same matrix leg                                                                                                        |
| Docker compose config        | ✓                     | ✓                      | ✗                      | Matrix leg `docker-compose-config` (Linux + macOS only); Windows skipped — bash anchors + POSIX secret paths in `infra/docker-compose.yml` |
| Privacy check (KVKK DELETE)  | ✓✓                    | ✓                      | ✓                      | Matrix leg `privacy-check` (3 OSes); grep + KVKK DELETE smoke test                                                     |
| Race detection (`-race`)     | ✓                     | ✗                      | ✗                      | `backend-test` job (`ubuntu-latest` only); Sprint 5 PR-31 added it (separate from the cross-OS matrix)                |
| iOS Xcode build              | ✗                     | ✓                      | ✗                      | Xcode + CocoaPods required; macOS-only — Windows + Linux runners not provisioned                                       |
| Android Gradle build         | ✓                     | ✗                      | ✗                      | Gradle wrapper runs on Linux only (no macOS/Windows runner provisioned for `gradlew assembleDebug`)                    |

### Runner rationale

* `ubuntu-latest` is the **canonical CI runner** — every PR must pass
  on Linux before merge.
* macOS + Windows are kept on the matrix for early cross-OS regression
  catching; both are Linux-equivalent for `go test` / `flutter test`.
* `-race` is Linux-only: the matrix's job is to confirm the suite
  PASSES on all 3 runners with the same source; race detection would
  multiply runner-minutes without proportional signal (Sprint 5
  PR-31).
* Docker compose validation is intentionally Linux + macOS —
  `infra/docker-compose.yml` uses bash anchors (`<<: *default-restart`)
  and POSIX secret file paths (`../.secrets/...`) that don't translate
  to Windows bash.

### Sprint 3 PR-MP-CI deliverable

* `.github/workflows/ci.yml` multi-OS matrix with 11 legs (3+3+2+3).
* This matrix is the canonical source for the ✓/✗ cells above.
* Sprint 5 PR-31 added the dedicated `backend-test` (race + cover) job
  on Linux (not in the original 11-leg matrix).

### Follow-ups

* Provision a `macos-latest` runner for iOS Xcode build (Sprint 6+).
* Decide whether Windows Android build is worth the runner-minute
  cost (low value — Flutter mobile targets Linux CI already).
* Reconsider `-race` parity on macOS once cgo / clang parity is
  verified (Sprint 5 PR-31 memory entry).

---

**Cross-references.**
* Source: [`docs/ARCHITECTURE_DECISIONS.md`](ARCHITECTURE_DECISIONS.md) §1, §2
* Contributor guide: [`docs/MULTIPLATFORM.md`](MULTIPLATFORM.md)
* Native dev tooling: [`docs/NATIVE-DEV-SETUP.md`](NATIVE-DEV-SETUP.md)
* Sprint 3 plan template: [`docs/SPRINT-3-PLAN-TEMPLATE.md`](SPRINT-3-PLAN-TEMPLATE.md)
* CI workflow: [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml)
// mobile/android/build.gradle.kts
//
// PR-28 (Sprint 5) — root project build script.
// Sprint 9.6.4 hotfix: AGP 8.1.4 → 8.6.1 (Flutter SDK 3.44.1 minimum).
//
// Minimal — Flutter's Android module is fully configured in
// `app/build.gradle.kts`. We only need to declare the plugin versions
// in one place (so `subprojects { ... }` can re-use them) and pin the
// repositories used for transitive resolution.

plugins {
    // Applied in subprojects via the `id("...")` shorthand in their own
    // `plugins {}` block. Versions are declared HERE so the AGP /
    // Kotlin / Flutter plugin loaders agree.
    //
    // Sprint 9.6.4 hotfix rationale (live build test 2026-07-08 15:59):
    //   Sprint 9.6.3 successfully resolved `flutter_tools/gradle`
    //   (PATH-based `which flutter`) and bumped Gradle 8.5 → 8.10 LTS,
    //   but the live workflow_dispatch run AFTER Sprint 9.6.3 cherry-pick
    //   failed at `mobile/android/app/build.gradle.kts:80` with:
    //
    //     Error: Your project's Android Gradle Plugin version (8.1.4)
    //            is lower than Flutter's minimum supported version of
    //            Android Gradle Plugin version 8.6.0. Please upgrade
    //            your Android Gradle Plugin version.
    //
    //   Flutter SDK 3.44.1 (project-wide pin via env.FLUTTER_VERSION in
    //   all 4 workflows) requires AGP >= 8.6.0. We picked 8.6.1
    //   (latest 8.6.x patch) for stability — AGP 8.6.x is the
    //   "stable LTS" line Flutter 3.44.1 ships against by default;
    //   8.7+ requires Gradle 8.9+ (we're on 8.10/8.14) and adds new
    //   APIs not yet exercised by the Flutter plugin loader.
    //
    //   See `tools/workflow-yaml-audit.py` check_agp_version() invariant.
    id("com.android.application") version "8.6.1" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("dev.flutter.flutter-gradle-plugin") version "1.0.0" apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

// Sub-projects inherit the JVM target (AGP requires Java 17 for 8.1+).
subprojects {
    afterEvaluate {
        // No-op stub — populated if/when we add Kotlin-only modules.
    }
}
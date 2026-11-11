plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    // OWASP dependency-check (guideline 1.8). Declared here so every module resolves one version,
    // and applied only in :app, which is where the shipped dependencies live.
    id("org.owasp.dependencycheck") version "13.0.0" apply false
}

buildscript {
    configurations.classpath {
        resolutionStrategy {
            // Transitive pins for the plugin classpath only (buildscript), not the app. dependency-check
            // 13.0.0 pulls these vulnerable versions in; 13.0.0 is the newest release on Maven Central
            // (checked 2026-11-11, no 13.x or 14.x after it), so a plugin upgrade cannot clear them and
            // the patched line is pinned here. Alert numbers come from Dependabot's open list.
            force(
                // Alerts 28-34: dependency-check ships jackson 2.22.1; 2.22.3 is the first release outside
                // both the 2.22.x ranges and every older 2.18.x range. Forcing the BOM keeps
                // jackson-annotations, dataformat-yaml, datatype-jsr310 and module-blackbird aligned.
                "com.fasterxml.jackson:jackson-bom:2.22.3",
                "com.fasterxml.jackson.core:jackson-core:2.22.3",
                "com.fasterxml.jackson.core:jackson-databind:2.22.3",
                // Alert 24: arrives at 33.6.0-jre; 33.8.0 is the latest patched release.
                "com.google.guava:guava:33.8.0-jre",
                // Alerts 5, 13, 14: arrives at 1.80.2; 1.87 is the latest patched release.
                "org.bouncycastle:bcprov-jdk18on:1.87",
                "org.bouncycastle:bcpkix-jdk18on:1.87",
                // Alert 4: arrives at 0.9.5.
                "org.bitbucket.b_c:jose4j:0.9.6",
                // Alert 3: arrives at 2.0.6.
                "org.jdom:jdom2:2.0.6.1",
                // Alerts 25, 26, 35: arrives at 1.23.1, below every fixed line (1.14.2, 1.15.3, 1.23.2).
                "org.jsoup:jsoup:1.24.1",
                // Alert 36: arrives at 20260522; 20260719 is the fixed build.
                "org.json:json:20260719",
                // Alerts 1 and 2: arrives below the fixed lines 4.5.13 and 3.18.0. These pinned versions are the
                // latest patched releases, so an explicit pin keeps the classpath from dropping back.
                "org.apache.httpcomponents:httpclient:4.5.14",
                "org.apache.commons:commons-lang3:3.21.0",
            )
        }
    }
}

// Alert 27: androidx.benchmark pulls wire-runtime 6.4.0 onto the app's benchmark androidTest classpath, not
// the shipped runtime. 6.4.5 is the first patched release; pin the latest 6.4.x patch. wire has no entry on
// releaseRuntimeClasspath, so the app's shipped dependencies stay exactly as they are.
subprojects {
    configurations.configureEach {
        resolutionStrategy.force(
            "com.squareup.wire:wire-runtime:6.4.7",
            "com.squareup.wire:wire-runtime-jvm:6.4.7",
        )
    }
}

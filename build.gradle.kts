plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
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
            "com.squareup.wire:wire-runtime:7.2.2",
            "com.squareup.wire:wire-runtime-jvm:6.4.7",
        )
    }
}

// Alerts 1, 2, 5, 13, 14, 24: AGP 9.5.1 resolves lint 32.5.1 into androidLintTool, and that classpath carries
// httpclient 4.5.6, commons-lang3 3.16.0, bcprov and bcpkix 1.80.2 and guava 33.4.0-jre. AGP fixes the lint version,
// so the libraries are pinned here. Only lint runs this classpath; no pinned library reaches the APK or the test APKs.
subprojects {
    configurations.matching { it.name == "androidLintTool" }.configureEach {
        resolutionStrategy.force(
            "org.apache.httpcomponents:httpclient:4.5.14",
            "org.apache.commons:commons-lang3:3.22.0",
            "org.bouncycastle:bcprov-jdk18on:1.87",
            "org.bouncycastle:bcpkix-jdk18on:1.87",
            "com.google.guava:guava:33.8.0-jre",
        )
    }
}

// Robolectric runs offline. Robolectric 4.17 downloads the Android SDK jar of the API level a test runs at (sdk = 36 here) from
// Maven Central during the test, with no retry, and a failed download failed the partition. Here the jar is a Gradle dependency:
// Gradle fetches it with its retries and keeps it in the Gradle user home, which cache-warm saves on master, so the PR partitions
// find it in the restored cache. robolectric.offline=true turns a missing jar into an error rather than a download.
// 16-robolectric-13921718-i7 is the sdk = 36 jar of Robolectric 4.17 (DefaultSdkProvider); a Robolectric upgrade moves it.
val robolectricSdk by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    robolectricSdk("org.robolectric:android-all-instrumented:16-robolectric-13921718-i7")
}

val robolectricSdkDir = layout.buildDirectory.dir("robolectric-sdk")

val syncRobolectricSdk by tasks.registering(Sync::class) {
    from(robolectricSdk)
    into(robolectricSdkDir)
}

// Only the modules whose unit tests run on Robolectric depend on the jar, so a JVM-only test task never waits for it.
listOf("app", "lumio", "shared").forEach { module ->
    project(":$module").tasks.withType<Test>().configureEach {
        dependsOn(syncRobolectricSdk)
        systemProperty("robolectric.offline", "true")
        systemProperty("robolectric.dependency.dir", robolectricSdkDir.get().asFile.absolutePath)
    }
}

// Lint analysis is cacheable. AGP leaves lintAnalyze tasks uncacheable, so every lint lane re-analyzed the Android modules
// (about four minutes) with no source change. The analysis reads only the module's inputs, and the lint report task still
// fails the build on an error, so this only skips re-analysis. The outputs keep absolute paths, so an entry is valid on the
// same checkout path, which CI always uses.
subprojects {
    tasks.matching { it.name.startsWith("lintAnalyze") }.configureEach {
        outputs.cacheIf("lint analysis depends only on its inputs") { true }
    }
}

// resolveAll resolves every resolvable configuration of every project and every buildscript classpath. That covers the
// robolectricSdk configuration above, which holds the Robolectric android-all jar the tests read. CI's deps job runs it
// online to fill the Gradle module cache; every other CI job runs with --offline against that cache, so a missing artifact
// fails by name instead of being fetched. See .github/workflows/pr-fast.yml.
// Each project resolves its own configurations in its own task: Gradle rejects resolving another project's configuration
// from a task. Not configuration-cache compatible: the task reads the project's configurations at execution time.
// AGP's per-variant classpaths (for example benchmarkCompileClasspath) match several variants when no artifact type is
// requested, so a bare resolve() cannot choose. The task that compiles against one requests its artifact type and resolves
// it, so only that ambiguity is skipped here; every other resolution failure still fails resolveAll.
fun Configuration.resolveForCache() {
    try {
        resolve()
    } catch (e: Exception) {
        val ambiguous = generateSequence(e as Throwable) { it.cause }
            .any { it.message?.contains("cannot choose between the following variants") == true }
        if (!ambiguous) throw e
        println("resolveAll: skipped $name, which needs an artifact type to pick its variant")
    }
}

allprojects {
    tasks.register("resolveConfigurations") {
        group = "help"
        description = "Resolves every configuration of this project and of its buildscript."
        notCompatibleWithConfigurationCache("reads the project's configurations at execution time")
        doLast {
            buildscript.configurations.toList().filter { it.isCanBeResolved }.forEach { it.resolveForCache() }
            // Resolving can add configurations to the container, so take snapshots until no new resolvable one appears.
            val done = mutableSetOf<String>()
            var pending = configurations.toList().filter { it.isCanBeResolved }
            while (pending.isNotEmpty()) {
                pending.forEach { it.resolveForCache(); done += it.name }
                pending = configurations.toList().filter { it.isCanBeResolved && it.name !in done }
            }
        }
    }
}

tasks.register("resolveAll") {
    group = "help"
    description = "Resolves every configuration of every project and buildscript, so later --offline builds find each artifact."
    notCompatibleWithConfigurationCache("resolves a detached configuration of the root project at execution time")
    dependsOn(rootProject.allprojects.map { it.path.trimEnd(':') + ":resolveConfigurations" })
    doLast {
        // AGP runs aapt2 from a detached configuration that no project lists, and the configuration cache stores that task input,
        // so the artifact must be cached. CI runs linux; a build on macOS runs osx. Version: AGP 9.5.1's bundled aapt2, as
        // verification-metadata.xml pins it. A new AGP names the new version when this artifact is missing offline.
        listOf("linux", "osx").forEach { platform ->
            configurations.detachedConfiguration(dependencies.create("com.android.tools.build:aapt2:9.5.1-16666295:$platform")).resolve()
        }
    }
}

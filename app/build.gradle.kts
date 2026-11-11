plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
    id("org.owasp.dependencycheck")
}

android {
    namespace = "app.lensly"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.lensly"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }

        // Performance tests run release-like code (no debug flag), signed with the debug key so a device
        // can install it. Release sets no proguard files and leaves minify off, so benchmark mirrors that:
        // the gate measures the code that ships, not a R8 build that differs from release.
        create("benchmark") {
            initWith(getByName("debug"))
            // Debug-only dependencies fall back to their debug variant; this app has none yet.
            matchingFallbacks += "debug"
            signingConfig = signingConfigs.getByName("debug")
            // Non-debuggable, like release: a debuggable APK cannot be profiled, so its timings would not be
            // comparable to shipped code.
            isDebuggable = false
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/*.kotlin_module",
                "META-INF/versions/9/module-info.class",
            )
            // DISCLAIMER is shipped by two tuweni jars (both pulled in by web3j); keep one copy.
            pickFirsts += setOf("META-INF/DISCLAIMER")
        }
    }

    testOptions {
        unitTests {
            // Robolectric renders Compose screens from the merged resources (fonts, themes).
            isIncludeAndroidResources = true
        }
    }
}

// `testBuildType` stays "debug". Setting it to "benchmark" is the usual recipe, and it would give
// :app:connectedBenchmarkAndroidTest, but it also moves every instrumented test (guideline 1.3 and 1.6) onto the
// benchmark variant and removes :app:connectedDebugAndroidTest. Instead, the benchmark variant gets its own androidTest
// component, and only that variant's runner is swapped. `testInstrumentationRunner` is a defaultConfig field, so debug
// and release keep AndroidJUnitRunner.
androidComponents {
    beforeVariants { variantBuilder ->
        if (variantBuilder.buildType == "benchmark") {
            // AGP builds the androidTest APK only for the testBuildType. This enables the same for benchmark, which is
            // what makes :app:connectedBenchmarkAndroidTest and :app:assembleBenchmarkAndroidTest exist at all.
            variantBuilder.enableAndroidTest = true
            // Release-like builds cannot be profiled; the benchmark runner needs this to collect timings.
            variantBuilder.profileable = true
        }
    }
    onVariants { variant ->
        if (variant.buildType == "benchmark") {
            variant.androidTest?.instrumentationRunner?.set(
                "androidx.benchmark.junit4.AndroidBenchmarkRunner",
            )
        }
    }
}

// Screenshot tests write PNGs here, and they are committed. Record with :app:recordRoborazziDebug;
// check against the committed PNGs with :app:verifyRoborazziDebug.
roborazzi {
    outputDir.set(file("src/test/screenshots"))
}

// OWASP dependency-check (guideline 1.8, static security), run by the nightly workflow only.
// It stays out of the PR lane: the task downloads the NVD feed on first use, so a cold run takes
// 10 to 15 minutes against a 3-minute PR budget.
dependencyCheck {
    // Fail on a high-severity finding, CVSS 7.0 and above. The plugin default is 11.0, which is
    // above the 10.0 maximum, so an unconfigured run never fails the build.
    getFailBuildOnCVSS().set(7.0f)
    // Scan what ships: the app's runtime classpath.
    getScanConfigurations().set(listOf("releaseRuntimeClasspath"))
    // Read the key from the environment (a CI repository secret, NVD_API_KEY) and never from the
    // repo. An absent key is not an error: the scan still runs, rate-limited to one NVD call every
    // 8 seconds instead of every 3.5.
    getNvd().getApiKey().set(providers.environmentVariable("NVD_API_KEY").orElse(""))
    // An Android APK is a zip of dex files, not a .NET assembly, so the assembly analyzer has
    // nothing to read and only costs scan time.
    getAnalyzers().getAssemblyEnabled().set(false)
}

// Robolectric reads FileDescriptor state through jdk.internal.access, which the JDK does not export to unnamed
// modules. Export it to the unit-test JVM so Robolectric starts on the JDK 26 this project builds with locally.
// The export is harmless on JDK 17, which CI uses.
// jazzer-junit is a JUnit 5 artifact, so the unit-test JVM runs the JUnit Platform. The engine pair is explicit:
// Jupiter runs the Jazzer fuzz targets, Vintage runs every existing JUnit 4 test (unit, Robolectric, Compose,
// Roborazzi). Keep the two versions equal; 5.14 is the newest line Jazzer 0.30.0 is built against.
val junitJupiter = "5.14.4"

// The 100 Roborazzi pixel tests in app.lensly.ui.screenshot push the PR `ui` partition past its
// 3-minute budget (guideline 2.1), so over-budget work moves to the nightly verifyRoborazziDebug
// run (guideline 2.7). scripts/ci/run-partition.sh passes -PprLane for that partition only, which
// drops the pixel tests from the PR JVM while behaviour tests in the same packages still run.
// Read through providers so the value is captured at configuration time and the task stays
// configuration-cache safe.
val prLane = providers.gradleProperty("prLane").isPresent

// Jazzer and Robolectric cannot share a JVM. Once a fuzz target has run, Jazzer's agent instruments the classes
// Robolectric defines in its sandbox, and the injected code calls com.code_intelligence.jazzer.runtime
// .JazzerInternal. That class ships inside the nested com/code_intelligence/jazzer/runtime/jazzer_bootstrap.jar,
// so it is not on the class path as a top-level class and Robolectric's sandbox loader cannot read its bytes. The
// next Robolectric test then fails with NoClassDefFoundError: JazzerInternal, on any JDK. The shared unit-test JVM
// therefore skips the fuzz package, and -PfuzzLane is the lane that runs the committed seeds: one Gradle
// invocation per target, always without Robolectric. scripts/ci/run-partition.sh, scripts/ci/fuzz-nightly.sh and
// the nightly workflow pass it.
val fuzzLane = providers.gradleProperty("fuzzLane").isPresent

tasks.withType<Test>().configureEach {
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
    useJUnitPlatform()
    if (prLane) {
        filter { excludeTestsMatching("app.lensly.ui.screenshot.*") }
    }
    if (!fuzzLane) {
        filter { excludeTestsMatching("app.lensly.fuzz.*") }
    }
    // Jazzer's JUnit integration reads extra libFuzzer flags from JUnit configuration parameters named
    // jazzer.internal.arg.<n>, and JUnit sources those from system properties. Element 0 is the libFuzzer
    // argv[0], so the flag goes at index 1. Jazzer appends these after the @FuzzTest duration, so the
    // shorter cap wins. scripts/ci/run-extended.sh passes -PfuzzMaxSeconds=60 for /run-fuzz; the nightly
    // run leaves it unset and keeps the 15m annotation.
    providers.gradleProperty("fuzzMaxSeconds").orNull?.let { seconds ->
        systemProperty("jazzer.internal.arg.0", "fake_argv0")
        systemProperty("jazzer.internal.arg.1", "-max_total_time=$seconds")
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    // Baked-in network and token catalogs (registry/*.json assets).
    implementation(libs.kotlinx.serialization.json)

    // Crypto and wallet encoding.
    implementation(libs.web3j.core) {
        // web3j pulls in the AWS KMS SDK (and with it Netty and a second jackson-core) for optional
        // KMS signing. Lensly is offline and never signs through KMS, so keep that stack out.
        exclude(group = "software.amazon.awssdk")
    }
    implementation(libs.bouncycastle.bcprov.jdk18on)
    implementation(libs.sparrowwallet.hummingbird)

    // Transitive pins for Dependabot alerts 6-12, 15-23 and 24. Both libraries arrive through web3j
    // 4.12.3, which is still the current release, and it asks for jackson 2.17.1 and guava
    // 33.3.1-android. These are constraints, not forces: they only raise the floor, so a future web3j
    // that asks for a newer version still wins.
    constraints {
        // Alerts 6-12, 15-23: every listed range ends at or before 2.18.11, and 2.18 is the patch line
        // that keeps the 2.x API web3j was built against. The 2.22 line (used on the build classpath) is
        // deliberately not used here: it is a newer minor than web3j has ever been tested with.
        implementation("com.fasterxml.jackson.core:jackson-databind:2.18.11")
        implementation("com.fasterxml.jackson.core:jackson-core:2.18.11")
        implementation("com.fasterxml.jackson.core:jackson-annotations:2.18.11")
        // Alert 24: 33.7.2 is the first release outside the range. The -android flavor is kept so no
        // jre-only guava classes land on the Android classpath.
        implementation("com.google.guava:guava:33.7.2-android")
    }

    // Password strength estimation for the vault's App Password policy.
    implementation(libs.zxcvbn)

    // QR generation and scanning.
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.video)
    implementation(libs.zxing.cpp.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)

    // Jazzer fuzz targets (app/src/test/java/app/lensly/fuzz). Regression mode replays the committed seeds as
    // ordinary tests; JAZZER_FUZZ=1 turns the same targets into a fuzzing run. See scripts/ci/fuzz-nightly.sh.
    testImplementation(libs.jazzer.junit)
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$junitJupiter")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:$junitJupiter")

    // Compose UI tests on Robolectric, and their screenshots through Roborazzi.
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi.core)
    testImplementation(libs.roborazzi.compose)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.uiautomator)

    // Accessibility Test Framework (guideline 1.9). The spike in app.lensly.a11y.AccessibilityTest uses it on
    // Compose screens; docs/testing/accessibility.md records what it does and does not see.
    androidTestImplementation(libs.androidx.test.espresso.accessibility)

    // Microbenchmarks (guideline 1.7). They run only on the benchmark build type, through the runner set above.
    androidTestImplementation(libs.androidx.benchmark.junit4)

    // AndroidX Benchmark puts `androidx.benchmark.IsolationActivity` in the *app* APK's manifest, not the test APK's:
    // AndroidBenchmarkRunner launches it by name in the app under test. So the library has to be on the benchmark
    // variant's own runtime too, or every run dies with "Unable to resolve activity for
    // androidx.benchmark.IsolationActivity".
    //
    // It drags in the test stack it was built against (androidx.test:runner 1.5.2, rules 1.5.0, monitor 1.6.1,
    // junit 4.13.2) and pins some of those with `strictly` constraints. AGP merges the tested app's runtime into the
    // androidTest runtime, so a plain add downgrades monitor to 1.6.1 and fails resolution of the runner/rules 1.7.0
    // this module pins, which strips `org.hamcrest.Matchers` from every instrumented test. The app side only needs
    // IsolationActivity and CpuInfo, and neither touches the test stack, so that stack stays on the androidTest
    // classpath only.
    //
    // Kotlin DSL does not generate a type-safe accessor for this build type, so the configuration is named as a string.
    "benchmarkImplementation"(libs.androidx.benchmark.junit4) {
        exclude(group = "androidx.test")
        exclude(group = "androidx.test.espresso")
        exclude(group = "androidx.test.services")
        exclude(group = "junit")
        exclude(group = "org.hamcrest")
    }

    // benchmark-macro-junit4 is in the catalog but not used here: macrobenchmarks need a separate
    // com.android.test module targeting this benchmark variant. That module has no catalog alias yet,
    // so it is deferred (see app/src/androidTest/benchmark-baselines/README.md).
}

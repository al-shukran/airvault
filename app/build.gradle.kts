plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
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

        // Phones are arm64-v8a, and the emulator the team uses is arm64-v8a too; x86_64 stays for x86 emulators. The 32-bit ARM
        // and x86 copies of every native library are left out of the APK.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    sourceSets {
        getByName("main") {
            // The third-party notice and the font licences are kept in licenses/ at the repository root, and the app ships them
            // as assets, so the Settings screen can show them.
            assets.srcDir("../licenses")
        }
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
// The unit-test JVM runs the JUnit Platform, and Vintage runs every JUnit 4 test in this module (unit, Robolectric,
// Compose, Roborazzi). The fuzz targets, which need Jupiter, live in :shared.
val junitJupiter = "5.14.4"

// The 100 Roborazzi pixel tests in app.lensly.ui.screenshot push the PR `ui` partition past its
// 3-minute budget (guideline 2.1), so over-budget work moves to the nightly verifyRoborazziDebug
// run (guideline 2.7). scripts/ci/run-partition.sh passes -PprLane for that partition only, which
// drops the pixel tests from the PR JVM while behaviour tests in the same packages still run.
// Read through providers so the value is captured at configuration time and the task stays
// configuration-cache safe.
val prLane = providers.gradleProperty("prLane").isPresent

tasks.withType<Test>().configureEach {
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
    useJUnitPlatform()
    if (prLane) {
        filter { excludeTestsMatching("app.lensly.ui.screenshot.*") }
    }
}

dependencies {
    // Domain code (core/*) and the screens and components that Lumio reuses live in :shared, with their Compose, web3j,
    // camera and crypto dependencies. See shared/build.gradle.kts.
    implementation(project(":shared"))

    // Lensly-only screens, navigation and view models.
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Unit tests. The shared test fixtures (fakes and helpers that more than one app uses) come from :shared.
    testImplementation(testFixtures(project(":shared")))
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    // The export and sign tests decode UR payloads with the same library the shared code uses.
    testImplementation(libs.sparrowwallet.hummingbird)
    // Bitcoin keys in unit tests load the JVM build of secp256k1, as :shared's tests do. The Android build is for the APK only.
    testImplementation(libs.secp256k1.kmp.jni.jvm)
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:$junitJupiter")
    // Gradle starts the Vintage engine through the JUnit Platform launcher, which must be on the test runtime classpath. It used
    // to come in through jazzer-junit; that moved to :shared. The BOM keeps the launcher on the engine's version line.
    testRuntimeOnly(platform("org.junit:junit-bom:$junitJupiter"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

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
    constraints {
        // Alerts 25, 26, 35: espresso-accessibility 3.7.0 (the newest stable release; 3.8.0 is still alpha) brings
        // accessibility-test-framework 3.1.2, which brings jsoup 1.12.2. The floor applies to the androidTest
        // classpath only; 1.24.1 is the newest stable jsoup, and no androidTest code ships in the APK.
        androidTestImplementation("org.jsoup:jsoup:1.24.1")
    }

    // Microbenchmarks (guideline 1.7). They run only on the benchmark build type, through the runner set above.
    androidTestImplementation(libs.androidx.benchmark.junit4)

    // The instrumented and benchmark tests call the QR and UR libraries directly. They reach :shared as implementation, so the
    // androidTest classpath declares them itself.
    androidTestImplementation(libs.zxing.core)
    androidTestImplementation(libs.zxing.cpp.android)
    androidTestImplementation(libs.sparrowwallet.hummingbird)

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

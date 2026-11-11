plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
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

tasks.withType<Test>().configureEach {
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
    useJUnitPlatform()
    if (prLane) {
        filter { excludeTestsMatching("app.lensly.ui.screenshot.*") }
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

    // Password strength estimation for the vault's App Password policy.
    implementation(libs.zxcvbn)

    // QR generation and scanning.
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
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

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)

    // Microbenchmarks (guideline 1.7). They run only on the benchmark build type, through the runner set above.
    androidTestImplementation(libs.androidx.benchmark.junit4)
    // benchmark-macro-junit4 is in the catalog but not used here: macrobenchmarks need a separate
    // com.android.test module targeting this benchmark variant. That module has no catalog alias yet,
    // so it is deferred (see app/src/androidTest/benchmark-baselines/README.md).
}

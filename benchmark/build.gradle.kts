// Macrobenchmark (docs/testing/guideline.md 1.7). A macrobenchmark launches the installed app and measures it from the
// outside, so it cannot share a module with the app: this is a com.android.test module that targets :app's benchmark
// variant and runs in its own process, driving the app through UI Automator.
//
// The module is intentionally absent from every other lane: :app:testDebugUnitTest, :app:assembleDebug and
// :app:verifyRoborazziDebug never configure or build it, because nothing here is a dependency of :app.
plugins {
    alias(libs.plugins.android.test)
}

android {
    namespace = "app.lensly.benchmark"
    compileSdk = 36

    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Mirrors :app's benchmark build type. A test module's variant is matched to the target project's variant by
    // name, so this "benchmark" variant measures the app's "benchmark" variant: debug-signed, not debuggable,
    // release-like assets. That is the build the guideline allows a benchmark to run on.
    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    targetProjectPath = ":app"

    // The benchmark APK instruments itself instead of a separate target APK. Macrobenchmark requires this.
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

// Build only the benchmark variant. The debug and release variants of a test module have nothing to measure here and
// would add tasks that no lane calls.
androidComponents {
    beforeVariants(selector().all()) { variantBuilder ->
        variantBuilder.enable = variantBuilder.buildType == "benchmark"
    }
}

dependencies {
    // Macrobenchmark rule, metrics and compilation modes.
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.ext.junit)
    // Waits on the launched activity's window instead of a fixed sleep.
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.test.runner)
}

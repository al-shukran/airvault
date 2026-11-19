plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Code and resources that Lensly and Lumio share (package names stay app.lensly.*). Each app depends on this module and
// keeps its own manifest, navigation and screens. Build types mirror :app's, so BuildConfig.DEBUG and the debug registry
// overlays are the same in every variant of each app.
android {
    namespace = "app.lensly.shared"
    compileSdk = 36

    defaultConfig {
        minSdk = 31
        // True only in the benchmark build type of an app. The emulator exemptions (FLAG_SECURE, the debugHidden extra) read it,
        // and only on an emulator.
        buildConfigField("boolean", "BENCHMARK", "false")
    }

    buildTypes {
        // Matches :app's and :lumio's benchmark build types by name. Without it, their benchmark variants would fall back to this
        // module's debug variant, which turns on the debug registry overlays and BuildConfig.DEBUG. It is not initWith(debug): that
        // copies debug's DEBUG flag, and the benchmark variants have DEBUG false.
        create("benchmark") {
            buildConfigField("boolean", "BENCHMARK", "true")
        }
    }

    sourceSets {
        getByName("main") {
            // The third-party notice and the font licences are kept in licenses/ at the repository root. Both apps read the notice
            // from these shared assets, so the Settings screens of Lensly and Lumio show the same one.
            assets.srcDir("../licenses")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    // Test helpers that more than one app's unit tests use (src/testFixtures).
    testFixtures {
        enable = true
    }

    testOptions {
        unitTests {
            // Robolectric renders Compose screens from the merged resources (fonts, themes).
            isIncludeAndroidResources = true
        }
    }
}

// Jazzer fuzz targets (src/test/java/app/lensly/fuzz). Regression mode replays the committed seeds as ordinary tests;
// JAZZER_FUZZ=1 turns the same targets into a fuzzing run. See scripts/ci/fuzz-nightly.sh.
// Jazzer and Robolectric cannot share a JVM. Once a fuzz target has run, Jazzer's agent instruments the classes Robolectric
// defines in its sandbox, and the injected code calls com.code_intelligence.jazzer.runtime.JazzerInternal, which is not on the
// class path as a top-level class. The next Robolectric test then fails with NoClassDefFoundError: JazzerInternal. The shared
// unit-test JVM therefore skips the fuzz package, and -PfuzzLane is the lane that runs the committed seeds: one Gradle
// invocation per target, always without Robolectric. scripts/ci/run-partition.sh, scripts/ci/fuzz-nightly.sh and the nightly
// workflow pass it.
//
// jazzer-junit is a JUnit 5 artifact, so the unit-test JVM runs the JUnit Platform. The engine pair is explicit: Jupiter runs
// the Jazzer fuzz targets, Vintage runs every existing JUnit 4 test (unit and Robolectric). Keep the two versions equal; 5.14
// is the newest line Jazzer 0.30.0 is built against.
val junitJupiter = "5.14.4"

// Read through providers so the value is captured at configuration time and the task stays configuration-cache safe.
val fuzzLane = providers.gradleProperty("fuzzLane").isPresent

tasks.withType<Test>().configureEach {
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
    useJUnitPlatform()
    if (!fuzzLane) {
        filter { excludeTestsMatching("app.lensly.fuzz.*") }
    }
    // Jazzer's JUnit integration reads extra libFuzzer flags from JUnit configuration parameters named
    // jazzer.internal.arg.<n>, and JUnit sources those from system properties. Element 0 is the libFuzzer
    // argv[0], so the flag goes at index 1. scripts/ci/run-extended.sh passes -PfuzzMaxSeconds=60 for /run-fuzz;
    // the nightly run leaves it unset and keeps the 15m annotation.
    providers.gradleProperty("fuzzMaxSeconds").orNull?.let { seconds ->
        systemProperty("jazzer.internal.arg.0", "fake_argv0")
        systemProperty("jazzer.internal.arg.1", "-max_total_time=$seconds")
    }
}

dependencies {
    // CBOR, parser limits and keypaths live in core/signer-api. Their types are part of this module's API, so :app and :lumio see them.
    api(project(":core:signer-api"))
    // The Algorand transaction types and the request codec live in chains/algorand/common; the decoder in .../signer.
    api(project(":chains:algorand:common"))
    api(project(":chains:algorand:signer"))
    // The Bitcoin PSBT codec, the bitcoin manifest and the crypto-psbt parser live in chains/bitcoin/signer.
    api(project(":chains:bitcoin:signer"))

    // Compose, activity and coroutines types are part of this module's public API, so they reach :app as api.
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.material.icons.core)
    api(libs.androidx.activity.compose)
    api(libs.androidx.navigation.compose)
    api(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Registry catalogs (registry/*.json assets).
    implementation(libs.kotlinx.serialization.json)

    // Crypto and wallet encoding. web3j is api because :app's sign and wallet code calls it directly.
    api(libs.web3j.core) {
        // web3j pulls in the AWS KMS SDK (and with it Netty and a second jackson-core) for optional
        // KMS signing. Lensly is offline and never signs through KMS, so keep that stack out.
        exclude(group = "software.amazon.awssdk")
        // web3j's HTTP service is the only user of OkHttp in it. :app and :shared never call it (they use crypto and utils), and
        // Lensly must not be able to reach the network, so OkHttp stays out of :app. :lumio declares its own OkHttp.
        exclude(group = "com.squareup.okhttp3")
    }
    implementation(libs.bouncycastle.bcprov.jdk18on)
    implementation(libs.sparrowwallet.hummingbird)

    // Bitcoin (app.lensly.core.btc): keys, addresses, PSBT and script checks from ACINQ's bitcoin-kmp (Apache-2.0).
    // Its types are part of the core/btc API (KeyPath, Psbt), so the apps that use core/btc see them too.
    // The Android build of secp256k1 ships its native library in the APK. The JVM build is only for unit tests:
    // on a JVM the Android loader fails to load its library and falls back to the JVM loader on the same classpath.
    api(libs.bitcoin.kmp)
    implementation(libs.secp256k1.kmp.jni.android)
    testImplementation(libs.secp256k1.kmp.jni.jvm)

    // Transitive pins for Dependabot alerts 6-12, 15-23, 24 and 37-39. web3j 4.12.3 is api here, and it asks for
    // jackson 2.17.1, guava 33.3.1-android and bcprov 1.78.1. These are api constraints, not implementation ones,
    // so they also reach the compile classpaths of :app and :lumio, where web3j is on the API surface. They only
    // raise the floor, so a future web3j that asks for a newer version still wins.
    constraints {
        // Alerts 6-12, 15-23: every listed range ends at or before 2.18.11, and 2.18 is the patch line
        // that keeps the 2.x API web3j was built against. The 2.22 line (used on the build classpath) is
        // deliberately not used here: it is a newer minor than web3j has ever been tested with.
        api("com.fasterxml.jackson.core:jackson-databind:2.18.11") { because("Dependabot alerts 6-12, 15-23") }
        api("com.fasterxml.jackson.core:jackson-core:2.18.11") { because("Dependabot alerts 6-12, 15-23") }
        api("com.fasterxml.jackson.core:jackson-annotations:2.18.11") { because("Dependabot alerts 6-12, 15-23") }
        // Alert 24: 33.7.2 is the first release outside the range. The -android flavor is kept so no
        // jre-only guava classes land on the Android classpath.
        api("com.google.guava:guava:33.7.2-android") { because("Dependabot alert 24") }
        // Alerts 37-39: web3j asks for bcprov 1.78.1. 1.86 is the version this module ships (libs.versions.toml),
        // and it is also above the 1.85 floor of alerts 13 and 14.
        api("org.bouncycastle:bcprov-jdk18on:1.86") { because("Dependabot alerts 37-39") }
        // OWASP CVE-2026-53914 matched kotlin-stdlib-jdk7 and -jdk8 at 1.9.10, which a dependency still pulls in. Since
        // Kotlin 2.x both artifacts are empty shims over kotlin-stdlib, so they follow the project's Kotlin version (fixed
        // in 2.4.20).
        api("org.jetbrains.kotlin:kotlin-stdlib-jdk7:${libs.versions.kotlin.get()}") { because("OWASP CVE-2026-53914") }
        api("org.jetbrains.kotlin:kotlin-stdlib-jdk8:${libs.versions.kotlin.get()}") { because("OWASP CVE-2026-53914") }
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

    // Jazzer fuzz targets. See the note above on fuzzLane.
    testImplementation(libs.jazzer.junit)
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$junitJupiter")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:$junitJupiter")

    // Compose UI tests on Robolectric.
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Test fixtures are used from the app's unit tests, so their API types are api here.
    testFixturesApi(libs.androidx.compose.ui.test.junit4)
    testFixturesImplementation(libs.roborazzi.core)
    testFixturesImplementation(libs.roborazzi.compose)
}

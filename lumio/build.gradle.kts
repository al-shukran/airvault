plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.roborazzi)
}

// Lumio, the companion app. It shares the camera cover, the gate and the theme with Lensly through :shared, and it has its own manifest,
// navigation and Home. It holds no key material. The network permission is its own: check-manifest.sh holds its manifest to an allow-list.
android {
    namespace = "app.lumio"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.lumio"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // The same ABIs as :app. Phones are arm64-v8a, and x86_64 covers the x86 emulators.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            // R8 minifies and optimizes the release build, and resource shrinking drops the resources no code path reaches.
            // proguard-rules.pro holds the keep rules, each with the reason it is needed.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }

        // Release's code for the emulator's jank and smoke checks: the same R8 build, signed with the debug key so a device can
        // install it. It is not debuggable, so its timings are release's. Its debug exemptions (FLAG_SECURE, the debugHidden extra)
        // apply on an emulator only, through BuildConfig.BENCHMARK in :shared. A phone running it is secure, as release is.
        create("benchmark") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            signingConfig = signingConfigs.getByName("debug")
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
            // :shared brings the same jars as :app, so the same META-INF files collide and are excluded the same way.
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
            // The request log calls android.util.Log, which the plain JVM tests read as its defaults.
            isReturnDefaultValues = true
        }
    }
}

// Screenshot tests write PNGs here, and they are committed. Record with :lumio:recordRoborazziDebug;
// check against the committed PNGs with :lumio:verifyRoborazziDebug.
roborazzi {
    outputDir.set(file("src/test/screenshots"))
}

val junitJupiter = "5.14.4"

// Robolectric reads FileDescriptor state through jdk.internal.access, which the JDK does not export to unnamed modules. The same
// export as :app and :shared lets Robolectric start on the JDK this project builds with.
tasks.withType<Test>().configureEach {
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
    useJUnitPlatform()
}

dependencies {
    implementation(project(":shared"))

    // Chain apps compiled into Lumio; see lumio/src/main/java/app/lumio/chains/InstalledWallets.kt.
    implementation(project(":chains:algorand:wallet"))
    implementation(project(":chains:solana:wallet"))

    // The Esplora client reads JSON with the kotlinx.serialization library that :shared already ships in the APK.
    implementation(libs.kotlinx.serialization.json)

    // Balances and nonces come from a public JSON-RPC endpoint over HTTPS. OkHttp is the client web3j already ships, at the same version.
    implementation(libs.okhttp)

    // The cache and the scan queue are one Room database in the app's private storage. Room's processor runs through KSP.
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    constraints {
        // Alert 24: room-compiler-processing 2.8.5 brings guava 33.2.1-jre into the KSP processor classpath. The floor
        // applies to that classpath only; no processor code reaches the APK.
        ksp("com.google.guava:guava:33.7.2-jre") { because("Dependabot alert 24") }
    }

    // The pairing codes are read as UR and Bitcoin key types, the same libraries :shared reads them with.
    implementation(libs.sparrowwallet.hummingbird)
    implementation(libs.bitcoin.kmp)

    // Lumio reads a version-2 pairing's Brotli body with Google's pure-Java decoder. The encoder is Lensly's alone.
    implementation(libs.brotli.dec)

    // Unit tests on Robolectric. The JUnit 4 tests run on the Vintage engine, as they do in :app.
    testImplementation(testFixtures(project(":shared")))
    // Keys on the JVM need the native secp256k1 that :shared's own unit tests load.
    testImplementation(libs.secp256k1.kmp.jni.jvm)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)

    // The Esplora tests answer from a local MockWebServer, never from the network.
    testImplementation(libs.okhttp.mockwebserver)
    // The Algorand send tests sign with a test key, the Ed25519 that :shared uses (its own classpath stays as it is).
    testImplementation(libs.bouncycastle.bcprov.jdk18on)
    // The scheduler and cache tests run in virtual time.
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // The goldens of the screens: Roborazzi captures from the same Robolectric compose rule as the behaviour tests.
    testImplementation(libs.roborazzi.core)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.robolectric)
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:$junitJupiter")
    testRuntimeOnly(platform("org.junit:junit-bom:$junitJupiter"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

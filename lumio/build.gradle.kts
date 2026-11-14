plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
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
        }
    }
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

    // The pairing codes are read as UR and Bitcoin key types, the same libraries :shared reads them with.
    implementation(libs.sparrowwallet.hummingbird)
    implementation(libs.bitcoin.kmp)

    // Unit tests on Robolectric. The JUnit 4 tests run on the Vintage engine, as they do in :app.
    testImplementation(testFixtures(project(":shared")))
    // Keys on the JVM need the native secp256k1 that :shared's own unit tests load.
    testImplementation(libs.secp256k1.kmp.jni.jvm)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:$junitJupiter")
    testRuntimeOnly(platform("org.junit:junit-bom:$junitJupiter"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

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

// Screenshot tests write PNGs here, and they are committed. Record with :app:recordRoborazziDebug;
// check against the committed PNGs with :app:verifyRoborazziDebug.
roborazzi {
    outputDir.set(file("src/test/screenshots"))
}

// Robolectric reads FileDescriptor state through jdk.internal.access, which the JDK does not export to unnamed
// modules. Export it to the unit-test JVM so Robolectric starts on the JDK 26 this project builds with locally.
// The export is harmless on JDK 17, which CI uses.
tasks.withType<Test>().configureEach {
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
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

    // Compose UI tests on Robolectric, and their screenshots through Roborazzi.
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi.core)
    testImplementation(libs.roborazzi.compose)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

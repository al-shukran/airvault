import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Android modules that depend on this code compile to Java 21, so the pure-JVM chain modules do too.
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    api(project(":core:signer-api"))
    // The PSBT codec and the script checks come from ACINQ's bitcoin-kmp (Apache-2.0).
    api(libs.bitcoin.kmp)
    // The address codec that the signer serves is the one in :chains:bitcoin:common.
    implementation(project(":chains:bitcoin:common"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}

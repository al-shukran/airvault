import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Android modules that depend on this code compile to Java 17, so the pure-JVM chain modules do too.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
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

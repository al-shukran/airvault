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
    api(project(":chains:solana:common"))
    // Base58 for the account keys a message names, from the bitcoin-kmp library that :shared already ships.
    implementation(libs.bitcoin.kmp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}

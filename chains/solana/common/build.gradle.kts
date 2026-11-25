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
    api(project(":core:model"))
    // Solana addresses are base58, read and written with bitcoin-kmp's Base58 as the Solana code in :shared does.
    implementation(libs.bitcoin.kmp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}

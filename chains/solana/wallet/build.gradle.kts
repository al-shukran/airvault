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
    // The pairing import hands each exported key to the app, as a signer reference and a public key (spec 3.3, 7.5).
    api(project(":core:wallet-api"))
    implementation(project(":chains:solana:common"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}

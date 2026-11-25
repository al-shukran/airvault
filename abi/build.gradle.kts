import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
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
    // The decoder reads the core's parser limits and budget, and the hex helper.
    api(project(":core:signer-api"))
    // WhitelistParam is part of the registry's serialized form, so its serializer comes from kotlinx.serialization.
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}

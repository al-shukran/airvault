import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
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
    // The decoder reads the core's parser limits and budget, and the hex helper.
    api(project(":core:signer-api"))
    // WhitelistParam is part of the registry's serialized form, so its serializer comes from kotlinx.serialization.
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}

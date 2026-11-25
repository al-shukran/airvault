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
    // framesOf and the UR codecs expose the hummingbird UR type.
    api(libs.sparrowwallet.hummingbird)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}

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
    api(project(":core:wallet-api"))
    implementation(project(":chains:algorand:common"))
    // The address checksum is SHA-512/256, computed as Lensly's AlgoAddress does, with the BouncyCastle version :shared ships.
    implementation(libs.bouncycastle.bcprov.jdk18on)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}

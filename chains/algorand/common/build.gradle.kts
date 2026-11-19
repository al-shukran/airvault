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
    api(project(":core:model"))
    // The request codec and the transaction types use CBOR, the parser limits and the UR type.
    api(project(":core:signer-api"))
    implementation(libs.bouncycastle.bcprov.jdk18on)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}

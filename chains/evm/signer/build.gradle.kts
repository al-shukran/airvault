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
    api(project(":core:signer-api"))
    api(project(":chains:evm:common"))
    api(project(":abi"))
    // Keccak-256, EIP-55 checksums and the ABI-free helpers of the transaction and typed data codecs come from web3j's crypto module.
    implementation(libs.web3j.crypto) {
        // tuweni-bytes (via web3j crypto) declares org.connid and kotlin-stdlib-jdk8 1.8.21 for nothing it runs here. The jdk7/jdk8
        // stdlib split has been empty since Kotlin 1.8, and resolving its 1.8.21 jar intermittently failed CI ("Could not find").
        exclude(group = "org.connid")
        exclude(group = "io.vertx")
        exclude(group = "io.netty")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    }
    // The registry's catalog entries are @Serializable, so the serialization plugin runs here as well as in :shared.
    implementation(libs.kotlinx.serialization.json)
    // The pins :shared holds for web3j's transitive libraries (Dependabot alerts 37-39). They keep this module on the
    // artifacts the verification metadata lists.
    constraints {
        implementation(libs.bouncycastle.bcprov.jdk18on)
    }

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}

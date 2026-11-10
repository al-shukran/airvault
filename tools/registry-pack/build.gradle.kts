plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

dependencies {
    // Ed25519 signing and verification, the same library the app verifies with.
    implementation(libs.bouncycastle.bcprov.jdk18on)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}

application {
    mainClass.set("app.lensly.tools.registrypack.MainKt")
}

// CLI paths are relative to the repository root, not to this module.
tasks.named<JavaExec>("run") {
    workingDir = rootDir
}

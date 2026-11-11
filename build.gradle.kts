plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    // OWASP dependency-check (guideline 1.8). Declared here so every module resolves one version,
    // and applied only in :app, which is where the shipped dependencies live.
    id("org.owasp.dependencycheck") version "13.0.0" apply false
}

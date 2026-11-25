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
    api(project(":core:signer-api"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    // The tests run the kit on the Algorand signer. The network test reads the classpath of every signer module
    // (spec 8.1), so each chain's signer is a test dependency, including the ones added later.
    rootProject.subprojects
        .filter { Regex(":chains:[^:]+:signer").matches(it.path) }
        .forEach { testImplementation(project(it.path)) }
}

// The network test scans the sources of the signer side from the repository root.
tasks.test {
    systemProperty("airvault.repoRoot", rootDir.absolutePath)
}

// Content filters: each group comes from the repository that hosts it. Google hosts the Android and AndroidX groups, and Maven Central
// hosts the rest. com.google.* is on both (Central has Guava, ZXing, protobuf and KSP), so both repositories may serve it.
val googleGroups = listOf("androidx(\\..*)?", "com\\.android(\\..*)?", "com\\.google(\\..*)?")
val centralExcludedGroups = listOf("androidx(\\..*)?", "com\\.android(\\..*)?")

pluginManagement {
    repositories {
        // pluginManagement is compiled on its own, so it cannot see the lists below; same filters, inline.
        google {
            content {
                includeGroupByRegex("androidx(\\..*)?")
                includeGroupByRegex("com\\.android(\\..*)?")
                includeGroupByRegex("com\\.google(\\..*)?")
            }
        }
        mavenCentral {
            content {
                excludeGroupByRegex("androidx(\\..*)?")
                excludeGroupByRegex("com\\.android(\\..*)?")
                // The Kotlin plugin markers are on Central and on the Portal, as different files. CI runners were served the
                // Portal's copy (the verification failures name the Portal), so the Portal is the one source for the markers, and
                // verification-metadata.xml pins its bytes. Kotlin's implementation jars still come from Central.
                excludeGroupByRegex("org\\.jetbrains\\.kotlin\\.(jvm|plugin\\..*)")
            }
        }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content { googleGroups.forEach { includeGroupByRegex(it) } }
        }
        mavenCentral {
            content { centralExcludedGroups.forEach { excludeGroupByRegex(it) } }
        }
    }
}

rootProject.name = "Lensly"
include(":app")
include(":lumio")
include(":shared")
include(":benchmark")
include(":tools:registry-pack")
include(":core:model")
include(":core:signer-api")
include(":core:wallet-api")
include(":core:conformance")
include(":chains:algorand:common")
include(":chains:algorand:signer")
include(":chains:algorand:wallet")
include(":chains:solana:common")
include(":chains:solana:signer")

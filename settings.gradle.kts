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

rootProject.name = "MusicRadio"

pluginManagement {
    repositories {
        mavenLocal()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("android.*")
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("android.*")
            }
        }
        mavenCentral()
        // Only kolhalashon-kmp comes from here. Scoped so a JitPack outage or a typo in any other
        // coordinate cannot silently resolve against it.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.abaye123.kolhalashon-kmp") }
        }
    }
}

/**
 * Builds the Kol Halashon client from a working copy next to this repository instead of resolving
 * the published artifact, for when the two are being changed together:
 *
 *     ./gradlew :desktopApp:run -Pkolhalashon.local=true
 *
 * Off by default because that path exists on exactly one machine, and CI has to resolve the real
 * artifact or the build is not reproducible. The substitution is spelled out because the published
 * coordinate (JitPack's com.github.*) and the library's own coordinate (dev.kdroid:kolhalashon) are
 * two names for the same thing, which Gradle cannot work out on its own.
 */
if (providers.gradleProperty("kolhalashon.local").orNull == "true") {
    includeBuild("../../kolhalashon-api/kolhalashon-kmp") {
        dependencySubstitution {
            substitute(module("com.github.abaye123.kolhalashon-kmp:kolhalashon")).using(project(":"))
        }
    }
}

include(":shared")
include(":androidApp")
include(":desktopApp")
include(":webApp")

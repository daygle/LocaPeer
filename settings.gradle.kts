pluginManagement {
    // Kotlin Gradle plugin version, shared by the android/compose/serialization plugins.
    // Defaults to the value in gradle.properties; override with -PkotlinVersion so the
    // CodeQL workflow can compile with a release the CodeQL extractor accepts.
    // (Read via providers rather than the deprecated `by settings` delegate.)
    val kotlinVersion: String =
        providers.gradleProperty("kotlinVersion").getOrElse("2.4.20-RC")

    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("org.jetbrains.kotlin.android") version kotlinVersion
        id("org.jetbrains.kotlin.plugin.compose") version kotlinVersion
        id("org.jetbrains.kotlin.plugin.serialization") version kotlinVersion
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "LocaPeer"
include(":app")

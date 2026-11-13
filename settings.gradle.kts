// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

pluginManagement {
    includeBuild("build-logic")
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
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://pvot-oss.github.io/PvotLib/maven") {
            content { includeGroup("com.prauga.pvot") }
        }
    }
}

rootProject.name = "PvotFinder"

include(":app")

include(":bluetooth")

include(":bluetooth:protocol")

include(":finding")

include(":identity")

include(":noise")

include(":nostr")

include(":pairing")

providers.gradleProperty("pvotLibDir").orNull?.let { includeBuild(it) }

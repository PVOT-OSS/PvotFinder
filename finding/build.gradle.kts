// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

plugins { alias(libs.plugins.kotlin.jvm) }

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":pairing"))
    api(project(":bluetooth:protocol"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

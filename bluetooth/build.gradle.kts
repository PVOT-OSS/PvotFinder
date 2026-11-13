// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

plugins { alias(libs.plugins.finder.android.library) }

android { namespace = "org.prauga.finder.bluetooth" }

dependencies {
    api(project(":bluetooth:protocol"))
    api(project(":pairing"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.kotlinx.coroutines.test)
}

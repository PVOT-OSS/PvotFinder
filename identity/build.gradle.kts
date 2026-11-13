// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

plugins {
    alias(libs.plugins.finder.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android { namespace = "org.prauga.finder.identity" }

room { schemaDirectory("$projectDir/schemas") }

dependencies {
    api(project(":pairing"))
    implementation(libs.room.runtime)
    implementation(libs.kotlinx.coroutines.android)
    ksp(libs.room.compiler)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

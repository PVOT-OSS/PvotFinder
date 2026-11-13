// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}

extra.apply {
    val versionMajor = 0
    val versionMinor = 1
    val versionPatch = 0
    set("versionCode", versionMajor * 10000 + versionMinor * 100 + versionPatch)
    set("versionName", "$versionMajor.$versionMinor.$versionPatch")
}

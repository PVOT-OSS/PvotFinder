// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder

import android.app.Application

class FinderApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

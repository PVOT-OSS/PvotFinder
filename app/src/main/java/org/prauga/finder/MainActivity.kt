// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder

import android.os.Bundle
import com.prauga.pvot.designsystem.platform.PvotBaseActivity
import com.prauga.pvot.designsystem.theme.PvotAppTheme

class MainActivity : PvotBaseActivity() {
    private val container: AppContainer
        get() = (application as FinderApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setPvotContent { PvotAppTheme { FinderNavigation(container) } }
    }

    override fun onStart() {
        super.onStart()
        container.discovery.refreshPermissions()
        container.discovery.setForeground(true)
    }

    override fun onStop() {
        container.discovery.setForeground(false)
        super.onStop()
    }
}

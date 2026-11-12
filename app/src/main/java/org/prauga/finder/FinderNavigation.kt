// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import kotlinx.serialization.Serializable
import org.prauga.finder.device.DeviceScreen
import org.prauga.finder.diagnostics.DiagnosticsScreen
import org.prauga.finder.home.HomeScreen

@Serializable data object HomeKey : NavKey

@Serializable data class DeviceKey(val idHex: String) : NavKey

@Serializable data object DiagnosticsKey : NavKey

@Composable
fun FinderNavigation(container: AppContainer) {
    val backStack = rememberNavBackStack(HomeKey)
    val back: () -> Unit = { backStack.removeLastOrNull() }
    NavDisplay(
        backStack = backStack,
        onBack = back,
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
        entryProvider =
            entryProvider {
                entry<HomeKey> {
                    HomeScreen(
                        discovery = container.discovery,
                        onOpenDevice = { backStack.add(DeviceKey(it.toHex())) },
                        onOpenDiagnostics = { backStack.add(DiagnosticsKey) },
                    )
                }
                entry<DeviceKey> { key -> DeviceScreen(container.discovery, key.idHex, back) }
                entry<DiagnosticsKey> {
                    DiagnosticsScreen(container.discovery, container.diagnostics, back)
                }
            },
    )
}

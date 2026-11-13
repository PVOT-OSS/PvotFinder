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
import org.prauga.finder.pairing.AddPhoneScreen
import org.prauga.finder.pairing.ScanCodeScreen
import org.prauga.finder.pairing.ShowCodeScreen
import org.prauga.finder.peers.PeerScreen

@Serializable data object HomeKey : NavKey

@Serializable data class DeviceKey(val key: String) : NavKey

@Serializable data object DiagnosticsKey : NavKey

@Serializable data object AddPhoneKey : NavKey

@Serializable data object ShowCodeKey : NavKey

@Serializable data object ScanCodeKey : NavKey

@Serializable data class PeerKey(val deviceIdHex: String) : NavKey

@Composable
fun FinderNavigation(container: AppContainer) {
    val backStack = rememberNavBackStack(HomeKey)
    val back: () -> Unit = { backStack.removeLastOrNull() }
    val backHome: () -> Unit = { backStack.retainAll { it == HomeKey } }
    NavDisplay(
        backStack = backStack,
        onBack = back,
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
        entryProvider =
            entryProvider {
                entry<HomeKey> {
                    HomeScreen(
                        discovery = container.discovery,
                        identity = container.identity,
                        onAddPhone = { backStack.add(AddPhoneKey) },
                        onOpenPeer = { backStack.add(PeerKey(it)) },
                        onOpenDevice = { backStack.add(DeviceKey(it.asString())) },
                        onOpenDiagnostics = { backStack.add(DiagnosticsKey) },
                    )
                }
                entry<DeviceKey> { entry ->
                    DeviceScreen(container.discovery, container.identity, entry.key, back)
                }
                entry<DiagnosticsKey> {
                    DiagnosticsScreen(container.discovery, container.diagnostics, back)
                }
                entry<AddPhoneKey> {
                    AddPhoneScreen(
                        onShowCode = { backStack.add(ShowCodeKey) },
                        onScanCode = { backStack.add(ScanCodeKey) },
                        onBack = back,
                    )
                }
                entry<ShowCodeKey> { ShowCodeScreen(container.pairing, backHome, back) }
                entry<ScanCodeKey> { ScanCodeScreen(container.pairing, backHome, back) }
                entry<PeerKey> { key -> PeerScreen(container.identity, key.deviceIdHex, back) }
            },
    )
}

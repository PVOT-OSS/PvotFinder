// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import kotlinx.serialization.Serializable
import org.prauga.finder.diagnostics.DiagnosticsScreen
import org.prauga.finder.home.HomeScreen
import org.prauga.finder.home.HomeViewModel
import org.prauga.finder.pairing.AddPhoneScreen
import org.prauga.finder.pairing.ScanCodeScreen
import org.prauga.finder.pairing.ShowCodeScreen
import org.prauga.finder.peers.PeerScreen
import org.prauga.finder.reports.BoardsScreen
import org.prauga.finder.settings.SettingsScreen
import org.prauga.finder.settings.SettingsViewModel

@Serializable data object HomeKey : NavKey

@Serializable data object SettingsKey : NavKey

@Serializable data object DiagnosticsKey : NavKey

@Serializable data object AddPhoneKey : NavKey

@Serializable data object ShowCodeKey : NavKey

@Serializable data object ScanCodeKey : NavKey

@Serializable data class PeerKey(val deviceIdHex: String) : NavKey

@Serializable data object BoardsKey : NavKey

@Composable
fun FinderNavigation(container: AppContainer) {
    val backStack = rememberNavBackStack(HomeKey)
    val back: () -> Unit = { backStack.removeLastOrNull() }
    val backHome: () -> Unit = { backStack.retainAll { it == HomeKey } }
    NavDisplay(
        backStack = backStack,
        onBack = back,
        entryDecorators =
            listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
        entryProvider =
            entryProvider {
                entry<HomeKey> {
                    val viewModel = viewModel {
                        HomeViewModel(
                            container.discovery,
                            container.findable,
                            container.reportSettings,
                            container.identity,
                            container.finder,
                            container.reportFetcher,
                        )
                    }
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    HomeScreen(
                        state = state,
                        onEvent = viewModel::onEvent,
                        onOpenPhone = { backStack.add(PeerKey(it)) },
                        onAddPhone = { backStack.add(AddPhoneKey) },
                        onOpenSettings = { backStack.add(SettingsKey) },
                    )
                }
                entry<SettingsKey> {
                    val viewModel = viewModel {
                        SettingsViewModel(
                            container.discovery,
                            container.findable,
                            container.reportSettings,
                            container.identity,
                        )
                    }
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    SettingsScreen(
                        state = state,
                        onEvent = viewModel::onEvent,
                        onOpenBoards = { backStack.add(BoardsKey) },
                        onOpenDiagnostics = { backStack.add(DiagnosticsKey) },
                        onBack = back,
                    )
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
                entry<BoardsKey> { BoardsScreen(container.reportSettings, back) }
                entry<PeerKey> { key ->
                    PeerScreen(
                        container.identity,
                        container.discovery,
                        container.finder,
                        container.reportFetcher,
                        key.deviceIdHex,
                        back,
                    )
                }
            },
    )
}

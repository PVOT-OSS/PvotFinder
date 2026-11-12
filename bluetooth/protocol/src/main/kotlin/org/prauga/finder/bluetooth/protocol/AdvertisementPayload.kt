// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.bluetooth.protocol

/** What a device puts in its advertisement's service data. */
data class AdvertisementPayload(
    val temporaryId: TemporaryId,
    val version: Int = FinderProtocol.VERSION,
    val flags: Int = 0,
) {
    init {
        require(version in 0..0xFF) { "Version $version does not fit in one byte" }
        require(flags in 0..0xFF) { "Flags $flags do not fit in one byte" }
    }
}

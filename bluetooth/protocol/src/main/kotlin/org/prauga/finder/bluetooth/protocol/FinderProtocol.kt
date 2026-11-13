// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.bluetooth.protocol

import java.util.UUID

object FinderProtocol {
    /** Service UUID for advertisements and the finding GATT service (development, not SIG-assigned). */
    val SERVICE_UUID: UUID = UUID.fromString("b656c287-0065-424d-932f-3d3a412b8331")

    /** Version 1: the identifier is the rotating one from [RotatingId]. */
    const val VERSION = 1

    /** Flag bit 0: the phone has not heard any of its paired phones for a while. */
    const val FLAG_SEPARATED = 0x01

    /** version (1) + flags (1) + temporary ID. */
    const val PAYLOAD_SIZE = 2 + TemporaryId.SIZE
}

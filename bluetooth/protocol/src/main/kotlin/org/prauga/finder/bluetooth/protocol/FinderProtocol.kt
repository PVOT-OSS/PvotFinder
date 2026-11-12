// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.bluetooth.protocol

import java.util.UUID

object FinderProtocol {
    /** Development service UUID; version 0 payloads are for testing only, not the secure protocol. */
    val SERVICE_UUID: UUID = UUID.fromString("b656c287-0065-424d-932f-3d3a412b8331")

    const val VERSION = 0

    /** version (1) + flags (1) + temporary ID. */
    const val PAYLOAD_SIZE = 2 + TemporaryId.SIZE
}

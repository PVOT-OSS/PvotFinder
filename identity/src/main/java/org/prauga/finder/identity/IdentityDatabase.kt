// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.identity

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "self")
class SelfEntity(
    @PrimaryKey val id: Int = 0,
    @ColumnInfo(name = "device_id") val deviceId: ByteArray,
    val name: String,
    @ColumnInfo(name = "beacon_secret") val sealedBeaconSecret: ByteArray,
    @ColumnInfo(name = "created_at") val createdAtMillis: Long,
)

@Entity(tableName = "peers")
class PeerEntity(
    @PrimaryKey @ColumnInfo(name = "device_id") val deviceIdHex: String,
    val name: String,
    @ColumnInfo(name = "app_version") val appVersion: String,
    @ColumnInfo(name = "static_key") val staticPublicKey: ByteArray,
    @ColumnInfo(name = "beacon_secret") val sealedBeaconSecret: ByteArray,
    @ColumnInfo(name = "paired_at") val pairedAtMillis: Long,
)

@Dao
interface IdentityDao {
    @Query("SELECT * FROM self WHERE id = 0") suspend fun self(): SelfEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSelf(self: SelfEntity)

    @Query("UPDATE self SET name = :name WHERE id = 0") suspend fun renameSelf(name: String)

    @Query("SELECT * FROM peers ORDER BY paired_at") fun peers(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE device_id = :deviceIdHex")
    suspend fun peer(deviceIdHex: String): PeerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putPeer(peer: PeerEntity)

    @Query("UPDATE peers SET name = :name WHERE device_id = :deviceIdHex")
    suspend fun renamePeer(deviceIdHex: String, name: String)

    @Query("DELETE FROM peers WHERE device_id = :deviceIdHex")
    suspend fun removePeer(deviceIdHex: String)

    @Query("DELETE FROM peers") suspend fun removeAllPeers()

    @Query("DELETE FROM self") suspend fun removeSelf()
}

@Database(entities = [SelfEntity::class, PeerEntity::class], version = 1)
abstract class IdentityDatabase : RoomDatabase() {
    abstract fun dao(): IdentityDao

    companion object {
        fun create(context: Context): IdentityDatabase =
            Room.databaseBuilder(context, IdentityDatabase::class.java, "identity.db").build()

        fun inMemory(context: Context): IdentityDatabase =
            Room.inMemoryDatabaseBuilder(context, IdentityDatabase::class.java).build()
    }
}

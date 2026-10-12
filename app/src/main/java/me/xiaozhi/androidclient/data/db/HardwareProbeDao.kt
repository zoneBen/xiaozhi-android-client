package me.xiaozhi.androidclient.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface HardwareProbeDao {
    @Query("SELECT * FROM hardware_probe WHERE id = ${HardwareProbeEntity.SINGLETON_ID}")
    suspend fun get(): HardwareProbeEntity?

    /** 单行、固定主键，所以 REPLACE 就是 upsert，不用先查再决定插还是改。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(probe: HardwareProbeEntity)

    @Query("DELETE FROM hardware_probe")
    suspend fun clear()
}

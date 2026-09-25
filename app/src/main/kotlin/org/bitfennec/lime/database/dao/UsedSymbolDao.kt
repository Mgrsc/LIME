package org.bitfennec.lime.database.dao

import androidx.room.Dao
import androidx.room.Query
import org.bitfennec.lime.database.BaseDao
import org.bitfennec.lime.database.entity.UsedSymbol

@Dao
interface UsedSymbolDao : BaseDao<UsedSymbol> {
    @Query("select * from usedSymbol where type = 'symbol' ORDER BY time DESC")
    suspend fun getAllUsedSymbol(): List<UsedSymbol>

    @Query("select * from usedSymbol where type = 'emoji' ORDER BY time DESC")
    suspend fun getAllSymbolEmoji(): List<UsedSymbol>

    @Query("SELECT COUNT(*) FROM usedSymbol where type = :type")
    suspend fun getCount(type: String): Int

    @Query("DELETE FROM usedSymbol WHERE symbol IN ( SELECT symbol FROM usedSymbol WHERE type = :type ORDER BY time ASC LIMIT :overflow)")
    suspend fun deleteOldest(type: String, overflow: Int)

    @Query("DELETE FROM usedSymbol")
    suspend fun deleteAll()
}

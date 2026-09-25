package org.bitfennec.lime.database.dao

import androidx.room.Dao
import androidx.room.Query
import org.bitfennec.lime.database.BaseDao
import org.bitfennec.lime.database.entity.SideSymbol

@Dao
interface SideSymbolDao : BaseDao<SideSymbol> {
    @Query("select * from side_symbol where symbolKey = :key AND type = :type")
    suspend fun getByKey(key: String, type: String = "pinyin"): SideSymbol?

    @Query("select * from side_symbol where type = 'number'")
    suspend fun getAllSideSymbolNumber(): List<SideSymbol>

    @Query("select * from side_symbol where type = 'pinyin'")
    suspend fun getAllSideSymbolPinyin(): List<SideSymbol>

    @Query("delete from side_symbol where symbolKey = :key AND type = :type")
    suspend fun deleteByKey(key: String, type: String = "pinyin")

    @Query("delete from side_symbol where type = :type")
    suspend fun deleteAll(type: String = "pinyin")

    @Query("update side_symbol set symbolValue =:value where symbolKey =:key AND type = :type")
    suspend fun updateSymbol(key: String, value: String, type: String = "pinyin")
}

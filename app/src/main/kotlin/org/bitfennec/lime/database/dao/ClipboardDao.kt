package org.bitfennec.lime.database.dao

import androidx.room.Dao
import androidx.room.Query
import org.bitfennec.lime.database.BaseDao
import org.bitfennec.lime.database.entity.Clipboard

@Dao
interface ClipboardDao : BaseDao<Clipboard> {

    @Query("select * from clipboard ORDER BY isKeep DESC, time DESC")
    fun getAllFlow(): kotlinx.coroutines.flow.Flow<List<Clipboard>>

    @Query("select * from clipboard ORDER BY isKeep DESC, time DESC")
    suspend fun getAll(): List<Clipboard>

    @Query("SELECT * FROM clipboard WHERE content LIKE '%' || :query || '%' ORDER BY isKeep DESC, time DESC")
    suspend fun search(query: String): List<Clipboard>

    @Query("delete from clipboard where content = :content")
    suspend fun deleteByContent(content: String)

    @Query("delete from clipboard")
    suspend fun deleteAll()

    @Query("SELECT * FROM clipboard WHERE content = :content LIMIT 1")
    suspend fun getByContent(content: String): Clipboard?

    @Query("SELECT COUNT(*) FROM clipboard")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM clipboard WHERE isKeep = 0")
    suspend fun getUnpinnedCount(): Int

    @Query("DELETE FROM clipboard WHERE isKeep = 0 AND content IN ( SELECT content FROM clipboard WHERE isKeep = 0 ORDER BY time ASC LIMIT :overflow)")
    suspend fun deleteOldest(overflow: Int)

    @Query("DELETE FROM clipboard WHERE isKeep = 0")
    suspend fun deleteAllExceptKeep()
}

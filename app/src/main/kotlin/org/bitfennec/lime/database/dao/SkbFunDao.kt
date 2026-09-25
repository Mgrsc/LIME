package org.bitfennec.lime.database.dao

import androidx.room.Dao
import androidx.room.Query
import org.bitfennec.lime.database.BaseDao
import org.bitfennec.lime.database.entity.SkbFun

@Dao
interface SkbFunDao : BaseDao<SkbFun> {
    @Query("select * from skbfun  where isKeep = 0 ORDER BY position ASC")
    suspend fun getAllMenu(): List<SkbFun>

    @Query("select * from skbfun where isKeep = 1 ORDER BY position ASC")
    suspend fun getALlBarMenu(): List<SkbFun>

    @Query("select * from skbfun where name = :name AND isKeep = 1")
    suspend fun getBarMenu(name: String): SkbFun?

    @Query("DELETE FROM skbfun WHERE name = :name AND isKeep = 1")
    suspend fun deleteBarMenu(name: String)

    @Query("DELETE FROM skbfun WHERE isKeep = 1")
    suspend fun deleteAllBarMenus()

    @Query("DELETE FROM skbfun WHERE name = :name")
    suspend fun deleteByName(name: String)

    @Query("delete from skbfun")
    suspend fun deleteAll()
}

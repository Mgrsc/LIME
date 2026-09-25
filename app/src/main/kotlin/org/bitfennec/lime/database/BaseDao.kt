package org.bitfennec.lime.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Update

@Dao
interface BaseDao<T> {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bean: T)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(bean: List<T>)

    @Delete
    suspend fun delete(bean: T)

    @Update
    suspend fun update(bean: T)
}

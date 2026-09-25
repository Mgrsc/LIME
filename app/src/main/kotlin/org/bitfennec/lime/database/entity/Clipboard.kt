package org.bitfennec.lime.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "clipboard")
data class Clipboard(
    @PrimaryKey
    @ColumnInfo(name = "content")
    var content: String,
    @ColumnInfo(name = "isKeep")
    val isKeep: Int = 0,
    @ColumnInfo(name = "time")
    val time: Long = System.currentTimeMillis(),
)
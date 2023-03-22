package com.google.ar.core.examples.java.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

@Entity
data class Node(
        @PrimaryKey(autoGenerate=true) val id: Int,
        @ColumnInfo(name = "x") val x: Float,
        @ColumnInfo(name = "y") val y: Float,
        @ColumnInfo(name = "z") val z: Float,
        @ColumnInfo(name = "name") val name: String?, //A null value in this field means it's a "walkable" node.
        @ColumnInfo(name = "adj") val adj: MutableSet<Node>?
)

class Converters {
        @TypeConverter
        fun fromNodeList(value: List<Node>): String {
                return Gson().toJson(value)
        }

        @TypeConverter
        fun toNodeList(value: String): List<Node> {
                val type = object : TypeToken<List<Node>>() {}.type
                return Gson().fromJson(value, type)
        }
}
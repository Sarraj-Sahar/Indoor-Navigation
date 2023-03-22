package com.google.ar.core.examples.java.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class Node(
        @PrimaryKey(autoGenerate=true) val id: Int,
        @ColumnInfo(name = "x") val x: Float,
        @ColumnInfo(name = "y") val y: Float,
        @ColumnInfo(name = "z") val z: Float,
        @ColumnInfo(name = "name") val name: String?,
        @ColumnInfo(name = "adj") val adj: List<Node>?
)
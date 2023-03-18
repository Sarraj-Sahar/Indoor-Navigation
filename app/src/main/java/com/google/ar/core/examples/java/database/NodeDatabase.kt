package com.google.ar.core.examples.java.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [Node::class], version = 1)
abstract class NodeDatabase : RoomDatabase() {
    abstract fun nodeDao(): NodeDao
}
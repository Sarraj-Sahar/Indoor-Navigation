package com.google.ar.core.examples.java.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [Node::class], version = 1)
@TypeConverters(Converters::class)

abstract class NodeDatabase : RoomDatabase() {
    abstract fun nodeDao(): NodeDao
}
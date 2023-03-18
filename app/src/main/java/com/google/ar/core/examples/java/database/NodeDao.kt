package com.google.ar.core.examples.java.database

import androidx.room.*

@Dao
interface NodeDao {

    @Insert
    fun insert(node: Node)

    @Update
    fun update(node: Node)

    @Delete
    fun delete(node: Node)

    @Query("delete from Node")
    fun deleteAll()

    @Query("select * from Node")
    fun getAll():List<Node>
}
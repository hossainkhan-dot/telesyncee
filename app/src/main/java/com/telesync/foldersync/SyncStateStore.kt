package com.telesync.foldersync

import android.content.Context
import org.json.JSONObject
import java.io.File

class SyncStateStore(private val context: Context) {

    private val file = File(context.filesDir, "sync_state.json")

    fun load(): JSONObject {
        return if (file.exists()) {
            try { JSONObject(file.readText()) } catch (e: Exception) { JSONObject() }
        } else JSONObject()
    }

    fun save(state: JSONObject) {
        file.writeText(state.toString())
    }
}

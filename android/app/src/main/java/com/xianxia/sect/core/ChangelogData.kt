package com.xianxia.sect.core

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStreamReader

@Serializable
data class ChangelogEntry(
    val version: String,
    val date: String,
    val changes: List<String>
)

object ChangelogData {

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cachedEntries: List<ChangelogEntry>? = null

    val entries: List<ChangelogEntry>
        get() = cachedEntries ?: emptyList()

    fun initialize(context: Context) {
        if (cachedEntries != null) return
        synchronized(this) {
            if (cachedEntries != null) return
            cachedEntries = loadEntries(context)
        }
    }

    private fun loadEntries(context: Context): List<ChangelogEntry> {
        return try {
            val inputStream = context.assets.open("changelog_entries.json")
            val reader = InputStreamReader(inputStream, "UTF-8")
            val jsonString = reader.readText()
            reader.close()
            json.decodeFromString<List<ChangelogEntry>>(jsonString)
        } catch (e: Exception) {
            emptyList()
        }
    }
}

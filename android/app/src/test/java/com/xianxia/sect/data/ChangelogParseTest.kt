package com.xianxia.sect.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChangelogParseTest {
    @Test
    fun parseAssetJson() {
        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
        val file = File("src/main/assets/changelog_entries.json")
        println("Reading changelog from absolute path: ${file.absolutePath}")
        val text = file.readText()
        val entries = json.decodeFromString<List<ChangelogEntry>>(text)
        println("Parsed ${entries.size} changelog entries")
        assertTrue("Changelog entries should not be empty", entries.isNotEmpty())
    }
}

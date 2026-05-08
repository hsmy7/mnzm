package com.xianxia.sect.core

data class ChangelogEntry(
    val version: String,
    val date: String,
    val changes: List<String>
)

object ChangelogData {
    val entries: List<ChangelogEntry> = listOf(
        ChangelogEntry(
            version = "3.0.01",
            date = "2026-05-08",
            changes = listOf("初始灵石从1000提升至2000")
        )
    )
}

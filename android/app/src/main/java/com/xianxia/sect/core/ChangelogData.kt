package com.xianxia.sect.core

data class ChangelogEntry(
    val version: String,
    val date: String,
    val changes: List<String>
)

object ChangelogData {
    val entries: List<ChangelogEntry> = listOf(
        ChangelogEntry(
            version = "3.0.03",
            date = "2026-05-08",
            changes = listOf(
                "灵矿场界面改为单矿场视图：点击某座灵矿场只显示该矿场的3个槽位",
                "一键任命仅填充当前查看矿场的槽位，不影响其他矿场",
                "修复灵矿场对话框宽度，与其他半屏弹窗保持一致"
            )
        ),
        ChangelogEntry(
            version = "3.0.02",
            date = "2026-05-08",
            changes = listOf(
                "灵矿场重构：移除扩建功能，每座灵矿场固定3个矿工槽位",
                "灵矿场可建造数量从1座提升至8座，可在宗门地图上建造多座灵矿场",
                "灵矿执事保持不变，2个执事槽位对所有灵矿场同时生效"
            )
        ),
        ChangelogEntry(
            version = "3.0.01",
            date = "2026-05-08",
            changes = listOf("初始灵石从1000提升至2000")
        )
    )
}

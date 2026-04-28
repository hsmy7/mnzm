package com.xianxia.sect.core

data class ChangelogEntry(
    val version: String,
    val date: String,
    val changes: List<String>
)

object ChangelogData {
    val entries: List<ChangelogEntry> = listOf(
        ChangelogEntry(
            version = "2.6.0",
            date = "2026-04-29",
            changes = listOf(
                "世界地图支持多支战斗队伍，宗门上方显示队伍名称徽章",
                "队伍支持查看、移动、进攻、解散四种操作",
                "移动可前往玩家宗门和已占领宗门，进攻可攻击非玩家宗门",
                "解散队伍时队伍先返回宗门再解散，队伍编号自动复用",
                "修复执法堂弟子选择界面不显示空闲内门弟子的问题",
                "修复药园/炼丹/锻造弟子和储备弟子状态显示不正确的问题",
                "修复存档加载后弟子状态可能不正确的问题",
                "统一所有弟子选择卡片为三行横向布局",
                "建筑选择界面增加对应属性加成显示",
                "境界筛选统一为三行布局",
                "灵矿场选择界面增加灵根和属性筛选",
                "设置界面新增更新日志功能"
            )
        ),
        ChangelogEntry(
            version = "2.5.13",
            date = "2026-04-15",
            changes = listOf(
                "优化世界地图宗门渲染性能",
                "修复战斗中弟子状态同步异常的问题",
                "修复存档加载后弟子状态可能不正确的问题"
            )
        ),
        ChangelogEntry(
            version = "2.5.12",
            date = "2026-04-01",
            changes = listOf(
                "新增执法堂系统，支持长老、执法弟子和储备弟子",
                "新增灵矿场执事和采矿系统",
                "优化弟子筛选界面，增加灵根和属性过滤"
            )
        )
    )
}

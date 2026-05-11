package com.xianxia.sect.core

data class ChangelogEntry(
    val version: String,
    val date: String,
    val changes: List<String>
)

object ChangelogData {
    val entries: List<ChangelogEntry> = listOf(
        ChangelogEntry(
            version = "3.0.00",
            date = "2026-05-11",
            changes = listOf("全新版本")
        ),
        ChangelogEntry(
            version = "3.0.08",
            date = "2026-05-10",
            changes = listOf(
                "新增男女弟子半身像池：男弟子7张、女弟子8张，创建时随机分配人物立绘",
                "建造按钮和仓库按钮替换为新版美术素材",
                "商人、招募、外交及全部11个建筑界面改为全屏显示"
            )
        ),
        ChangelogEntry(
            version = "3.0.07",
            date = "2026-05-09",
            changes = listOf(
                "全界面横屏适配：23个弹窗统一尺寸和标题栏样式",
                "弟子详情界面重新设计：左侧人物立绘+右侧标签页切换",
                "建筑材料界面迁移至横屏弹窗"
            )
        ),
        ChangelogEntry(
            version = "3.0.06",
            date = "2026-05-09",
            changes = listOf(
                "游戏由竖屏改为横屏：全屏宗门地图左右分列悬浮按钮",
                "弟子列表、仓库、设置改为全屏弹窗，操作更直观",
                "界面背景替换为横屏素材，视觉更适配"
            )
        ),
        ChangelogEntry(
            version = "3.0.05",
            date = "2026-05-09",
            changes = listOf(
                "移除宗门消息系统：底部消息栏、事件日志弹窗均已移除",
                "简化总览界面布局，减少不必要的消息提示",
                "战斗日志和战斗回合消息保留不变"
            )
        ),
        ChangelogEntry(
            version = "3.0.03",
            date = "2026-05-09",
            changes = listOf(
                "优化建造栏卡片布局：提高卡片高度，建筑名称和价格不再压在素材图片上",
                "建造栏两行卡片恰好占满可视区域，无需滚动即可浏览全部建筑"
            )
        ),
        ChangelogEntry(
            version = "3.0.02",
            date = "2026-05-08",
            changes = listOf(
                "灵矿场重构：移除扩建功能，每座灵矿场固定3个矿工槽位",
                "灵矿场可建造数量从1座提升至8座，可在宗门地图上建造多座灵矿场",
                "灵矿场界面改为单矿场视图：点击某座灵矿场只显示该矿场的3个槽位",
                "一键任命仅填充当前查看矿场的槽位，不影响其他矿场",
                "灵矿执事保持不变，2个执事槽位对所有灵矿场同时生效"
            )
        ),
        ChangelogEntry(
            version = "3.0.04",
            date = "2026-05-09",
            changes = listOf(
                "世界地图关卡系统上线：妖兽与洞府合并为统一关卡池，每月随机生成0~3个关卡",
                "妖兽与洞府在世界地图上显示专属美术素材，点击即可查看详情并派遣弟子挑战",
                "新增弟子槽位系统：8个正方形槽位，支持一键任命、卸任、更换弟子",
                "洞府守护兽登场：每个洞府由2只修仙风格命名的守护兽驻守，击败可获得丰厚灵石与功法装备丹药奖励",
                "妖兽关卡可重复挑战直至胜利或3年后自动消失，战斗失败无惩罚",
                "修复世界地图妖兽概率显示为洞府美术素材的问题",
                "洞府信息界面优化：新增独立洞府名称显示在守护兽名称上方",
                "关卡境界显示改为只显示大境界，不再显示小层（实际战斗中每个妖兽/守护兽小层独立随机）",
                "关卡生成规则完善：增加关卡间最小距离约束，防止重叠生成"
            )
        ),
        ChangelogEntry(
            version = "3.0.01",
            date = "2026-05-08",
            changes = listOf("初始灵石从1000提升至2000")
        )
    )
}

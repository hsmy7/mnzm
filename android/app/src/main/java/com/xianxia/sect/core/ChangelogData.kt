package com.xianxia.sect.core

data class ChangelogEntry(
    val version: String,
    val date: String,
    val changes: List<String>
)

object ChangelogData {
    val entries: List<ChangelogEntry> = listOf(
        ChangelogEntry(
            version = "3.0.25",
            date = "2026-05-15",
            changes = listOf(
                "建造栏每个建筑卡片下方显示当前建造数量/最大数量"
            )
        ),
        ChangelogEntry(
            version = "3.0.24",
            date = "2026-05-15",
            changes = listOf(
                "补MIGRATION_2_3空迁移，防止从v3.0.22中间版本升级崩溃"
            )
        ),
        ChangelogEntry(
            version = "3.0.23",
            date = "2026-05-15",
            changes = listOf(
                "修复从3.0.20升级后旧存档全部丢失的问题，新增数据库迁移和文件备份系统"
            )
        ),
        ChangelogEntry(
            version = "3.0.22",
            date = "2026-05-15",
            changes = listOf(
                "炼丹炉和锻造坊可建造7座，每座1个生产槽位，需分配弟子上岗才能开始生产",
                "生产中弟子被卸任或死亡时进度自动冻结，重新分配弟子后恢复",
                "炼丹长老和锻造长老移至天枢殿，放在副宗主下方统一管理",
                "自动炼丹/锻造改为每个槽位独立开关"
            )
        ),
        ChangelogEntry(
            version = "3.0.21",
            date = "2026-05-14",
            changes = listOf(
                "接入TapDB游戏时长追踪，后台可查看用户活跃时长数据"
            )
        ),
        ChangelogEntry(
            version = "3.0.20",
            date = "2026-05-14",
            changes = listOf(
                "宗门详情界面和进攻妖兽/洞府界面改为半屏弹窗，世界地图背景可见",
                "修复宗门详情界面和进攻弟子选择界面按钮大小不符合标准（72×38）的问题"
            )
        ),
        ChangelogEntry(
            version = "3.0.19",
            date = "2026-05-14",
            changes = listOf(
                "宗门战争系统全面重构：移除战斗队伍，改为宗门信息界面直接进攻；占领后驻守槽位任命弟子；防守仅驻守弟子出战；AI战斗即时结算不再行军",
                "进攻弟子选择界面：10槽位2行5列方形槽位，卸任/更换按钮，点击弟子弹出详细信息",
                "玩家主宗被攻击自动选10名在宗门内最高境界弟子防守，占领宗门仅驻守弟子防守",
                "AI占领宗门每月自动补全驻守弟子至10人，移除路线连通限制"
            )
        ),
        ChangelogEntry(
            version = "3.0.18",
            date = "2026-05-14",
            changes = listOf(
                "主界面功能按钮和宗门信息向内收敛，避免被手机前置摄像头遮挡"
            )
        ),
        ChangelogEntry(
            version = "3.0.17",
            date = "2026-05-14",
            changes = listOf(
                "修复游戏平台启动时全屏界面左右仍有缝隙的问题，全屏弹窗改为Box叠加层避免平台对Dialog窗口的重定位"
            )
        ),
        ChangelogEntry(
            version = "3.0.16",
            date = "2026-05-13",
            changes = listOf(
                "修复妖兽关卡任命弟子后进攻按钮无法点击的问题"
            )
        ),
        ChangelogEntry(
            version = "3.0.15",
            date = "2026-05-13",
            changes = listOf(
                "弟子选择界面筛选栏上方不再显示多行条件文本，改为仅在无符合条件弟子时显示，释放列表空间"
            )
        ),
        ChangelogEntry(
            version = "3.0.14",
            date = "2026-05-13",
            changes = listOf(
                "统一所有弟子选择界面的筛选栏为灵根、属性、境界三按钮下拉式，操作更便捷一致"
            )
        ),
        ChangelogEntry(
            version = "3.0.13",
            date = "2026-05-13",
            changes = listOf(
                "统一所有半屏弹窗为85%宽×78%高标准尺寸，修复大小不一问题",
                "补全所有弹窗的背景图，修复部分弹窗背景透明或白色的问题",
                "主要功能界面（炼丹/锻造/招募/背包/行商/宗门外交等）保持全屏显示",
                "半屏弹窗后方可见游戏画面，不再遮挡背景"
            )
        ),
        ChangelogEntry(
            version = "3.0.12",
            date = "2026-05-13",
            changes = listOf(
                "修复全屏弹窗（弟子/仓库/设置/世界地图/招募/炼丹/锻造/药园等）在部分手机上左右两侧有空隙的问题，统一配置 Dialog 窗口边距"
            )
        ),
        ChangelogEntry(
            version = "3.0.11",
            date = "2026-05-12",
            changes = listOf(
                "修复弟子卡片弹窗未全屏显示、上方仍可见筛选栏的问题",
                "修复招募界面所有弟子卡片显示同一张半身图的问题，每年刷新招募列表时正确分配随机肖像"
            )
        ),
        ChangelogEntry(
            version = "3.0.10",
            date = "2026-05-12",
            changes = listOf(
                "弟子卡牌天赋区域固定为两行高度，解决天赋数量不同的弟子卡片大小不一致问题",
                "新增大量男女弟子半身图（男弟子20张、女弟子17张）"
            )
        ),
        ChangelogEntry(
            version = "3.0.08",
            date = "2026-05-12",
            changes = listOf(
                "修复全屏界面（弟子列表/仓库/设置/炼丹/锻造/药园/藏经阁等）左右有缝隙、背景不贴边的问题，将全屏弹窗改为游戏内叠加层"
            )
        ),
        ChangelogEntry(
            version = "3.0.07",
            date = "2026-05-12",
            changes = listOf(
                "仓库界面标题栏与筛选栏合并，移除重复的仓库文字，筛选按钮修复为标准大小"
            )
        ),
        ChangelogEntry(
            version = "3.0.06",
            date = "2026-05-12",
            changes = listOf(
                "所有界面的弟子卡片全部统一为左侧半身像+右侧多行信息设计，新增第4-5行显示弟子天赋",
                "招募、执法堂、思过崖、问道塔/青云塔等所有弟子列表统一使用新卡片，移除界面各自的定制卡片实现"
            )
        ),
        ChangelogEntry(
            version = "3.0.05",
            date = "2026-05-12",
            changes = listOf(
                "修复读档后加载进度条100%卡住无法进入游戏的问题",
                "修复新游戏保存后存档卡显示0弟子、读档后弟子全部丢失的问题"
            )
        ),
        ChangelogEntry(
            version = "3.0.03",
            date = "2026-05-12",
            changes = listOf(
                "移除内置开发用兑换码，清理未使用的兑换码配置代码",
                "神魂系统重做：神魂改为突破率加成（每10点+1%，最多+10%），适用于所有境界突破；弟子初始神魂为0"
            )
        ),
        ChangelogEntry(
            version = "3.0.02",
            date = "2026-05-12",
            changes = listOf(
                "弟子卡片全部统一为左侧半身像+右侧信息的设计，外门大比、任务大厅、联盟外交等所有弟子选择界面均已统一"
            )
        ),
        ChangelogEntry(
            version = "3.0.01",
            date = "2026-05-12",
            changes = listOf(
                "修复新游戏开始时保存失败导致加载界面卡在0%无法进入的问题",
                "数据库异常时自动重建，避免因数据损坏导致无法启动"
            )
        ),
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

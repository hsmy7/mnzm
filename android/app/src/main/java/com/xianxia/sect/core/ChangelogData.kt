package com.xianxia.sect.core

data class ChangelogEntry(
    val version: String,
    val date: String,
    val changes: List<String>
)

object ChangelogData {
    val entries: List<ChangelogEntry> = listOf(
        ChangelogEntry(
            version = "2.6.10",
            date = "2026-05-01",
            changes = listOf(
                "洞府守护兽统一使用秘境妖兽战斗属性计算公式（三处妖兽属性计算一致）",
                "洞府守护兽补充技能配置（此前为emptyList）"
            )
        ),
        ChangelogEntry(
            version = "2.6.09",
            date = "2026-05-01",
            changes = listOf(
                "修正妖兽属性预计算表数值，与运行时代码实际倍率保持一致",
                "简化createBeast()计算逻辑，移除运行时固定倍率和beastAdvantage常量",
                "妖兽方差计算从6个独立变量减少到4个"
            )
        ),
        ChangelogEntry(
            version = "2.6.08",
            date = "2026-05-01",
            changes = listOf(
                "新增弟子自动穿戴宗门仓库装备功能（装备栏标题右侧勾选框）",
                "新增弟子自动学习宗门仓库功法功能（功法栏标题右侧勾选框）",
                "自动穿戴/学习受境界限制，优先高品阶，锁定物品不可自动穿戴/学习",
                "自动穿戴/学习仅填充空闲槽位，不替换已有装备/功法",
                "多弟子竞争同一仓库物品时，已关注弟子和高境界弟子优先"
            )
        ),
        ChangelogEntry(
            version = "2.6.07",
            date = "2026-05-01",
            changes = listOf(
                "弟子每日血量/灵力恢复量从1%提升至5%",
                "修复秘境队伍成员始终显示满血的问题",
                "修复DiscipleAggregate.maxHp不包含丹药/境界加成的问题",
                "每日事件处理添加异常隔离，避免单个事件异常导致后续事件（如血量恢复）被跳过"
            )
        ),
        ChangelogEntry(
            version = "2.6.06",
            date = "2026-04-30",
            changes = listOf(
                "修复功法和装备的血量加成在弟子详情界面不显示的问题（实际战斗中已生效，仅显示遗漏）"
            )
        ),
        ChangelogEntry(
            version = "2.6.05",
            date = "2026-04-30",
            changes = listOf(
                "修复世界地图探查和游说弟子选择界面不显示空闲弟子的问题"
            )
        ),
        ChangelogEntry(
            version = "2.6.04",
            date = "2026-04-30",
            changes = listOf(
                "修复序列化bug导致建筑生产槽位数据在存档/读档时丢失的问题"
            )
        ),
        ChangelogEntry(
            version = "2.6.03",
            date = "2026-04-29",
            changes = listOf(
                "新增自动招募功能，可按灵根种类筛选自动招募弟子",
                "统一所有建筑弟子选择界面的筛选组件，修复执法堂/灵药宛/灵矿场/任务阁/秘境等筛选缺失或重复问题"
            )
        ),
        ChangelogEntry(
            version = "2.6.02",
            date = "2026-04-29",
            changes = listOf(
                "副宗主选择条件与其他建筑统一：空闲内门弟子即可，不再要求必须已是长老"
            )
        ),
        ChangelogEntry(
            version = "2.6.01",
            date = "2026-04-29",
            changes = listOf(
                "彻底修复执法堂弟子选择不显示空闲内门弟子（替换可疑扩展委托为直接判断）",
                "天枢殿副宗主选择界面增加灵根/属性/境界筛选（此前完全缺失）"
            )
        ),
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
            version = "2.5.96",
            date = "2026-04-28",
            changes = listOf(
                "数据库迁移修复：防止旧存档全部为空",
                "新增MIGRATION_19_20安全恢复迁移",
                "属性筛选按钮新增采矿选项"
            )
        ),
        ChangelogEntry(
            version = "2.5.95",
            date = "2026-04-28",
            changes = listOf(
                "新增储物袋没收功能",
                "新增矿工说明按钮和采矿天赋地脉感应"
            )
        ),
        ChangelogEntry(
            version = "2.5.94",
            date = "2026-04-28",
            changes = listOf(
                "新增挖矿系统和灵矿场改造",
                "修复气血条/修炼进度条数值不居中",
                "修复锻造装备属性全为0的问题",
                "全境界弟子基础HP+30%"
            )
        ),
        ChangelogEntry(
            version = "2.5.93",
            date = "2026-04-28",
            changes = listOf(
                "各境界战斗属性+30%（速度不变）",
                "移除废弃的雷劫系统"
            )
        ),
        ChangelogEntry(
            version = "2.5.84",
            date = "2026-04-27",
            changes = listOf(
                "修复锻造装备属性为零",
                "旧存档生产系统fallback初始化",
                "移除复活逻辑"
            )
        ),
        ChangelogEntry(
            version = "2.5.83",
            date = "2026-04-27",
            changes = listOf(
                "修复丹药治疗/灵力恢复无效",
                "移除每日恢复限制"
            )
        ),
        ChangelogEntry(
            version = "2.5.79",
            date = "2026-04-27",
            changes = listOf(
                "存档系统稳健性修复：防止槽位全部消失",
                "数据库schema修复：回滚未完成的GameData拆分",
                "错误处理改进"
            )
        ),
        ChangelogEntry(
            version = "2.5.77",
            date = "2026-04-27",
            changes = listOf(
                "修复旧存档丢失与新建游戏不运行（isPaused竞态）",
                "修复加载进度残留问题"
            )
        ),
        ChangelogEntry(
            version = "2.5.76",
            date = "2026-04-27",
            changes = listOf(
                "战斗系统境界差距系数修复（gap符号纠正）"
            )
        ),
        ChangelogEntry(
            version = "2.5.73",
            date = "2026-04-27",
            changes = listOf(
                "Disciple双模型迁移Phase 1-2",
                "消除循环依赖、收敛写路径"
            )
        ),
        ChangelogEntry(
            version = "2.5.72",
            date = "2026-04-27",
            changes = listOf(
                "错误类型系统统一（AppError三层体系）",
                "新增6个Domain分类"
            )
        ),
        ChangelogEntry(
            version = "2.5.69",
            date = "2026-04-27",
            changes = listOf(
                "StorageEngine拆分重构为5个职责单一类"
            )
        ),
        ChangelogEntry(
            version = "2.5.68",
            date = "2026-04-27",
            changes = listOf(
                "性能监控统一到UnifiedPerformanceMonitor"
            )
        ),
        ChangelogEntry(
            version = "2.5.67",
            date = "2026-04-27",
            changes = listOf(
                "测试修复：SaveCryptoTest/InventorySystemTest"
            )
        ),
        ChangelogEntry(
            version = "2.5.66",
            date = "2026-04-27",
            changes = listOf(
                "代码质量P0/P1修复",
                "提取ElderManagementUseCase等3个UseCase",
                "提取魔法数字为GameConfig常量"
            )
        ),
        ChangelogEntry(
            version = "2.5.65",
            date = "2026-04-27",
            changes = listOf(
                "代码质量P1完整修复",
                "9个独立CoroutineScope统一到ApplicationScopeProvider",
                "提取BaseViewModel统一错误处理"
            )
        ),
        ChangelogEntry(
            version = "2.5.64",
            date = "2026-04-27",
            changes = listOf(
                "代码质量P2完整修复",
                "提取TimeProgressUtil等工具类",
                "删除重复代码"
            )
        ),
        ChangelogEntry(
            version = "2.5.45",
            date = "2026-04-27",
            changes = listOf(
                "P0关键修复：完整性校验三重缺陷",
                "MainGameScreen拆分（8709行→860行+10模块）",
                "线程安全修复"
            )
        ),
        ChangelogEntry(
            version = "2.5.44",
            date = "2026-04-26",
            changes = listOf(
                "修复仓库战利品计算key冲突",
                "AI洞府探索成功事件通知"
            )
        ),
        ChangelogEntry(
            version = "2.5.43",
            date = "2026-04-26",
            changes = listOf(
                "修复InventorySystem.clear()空操作",
                "修复derived StateFlow过期数据问题"
            )
        ),
        ChangelogEntry(
            version = "2.5.42",
            date = "2026-04-26",
            changes = listOf(
                "区分玩家与AI宗门仓库设计",
                "战利品改为发放到宗门仓库"
            )
        ),
        ChangelogEntry(
            version = "2.5.40",
            date = "2026-04-26",
            changes = listOf(
                "月度外交事件修复：条件检查完善",
                "盟友协作事件触发条件修复"
            )
        ),
        ChangelogEntry(
            version = "2.5.39",
            date = "2026-04-26",
            changes = listOf(
                "修复AI占领逻辑多个边界条件",
                "驻守队伍全灭时状态清理"
            )
        ),
        ChangelogEntry(
            version = "2.5.38",
            date = "2026-04-26",
            changes = listOf(
                "召回驻守队伍状态清理修复",
                "新增游戏失败机制"
            )
        ),
        ChangelogEntry(
            version = "2.5.37",
            date = "2026-04-26",
            changes = listOf(
                "月度外交随机事件系统（16种事件）"
            )
        ),
        ChangelogEntry(
            version = "2.5.36",
            date = "2026-04-26",
            changes = listOf(
                "外交系统扩展：物品送礼/自动结盟/好感度重做",
                "修复AI_ONLY外交事件不触发"
            )
        ),
        ChangelogEntry(
            version = "2.5.35",
            date = "2026-04-26",
            changes = listOf(
                "驻守队伍设计重构：驻守即战斗队伍",
                "补充弟子逻辑统一"
            )
        ),
        ChangelogEntry(
            version = "2.5.34",
            date = "2026-04-26",
            changes = listOf(
                "游戏失败机制：宗门全失即失败",
                "初始好感度统一随机40-60"
            )
        ),
        ChangelogEntry(
            version = "2.5.33",
            date = "2026-04-26",
            changes = listOf(
                "占领条件修复：改为全体弟子全灭"
            )
        ),
        ChangelogEntry(
            version = "2.5.32",
            date = "2026-04-26",
            changes = listOf(
                "小境界突破概率平滑过渡",
                "战斗队伍地图标记和移动路径显示",
                "AI队伍弟子选择和死亡清理修复"
            )
        ),
        ChangelogEntry(
            version = "2.5.26",
            date = "2026-04-26",
            changes = listOf(
                "宗门战争系统重构：攻击全地图、10v10格式",
                "占领条件改为化神及以上弟子全灭"
            )
        ),
        ChangelogEntry(
            version = "2.5.22",
            date = "2026-04-26",
            changes = listOf(
                "AI宗门弟子生成逻辑重构",
                "AI弟子平时无功法装备、战斗时自动生成"
            )
        ),
        ChangelogEntry(
            version = "2.5.21",
            date = "2026-04-26",
            changes = listOf(
                "修复存档丢失问题：fallback收紧、竞态修复",
                "WAL checkpoint防止数据丢失"
            )
        ),
        ChangelogEntry(
            version = "2.5.20",
            date = "2026-04-26",
            changes = listOf(
                "修复所有功法学习路径缺少同名检查"
            )
        ),
        ChangelogEntry(
            version = "2.5.19",
            date = "2026-04-26",
            changes = listOf(
                "设置页面新增隐私设置（限制广告追踪）"
            )
        ),
        ChangelogEntry(
            version = "2.5.17",
            date = "2026-04-25",
            changes = listOf(
                "修复learnManual缺少槽位上限检查"
            )
        ),
        ChangelogEntry(
            version = "2.5.16",
            date = "2026-04-25",
            changes = listOf(
                "装备/功法系统重构：原子事务消除竞态",
                "BagUtils提取共用方法"
            )
        ),
        ChangelogEntry(
            version = "2.5.13",
            date = "2026-04-25",
            changes = listOf(
                "修复仓库多件装备时手动穿戴不显示",
                "equipEquipment原子事务修复竞态条件"
            )
        ),
        ChangelogEntry(
            version = "2.5.12",
            date = "2026-04-25",
            changes = listOf(
                "修复一键出售需两次才能卖干净的bug",
                "sellXxx方法改为事务中同步执行"
            )
        ),
        ChangelogEntry(
            version = "2.5.11",
            date = "2026-04-25",
            changes = listOf(
                "弟子命名系统统一：NameService + 复姓支持",
                "重名检测全面修复"
            )
        ),
        ChangelogEntry(
            version = "2.5.0",
            date = "2026-04-25",
            changes = listOf(
                "世界地图扩容：6000x5000、80宗门",
                "宗门聚类分布、路径贝塞尔弯曲"
            )
        ),
        ChangelogEntry(
            version = "2.4.18",
            date = "2026-04-25",
            changes = listOf(
                "弟子自动使用丹药/装备/功法从每月改为每日",
                "丹药持续时间从月度改为日度"
            )
        ),
        ChangelogEntry(
            version = "2.4.0",
            date = "2026-04-24",
            changes = listOf(
                "宗门任务系统全面升级：24个任务模板",
                "人型敌人系统",
                "奖励差异化"
            )
        ),
        ChangelogEntry(
            version = "2.3.20",
            date = "2026-04-23",
            changes = listOf(
                "所有弟子界面增加灵根和属性筛选行",
                "灵根多选+属性排序+境界筛选联合使用"
            )
        ),
        ChangelogEntry(
            version = "2.3.17",
            date = "2026-04-22",
            changes = listOf(
                "修复赏赐装备数量翻倍严重Bug",
                "修复一件装备同时出现在仓库和储物袋"
            )
        ),
        ChangelogEntry(
            version = "2.3.16",
            date = "2026-04-22",
            changes = listOf(
                "修复停止自动存档后仍在后台继续执行"
            )
        ),
        ChangelogEntry(
            version = "2.3.15",
            date = "2026-04-22",
            changes = listOf(
                "修复大比对话框重复弹出",
                "修复游戏后台时间继续流逝"
            )
        ),
        ChangelogEntry(
            version = "2.3.10",
            date = "2026-04-22",
            changes = listOf(
                "堆叠上限修正与统一",
                "31处硬编码改为InventoryConfig常量"
            )
        ),
        ChangelogEntry(
            version = "2.0.10",
            date = "2026-04-19",
            changes = listOf(
                "修复驱逐弟子功能竞态条件",
                "修复储物袋物品未归还宗门"
            )
        ),
        ChangelogEntry(
            version = "2.0.07",
            date = "2026-04-12",
            changes = listOf(
                "正式上线版本"
            )
        )
    )
}

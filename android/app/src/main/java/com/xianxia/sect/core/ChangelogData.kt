package com.xianxia.sect.core

data class ChangelogEntry(
    val version: String,
    val date: String,
    val changes: List<String>
)

object ChangelogData {
    val entries: List<ChangelogEntry> = listOf(
        ChangelogEntry(
            version = "3.1.75",
            date = "2026-05-31",
            changes = listOf(
                "修复自动管理界面无法滚动导致锻造设置和保存按钮被遮挡"
            )
        ),
        ChangelogEntry(
            version = "3.1.74",
            date = "2026-05-31",
            changes = listOf(
                "紧急修复v3.1.73自动存档变空：SectPolicies新增字段Set<Int>改为List<Int>，ProtoBuf不支持Set导致序列化失败"
            )
        ),
        ChangelogEntry(
            version = "3.1.73",
            date = "2026-05-31",
            changes = listOf(
                "新增天枢殿→宗门管理→自动管理：配置空闲弟子自动分配灵矿场/灵植阁/炼丹炉/锻造坊的属性门槛与灵根筛选，月度tick触发自动分配",
                "SectPolicies扩展12个字段支持4种自动分配类型（采矿/种植/炼丹/炼器），ProtoBuf向后兼容无数据库迁移"
            )
        ),
        ChangelogEntry(
            version = "3.1.72",
            date = "2026-05-30",
            changes = listOf(
                "移除GameStateStore中18个stateIn的replayExpirationMillis=30s限制，改为默认永不过期，彻底消除App切后台>35s回来后StateFlow返回空列表导致UI闪白的问题"
            )
        ),
        ChangelogEntry(
            version = "3.1.71",
            date = "2026-05-30",
            changes = listOf(
                "修复CultivationService中12个setter使用scope.launch异步写状态导致多域更新不原子的问题，改为同步direct方法直接写入_state"
            )
        ),
        ChangelogEntry(
            version = "3.1.70",
            date = "2026-05-30",
            changes = listOf(
                "优化状态流架构：GameViewModel中19个无变换透传StateFlow从stateIn()改为get()委托，移除冗余的viewModelScope stateIn层，减少每tick O(N)通知开销"
            )
        ),
        ChangelogEntry(
            version = "3.1.69",
            date = "2026-05-30",
            changes = listOf(
                "系统性修复GameEngine中18处stateIn派生StateFlow的.value读取为Snapshot直读，消除assignGarrisonDisciple/startMission/checkAndProcessCompletedMissions等函数的WhileSubscribed replay过期隐患（与此前修复的scoutSect/attackSect/攻击妖兽战斗结算同模式）"
            )
        ),
        ChangelogEntry(
            version = "3.1.68",
            date = "2026-05-30",
            changes = listOf(
                "修复仓库首次选中物品点查看不弹出详情：remember无keys导致derivedStateOf闭包永久捕获stateIn初始空列表，无法找到物品后守卫子句静默重置（与v3.1.64/67同类的WhileSubscribed初始空值bug）"
            )
        ),
        ChangelogEntry(
            version = "3.1.67",
            date = "2026-05-30",
            changes = listOf(
                "修复探查和宗门战后战斗结算界面不弹出：scoutSect/attackSect改用Snapshot直读_state.value替代stateIn派生StateFlow，解决WhileSubscribed replay过期导致.value返回空列表静默跳过战斗的bug（与v3.1.64妖兽战斗修复同根因）"
            )
        ),
        ChangelogEntry(
            version = "3.1.66",
            date = "2026-05-30",
            changes = listOf(
                "修复世界地图探查按钮直接弹出弟子选择器、跳过探查派遣界面的回归问题（v3.0.83统一单选时误伤），恢复10槽位编队+探查确认按钮的完整战斗派遣流程",
                "探查界面与进攻界面交互统一：10槽位编队、点击空槽位选弟子、点击已填槽位查看详情、卸任更换按钮、确认后执行"
            )
        ),
        ChangelogEntry(
            version = "3.1.65",
            date = "2026-05-30",
            changes = listOf(
                "ProtoBuf序列化彻底优化：Room路径改用encodeDefaults=false，可空字段自动省略，移除JSON降级"
            )
        ),
        ChangelogEntry(
            version = "3.1.64",
            date = "2026-05-30",
            changes = listOf(
                "修复世界地图进攻妖兽后战斗结算界面不弹出、战斗日志无记录的回归问题",
                "修复自动存档因ProtoBuf序列化null字段失败导致存档变空的问题"
            )
        ),
        ChangelogEntry(
            version = "3.1.63",
            date = "2026-05-30",
            changes = listOf(
                "新增宗门战斗力系统，主界面宗门信息卡片显示战斗力",
                "战斗力基于弟子最终属性计算（含装备功法丹药加成）",
                "AI宗门战力基于基础属性×3，境界变化时增量更新"
            )
        ),
        ChangelogEntry(
            version = "3.1.62",
            date = "2026-05-30",
            changes = listOf(
                "修复建筑放置在树木装饰物上时只清除部分格子导致残留半棵树的问题：触碰树木任意视觉区域即整棵清除"
            )
        ),
        ChangelogEntry(
            version = "3.1.61",
            date = "2026-05-30",
            changes = listOf(
                "修复建筑拖动/点击手势冲突：统一为awaitEachGesture单循环，消除拖拽失效"
            )
        ),
        ChangelogEntry(
            version = "3.1.60",
            date = "2026-05-30",
            changes = listOf(
                "存档架构优化：移除本地.sav文件双写，统一为Room数据库存储",
                "新增长旧存档迁移器：首次启动自动将.sav存档迁移至Room数据库"
            )
        ),
        ChangelogEntry(
            version = "3.1.59",
            date = "2026-05-30",
            changes = listOf(
                "修复DOT多buff叠加时跨境界减伤不生效：coerceAtLeast移到总伤害计算",
                "修复功法自动学习属性匹配：法攻偏好不再误选治疗/辅助功法",
                "旧存档自动装备/学习设置向后兼容：未配置sect时回退读取弟子旧标志"
            )
        ),
        ChangelogEntry(
            version = "3.1.58",
            date = "2026-05-30",
            changes = listOf(
                "修复DOT持续伤害绕过跨境界减伤：低境界敌人毒/灼烧对高境界弟子伤害被正确压缩",
                "修复TapTap新账号登录后卡在登录界面：合规认证SDK注册竞态+线程问题"
            )
        ),
        ChangelogEntry(
            version = "3.1.57",
            date = "2026-05-29",
            changes = listOf(
                "天赋文本颜色加深：从浅粉彩改为深色品阶色，白色背景上清晰可辨"
            )
        ),
        ChangelogEntry(
            version = "3.1.56",
            date = "2026-05-29",
            changes = listOf(
                "天枢殿→宗门管理新增「弟子管理」：可配置自动使用突破丹/装备/功法条件",
                "自动使用突破丹：符合条件的弟子突破时优先消耗仓库突破丹（高品阶优先）",
                "自动装备：符合条件的弟子每日自动穿戴仓库装备（优先匹配攻击属性方向）",
                "自动学习功法：符合条件的弟子每日自动学习仓库功法（优先匹配攻击属性方向）",
                "弟子详情界面移除自动穿戴/学习勾选框，统一由弟子管理界面控制"
            )
        ),
        ChangelogEntry(
            version = "3.1.55",
            date = "2026-05-29",
            changes = listOf(
                "新增凡品突破丹「聚气丹」：炼气期小层突破使用，商人/宗门交易/炼丹均可获取",
                "突破丹逻辑修正：大境界突破使用目标境界丹药（如炼气→筑基用筑基丹、筑基→金丹用凝金丹）"
            )
        ),
        ChangelogEntry(
            version = "3.1.54",
            date = "2026-05-29",
            changes = listOf(
                "巡视楼战斗胜利后幸存弟子神魂+1（与关卡/宗门战一致）",
                "拥有「百战通神」天赋的弟子胜利后随机属性+1（17种属性中随机一种）"
            )
        ),
        ChangelogEntry(
            version = "3.1.53",
            date = "2026-05-29",
            changes = listOf(
                "性能优化：打开弟子详情时该弟子200ms实时刷新，其他弟子1s分频刷新",
                "性能优化：切换Tab时通知引擎调整数据更新优先级，减少不必要刷新"
            )
        ),
        ChangelogEntry(
            version = "3.1.52",
            date = "2026-05-29",
            changes = listOf(
                "性能优化：游戏循环自适应节流，tick超时不再自旋、连续超时自动降频",
                "性能优化：StateFlow分配优化，跳过不变时的计算减少GC压力"
            )
        ),
        ChangelogEntry(
            version = "3.1.51",
            date = "2026-05-29",
            changes = listOf(
                "修复雷电模拟器TapTap启动卡死：SDK初始化切到后台线程+超时保护",
                "移除x86 ABI支持强制ARM翻译，避免模拟器兼容问题"
            )
        ),
        ChangelogEntry(
            version = "3.1.50",
            date = "2026-05-29",
            changes = listOf(
                "巡视楼战斗结算弹窗：击败妖兽后弹出结算界面、生成战斗日志、发放奖励",
                "设置界面新增巡视楼结算开关，默认关闭，开启后自动弹出结算",
                "巡视楼Switch改为圆形勾选框，文本更新，提取共享CircularCheckbox组件"
            )
        ),
        ChangelogEntry(
            version = "3.1.49",
            date = "2026-05-29",
            changes = listOf(
                "跨境界斩杀机制：进攻方境界比防守方大三个大境界以上时攻击必中且一击必杀"
            )
        ),
        ChangelogEntry(
            version = "3.1.48",
            date = "2026-05-29",
            changes = listOf(
                "巡视楼巡视槽位从10减为8，旧存档多余弟子自动回归空闲"
            )
        ),
        ChangelogEntry(
            version = "3.1.47",
            date = "2026-05-29",
            changes = listOf(
                "道侣生子机制重构：从每日概率改为每年判定一次，通过后在当年随机月份生育"
            )
        ),
        ChangelogEntry(
            version = "3.1.46",
            date = "2026-05-29",
            changes = listOf(
                "提示框尺寸放大：宽40%→50%、高45%→55%"
            )
        ),
        ChangelogEntry(
            version = "3.1.45",
            date = "2026-05-29",
            changes = listOf(
                "修复弟子详情修炼速度显示不含住所建筑加成的问题，显示值与引擎实际增益一致"
            )
        ),
        ChangelogEntry(
            version = "3.1.44",
            date = "2026-05-29",
            changes = listOf(
                "修复弟子详情界面修为进度条不实时更新的问题（改用实时弟子列表替代快照）"
            )
        ),
        ChangelogEntry(
            version = "3.1.43",
            date = "2026-05-29",
            changes = listOf(
                "架构重构：提取 SpiritRootGenerator 统一灵根生成，消除 5 处重复代码",
                "架构重构：修复 core→ui 循环依赖，DisciplePositionHelper 迁移至 core/util",
                "架构重构：EventBus 提取 EventBusPort 接口，消费者通过接口依赖",
                "架构重构：为全部 9 个 Service 创建接口契约",
                "架构重构：PartnerSystem 和 ChildBirthSystem 从 CultivationService 独立为 GameSystem"
            )
        ),
        ChangelogEntry(
            version = "3.1.42",
            date = "2026-05-29",
            changes = listOf(
                "新生儿灵根继承机制：30%继承父亲灵根、30%继承母亲灵根、40%随机生成"
            )
        ),
        ChangelogEntry(
            version = "3.1.41",
            date = "2026-05-29",
            changes = listOf(
                "天枢殿「宗门事务」改为「宗门管理」，新增道侣管理系统",
                "道侣管理支持按灵根数量禁婚（单灵根~五灵根），勾选后对应灵根弟子不会与异性弟子结为道侣",
                "道侣管理支持结婚审批模式：开启后每次有弟子请求结婚时弹出审批框，可同意或拒绝"
            )
        ),
        ChangelogEntry(
            version = "3.1.40",
            date = "2026-05-28",
            changes = listOf(
                "数量输入框超上限自动取上限值（如输入999自动变为上限13）"
            )
        ),
        ChangelogEntry(
            version = "3.1.39",
            date = "2026-05-28",
            changes = listOf(
                "修正宗门占领设计：正常宗门需全池无化神及以上弟子才可占领，被AI占领的宗门击败驻守弟子即可占领",
                "玩家占领宗门后该宗门所有存活弟子进入招募列表，后续每年新弟子也加入招募列表",
                "AI占领宗门后防御方存活弟子直接并入占领者宗门，后续每年新弟子也加入占领者",
                "关卡妖兽数量范围从3~12改为1~13",
                "AI宗门年度新弟子数量从固定5名改为随机0~6名"
            )
        ),
        ChangelogEntry(
            version = "3.1.38",
            date = "2026-05-28",
            changes = listOf(
                "巡视楼移除建造上限，可建造多座，每座独立管理10个槽位和进攻配置",
                "多座巡视楼分塔分队自动攻击，不同塔不会重复进攻同一只妖兽"
            )
        ),
        ChangelogEntry(
            version = "3.1.37",
            date = "2026-05-28",
            changes = listOf(
                "巡视楼自动攻击实装：每月根据进攻范围配置自动攻击匹配的妖兽"
            )
        ),
        ChangelogEntry(
            version = "3.1.36",
            date = "2026-05-28",
            changes = listOf(
                "新增巡视楼建筑：2×3占地/5000灵石/上限1座，驻守弟子自动巡视攻击妖兽",
                "巡视楼界面：10个巡视弟子槽位、一键任命（优先高境界）、进攻范围配置",
                "进攻范围：可选目标境界（默认炼气）+ 妖兽数量上限（默认1，最大13）+ 满状态条件",
                "满状态条件：勾选后所有巡视弟子需满气血满灵力才可进攻"
            )
        ),
        ChangelogEntry(
            version = "3.1.35",
            date = "2026-05-28",
            changes = listOf(
                "突破丹支持对应境界小层突破（如大乘丹可用于大乘一层→二层及大乘九层→渡劫），自动消耗储物袋中突破丹并优先使用高品质"
            )
        ),
        ChangelogEntry(
            version = "3.1.34",
            date = "2026-05-28",
            changes = listOf(
                "修复弟子详情界面内外门切换按钮点击后不刷新的问题"
            )
        ),
        ChangelogEntry(
            version = "3.1.33",
            date = "2026-05-27",
            changes = listOf(
                "全屏界面左右增加32dp安全边距，避免前置摄像头遮挡游戏内容",
                "主界面悬浮组件（宗门信息卡/功能按钮）统一改为对称固定边距"
            )
        ),
        ChangelogEntry(
            version = "3.1.32",
            date = "2026-05-27",
            changes = listOf(
                "优化一键任命：灵矿场优先任命高采矿弟子，副本战斗优先任命高境界弟子",
                "修复副本战斗一键任命因排序方向错误导致优先选择低境界弟子的问题",
                "修复攻击AI宗门战斗详情始终显示0回合的问题，现在显示实际战斗回合数",
                "修复AI宗门击败守军后无法占领宗门的问题，击败所有守军即可占领",
                "修复被其他AI占领的宗门驻军为空时攻击立即胜利但无法占领的问题",
                "战斗详情中区分「攻占」和「击溃守军」，准确反映是否实际占领"
            )
        ),
        ChangelogEntry(
            version = "3.1.30",
            date = "2026-05-27",
            changes = listOf(
                "新增仓库建筑玩法：3×2格占地/1000灵石/无上限，每座+50格仓库容量，配备驻守弟子槽位",
                "仓库容量改为动态计算（基础50格+每座仓库50格），旧存档同步生效",
                "仓库超限：物品无法入库时直接遗失并弹出提示框，仓库界面显示容量与满仓状态",
                "驻守防盗：偷盗时随机仓库→与驻守弟子1v1战斗判定胜负→胜偷败捕，无驻守则直接成功",
                "偷盗机制重构：道德+忠诚双门槛(<30)，偷盗与脱离均改为概率判定而非必定触发",
                "忠诚月度动态：领月俸+1/欠月俸-1（原每3次），矿工每月-1（执事不减），住宿每月+1"
            )
        ),
        ChangelogEntry(
            version = "3.1.28",
            date = "2026-05-27",
            changes = listOf(
                "修复灵植弟子选择列表只显示内门弟子的问题，改为所有存活空闲弟子均可选"
            )
        ),
        ChangelogEntry(
            version = "3.1.27",
            date = "2026-05-27",
            changes = listOf(
                "性能优化：SideEffect改为LaunchedEffect减少主线程bitmap绘制，UI层StateFlow采样降频减少Compose重组",
                "招募界面同意/拒绝按钮改为自适应等宽，填充卡片可用空间",
                "灵植弟子标题右侧增加详情按钮描述增益效果"
            )
        ),
        ChangelogEntry(
            version = "3.1.26",
            date = "2026-05-27",
            changes = listOf(
                "修复种植界面已种植种子卡片因库存耗尽不显示的问题"
            )
        ),
        ChangelogEntry(
            version = "3.1.25",
            date = "2026-05-27",
            changes = listOf(
                "种子成熟时间按稀有度分档：凡品3年→灵品6年→宝品20年→玄品45年→地品70年→天品120年",
                "种子详情成熟时间由月显示改为年显示"
            )
        ),
        ChangelogEntry(
            version = "3.1.24",
            date = "2026-05-27",
            changes = listOf(
                "灵植阁放置/移动时显示绿色光环范围圈，范围内灵田显示绿色覆盖",
                "灵田部分处于范围即享受增益，光环判定改为最近点距离"
            )
        ),
        ChangelogEntry(
            version = "3.1.23",
            date = "2026-05-27",
            changes = listOf(
                "灵植长老移至天枢殿，与炼丹/天工长老同行；灵植阁改为范围光环建筑（半径6格）",
                "灵植阁弟子槽位缩为1个灵植弟子，加成公式改为50基准每5点+1%成熟速度（上限20%），仅光环内灵田生效",
                "灵植长老加成改为80基准每4点+1%成熟速度（上限20%），全局生效；灵植阁移除建造上限"
            )
        ),
        ChangelogEntry(
            version = "3.1.22",
            date = "2026-05-27",
            changes = listOf(
                "灵矿场、锻造坊、炼丹炉移除建造上限，建造栏不再显示数量",
                "打开任何界面自动关闭建造栏，避免遮挡地图"
            )
        ),
        ChangelogEntry(
            version = "3.1.21",
            date = "2026-05-27",
            changes = listOf(
                "修复safeDropColumns中API级别判断不可靠导致部分设备SQLite DROP COLUMN语法错误崩溃的问题",
                "统一使用PRAGMA表重建方案替代原生ALTER TABLE DROP COLUMN，全Android版本兼容"
            )
        ),
        ChangelogEntry(
            version = "3.1.20",
            date = "2026-05-27",
            changes = listOf(
                "全功能模块化架构重构：BuildingRegistry单一数据源、ViewModel 4层委托拆分、MainGameScreen 3组件提取",
                "修改任意功能仅影响对应模块，不再波及全局"
            )
        ),
        ChangelogEntry(
            version = "3.1.19",
            date = "2026-05-27",
            changes = listOf(
                "种子网格动态计算每页行列数，充分利用屏幕空间",
                "放置确认/取消按钮模块化并固定出现在建筑上方",
                "代码库死代码清理，移除未使用文件/函数/导入"
            )
        ),
        ChangelogEntry(
            version = "3.1.18",
            date = "2026-05-26",
            changes = listOf(
                "种植界面优化：按钮颜色、数量选择器、布局调整",
                "灵田1x1修复、精灵图修复、自动补种机制",
                "收获草药入库修复、种植批量处理"
            )
        ),
        ChangelogEntry(
            version = "3.1.17",
            date = "2026-05-26",
            changes = listOf(
                "种植系统引入：新增灵田建筑（1格/200灵石/无上限），建造后通过种植界面将种子种植到灵田，成熟后自动收获",
                "灵植阁改造为纯增益建筑，移除种植槽位，灵植弟子对所有同宗门灵田提供产量加成",
                "新增顶层overlay z-order排序机制，保证后打开的界面在最顶层"
            )
        ),
        ChangelogEntry(
            version = "3.1.16",
            date = "2026-05-26",
            changes = listOf(
                "新增顶层overlay z-order排序机制，后打开的界面保证在最顶层",
                "BattleResult/BattleLogDetail纳入排序列表"
            )
        ),
        ChangelogEntry(
            version = "3.1.15",
            date = "2026-05-26",
            changes = listOf(
                "弟子详情界面全屏渲染架构重构，统一顶层覆盖模式"
            )
        ),
        ChangelogEntry(
            version = "3.1.14",
            date = "2026-05-26",
            changes = listOf(
                "所有妖兽基础属性（hp/mp/attack/defense/speed）下调30%"
            )
        ),
        ChangelogEntry(
            version = "3.1.13",
            date = "2026-05-25",
            changes = listOf(
                "数据库迁移safeDropColumns封装替代DROP COLUMN，消除低版本Android兼容隐患",
                "CLAUDE.md增加DROP COLUMN禁令规范"
            )
        ),
        ChangelogEntry(
            version = "3.1.12",
            date = "2026-05-25",
            changes = listOf(
                "修复低版本Android SQLite不支持DROP COLUMN导致数据库迁移崩溃的问题"
            )
        ),
        ChangelogEntry(
            version = "3.1.11",
            date = "2026-05-25",
            changes = listOf(
                "弟子详情页突破率右侧增加详情按钮，弹窗显示各加成明细",
                "神魂行不再显示突破加成，仅显示数值",
                "弹窗自动隐藏零加成项，内门/外门长老悟性加成实时计算"
            )
        ),
        ChangelogEntry(
            version = "3.1.10",
            date = "2026-05-25",
            changes = listOf(
                "弟子详情页神魂行移除突破加成显示，仅显示数值",
                "突破率右侧增加详情按钮，点击弹窗显示全部分加成明细（基础/长老/神魂/天赋/丹药）",
                "弹窗使用3列网格动态布局，标题栏右侧关闭按钮"
            )
        ),
        ChangelogEntry(
            version = "3.1.09",
            date = "2026-05-25",
            changes = listOf(
                "修复占领宗门灵矿场/炼丹炉/锻造坊无法任命弟子的问题",
                "弟子槽位（灵矿弟子、炼丹/锻造/灵植弟子、储备弟子）按宗门独立管理",
                "长老、传道师、执法弟子、灵矿执事保持全局共享"
            )
        ),
        ChangelogEntry(
            version = "3.1.08",
            date = "2026-05-25",
            changes = listOf(
                "修复占领宗门地图灵矿场/炼丹炉/锻造坊无法任命弟子的问题",
                "执事槽位按宗门拆分（DirectDiscipleSlot加sectId），每个宗门独立管理执事",
                "长老保持全局共享，切换宗门不影响长老职位"
            )
        ),
        ChangelogEntry(
            version = "3.1.07",
            date = "2026-05-25",
            changes = listOf(
                "战斗胜利后存活弟子神魂+1（世界关卡、宗门战、任务战斗），修复神魂只在旧洞府探索增长的bug",
                "清理旧洞府系统：停止生成旧洞府、删除不可达的洞府详情/CaveMarker等死UI代码",
                "删除未实现的战斗成长（winGrowth）死代码",
                "修正弟子详情页神魂显示公式与计算一致（/20而非/10）"
            )
        ),
        ChangelogEntry(
            version = "3.1.06",
            date = "2026-05-25",
            changes = listOf(
                "弟子脱离提示框的弟子槽位不再显示血条"
            )
        ),
        ChangelogEntry(
            version = "3.1.05",
            date = "2026-05-25",
            changes = listOf(
                "建筑标识从整数序号改为instanceId，移除灵矿场/炼丹炉/锻造坊序号标签，修复多宗门槽位串位"
            )
        ),
        ChangelogEntry(
            version = "3.1.04",
            date = "2026-05-25",
            changes = listOf(
                "修复占领宗门地图无法建造建筑的问题"
            )
        ),
        ChangelogEntry(
            version = "3.1.03",
            date = "2026-05-25",
            changes = listOf(
                "修复探查过的AI宗门弟子分布信息不随宗门战死亡而更新的问题"
            )
        ),
        ChangelogEntry(
            version = "3.1.02",
            date = "2026-05-25",
            changes = listOf(
                "进入宗门后自动关闭所有弹窗直接显示宗门地图"
            )
        ),
        ChangelogEntry(
            version = "3.1.01",
            date = "2026-05-25",
            changes = listOf(
                "修复AI宗门弟子在宗门战中被击杀后下次攻击仍可出战的问题"
            )
        ),
        ChangelogEntry(
            version = "3.1.00",
            date = "2026-05-25",
            changes = listOf(
                "占领宗门后可进入该宗门并自由建造建筑，支持多宗门地图切换"
            )
        ),
        ChangelogEntry(
            version = "3.0.100",
            date = "2026-05-25",
            changes = listOf(
                "占领宗门后世界地图宗门详情界面和外交界面隐藏探查、送礼、结盟、交易按钮"
            )
        ),
        ChangelogEntry(
            version = "3.0.99",
            date = "2026-05-25",
            changes = listOf(
                "弟子脱离宗门提示框中的文字卡片改为弟子槽位展示"
            )
        ),
        ChangelogEntry(
            version = "3.0.98",
            date = "2026-05-25",
            changes = listOf(
                "监牢思过年限从10年缩短为5年，思过结束后弟子增加5点道德和5点忠诚",
                "弟子所有基础属性移除100上限，下限统一改为0"
            )
        ),
        ChangelogEntry(
            version = "3.0.97",
            date = "2026-05-25",
            changes = listOf(
                "修复弟子脱离和偷盗被捕提示框关闭后重复弹出的问题"
            )
        ),
        ChangelogEntry(
            version = "3.0.96",
            date = "2026-05-25",
            changes = listOf(
                "修复战斗结算和战斗详情界面中弟子死亡后槽位不显示\"死亡\"标识的问题"
            )
        ),
        ChangelogEntry(
            version = "3.0.95",
            date = "2026-05-24",
            changes = listOf(
                "问道塔和青云塔不再开局免费建造，需通过建造栏花费灵石建造",
                "移除问道塔和青云塔对话框中的弟子名册展示区域"
            )
        ),
        ChangelogEntry(
            version = "3.0.94",
            date = "2026-05-24",
            changes = listOf(
                "修复长按建筑后无法进入移动模式的问题"
            )
        ),
        ChangelogEntry(
            version = "3.0.93",
            date = "2026-05-24",
            changes = listOf(
                "修复攻打宗门胜利一次即直接占领的问题：现在需消灭宗门内所有化神及以上弟子才能占领"
            )
        ),
        ChangelogEntry(
            version = "3.0.92",
            date = "2026-05-24",
            changes = listOf(
                "所有功法和装备的基础属性增益提升50%"
            )
        ),
        ChangelogEntry(
            version = "3.0.91",
            date = "2026-05-24",
            changes = listOf(
                "神魂突破率加成削弱：每20点神魂+1%突破率（原每10点+1%），上限5%（原10%）",
                "突破失败惩罚：扣除弟子90%当前气血和灵力（保留10%，最少1点）",
                "自动突破条件新增：弟子必须满气血满灵力才会自动尝试突破"
            )
        ),
        ChangelogEntry(
            version = "3.0.90",
            date = "2026-05-24",
            changes = listOf(
                "调整全境界突破率：单灵根炼气90%→筑基80%→金丹60%→元婴42%→化神34%→炼虚26%→合体16%→大乘12%→渡劫6%→仙人2%",
                "多灵根突破率按固定百分点递减：双灵根-20%、三灵根-30%、四灵根-50%、五灵根-60%，最低为0%",
                "外门/内门长老突破率加成削弱：悟性每高4点+1%突破率（原每高1点+1%）"
            )
        ),
        ChangelogEntry(
            version = "3.0.89",
            date = "2026-05-24",
            changes = listOf(
                "修复弟子住所槽位点击后不弹出选择弟子界面的问题",
                "多人住所网格尺寸改为3×2（与单人住所一致）"
            )
        ),
        ChangelogEntry(
            version = "3.0.88",
            date = "2026-05-24",
            changes = listOf(
                "统一所有提示确认弹窗样式为标准提示框（dialog_box背景、居中按钮居于底部、12dp圆角）",
                "设置界面退出游戏按钮新增二次确认提示框，防止误触退出",
                "天赋详情界面改用标准提示框样式，右上角关闭按钮替代底部按钮"
            )
        ),
        ChangelogEntry(
            version = "3.0.87",
            date = "2026-05-24",
            changes = listOf(
                "修复天赋详情界面缺少背景的问题，改为半屏显示与其他界面风格统一"
            )
        ),
        ChangelogEntry(
            version = "3.0.86",
            date = "2026-05-24",
            changes = listOf(
                "宗门战斗奖励灵石改为参与随机物品池（与其他6类物品等概率）",
                "不同等级宗门产出的单件灵石数量不同（小型2000/中型6000/大型3万/顶级8万）",
                "修复宗门战斗胜利后战利品未实际入库的问题",
                "修复攻打宗门时防守方选出低境界弟子的问题（现改为选出最高境界）"
            )
        ),
        ChangelogEntry(
            version = "3.0.85",
            date = "2026-05-24",
            changes = listOf(
                "移除外门大比机制：弟子晋升改为在弟子信息界面直接切换",
                "弟子详情界面新增内外门切换按钮（关系按钮左侧），按钮与下拉菜单一体式设计，点击按钮向下展开选项",
                "所有弟子默认为外门弟子，可在弟子信息界面随时手动晋升为内门或降为外门",
                "点击界面其他位置（标签页、按钮、天赋、装备槽等）自动收起下拉菜单，不影响原有点击功能",
                "切换内外门身份时自动清理对应职位（灵矿矿工、长老等）"
            )
        ),
        ChangelogEntry(
            version = "3.0.84",
            date = "2026-05-24",
            changes = listOf(
                "所有弟子选择界面统一改为单选模式，彻底移除多选机制",
                "点击弟子卡片即完成选择，无需额外确认按钮，操作更流畅"
            )
        ),
        ChangelogEntry(
            version = "3.0.83",
            date = "2026-05-24",
            changes = listOf(
                "所有弟子选择界面统一改为单选模式，彻底移除多选机制",
                "点击弟子卡片即完成选择，无需额外确认按钮，操作更流畅"
            )
        ),
        ChangelogEntry(
            version = "3.0.82",
            date = "2026-05-24",
            changes = listOf(
                "所有弟子选择界面优化：境界筛选栏合并到标题栏区域，紧贴标题下方",
                "标题栏和筛选栏间距大幅缩减，为弟子卡片留出更多展示空间"
            )
        ),
        ChangelogEntry(
            version = "3.0.81",
            date = "2026-05-23",
            changes = listOf(
                "灵矿更换矿工界面改为单选：点击弟子卡片直接替换，无需额外确认",
                "所有多选弟子界面确认按钮统一移至卡片网格下方居中显示"
            )
        ),
        ChangelogEntry(
            version = "3.0.80",
            date = "2026-05-23",
            changes = listOf(
                "新增弟子住所系统：可在宗门地图建造单人住所（800灵石）和多人住所（2000灵石）",
                "单人住所可入住1名弟子，修炼速度+25%；多人住所可入住4名弟子，修炼速度+10%",
                "单人住所可升级为中级单人住所（5000灵石），修炼速度提升至+50%",
                "点击宗门地图上的住所建筑可打开详情界面，管理入住弟子"
            )
        ),
        ChangelogEntry(
            version = "3.0.79",
            date = "2026-05-23",
            changes = listOf(
                "修复弟子战斗阵亡后仍显示在弟子列表并可被任命为长老/亲传弟子的问题"
            )
        ),
        ChangelogEntry(
            version = "3.0.78",
            date = "2026-05-23",
            changes = listOf(
                "修复战斗胜利后战利品仅部分入库的问题：将战利品生成与入库统一在事务内原子执行",
                "战斗结算弹窗现在仅显示实际成功入库的物品，避免展示与实际不一致"
            )
        ),
        ChangelogEntry(
            version = "3.0.77",
            date = "2026-05-23",
            changes = listOf(
                "修复世界地图战斗结算时背景变成宗门地图的问题",
                "修复兽潮关卡战斗胜利后灵石未入库的问题"
            )
        ),
        ChangelogEntry(
            version = "3.0.76",
            date = "2026-05-23",
            changes = listOf(
                "修复读档后游历商人物品自动刷新的问题"
            )
        ),
        ChangelogEntry(
            version = "3.0.75",
            date = "2026-05-23",
            changes = listOf(
                "锻造/炼丹界面优化：进度条宽度与槽位对齐，进度条上方新增成功率显示",
                "进度条动画改为逐日平滑增长，不再逐月跳动"
            )
        ),
        ChangelogEntry(
            version = "3.0.74",
            date = "2026-05-23",
            changes = listOf(
                "新增弟子叛逃提示框：弟子脱离宗门时弹出提示，显示弟子信息",
                "新增弟子偷盗被捕提示框：执法堂抓获偷盗弟子后弹出提示，可选择驱逐、押入监牢或释放",
                "释放偷盗弟子随机增加1~10忠诚度并弹出提示"
            )
        ),
        ChangelogEntry(
            version = "3.0.73",
            date = "2026-05-22",
            changes = listOf(
                "修复部分机型按钮文字只显示2个字符的问题：按钮宽度72→84dp、内边距缩小、溢出改为省略号"
            )
        ),
        ChangelogEntry(
            version = "3.0.72",
            date = "2026-05-22",
            changes = listOf(
                "彻底修复丹药/装备/功法选择界面滚动卡顿：精灵PNG缩放到显示尺寸、无损压缩、加载界面预解码为ImageBitmap缓存，滚动时零解码",
                "CompositionLocal全局注入精灵缓存，所有物品卡片自动使用预解码贴图",
                "丹药/锻造选择列表添加稳定key，避免不必要重组"
            )
        ),
        ChangelogEntry(
            version = "3.0.71",
            date = "2026-05-22",
            changes = listOf(
                "优化加载速度：所有静态资源（功法库、丹药模板、配方、装备、妖兽材料、建筑贴图等）在读档/新游戏加载界面统一预加载，消除进入游戏后的首次操作卡顿",
                "功法库初始化从应用启动移到游戏加载界面，加快应用启动速度"
            )
        ),
        ChangelogEntry(
            version = "3.0.70",
            date = "2026-05-22",
            changes = listOf(
                "移除跨境界伤害乘数的所有上限和下限：境界差加成不再被MAX_REALM_GAP(5)和MAX_DAMAGE_RATIO(3.0x)限制，仙人打炼气从3.0x变为5.5x；低境界攻击高境界惩罚保底为0"
            )
        ),
        ChangelogEntry(
            version = "3.0.69",
            date = "2026-05-22",
            changes = listOf(
                "修复战斗普通攻击描述使用妖兽动词的问题：弟子进行普通攻击时正确显示武器攻击描述（如「一剑刺向」），而非妖兽攻击描述（如「猛扑向」）",
                "修复探查中防守方AI弟子战斗描述同样使用妖兽动词的问题"
            )
        ),
        ChangelogEntry(
            version = "3.0.68",
            date = "2026-05-22",
            changes = listOf(
                "战斗结算界面：战斗结束后弹出半屏结算界面，展示出战弟子状态和战利品",
                "结算界面底部显示「战斗详情」和「确定」按钮，战斗详情可查看完整回合记录",
                "所有战斗类型（世界关卡、宗门战、探查、洞府探索）均支持战斗结算弹窗"
            )
        ),
        ChangelogEntry(
            version = "3.0.67",
            date = "2026-05-22",
            changes = listOf(
                "突破丹药突破率调整：下品5%、中品12%、上品20%（原15%/30%/60%）",
                "修复丹药选择界面部分突破丹药缺失的问题：分组逻辑改为按名称区分，同品阶不同突破丹药不再合并显示"
            )
        ),
        ChangelogEntry(
            version = "3.0.66",
            date = "2026-05-22",
            changes = listOf(
                "修复弟子信息界面神魂显示两个0的问题：数值标签与StatItem重复显示",
                "修复弟子翻页顺序与列表显示顺序不一致的问题：详情弹窗翻页现在遵循列表的排序和筛选",
                "修复仓库界面显示两个仓库标题的问题：移除内部重复标题，一键出售按钮移至标题栏"
            )
        ),
        ChangelogEntry(
            version = "3.0.65",
            date = "2026-05-22",
            changes = listOf(
                "丹药道具新增品阶精灵图：凡品/灵品/宝品/玄品/地品/天品丹药均有专属精灵图，替换敬请期待占位文字",
                "丹药选择界面去重：每种丹药只显示一张卡片，不再按品质（下品/中品/上品）分开展示",
                "丹药详情界面显示效果范围（下品~上品），更清晰展示炼制产出的品质波动"
            )
        ),
        ChangelogEntry(
            version = "3.0.64",
            date = "2026-05-22",
            changes = listOf(
                "修复弟子槽位境界文字显示不全的问题：槽位高度微调确保文字完整显示",
                "锻造/炼丹/种植槽位按钮样式统一：取消和更换按钮改为纯文字样式，与弟子槽位按钮一致",
                "生产槽位进度条宽度调整为与槽位同宽"
            )
        ),
        ChangelogEntry(
            version = "3.0.63",
            date = "2026-05-21",
            changes = listOf(
                "每年招募弟子数量从3-15名调整为0-6名",
                "所有弟子槽位新增名称显示：精灵图上方显示弟子名称，下方显示境界",
                "战斗界面弟子槽位同步显示名称，槽位组件重构统一渲染"
            )
        ),
        ChangelogEntry(
            version = "3.0.62",
            date = "2026-05-21",
            changes = listOf(
                "修复同种材料分散在多个堆叠时无法锻造玄品和宝品装备的问题：后端材料数量覆写改为累加",
                "修复锻造和炼丹失败时无任何错误提示的问题：材料不足或未分配弟子时显示错误消息",
                "锻造选择界面和炼丹选择界面的确认按钮严格遵守72×38dp标准尺寸"
            )
        ),
        ChangelogEntry(
            version = "3.0.61",
            date = "2026-05-21",
            changes = listOf(
                "一键出售界面支持滚动，修复选择品阶和类型后物品列表无法滚动的问题",
                "自动炼丹/锻造/种植：开启后空闲槽位立即开始生产，优先炼制高品阶物品；工作槽位完成后自动续炼同种物品，材料不足则降级炼制",
                "炼丹炉/锻造坊/灵植阁槽位卡片升级：显示物品精灵图或敬请期待占位，底部显示物品名称",
                "槽位新增进度条显示剩余时间、取消按钮（材料不退还）和更换按钮（选新物品直接替换）",
                "槽位边框统一改为固定灰色；移除槽位序号文本"
            )
        ),
        ChangelogEntry(
            version = "3.0.60",
            date = "2026-05-21",
            changes = listOf(
                "任务阁弟子选择简化：移除取消和任命按钮，点击弟子卡片即完成任命；弟子卡片改为两列显示",
                "任务刷新机制调整：移除未接取任务的自动过期，每三月刷新时清空全部未执行任务后生成新任务",
                "任务卡片新增弟子条件显示：标注外门弟子/内门弟子/无条件，派遣界面同步显示"
            )
        ),
        ChangelogEntry(
            version = "3.0.59",
            date = "2026-05-21",
            changes = listOf(
                "功法卡片新增品阶精灵图，替换敬请期待占位文本，六品阶各有专属功法图"
            )
        ),
        ChangelogEntry(
            version = "3.0.58",
            date = "2026-05-20",
            changes = listOf(
                "修复外门大比关闭按钮无反应：关闭逻辑从间接StateFlow驱动改为直接关闭导航路由，清理了遗留的无效状态标志和死代码"
            )
        ),
        ChangelogEntry(
            version = "3.0.57",
            date = "2026-05-20",
            changes = listOf(
                "修复外门大比不显示：v3.0.43界面重构时将外门大比结果对话框移入了世界地图组件，导致不在世界地图时大比界面无法弹出；将大比对话框提取为独立导航目的地，无论在任何界面都能正常显示"
            )
        ),
        ChangelogEntry(
            version = "3.0.56",
            date = "2026-05-20",
            changes = listOf(
                "弟子选择界面全面优化：筛选栏上移减少标题间距，弟子卡片可视区域扩大（280dp→400dp），推荐属性（采矿/智力/炼丹等）移至标题右侧不再占用卡片空间",
                "灵矿场更换按钮修复：点击更换始终弹出选择界面，支持替换当前槽位弟子而非分配到空位",
                "对话框点击穿透修复：选择弟子界面内点击空白区域不再意外关闭界面",
                "执法堂弟子选择标题修正：\"选择亲传弟子\"改为根据建筑动态生成（选择执法弟子/炼丹弟子等）",
                "执法堂储备弟子界面优化：推荐属性移至标题栏，与添加按钮并排显示"
            )
        ),
        ChangelogEntry(
            version = "3.0.55",
            date = "2026-05-20",
            changes = listOf(
                "探查战斗AI防守弟子修复：使用功法技能、完整装备属性、真实灵根元素，战斗描述不再使用妖兽攻击动词",
                "战斗日志修复：妖兽战斗正确显示妖兽精灵图，AI宗门弟子各显示随机头像不再全部相同",
                "弟子死亡处理修复：死亡弟子状态改为DEAD，不再出现在可任命列表中，探查/妖兽战死亡弟子自动清理职务槽位并归还装备"
            )
        ),
        ChangelogEntry(
            version = "3.0.54",
            date = "2026-05-19",
            changes = listOf(
                "探查改为即时战斗：选好弟子后立即与目标宗门防守弟子交战，不再需要等待旅行时间",
                "探查战斗防守方为5-10名炼气到金丹境界弟子，随机选取",
                "探查胜利后宗门界面实时显示各境界弟子分布，零人境界显示0而非？",
                "探查战斗记录完整显示在战斗日志中，包含回合详情",
                "弟子卸下/更换的装备和功法改为归还宗门仓库而非储物袋",
                "数据库迁移：aiSectDisciples字段持久化，修复存档读回后AI宗门弟子数据丢失"
            )
        ),
        ChangelogEntry(
            version = "3.0.53",
            date = "2026-05-18",
            changes = listOf(
                "任务阁派遣队伍全面优化：修复选择弟子界面被遮挡无法操作的问题，派遣界面支持滑动，弟子槽位增加卸任/更换按钮，点击已占槽位弹出弟子详情，任务详情界面支持滑动",
                "任务阁弟子选择界面卡片不再被压缩，列数自适应屏幕宽度",
                "商人界面灵石数量移至标题右侧，仓库界面一键出售按钮移至标题右侧",
                "招募界面移除灵石数量显示",
                "外交界面送礼/结盟/交易按钮修复为标准尺寸，修复点击按钮无法弹出子对话框的问题",
                "探查弟子/游说弟子界面的按钮统一为标准尺寸，修复点击无响应问题",
                "天枢殿按钮统一为标准尺寸，炼丹长老与锻造长老槽位并排显示",
                "宗门交易界面物品卡片改为统一标准卡片，修复精灵图不显示、样式不一致问题",
                "打开任意界面时建造栏自动关闭",
                "蕴灵戒精灵图更新",
                "无数据库迁移"
            )
        ),
        ChangelogEntry(
            version = "3.0.52",
            date = "2026-05-18",
            changes = listOf(
                "靴子更名并更新描述：布鞋→青澜靴、皮靴→兽皮靴、迷雾靴→云栖靴、虚空步→溯光靴、影舞步→赤煞靴、仙踪步→鸾羽履、混沌履→鹤岚靴",
                "饰品更名并更新描述：疾风戒→蕴灵戒、地灵核→渡厄佩、风行坠→隐云佩、混沌灵珠→幽朔珠、天行戒→长明坠",
                "新增24个精灵图：12双靴子（凡品~天品全覆盖）+ 12个饰品（凡品~天品全覆盖），靴子饰品精灵图覆盖率达100%",
                "无数据库迁移"
            )
        ),
        ChangelogEntry(
            version = "3.0.51",
            date = "2026-05-18",
            changes = listOf(
                "任务阁优化：改为槽位式派遣队伍界面，6个弟子槽位逐个任命，支持一键自动填充（高境界优先）",
                "无数据库迁移"
            )
        ),
        ChangelogEntry(
            version = "3.0.50",
            date = "2026-05-18",
            changes = listOf(
                "修复进攻AI宗门直接获胜且战斗日志显示零弟子的问题（AI弟子老化从每月1岁修正为每年1岁）",
                "无数据库迁移"
            )
        ),
        ChangelogEntry(
            version = "3.0.49",
            date = "2026-05-18",
            changes = listOf(
                "修复从旧版本（3.0.41）升级后读档宗门地图建筑全部消失的问题",
                "修复宗门地图建筑足迹下方出现错位地面纹理条纹的问题",
                "修正24个单元测试期望值（突破概率-5%与品阶颜色更新后测试未同步）",
                "无数据库迁移"
            )
        ),
        ChangelogEntry(
            version = "3.0.48",
            date = "2026-05-18",
            changes = listOf(
                "修复任命炼丹/锻造弟子后槽位仍显示空闲的问题",
                "修复关闭界面回到宗门地图后视角自动移到地图中间的问题",
                "统一生产槽位数据路径，弟子任命/移除/自动炼丹切换等操作现在正确写入 Repository",
                "无数据库迁移"
            )
        ),
        ChangelogEntry(
            version = "3.0.47",
            date = "2026-05-18",
            changes = listOf(
                "新增长按建筑拖动重新放置功能，长按建筑0.6秒即可拖动到新位置确认后落位",
                "修复设置界面重新开始游戏后设置界面不关闭的问题",
                "无数据库迁移"
            )
        ),
        ChangelogEntry(
            version = "3.0.46",
            date = "2026-05-17",
            changes = listOf(
                "优化建筑建造拖拽体验，建筑预览与手指1:1丝滑同步，不再有阻力滞后感",
                "建筑预览改为平滑滑动渲染，拖拽过程中不再逐格跳动",
                "无数据库迁移"
            )
        ),
        ChangelogEntry(
            version = "3.0.45",
            date = "2026-05-17",
            changes = listOf(
                "布衣更名为灵竹衣，青铜铠更名为精铁甲，锻造配方描述同步更新",
                "铁叶甲更名为碧叶甲，精钢铠更名为丹羽衣，鳞甲更名为青鳞铠，板甲更名为银板铠，玄法袍更名为汐流衣",
                "龙鳞甲更名为龙鳞铠，泰坦铠更名为渊岩铠，虚空袍更名为瑶光袍",
                "神铸铠更名为墨幽铠，天罡袍更名为凌星袍，大地甲更名为玄幽袍，虚空影袍更名为定海铠",
                "鸿蒙铠更名为苍罡铠，仙衣更名为曦光铠，混沌袍更名为云影袍",
                "新增20件防具精灵图（碧叶甲、丹羽衣、青鳞铠、银板铠、汐流衣、灵丝袍、云纹袍、龙鳞铠、渊岩铠、瑶光袍、月华袍、星辰袍、玄幽袍、墨幽铠、凌星袍、定海铠、不朽铠、苍罡铠、曦光铠、云影袍），防具精灵图覆盖率达100%",
                "新增灵竹衣、精铁甲、锁子甲、皮甲4件防具精灵图，替换敬请期待占位文本",
                "无数据库迁移"
            )
        ),
        ChangelogEntry(
            version = "3.0.44",
            date = "2026-05-17",
            changes = listOf(
                "锻造/炼丹弟子筛选移除内门弟子限制，外门弟子也可担任锻造和炼丹工作",
                "修复炼丹炉和锻造坊建造一次后变灰无法继续建造的问题，现可建造至上限7个",
                "仓库一键出售界面右上角增加关闭按钮",
                "无数据库迁移"
            )
        ),
        ChangelogEntry(
            version = "3.0.43",
            date = "2026-05-17",
            changes = listOf(
                "全屏界面架构重构：引入Navigation路由系统，统一所有对话框打开/关闭方式",
                "拆分大文件（WorldMapDialogs拆为6个独立文件），对话框文件统一移至dialogs/目录",
                "统一对话框包装器为UnifiedGameDialog，废弃HalfScreenDialog和GameFullDialog",
                "共享组件提取：DialogHeader、DiscipleFilterState、DiscipleSelectorDialog",
                "修复按钮无响应、双标题、世界地图缩小、背景图分离等真机测试问题",
                "无数据库迁移"
            )
        ),
        ChangelogEntry(
            version = "3.0.42",
            date = "2026-05-17",
            changes = listOf(
                "自动招募筛选界面优化：右上角增加关闭按钮，筛选条件改为5列显示，勾选框改为黑色",
                "筛选界面底部增加取消按钮（左）和保存按钮（右），修改后需点击保存才生效",
                "修改筛选条件后点击关闭按钮弹出确认对话框，提示未保存更改，使用对话框素材",
                "无数据库迁移"
            )
        ),
        ChangelogEntry(
            version = "3.0.41",
            date = "2026-05-16",
            changes = listOf(
                "武器更名：屠龙刀→凤炎刃、寒霜刃→青碧刃、混沌刀→玄玉刃、雷霆杖→玄雷杖、虚空杖→虚华杖、天星珠→天玄杖、鸿蒙杖→天星杖、水晶珠→碧木扇、玄冰珠→玄冰扇、凤凰扇→凰焰扇、凤凰羽→阴阳扇、阴阳珠→天玄扇",
                "新增16把武器精灵图：凤炎刃、青碧刃、暗影刃、玄玉刃、桃木杖、碧玉杖、玄雷杖、虚华杖、天玄杖、天星杖、碧木扇、灵风扇、玄冰扇、凰焰扇、阴阳扇、天玄扇，精灵图覆盖率100%",
                "适配所有更名武器的描述文本（共11把），消除旧名称残留（如水晶球→碧木扇、屠龙→凤炎、混沌→玄玉等）",
                "无数据库迁移"
            )
        ),
        ChangelogEntry(
            version = "3.0.40",
            date = "2026-05-16",
            changes = listOf(
                "武器改名：弑神剑→青莲剑、青铜匕首→精铁刀、战斧→凌华刀",
                "新增武器精灵图：青莲剑、灵锋剑、精铁刀、凌华刀（共8把武器已有素材）",
                "商人界面筛选按钮统一为72×38dp标准尺寸",
                "无数据库迁移"
            )
        ),
        ChangelogEntry(
            version = "3.0.39",
            date = "2026-05-16",
            changes = listOf(
                "道具数量显示优化：所有卡片右下角数量去除x前缀（x3→3），数量为1时也显示数字",
                "弟子详情界面装备槽位和功法槽位隐藏数量显示"
            )
        ),
        ChangelogEntry(
            version = "3.0.38",
            date = "2026-05-16",
            changes = listOf(
                "全界面物品卡片样式统一：卡片统一为60dp尺寸，上方素材区（品阶色背景，有精灵图显示精灵图、无图显示「敬请期待」白字），下方名称区（白底黑字）",
                "卡片边框统一为灰色，选中时变为金色",
                "品阶（上品/中品/下品）和数量保留在素材区左下/右下角贴边显示",
                "锻造/炼丹配方卡片的炼制时长和品级信息移至卡片外部下方",
                "弟子装备槽位和功法槽位统一使用52dp新卡片，熟练度信息移至卡片外部下方",
                "灵植阁种子选择卡片统一为52dp尺寸",
                "无数据库迁移"
            )
        ),
        ChangelogEntry(
            version = "3.0.37",
            date = "2026-05-16",
            changes = listOf(
                "修复全场景空槽位点击行为：灵植阁/执法堂/各峰/生产设施/灵矿场/驻守空槽位点击统一触发更换操作"
            )
        ),
        ChangelogEntry(
            version = "3.0.36",
            date = "2026-05-16",
            changes = listOf(
                "弟子装备界面装备槽位显示优化：装备槽位缩小，品阶色背景上显示装备精灵图（已支持精铁剑/烈焰剑/雷霆剑/诛仙剑），暂无素材的装备显示「敬请期待」",
                "装备名称移至槽位下方白底显示，品阶颜色边框标识稀有度",
                "弟子功法界面功法槽位样式统一为与装备槽位一致",
                "道具品阶颜色标准统一：凡品#B8B8B8、灵品#AFCB8A、宝品#9FC2EE、玄品#C0A2DD、地品#E7C67D、天品#E3A0A0",
                "所有道具卡片（商人/储物袋/仓库/背包/锻造/炼丹/天赋等）统一使用新品阶颜色背景"
            )
        ),
        ChangelogEntry(
            version = "3.0.35",
            date = "2026-05-16",
            changes = listOf(
                "修复弟子详情弹窗在弟子列表、进攻编队、关卡详情中未全屏显示的问题，统一使用 Dialog 独立窗口渲染"
            )
        ),
        ChangelogEntry(
            version = "3.0.34",
            date = "2026-05-16",
            changes = listOf(
                "修复战斗日志详情中敌方妖兽错误显示弟子头像的问题，现在正确显示对应妖兽图像"
            )
        ),
        ChangelogEntry(
            version = "3.0.33",
            date = "2026-05-15",
            changes = listOf(
                "所有弟子槽位交互统一：点击已占槽位弹出弟子详情，所有槽位下方均有「卸任」+「更换」按钮",
                "炼丹/炼器/灵植/灵矿/藏经阁/执法堂/天枢堂/青云塔/问道塔/弟子标签/进攻/驻守/关卡/任务等全场景槽位全部统一",
                "新增 DiscipleSlotWithActions 共享组件，消除各处重复的槽位+按钮代码"
            )
        ),
        ChangelogEntry(
            version = "3.0.32",
            date = "2026-05-15",
            changes = listOf(
                "修复进攻宗门战斗未触发的严重Bug：派遣弟子进攻AI宗门时未获取防守方弟子（仅检查空的驻守槽位），导致战斗直接跳过、宗门立即被占领",
                "进攻宗门战斗现在正确从AI宗门弟子池中选取防守方，低境界弟子无法再轻松取胜",
                "进攻宗门战斗结束后生成战斗日志记录"
            )
        ),
        ChangelogEntry(
            version = "3.0.31",
            date = "2026-05-15",
            changes = listOf(
                "驻守弟子状态显示为「驻守中」（不再显示为「队伍中」）",
                "战斗队伍中的弟子即使队伍未派遣也显示「队伍中」状态"
            )
        ),
        ChangelogEntry(
            version = "3.0.30",
            date = "2026-05-15",
            changes = listOf(
                "驻守弟子槽位增至与进攻槽位一致：点击弟子弹出详情，下方更换+卸任两按钮",
                "弟子槽位居中排列，更换卸任按钮间距优化",
                "弟子详情弹窗修复在宗门界面中无法正常显示的问题"
            )
        ),
        ChangelogEntry(
            version = "3.0.28",
            date = "2026-05-15",
            changes = listOf(
                "所有弟子槽位统一改为弟子半身像+境界显示（52×76dp固定尺寸），移除纯文字名字显示",
                "长老槽位不再显示额外属性文字（炼丹/炼器/灵植/采矿/道德）",
                "战斗日志参战弟子槽位同步统一，血条移至槽位上方",
                "所有弟子槽位禁止同一弟子重复分配，驻守后界面实时刷新"
            )
        ),
        ChangelogEntry(
            version = "3.0.27",
            date = "2026-05-15",
            changes = listOf(
                "所有弟子选择界面统一为半屏弹窗+两列网格展示，补齐背景图",
                "弟子卡片展示界面统一布局：半屏弹窗两列、全屏界面三列",
                "外门大比结果界面改为三列展示"
            )
        ),
        ChangelogEntry(
            version = "3.0.26",
            date = "2026-05-15",
            changes = listOf(
                "半屏弹窗宽度由85%调整为83%，所有建筑界面（炼丹炉、锻造坊、问道塔、青云塔、天枢殿、执法堂、灵矿场、灵植阁、藏经阁、任务阁、监牢）改为半屏展示"
            )
        ),
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

package com.xianxia.sect.core.model

import androidx.compose.runtime.Immutable

/**
 * 具名角色模板——寻访卡池的身份层（产品方案 `docs/character-gacha-redesign-2026-09-23.md` §4.3）。
 *
 * 模板只钉身份：姓名、性别、灵根元素、头像键与立绘键，**共 6 项、不含任何数值字段**。
 * 六维方差、悟性、技能等仍由实例化时的既有确定性 roll 链生成；初始境界与初始星级
 * 是全员同值的口径常量，落在 [CharacterTemplateDb] 的 `STARTUP_*` / `STARTER_STAR` 上。
 *
 * ## 数据来源与镜像纪律
 *
 * 真源为中性源 `scripts/data/gacha_config_sample.json` 的 `characterTemplates`，
 * 经 `node scripts/gen-game-data.mjs` 聚合进产物
 * `android/app/src/main/assets/data/game-data.json` 的 `db.characterTemplates`。
 * 本类与 [CharacterTemplateDb] 构成 **Kotlin 侧镜像**（本域 C++ 无查表需求，故不在
 * game-core 建第二份表），由 `CharacterTemplateGuardTest` 逐字段比对产物配置。
 * **改数据必须先改中性源并重跑生成器**，再同步本表，否则守卫判红。
 *
 * @param id 模板 id；同时是碎片与星级账本（`gachaFragmentCounts` / `gachaStarMap`）的 key 域
 * @param name 弟子姓名，实例化后写入 `Disciple.name` 并终身固定
 * @param gender `"male"` / `"female"`，与 [Disciple.gender] 同取值域（配置侧写作 `"M"` / `"F"`）
 * @param spiritRoots 灵根元素序列，顺序与配置一致，元素取值见 `SpiritRootGenerator` 的元素表
 * @param avatarKey 头像精灵名，经 `SpriteResRegistry.resolve` 解析
 * @param portraitKey 立绘精灵名，经 `PortraitResolver`（内部走 `SpriteResRegistry`）解析
 * @param innateDamageType 固有伤害属性（`"PHYSICAL"`/`"MAGIC"`，方案 §15.3 Q1）：
 *   该角色普攻的伤害类型，创建弟子时写入 `Disciple.combat.innateDamageType` 终身不变
 */
@Immutable
data class CharacterTemplate(
    val id: String,
    val name: String,
    val gender: String,
    val spiritRoots: List<String>,
    val avatarKey: String,
    val portraitKey: String,
    val innateDamageType: String,
) {
    /** 与 [Disciple.spiritRootType] 同域：多灵根以逗号拼接（约定见 `SpiritRootGenerator.generate`） */
    val spiritRootType: String get() = spiritRoots.joinToString(",")
}

/**
 * 6 个具名角色模板的静态表（Kotlin 侧镜像，镜像纪律见 [CharacterTemplate]）。
 *
 * 条目内容与顺序均与 `game-data.json` 的 `db.characterTemplates` 一致，
 * 唯一的字面改写是性别 `"M"`/`"F"` → `"male"`/`"female"`，其余零改写。
 */
object CharacterTemplateDb {

    /** 开局模板弟子 id；与配置 `gachaDefaults.startupTemplateId` 同值，由守卫比对 */
    const val STARTUP_TEMPLATE_ID = "zhouming"

    /** 模板弟子初始境界：9 = 炼气（[Disciple.realm] 取值域） */
    const val STARTUP_REALM = 9

    /** 模板弟子初始境界层数：全员炼气一层 */
    const val STARTUP_REALM_LAYER = 1

    /** 开局即拥有 1 星实例，碎片进度 0（Q34 口径：开局不送碎片） */
    const val STARTER_STAR = 1

    /** 全表，顺序与 `db.characterTemplates` 一致 */
    val ALL: List<CharacterTemplate> = listOf(
        CharacterTemplate(
            id = "zhouming",
            name = "周明",
            gender = "male",
            spiritRoots = listOf("metal"),
            avatarKey = "avatar_zhouming",
            portraitKey = "portrait_zhouming",
            innateDamageType = InnateDamageType.PHYSICAL,
        ),
        CharacterTemplate(
            id = "suqing",
            name = "苏晴",
            gender = "female",
            spiritRoots = listOf("water"),
            avatarKey = "avatar_suqing",
            portraitKey = "portrait_suqing",
            innateDamageType = InnateDamageType.MAGIC,
        ),
        CharacterTemplate(
            id = "linxuetang",
            name = "林雪棠",
            gender = "female",
            spiritRoots = listOf("wood", "water"),
            avatarKey = "avatar_linxuetang",
            portraitKey = "portrait_linxuetang",
            innateDamageType = InnateDamageType.MAGIC,
        ),
        CharacterTemplate(
            id = "xuhe",
            name = "许荷",
            gender = "female",
            spiritRoots = listOf("wood", "earth"),
            avatarKey = "avatar_xuhe",
            portraitKey = "portrait_xuhe",
            innateDamageType = InnateDamageType.MAGIC,
        ),
        CharacterTemplate(
            id = "xieche",
            name = "谢澈",
            gender = "male",
            spiritRoots = listOf("metal", "water"),
            avatarKey = "avatar_xieche",
            portraitKey = "portrait_xieche",
            innateDamageType = InnateDamageType.PHYSICAL,
        ),
        CharacterTemplate(
            id = "zhaoyan",
            name = "赵言",
            gender = "male",
            spiritRoots = listOf("fire", "earth"),
            avatarKey = "avatar_zhaoyan",
            portraitKey = "portrait_zhaoyan",
            innateDamageType = InnateDamageType.MAGIC,
        ),
    )

    private val indexById: Map<String, CharacterTemplate> = ALL.associateBy { it.id }

    /** 全部模板 id，即碎片/星级账本 key 的合法域（顺序同 [ALL]） */
    val ids: Set<String> = indexById.keys

    /**
     * 按模板 id 取模板。
     *
     * @return 命中的模板；未知 id 返回 null——调用方必须显式拒绝，禁止兜底到开局模板
     */
    fun byId(id: String): CharacterTemplate? = indexById[id]
}

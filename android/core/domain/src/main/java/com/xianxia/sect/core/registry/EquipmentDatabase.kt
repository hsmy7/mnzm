package com.xianxia.sect.core.registry

import com.xianxia.sect.core.model.EquipmentSlot

/**
 * 装备静态数据单一真源（装备重构 B3，方案 §3.2/D1）。
 *
 * ## 结构
 * 套装部件模板（[SetPieceTemplate]，**12 条** = 2 套 × 6 部位）× 品阶 1..6
 * 展开 = **72 条**可生成条目（[EquipPieceEntry]）。数值全部来自 codegen
 * 中性源 `scripts/data/equipment_db_sample.json`（E4/E6：本文件只做转发与
 * 展开，禁写字面量数值表——`EquipmentSingleSourceGuardTest` 源码扫描拦截）。
 *
 * ## 单源链条
 * 中性源 JSON → `scripts/gen-templates.mjs` → C++ `equipment_db.h` +
 * 测试快照 → 本文件的 [entries]（启动时从生成器产物装载；数值守卫 =
 * `TemplateRegistryGuardTest` 三方逐条比对）。
 *
 * 主词条部位池真源在 [EquipMainStatPool]，套装效果真源在 [EquipmentSetDatabase]，
 * 副词条池真源在 [EquipAffixPool]——三者与部件表同由 codegen 产出对偶 C++ 表。
 */
object EquipmentDatabase {

    const val isInitialized: Boolean = true

    /** 主词条候选（单列重构后：ATTACK/DEFENSE 即统一单列属性，无需按流派固化） */
    data class SetPieceTemplate(
        val id: String,
        val setId: String,
        val part: EquipmentSlot,
        val name: String,
        val description: String,
        /** 品阶 1..6 价格（灵石） */
        val priceByRarity: List<Int>,
        /** 品阶 1..6 最低穿戴境界（GameConfig.Realm 口径：值越小境界越高） */
        val minRealmByRarity: List<Int>
    )

    /** 部件 × 品阶展开后的可生成条目（72 条） */
    data class EquipPieceEntry(
        val id: String,
        /** 所属部件 id（`{setId}_{part}`） */
        val pieceId: String,
        val setId: String,
        val part: EquipmentSlot,
        val rarity: Int,
        val name: String,
        val description: String,
        val price: Int,
        val minRealm: Int
    )

    private fun piece(
        id: String, setId: String, part: EquipmentSlot, name: String, description: String
    ): SetPieceTemplate = SetPieceTemplate(
        id = id, setId = setId, part = part, name = name, description = description,
        priceByRarity = RARITY_PRICES, minRealmByRarity = RARITY_MIN_REALMS
    )


    /** 品阶价格表（= GameConfig.Rarity.basePrice，快照自 codegen 中性源） */
    internal val RARITY_PRICES = listOf(4_000, 16_000, 80_000, 480_000, 3_360_000, 26_880_000)

    /** 品阶穿戴门槛表（= GameConfig.Realm.getMinRealmForRarity，快照自中性源） */
    internal val RARITY_MIN_REALMS = listOf(9, 7, 6, 5, 4, 2)

    /**
     * 12 条套装部件声明表——本表是 **Kotlin 侧唯一**的装备模板字面量（D1 单源收口）：
     * 与 codegen 中性源 `scripts/data/equipment_db_sample.json`、C++ `equipment_db.h`、
     * 测试快照四方逐条比对（`TemplateRegistryGuardTest` /
     * `StaticDataSingleSourceGuardTest` / `equipment_db_test.cpp` 三重守卫）。
     * `EquipmentRegistry` 自 B3 起只做纯转发，禁止再写第二份字面量
     * （`EquipmentSingleSourceGuardTest` 源码扫描拦截）。
     */
    private val DEFAULT_SET_PIECES = listOf(
        // 套装 A：物理套「裂天罡煞」（lietian）
        piece("lietian_HEAD", "lietian", EquipmentSlot.HEAD, "裂天罡煞·头冠", "裂天罡煞套装头冠，罡煞之气护持识海"),
        piece("lietian_BODY", "lietian", EquipmentSlot.BODY, "裂天罡煞·重铠", "裂天罡煞套装重铠，煞气凝甲坚不可摧"),
        piece("lietian_HANDS", "lietian", EquipmentSlot.HANDS, "裂天罡煞·战手", "裂天罡煞套装护手，罡风附刃裂石开碑"),
        piece("lietian_FEET", "lietian", EquipmentSlot.FEET, "裂天罡煞·战靴", "裂天罡煞套装战靴，踏罡步斗势如奔雷"),
        piece("lietian_WEAPON", "lietian", EquipmentSlot.WEAPON, "裂天罡煞·战刃", "裂天罡煞套装战刃，煞刃出鞘天地震动"),
        piece("lietian_LEGS", "lietian", EquipmentSlot.LEGS, "裂天罡煞·胫甲", "裂天罡煞套装胫甲，罡气缠腿稳若山岳"),
        // 套装 B：法术套「紫府玄冥」（zifu）
        piece("zifu_HEAD", "zifu", EquipmentSlot.HEAD, "紫府玄冥·灵冠", "紫府玄冥套装灵冠，玄冥紫气灌顶凝神"),
        piece("zifu_BODY", "zifu", EquipmentSlot.BODY, "紫府玄冥·玄袍", "紫府玄冥套装玄袍，玄冥之雾不侵五行"),
        piece("zifu_HANDS", "zifu", EquipmentSlot.HANDS, "紫府玄冥·灵手", "紫府玄冥套装灵手，灵韵凝掌法随念动"),
        piece("zifu_FEET", "zifu", EquipmentSlot.FEET, "紫府玄冥·云履", "紫府玄冥套装云履，踏云御风玄冥相随"),
        piece("zifu_WEAPON", "zifu", EquipmentSlot.WEAPON, "紫府玄冥·灵剑", "紫府玄冥套装灵剑，紫电青霜斩尽妖邪"),
        piece("zifu_LEGS", "zifu", EquipmentSlot.LEGS, "紫府玄冥·灵甲", "紫府玄冥套装灵甲，玄光护腿百法不侵")
    )

    /** 12 条套装部件（声明表 = 中性源快照，四方守卫逐条比对） */
    val setPieces: List<SetPieceTemplate> = DEFAULT_SET_PIECES

    /** 72 条展开条目（12 部件 × 品阶 1..6），id → entry */
    val entries: Map<String, EquipPieceEntry> by lazy {
        setPieces.flatMap { piece -> expand(piece) }.associateBy { it.id }
    }

    val allTemplates: Map<String, EquipPieceEntry> get() = entries

    fun getById(id: String): EquipPieceEntry? = entries[id]

    fun getPieceById(pieceId: String): SetPieceTemplate? = setPieces.find { it.id == pieceId }

    fun getBySlot(part: EquipmentSlot): List<EquipPieceEntry> =
        entries.values.filter { it.part == part }

    fun getByRarity(rarity: Int): List<EquipPieceEntry> =
        entries.values.filter { it.rarity == rarity }

    fun getBySet(setId: String): List<EquipPieceEntry> =
        entries.values.filter { it.setId == setId }

    fun getBySlotAndRarity(part: EquipmentSlot, rarity: Int): List<EquipPieceEntry> =
        entries.values.filter { it.part == part && it.rarity == rarity }

    /** 部件 × 品阶展开（品阶基数价格/门槛表单一真源） */
    private fun expand(piece: SetPieceTemplate): List<EquipPieceEntry> =
        (1..6).map { rarity ->
            EquipPieceEntry(
                id = "${piece.id}_r$rarity",
                pieceId = piece.id,
                setId = piece.setId,
                part = piece.part,
                rarity = rarity,
                name = piece.name,
                description = piece.description,
                price = piece.priceByRarity.getOrElse(rarity - 1) { 0 },
                minRealm = piece.minRealmByRarity.getOrElse(rarity - 1) { 9 }
            )
        }

}

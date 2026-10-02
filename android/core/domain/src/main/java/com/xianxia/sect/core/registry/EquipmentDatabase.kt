package com.xianxia.sect.core.registry

import com.xianxia.sect.core.model.EquipmentSlot

/**
 * 装备静态数据单一真源（四部位套装体系）。
 *
 * ## 结构
 * 套装部件模板（[SetPieceTemplate]，**24 条** = 6 套 × 4 部位）× 品阶 1..6
 * 展开 = **144 条**可生成条目（[EquipPieceEntry]）。数值全部来自 codegen
 * 中性源 `scripts/data/equipment_db_sample.json`（本文件只做转发与
 * 展开，禁写字面量数值表——`EquipmentSingleSourceGuardTest` 源码扫描拦截）。
 *
 * ## 单源链条
 * 中性源 JSON → `scripts/gen-templates.mjs` → C++ `equipment_db.h` +
 * 测试快照 → 本文件的 [entries]（启动时从生成器产物装载；数值守卫 =
 * `TemplateRegistryGuardTest` 三方逐条比对）。
 *
 * ## 24 部件命名
 * 物理套保留部件名（头冠/重铠/战手/战靴）；5 元素套统一后缀表
 * （灵冠/法袍/灵护/云履 = 头/身/手/脚），前缀 = 套名——24 个名字由
 * 1 张后缀表 × 6 个套名完全确定。
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

    /** 部件 × 品阶展开后的可生成条目（144 条） */
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
     * 24 条套装部件声明表——本表是 **Kotlin 侧唯一**的装备模板字面量（D1 单源收口）：
     * 与 codegen 中性源 `scripts/data/equipment_db_sample.json`、C++ `equipment_db.h`、
     * 测试快照四方逐条比对（`TemplateRegistryGuardTest` /
     * `StaticDataSingleSourceGuardTest` / `equipment_db_test.cpp` 三重守卫）。
     * `EquipmentRegistry` 只做纯转发，禁止再写第二份字面量
     * （`EquipmentSingleSourceGuardTest` 源码扫描拦截）。
     */
    private val DEFAULT_SET_PIECES = listOf(
        // 套装 1：物理套「裂天罡煞」（lietian，保留装备重构部件名）
        piece("lietian_HEAD", "lietian", EquipmentSlot.HEAD, "裂天罡煞·头冠", "裂天罡煞套装头冠，罡煞之气护持识海"),
        piece("lietian_BODY", "lietian", EquipmentSlot.BODY, "裂天罡煞·重铠", "裂天罡煞套装重铠，煞气凝甲坚不可摧"),
        piece("lietian_HANDS", "lietian", EquipmentSlot.HANDS, "裂天罡煞·战手", "裂天罡煞套装护手，罡风附刃裂石开碑"),
        piece("lietian_FEET", "lietian", EquipmentSlot.FEET, "裂天罡煞·战靴", "裂天罡煞套装战靴，踏罡步斗势如奔雷"),
        // 套装 2：金套「庚金白虎」（gengjin）
        piece("gengjin_HEAD", "gengjin", EquipmentSlot.HEAD, "庚金白虎·灵冠", "庚金白虎套装灵冠，白虎金睛洞察秋毫"),
        piece("gengjin_BODY", "gengjin", EquipmentSlot.BODY, "庚金白虎·法袍", "庚金白虎套装法袍，金气织体刀兵不侵"),
        piece("gengjin_HANDS", "gengjin", EquipmentSlot.HANDS, "庚金白虎·灵护", "庚金白虎套装灵护，锐金凝爪裂金断玉"),
        piece("gengjin_FEET", "gengjin", EquipmentSlot.FEET, "庚金白虎·云履", "庚金白虎套装云履，虎啸风生金戈疾行"),
        // 套装 3：木套「青木长生」（qingmu）
        piece("qingmu_HEAD", "qingmu", EquipmentSlot.HEAD, "青木长生·灵冠", "青木长生套装灵冠，青木灵韵清心明神"),
        piece("qingmu_BODY", "qingmu", EquipmentSlot.BODY, "青木长生·法袍", "青木长生套装法袍，生生不息缠枝为衣"),
        piece("qingmu_HANDS", "qingmu", EquipmentSlot.HANDS, "青木长生·灵护", "青木长生套装灵护，藤蔓缠腕生机盎然"),
        piece("qingmu_FEET", "qingmu", EquipmentSlot.FEET, "青木长生·云履", "青木长生套装云履，踏叶而行轻若春风"),
        // 套装 4：水套「玄水寒渊」（xuanshui）
        piece("xuanshui_HEAD", "xuanshui", EquipmentSlot.HEAD, "玄水寒渊·灵冠", "玄水寒渊套装灵冠，寒渊之息凝神静念"),
        piece("xuanshui_BODY", "xuanshui", EquipmentSlot.BODY, "玄水寒渊·法袍", "玄水寒渊套装法袍，玄水环身百法不沾"),
        piece("xuanshui_HANDS", "xuanshui", EquipmentSlot.HANDS, "玄水寒渊·灵护", "玄水寒渊套装灵护，寒潮覆掌冻结万机"),
        piece("xuanshui_FEET", "xuanshui", EquipmentSlot.FEET, "玄水寒渊·云履", "玄水寒渊套装云履，凌波微步踏水无痕"),
        // 套装 5：火套「离火焚天」（lihuo）
        piece("lihuo_HEAD", "lihuo", EquipmentSlot.HEAD, "离火焚天·灵冠", "离火焚天套装灵冠，离火真焰炼神涤魄"),
        piece("lihuo_BODY", "lihuo", EquipmentSlot.BODY, "离火焚天·法袍", "离火焚天套装法袍，炎纹织体烈焰随身"),
        piece("lihuo_HANDS", "lihuo", EquipmentSlot.HANDS, "离火焚天·灵护", "离火焚天套装灵护，火灵附掌焚尽八荒"),
        piece("lihuo_FEET", "lihuo", EquipmentSlot.FEET, "离火焚天·云履", "离火焚天套装云履，踏火而行燎原疾影"),
        // 套装 6：土套「厚土镇岳」（houtu）
        piece("houtu_HEAD", "houtu", EquipmentSlot.HEAD, "厚土镇岳·灵冠", "厚土镇岳套装灵冠，厚土之德沉稳心神"),
        piece("houtu_BODY", "houtu", EquipmentSlot.BODY, "厚土镇岳·法袍", "厚土镇岳套装法袍，山岳之甲岿然不动"),
        piece("houtu_HANDS", "houtu", EquipmentSlot.HANDS, "厚土镇岳·灵护", "厚土镇岳套装灵护，镇岳之力撼地崩山"),
        piece("houtu_FEET", "houtu", EquipmentSlot.FEET, "厚土镇岳·云履", "厚土镇岳套装云履，踏地生根移山填谷"),
    )

    /** 24 条套装部件（声明表 = 中性源快照，四方守卫逐条比对） */
    val setPieces: List<SetPieceTemplate> = DEFAULT_SET_PIECES

    /** 144 条展开条目（24 部件 × 品阶 1..6），id → entry */
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

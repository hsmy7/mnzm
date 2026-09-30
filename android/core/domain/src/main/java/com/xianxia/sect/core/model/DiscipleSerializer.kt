package com.xianxia.sect.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Disciple 的自定义序列化器。
 *
 * ## 为什么需要自定义序列化器
 * Disciple 域类型使用 Room @Embedded 将字段分散在 5 个子类中，但 Protobuf 要求
 * 全部字段平铺在同一层（与旧 SerializableDisciple 兼容）。直接在每个
 * @Embedded 子类上加 @ProtoNumber 会导致 Protobuf 产生嵌套消息，破坏向后兼容。
 *
 * ## 实现方式
 * 采用「复合 via 代理」（Composite via surrogate）模式：
 * 1. 私有 [DiscipleSurrogate] 数据类模拟旧 SerializableDisciple 的平铺结构
 * 2. 序列化时 Disciple → DiscipleSurrogate → Protobuf bytes
 * 3. 反序列化时 Protobuf bytes → DiscipleSurrogate → Disciple
 * 4. 保证编码后的二进制与旧格式完全一致
 *
 * ## 处理说明
 * - `cultivationCheckpoint`：域模型为 Double，序列化为 Long（与旧格式兼容）
 * - @Ignore 字段（lifeEvents, 运行时 Set 字段）不序列化
 * - slotId 不序列化（Room 复合主键，非游戏字段）
 */
object DiscipleSerializer : KSerializer<Disciple> {
    override val descriptor: SerialDescriptor = DiscipleSurrogate.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Disciple) {
        val surrogate = buildSurrogate(value = value)
        encoder.encodeSerializableValue(DiscipleSurrogate.serializer(), surrogate)
    }

    /** 构建平铺代理对象：直接字段 + 各 @Embedded 段 copy 填充 */
    private fun buildSurrogate(value: Disciple): DiscipleSurrogate {
        var surrogate = DiscipleSurrogate(
            // ===== 直接字段 =====
            id = value.id,
            name = value.name,
            surname = value.surname,
            realm = value.realm,
            realmLayer = value.realmLayer,
            cultivation = value.cultivation,
            cultivationCheckpoint = value.cultivationCheckpoint.toLong(),
            cultivationCheckpointGameMonth = value.cultivationCheckpointGameMonth,
            spiritRootType = value.spiritRootType,
            isAlive = value.isAlive,
            gender = value.gender,
            portraitRes = value.portraitRes,
            templateId = value.templateId,
            manualIds = value.manualIds,
            manualMasteries = value.manualMasteries,
            status = value.status.name,
            statusData = value.statusData,
            cultivationSpeedBonus = value.cultivationSpeedBonus,
            cultivationSpeedDuration = value.cultivationSpeedDuration,
            discipleType = value.discipleType,
            cultivationCompletionMonth = value.cultivationCompletionMonth,
            manualCompletionMonth = value.manualCompletionMonth,
            manualCompletionPhase = value.manualCompletionPhase
        )
        surrogate = withCombatPillFields(surrogate = surrogate, value = value)
        surrogate = withEquipmentUsageFields(surrogate = surrogate, value = value)
        surrogate = withSkillFields(surrogate = surrogate, value = value)
        return surrogate
    }

    /** 战斗属性 + 丹药效果段 */
    private fun withCombatPillFields(surrogate: DiscipleSurrogate, value: Disciple): DiscipleSurrogate =
        surrogate.copy(
            // ===== CombatAttributes @Embedded =====
            baseHp = value.combat.baseHp,
            baseMp = value.combat.baseMp,
            baseAttack = value.combat.baseAttack,
            baseDefense = value.combat.baseDefense,
            baseSpeed = value.combat.baseSpeed,
            hpVariance = value.combat.hpVariance,
            mpVariance = value.combat.mpVariance,
            attackVariance = value.combat.attackVariance,
            defenseVariance = value.combat.defenseVariance,
            speedVariance = value.combat.speedVariance,
            innateDamageType = value.combat.innateDamageType,
            totalCultivation = value.combat.totalCultivation,
            breakthroughCount = value.combat.breakthroughCount,
            breakthroughFailCount = value.combat.breakthroughFailCount,
            currentHp = value.combat.currentHp,
            currentMp = value.combat.currentMp,

            // ===== PillEffects @Embedded =====
            pillAttackBonus = value.pillEffects.pillAttackBonus,
            pillDefenseBonus = value.pillEffects.pillDefenseBonus,
            pillHpBonus = value.pillEffects.pillHpBonus,
            pillMpBonus = value.pillEffects.pillMpBonus,
            pillSpeedBonus = value.pillEffects.pillSpeedBonus,
            pillCritRateBonus = value.pillEffects.pillCritRateBonus,
            pillCritEffectBonus = value.pillEffects.pillCritEffectBonus,
            pillCultivationSpeedBonus = value.pillEffects.pillCultivationSpeedBonus,
            pillSkillExpSpeedBonus = value.pillEffects.pillSkillExpSpeedBonus,
            pillEffectDuration = value.pillEffects.pillEffectDuration,
            activePillCategory = value.pillEffects.activePillCategory,
            activePillTypes = value.pillEffects.activePillTypes.toList()
        )

    /** 装备 + 使用追踪段 */
    private fun withEquipmentUsageFields(surrogate: DiscipleSurrogate, value: Disciple): DiscipleSurrogate =
        surrogate.copy(
            // ===== EquipmentSet @Embedded（六部位，B3 接线：weaponId(17) 复用 +
            // headId(112)..legsId(116) 新增段；旧四槽 id 与 nurture 退役不写） =====
            headId = value.equipment.headId,
            bodyId = value.equipment.bodyId,
            handsId = value.equipment.handsId,
            feetId = value.equipment.feetId,
            weaponId = value.equipment.weaponId,
            legsId = value.equipment.legsId,
            storageBagItems = value.equipment.storageBagItems,
            storageBagSpiritStones = value.equipment.storageBagSpiritStones,
            spiritStones = value.equipment.spiritStones,

            // ===== UsageTracking @Embedded =====
            usedFunctionalPillTypes = value.usage.usedFunctionalPillTypes,
            usedPermanentPillKeys = value.usage.usedPermanentPillKeys.toList(),
            recruitedMonth = value.usage.recruitedMonth,
            hasReviveEffect = value.usage.hasReviveEffect,
            hasClearAllEffect = value.usage.hasClearAllEffect
        )

    /** 技能属性段 */
    private fun withSkillFields(surrogate: DiscipleSurrogate, value: Disciple): DiscipleSurrogate =
        surrogate.copy(
            // ===== SkillStats @Embedded =====
            intelligence = value.skills.intelligence,
            charm = value.skills.charm,
            comprehension = value.skills.comprehension,
            artifactRefining = value.skills.artifactRefining,
            pillRefining = value.skills.pillRefining,
            spiritPlanting = value.skills.spiritPlanting,
            mining = value.skills.mining,
            teaching = value.skills.teaching,
            morality = value.skills.morality,
            salaryPaidCount = value.skills.salaryPaidCount,
            salaryMissedCount = value.skills.salaryMissedCount,
            alchemyLevel = value.skills.alchemyLevel,
            alchemyPromotionCount = value.skills.alchemyPromotionCount,
            forgeLevel = value.skills.forgeLevel,
            forgePromotionCount = value.skills.forgePromotionCount
        )

    override fun deserialize(decoder: Decoder): Disciple {
        val surrogate = decoder.decodeSerializableValue(DiscipleSurrogate.serializer())
        return buildDisciple(surrogate = surrogate)
    }

    /** 从平铺代理对象构建 Disciple：直接字段 + 各 @Embedded 段 copy 填充 */
    private fun buildDisciple(surrogate: DiscipleSurrogate): Disciple {
        var disciple = Disciple(
            // ===== 直接字段 =====
            id = surrogate.id,
            slotId = 0, // slotId 由 StorageEngine 写入时赋值
            name = surrogate.name,
            surname = surrogate.surname,
            realm = surrogate.realm,
            realmLayer = surrogate.realmLayer,
            cultivation = surrogate.cultivation,
            cultivationCheckpoint = surrogate.cultivationCheckpoint.toDouble(),
            cultivationCheckpointGameMonth = surrogate.cultivationCheckpointGameMonth,
            spiritRootType = surrogate.spiritRootType,
            isAlive = surrogate.isAlive,
            gender = surrogate.gender,
            portraitRes = surrogate.portraitRes,
            templateId = surrogate.templateId,
            manualIds = surrogate.manualIds,
            manualMasteries = surrogate.manualMasteries,
            status = safeDiscipleStatus(surrogate.status),
            statusData = surrogate.statusData,
            cultivationSpeedBonus = surrogate.cultivationSpeedBonus,
            cultivationSpeedDuration = surrogate.cultivationSpeedDuration,
            discipleType = surrogate.discipleType,
            cultivationCompletionMonth = surrogate.cultivationCompletionMonth,
            manualCompletionMonth = surrogate.manualCompletionMonth,
            manualCompletionPhase = surrogate.manualCompletionPhase
        )
        disciple = withCombatPillValues(disciple = disciple, surrogate = surrogate)
        disciple = withEquipmentUsageValues(disciple = disciple, surrogate = surrogate)
        disciple = withSkillsValues(disciple = disciple, surrogate = surrogate)
        return disciple
    }

    /** 战斗属性 + 丹药效果段（读面：旧物法双列经线性归一化并入新单列——
     *  新档旧号恒缺省 0，归一化对两代存档皆正确；口径与 Room v62 迁移回填一致） */
    private fun withCombatPillValues(disciple: Disciple, surrogate: DiscipleSurrogate): Disciple =
        disciple.copy(
            combat = CombatAttributes(
                baseHp = surrogate.baseHp,
                baseMp = surrogate.baseMp,
                baseAttack = surrogate.baseAttack + surrogate.basePhysicalAttack + surrogate.baseMagicAttack,
                baseDefense = surrogate.baseDefense + surrogate.basePhysicalDefense + surrogate.baseMagicDefense,
                baseSpeed = surrogate.baseSpeed,
                hpVariance = surrogate.hpVariance,
                mpVariance = surrogate.mpVariance,
                attackVariance = surrogate.attackVariance +
                    (surrogate.physicalAttackVariance + surrogate.magicAttackVariance) / 2,
                defenseVariance = surrogate.defenseVariance +
                    (surrogate.physicalDefenseVariance + surrogate.magicDefenseVariance) / 2,
                speedVariance = surrogate.speedVariance,
                innateDamageType = surrogate.innateDamageType,
                totalCultivation = surrogate.totalCultivation,
                breakthroughCount = surrogate.breakthroughCount,
                breakthroughFailCount = surrogate.breakthroughFailCount,
                currentHp = surrogate.currentHp,
                currentMp = surrogate.currentMp
            ),
            pillEffects = PillEffects(
                pillAttackBonus = surrogate.pillAttackBonus +
                    surrogate.pillPhysicalAttackBonus + surrogate.pillMagicAttackBonus,
                pillDefenseBonus = surrogate.pillDefenseBonus +
                    surrogate.pillPhysicalDefenseBonus + surrogate.pillMagicDefenseBonus,
                pillHpBonus = surrogate.pillHpBonus,
                pillMpBonus = surrogate.pillMpBonus,
                pillSpeedBonus = surrogate.pillSpeedBonus,
                pillCritRateBonus = surrogate.pillCritRateBonus,
                pillCritEffectBonus = surrogate.pillCritEffectBonus,
                pillCultivationSpeedBonus = surrogate.pillCultivationSpeedBonus,
                pillSkillExpSpeedBonus = surrogate.pillSkillExpSpeedBonus,
                pillEffectDuration = surrogate.pillEffectDuration,
                activePillCategory = surrogate.activePillCategory,
                activePillTypes = surrogate.activePillTypes.toSet()
            )
        )

    /** 装备 + 使用追踪段 */
    private fun withEquipmentUsageValues(disciple: Disciple, surrogate: DiscipleSurrogate): Disciple =
        disciple.copy(
            equipment = EquipmentSet(
                // 六部位读面（B3 接线）：weaponId(17) 复用 + headId(112)..legsId(116)；
                // 旧四槽 id（18/19/20）与 nurture（24..27）退役不读——旧档值由
                // MIGRATION_63_64 清空/删列，读默认值即正确语义
                headId = surrogate.headId,
                bodyId = surrogate.bodyId,
                handsId = surrogate.handsId,
                feetId = surrogate.feetId,
                weaponId = surrogate.weaponId,
                legsId = surrogate.legsId,
                storageBagItems = surrogate.storageBagItems,
                storageBagSpiritStones = surrogate.storageBagSpiritStones,
                spiritStones = surrogate.spiritStones
            ),
            usage = UsageTracking(
                usedFunctionalPillTypes = surrogate.usedFunctionalPillTypes,
                usedPermanentPillKeys = surrogate.usedPermanentPillKeys.toSet(),
                recruitedMonth = surrogate.recruitedMonth,
                hasReviveEffect = surrogate.hasReviveEffect,
                hasClearAllEffect = surrogate.hasClearAllEffect
            )
        )

    /** 技能属性段 */
    private fun withSkillsValues(disciple: Disciple, surrogate: DiscipleSurrogate): Disciple =
        disciple.copy(
            skills = SkillStats(
                intelligence = surrogate.intelligence,
                charm = surrogate.charm,
                comprehension = surrogate.comprehension,
                artifactRefining = surrogate.artifactRefining,
                pillRefining = surrogate.pillRefining,
                spiritPlanting = surrogate.spiritPlanting,
                mining = surrogate.mining,
                teaching = surrogate.teaching,
                morality = surrogate.morality,
                salaryPaidCount = surrogate.salaryPaidCount,
                salaryMissedCount = surrogate.salaryMissedCount,
                alchemyLevel = surrogate.alchemyLevel,
                alchemyPromotionCount = surrogate.alchemyPromotionCount,
                forgeLevel = surrogate.forgeLevel,
                forgePromotionCount = surrogate.forgePromotionCount
            )
        )

    private fun safeDiscipleStatus(name: String): DiscipleStatus {
        return try {
            DiscipleStatus.valueOf(name.trim())
        } catch (_: IllegalArgumentException) {
            DiscipleStatus.IDLE
        }
    }

    // ==================== 代理：平铺的 Disciple Protobuf 编码 ====================

    @Serializable
    private data class DiscipleSurrogate(
        // ===== 直接字段 =====
        @ProtoNumber(1) val id: String = "",
        @ProtoNumber(2) val name: String = "",
        @ProtoNumber(100) val surname: String = "",
        @ProtoNumber(3) val realm: Int = 9,
        @ProtoNumber(4) val realmLayer: Int = 1,
        @ProtoNumber(5) val cultivation: Double = 0.0,
        @ProtoNumber(101) val cultivationCheckpoint: Long = 0L,        // 域模型为 Double，序列化为 Long
        @ProtoNumber(91) val cultivationCheckpointGameMonth: Int = 0,
        @ProtoNumber(6) val spiritRootType: String = "metal",
        // reserved 7,8,29,50,76,88;（age/lifespan/soulPower/loyalty/usedExtendLifePillIds/
        // usedExtendLifePillTypes 字段号已退役，禁止复用）
        @ProtoNumber(9) val isAlive: Boolean = true,
        @ProtoNumber(10) val gender: String = "male",
        @ProtoNumber(90) val portraitRes: String = "",
        @ProtoNumber(111) val templateId: String = "",
        @ProtoNumber(21) val manualIds: List<String> = emptyList(),
        // reserved 22,104,105;（talentIds/physiqueIds/affixIds 字段号已退役，禁止复用）
        @ProtoNumber(23) val manualMasteries: Map<String, Int> = emptyMap(),
        @ProtoNumber(32) val status: String = "IDLE",
        @ProtoNumber(33) val statusData: Map<String, String> = emptyMap(),
        @ProtoNumber(34) val cultivationSpeedBonus: Double = 0.0,
        @ProtoNumber(35) val cultivationSpeedDuration: Int = 0,
        @ProtoNumber(74) val discipleType: String = "outer",
        @ProtoNumber(94) val cultivationCompletionMonth: Int = 0,
        // ProtoNumber(95) 已退役（#10 死值 1）：旧档 field 95 宽松忽略
        @ProtoNumber(96) val manualCompletionMonth: Int = 0,
        @ProtoNumber(97) val manualCompletionPhase: Int = 1,
        // reserved 98,99;（equipmentNurturingCompletionMonth/Phase，B3/EQ-B3 退役——
        // 旧档字节按未知字段忽略，禁复用；列式删除见 MIGRATION_63_64）

        // ===== CombatAttributes @Embedded =====
        // 属性单列段（B1，方案 §15 / E1 冻结表增量登记）：
        // 新写入 baseAttack(118)/baseDefense(119)/attackVariance(120)/defenseVariance(121)
        // /innateDamageType(117 接线)；旧双列 69–72 与旧方差 62–65 保留声明仅作
        // 旧档归一化读取（不再写入，退役号禁复用；守卫 EquipmentProtoNumberFrozenTest）。
        @ProtoNumber(67) val baseHp: Int = 120,
        @ProtoNumber(68) val baseMp: Int = 60,
        @Deprecated("旧物攻基值，仅旧档归一化读取；写入走 baseAttack(118)")
        @ProtoNumber(69) val basePhysicalAttack: Int = 0,
        @Deprecated("旧法攻基值，仅旧档归一化读取")
        @ProtoNumber(70) val baseMagicAttack: Int = 0,
        @Deprecated("旧物防基值，仅旧档归一化读取；写入走 baseDefense(119)")
        @ProtoNumber(71) val basePhysicalDefense: Int = 0,
        @Deprecated("旧法防基值，仅旧档归一化读取")
        @ProtoNumber(72) val baseMagicDefense: Int = 0,
        @ProtoNumber(118) val baseAttack: Int = 0,
        @ProtoNumber(119) val baseDefense: Int = 0,
        @ProtoNumber(73) val baseSpeed: Int = 15,
        @ProtoNumber(60) val hpVariance: Int = 0,
        @ProtoNumber(61) val mpVariance: Int = 0,
        @Deprecated("旧物攻方差，仅旧档归一化读取；写入走 attackVariance(120)")
        @ProtoNumber(62) val physicalAttackVariance: Int = 0,
        @Deprecated("旧法攻方差，仅旧档归一化读取")
        @ProtoNumber(63) val magicAttackVariance: Int = 0,
        @Deprecated("旧物防方差，仅旧档归一化读取；写入走 defenseVariance(121)")
        @ProtoNumber(64) val physicalDefenseVariance: Int = 0,
        @Deprecated("旧法防方差，仅旧档归一化读取")
        @ProtoNumber(65) val magicDefenseVariance: Int = 0,
        @ProtoNumber(120) val attackVariance: Int = 0,
        @ProtoNumber(121) val defenseVariance: Int = 0,
        @ProtoNumber(66) val speedVariance: Int = 0,
        @ProtoNumber(117) val innateDamageType: String = "",
        @ProtoNumber(81) val totalCultivation: Long = 0,
        @ProtoNumber(82) val breakthroughCount: Int = 0,
        @ProtoNumber(83) val breakthroughFailCount: Int = 0,
        @ProtoNumber(79) val currentHp: Int = -1,
        @ProtoNumber(80) val currentMp: Int = -1,

        // ===== PillEffects @Embedded =====
        // 新写入 pillAttackBonus(122)/pillDefenseBonus(123)；旧四列 36–39 保留声明
        // 仅作旧档归一化读取（不再写入）。
        @Deprecated("旧丹药物攻加成，仅旧档归一化读取；写入走 pillAttackBonus(122)")
        @ProtoNumber(36) val pillPhysicalAttackBonus: Int = 0,
        @Deprecated("旧丹药法攻加成，仅旧档归一化读取")
        @ProtoNumber(37) val pillMagicAttackBonus: Int = 0,
        @Deprecated("旧丹药物防加成，仅旧档归一化读取；写入走 pillDefenseBonus(123)")
        @ProtoNumber(38) val pillPhysicalDefenseBonus: Int = 0,
        @Deprecated("旧丹药法防加成，仅旧档归一化读取")
        @ProtoNumber(39) val pillMagicDefenseBonus: Int = 0,
        @ProtoNumber(122) val pillAttackBonus: Int = 0,
        @ProtoNumber(123) val pillDefenseBonus: Int = 0,
        @ProtoNumber(40) val pillHpBonus: Int = 0,
        @ProtoNumber(41) val pillMpBonus: Int = 0,
        @ProtoNumber(42) val pillSpeedBonus: Int = 0,
        @ProtoNumber(43) val pillCritRateBonus: Double = 0.0,
        @ProtoNumber(44) val pillCritEffectBonus: Double = 0.0,
        @ProtoNumber(45) val pillCultivationSpeedBonus: Double = 0.0,
        @ProtoNumber(46) val pillSkillExpSpeedBonus: Double = 0.0,
        // reserved 47;（pillNurtureSpeedBonus 孕养丹速度加成，B2/EQ-B2 退役——旧档字节
        // 按未知字段忽略，禁复用；列式删除见 MIGRATION_62_63）
        @ProtoNumber(48) val pillEffectDuration: Int = 0,
        @ProtoNumber(49) val activePillCategory: String = "",
        @ProtoNumber(89) val activePillTypes: List<String> = emptyList(),

        // ===== EquipmentSet @Embedded =====
        // 🔴 E1 冻结表（equipment-batches §1 / 方案 §四 WP0，B0 定稿；B3 落地退役）：
        // weaponId(17) 复用为六部位的武器部位列（唯一复用号，禁再映射其他语义）；
        // 18/19/20 与 24..27 已退役（B3/EQ-B3）——保留声明仅供旧档反序列化
        // （旧档字节可读、值恒丢弃），写入/读取面已全部切六部位，退役号禁复用
        // （守卫 EquipmentProtoNumberFrozenTest）。
        @ProtoNumber(17) val weaponId: String = "",
        @Deprecated("旧护甲部位列，B3 退役；保留声明仅供旧档反序列化，写入走 bodyId(113)")
        @ProtoNumber(18) val armorId: String = "",
        @Deprecated("旧靴子部位列，B3 退役；保留声明仅供旧档反序列化，写入走 feetId(115)")
        @ProtoNumber(19) val bootsId: String = "",
        @Deprecated("旧饰品部位列，B3 退役（饰品位随六部位移除）；保留声明仅供旧档反序列化")
        @ProtoNumber(20) val accessoryId: String = "",
        @Deprecated("旧武器孕养数据，B3 退役（等级随 EquipmentInstance.growth 单点）")
        @ProtoNumber(24) val weaponNurture: EquipmentNurtureData = EquipmentNurtureData("", 0),
        @Deprecated("旧护甲孕养数据，B3 退役")
        @ProtoNumber(25) val armorNurture: EquipmentNurtureData = EquipmentNurtureData("", 0),
        @Deprecated("旧靴子孕养数据，B3 退役")
        @ProtoNumber(26) val bootsNurture: EquipmentNurtureData = EquipmentNurtureData("", 0),
        @Deprecated("旧饰品孕养数据，B3 退役")
        @ProtoNumber(27) val accessoryNurture: EquipmentNurtureData = EquipmentNurtureData("", 0),
        @ProtoNumber(30) val storageBagItems: List<StorageBagItem> = emptyList(),
        @ProtoNumber(31) val storageBagSpiritStones: Long = 0,
        @ProtoNumber(28) val spiritStones: Int = 0,

        // ===== 六部位新增段（E1 冻结表，B0 占号定稿：112..116 部位列按显示序 头/身/手/脚/武/腿） =====
        // 112..116 已于 B3 接线（weaponId(17) 复用为武器部位）；117 innateDamageType 已于 B1 接线。
        // 后续批只允许使用已冻结编号，禁临时新增、禁改号（守卫 EquipmentProtoNumberFrozenTest）。
        @ProtoNumber(112) val headId: String = "",
        @ProtoNumber(113) val bodyId: String = "",
        @ProtoNumber(114) val handsId: String = "",
        @ProtoNumber(115) val feetId: String = "",
        @ProtoNumber(116) val legsId: String = "",

        // reserved 11,12,13,14,15,16,102;（partnerId/partnerSectId/parentId1/parentId2/
        // lastChildYear/griefEndYear/childBirthMonth 字段号已退役，禁止复用）
        // reserved 93;（masterId 字段号已退役，禁止复用）

        // ===== SkillStats @Embedded =====
        @ProtoNumber(84) val intelligence: Int = 50,
        @ProtoNumber(85) val charm: Int = 50,
        @ProtoNumber(51) val comprehension: Int = 50,
        @ProtoNumber(52) val artifactRefining: Int = 50,
        @ProtoNumber(53) val pillRefining: Int = 50,
        @ProtoNumber(54) val spiritPlanting: Int = 50,
        @ProtoNumber(86) val mining: Int = 50,
        @ProtoNumber(55) val teaching: Int = 50,
        @ProtoNumber(56) val morality: Int = 50,
        @ProtoNumber(57) val salaryPaidCount: Int = 0,
        @ProtoNumber(58) val salaryMissedCount: Int = 0,
        @ProtoNumber(106) val alchemyLevel: Int = 0,
        @ProtoNumber(107) val alchemyPromotionCount: Int = 0,
        @ProtoNumber(108) val forgeLevel: Int = 0,
        @ProtoNumber(109) val forgePromotionCount: Int = 0,
        // reserved 110;（aptitude 字段号已退役，禁止复用）

        // ===== UsageTracking @Embedded =====
        @ProtoNumber(75) val usedFunctionalPillTypes: List<String> = emptyList(),
        @ProtoNumber(87) val usedPermanentPillKeys: List<String> = emptyList(),
        @ProtoNumber(59) val recruitedMonth: Int = 0,
        @ProtoNumber(77) val hasReviveEffect: Boolean = false,
        @ProtoNumber(78) val hasClearAllEffect: Boolean = false,
    )
}

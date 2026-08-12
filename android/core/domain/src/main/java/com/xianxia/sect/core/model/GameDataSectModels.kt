package com.xianxia.sect.core.model

import androidx.annotation.Keep
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.protobuf.ProtoPacked

// GameDataSectModels.kt — 宗门政策/长老/直系弟子（P-2 从 GameData.kt 拆分，同包模型，序列化字段不变）

// 宗门政策数据
@Keep
@Serializable
data class SectPolicies(
    // 旧有7项政策
    @ProtoNumber(1) val spiritMineBoost: Boolean = false,
    @ProtoNumber(2) val enhancedSecurity: Boolean = false,
    @ProtoNumber(3) val alchemyIncentive: Boolean = false,
    @ProtoNumber(4) val forgeIncentive: Boolean = false,
    @ProtoNumber(5) val herbCultivation: Boolean = false,
    @ProtoNumber(6) val cultivationSubsidy: Boolean = false,
    @ProtoNumber(7) val manualResearch: Boolean = false,

    // 自动分配政策组（连续编号 8-28）
    @ProtoNumber(8) val autoPlant: Boolean = false,
    @ProtoNumber(9) val autoAlchemy: Boolean = false,
    @ProtoNumber(10) val autoForge: Boolean = false,
    // 自动分配：focused = 已关注, rootCounts = 灵根数量筛选, threshold = 属性门槛
    @ProtoNumber(11) val autoMineFocused: Boolean = false,
    @ProtoPacked @ProtoNumber(12) val autoMineRootCounts: List<Int> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @ProtoNumber(13) val autoMineThreshold: Int = 1,
    @ProtoNumber(14) val autoPlantFocused: Boolean = false,
    @ProtoPacked @ProtoNumber(15) val autoPlantRootCounts: List<Int> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @ProtoNumber(16) val autoPlantThreshold: Int = 1,
    @ProtoNumber(17) val autoAlchemyFocused: Boolean = false,
    @ProtoPacked @ProtoNumber(18) val autoAlchemyRootCounts: List<Int> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @ProtoNumber(19) val autoAlchemyThreshold: Int = 1,
    @ProtoNumber(20) val autoForgeFocused: Boolean = false,
    @ProtoPacked @ProtoNumber(21) val autoForgeRootCounts: List<Int> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @ProtoNumber(22) val autoForgeThreshold: Int = 1,
    @ProtoNumber(23) val autoSingleResidenceFocused: Boolean = false,
    @ProtoPacked @ProtoNumber(24) val autoSingleResidenceRootCounts: List<Int> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @ProtoNumber(25) val autoSingleResidenceThreshold: Int = 1,
    @ProtoNumber(26) val autoMultiResidenceFocused: Boolean = false,
    @ProtoPacked @ProtoNumber(27) val autoMultiResidenceRootCounts: List<Int> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @ProtoNumber(28) val autoMultiResidenceThreshold: Int = 1,

    // 新增10项政策
    @ProtoNumber(29) val openRecruitment: Boolean = false,           // 广纳门徒
    @ProtoNumber(30) val asceticTraining: Boolean = false,            // 苦修令
    @ProtoNumber(31) val curfew: Boolean = false,                     // 宵禁
    @ProtoNumber(32) val rewardPunish: Boolean = false,               // 赏善罚恶
    @ProtoNumber(33) val strictTraining: Boolean = false,             // 严苛训练
    @ProtoNumber(34) val relaxedMgmt: Boolean = false,                // 松弛管理
    @ProtoNumber(35) val spiritSpring: Boolean = false,               // 灵泉灌溉
    @ProtoNumber(36) val frugality: Boolean = false,                  // 开源节流
    @ProtoNumber(37) val moralEducation: Boolean = false,             // 教化之道
    @ProtoNumber(38) val benevolentGovernance: Boolean = false       // 仁政爱徒
)

// 长老槽位数据
@Keep
@Serializable
data class ElderSlots(
    @ProtoNumber(1) val viceSectMaster: String = "",
    @ProtoNumber(2) val herbGardenElder: String = "",
    @ProtoNumber(3) val alchemyElder: String = "",
    @ProtoNumber(4) val forgeElder: String = "",
    @ProtoNumber(6) val outerElder: String = "",
    @ProtoNumber(7) val preachingElder: String = "",
    @ProtoNumber(8) val preachingMasters: List<DirectDiscipleSlot> = emptyList(),
    @ProtoNumber(9) val lawEnforcementElder: String = "",
    @ProtoNumber(10) val lawEnforcementDisciples: List<DirectDiscipleSlot> = emptyList(),
    @ProtoNumber(12) val innerElder: String = "",
    @ProtoNumber(13) val qingyunPreachingElder: String = "",
    @ProtoNumber(14) val qingyunPreachingMasters: List<DirectDiscipleSlot> = emptyList(),
    @ProtoNumber(15) val herbGardenDisciples: List<DirectDiscipleSlot> = emptyList(),
    @ProtoNumber(16) val alchemyDisciples: List<DirectDiscipleSlot> = emptyList(),
    @ProtoNumber(17) val forgeDisciples: List<DirectDiscipleSlot> = emptyList(),
    @ProtoNumber(22) val spiritMineDeaconDisciples: List<DirectDiscipleSlot> = emptyList(),
    @ProtoNumber(23) val recruitingElder: String = ""
) {
    fun isDiscipleInAnyPosition(discipleId: String): Boolean {
        if (viceSectMaster == discipleId) return true

        val allElderIds = listOf(
            herbGardenElder, alchemyElder, forgeElder,
            outerElder, preachingElder, lawEnforcementElder,
            innerElder, recruitingElder, qingyunPreachingElder
        )
        if (allElderIds.contains(discipleId)) return true

        val allDirectDiscipleIds = listOf(
            herbGardenDisciples, alchemyDisciples, forgeDisciples,
            preachingMasters, lawEnforcementDisciples,
            qingyunPreachingMasters, spiritMineDeaconDisciples
        ).flatten().mapNotNull { it.discipleId.ifEmpty { null } }

        return allDirectDiscipleIds.contains(discipleId)
    }

    /**
     * 解析弟子当前担任的具体职位名（用于 MANAGING 状态的职位文案）。
     * 优先级与 MANAGING 状态推导的槽位数据源一致：长老职位（副宗主/各长老）在前，
     * 弟子职务（灵植/炼丹/锻造弟子）在后；无职位返回 null（UI 层兜底"管理中"）。
     */
    fun resolvePositionName(discipleId: String): String? {
        if (discipleId.isBlank()) return null
        return resolveElderPositionName(discipleId) ?: when {
            herbGardenDisciples.any { it.discipleId == discipleId } -> "灵植弟子"
            alchemyDisciples.any { it.discipleId == discipleId } -> "炼丹弟子"
            forgeDisciples.any { it.discipleId == discipleId } -> "锻造弟子"
            else -> null
        }
    }

    /** 长老职位名解析（10 槽位 when 独立成函数，控制 [resolvePositionName] 圈复杂度） */
    private fun resolveElderPositionName(discipleId: String): String? = when (discipleId) {
        viceSectMaster -> formatSlotTypeName(ElderSlotType.VICE_SECT_MASTER)
        herbGardenElder -> formatSlotTypeName(ElderSlotType.HERB_GARDEN)
        alchemyElder -> formatSlotTypeName(ElderSlotType.ALCHEMY)
        forgeElder -> formatSlotTypeName(ElderSlotType.FORGE)
        outerElder -> formatSlotTypeName(ElderSlotType.OUTER_ELDER)
        innerElder -> formatSlotTypeName(ElderSlotType.INNER_ELDER)
        recruitingElder -> formatSlotTypeName(ElderSlotType.RECRUITING)
        preachingElder -> formatSlotTypeName(ElderSlotType.PREACHING)
        qingyunPreachingElder -> formatSlotTypeName(ElderSlotType.CLOUD_PREACHING)
        lawEnforcementElder -> formatSlotTypeName(ElderSlotType.LAW_ENFORCEMENT)
        else -> null
    }
}

// 亲传弟子槽位数据
@Keep
@Serializable
data class DirectDiscipleSlot(
    @ProtoNumber(1) val index: Int = 0,
    @ProtoNumber(2) val discipleId: String = "",
    @ProtoNumber(3) val discipleName: String = "",
    @ProtoNumber(4) val discipleRealm: String = "",
    @ProtoNumber(5) val discipleSpiritRootColor: String = "#E0E0E0",
    @ProtoNumber(6) val sectId: String = ""
) {
    val isActive: Boolean get() = discipleId.isNotEmpty()
}

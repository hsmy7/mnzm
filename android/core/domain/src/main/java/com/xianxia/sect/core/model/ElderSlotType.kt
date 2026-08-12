package com.xianxia.sect.core.model

enum class ElderSlotType {
    VICE_SECT_MASTER,
    HERB_GARDEN,
    ALCHEMY,
    FORGE,
    OUTER_ELDER,
    PREACHING,
    LAW_ENFORCEMENT,
    INNER_ELDER,
    RECRUITING,
    CLOUD_PREACHING;

    val key: String get() = when (this) {
        VICE_SECT_MASTER -> "viceSectMaster"
        HERB_GARDEN -> "herbGarden"
        ALCHEMY -> "alchemy"
        FORGE -> "forge"
        OUTER_ELDER -> "outerElder"
        PREACHING -> "preachingElder"
        LAW_ENFORCEMENT -> "lawEnforcementElder"
        INNER_ELDER -> "innerElder"
        RECRUITING -> "recruitingElder"
        CLOUD_PREACHING -> "qingyunPreachingElder"
    }
}

/** 职务类型显示名（担任该职务时的职位文案，引擎推导与 UI 展示共用） */
fun formatSlotTypeName(slotType: ElderSlotType): String = when (slotType) {
    ElderSlotType.VICE_SECT_MASTER -> "副宗主"
    ElderSlotType.HERB_GARDEN -> "灵田长老"
    ElderSlotType.ALCHEMY -> "炼丹长老"
    ElderSlotType.FORGE -> "炼器长老"
    ElderSlotType.OUTER_ELDER -> "外门长老"
    ElderSlotType.PREACHING -> "传道长老"
    ElderSlotType.LAW_ENFORCEMENT -> "执法长老"
    ElderSlotType.INNER_ELDER -> "内门长老"
    ElderSlotType.RECRUITING -> "纳徒长老"
    ElderSlotType.CLOUD_PREACHING -> "青云传道长老"
}

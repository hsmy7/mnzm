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
        CLOUD_PREACHING -> "qingyunPreachingElder"
    }
}

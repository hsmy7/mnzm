package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.ManualStack

/** 单弟子自动装备/学习结果（B：袋内实例装配 / 被替换旧实例） */
internal data class AutoWarehouseResult(
    val disciple: Disciple,
    val eqStacks: List<EquipmentStack>,
    val mnStacks: List<ManualStack>
)

package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.EquipmentNurtureSystem
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.state.DiscipleTables
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 装备孕养服务。
 *
 * 职责：
 * - 计算单弟子装备孕养增量
 * - 装备孕养结算写入累积映射
 */
@Singleton
@GameService("EquipmentNurtureService")
class EquipmentNurtureService @Inject constructor() {

    /** 纯函数：计算单弟子装备孕养增量 */
    fun computeNurtureDelta(
        id: Int, tables: DiscipleTables,
        equipmentMap: Map<String, EquipmentInstance>,
        nurtureGainPerPhase: Double, phasesToSettle: Int,
        resultMap: MutableMap<String, EquipmentInstance>
    ) {
        listOf(
            tables.weaponIds[id], tables.armorIds[id],
            tables.bootsIds[id], tables.accessoryIds[id]
        ).filter { it.isNotEmpty() }.forEach { eqId ->
            val eq = equipmentMap[eqId] ?: return@forEach
            val result = EquipmentNurtureSystem.updateNurtureExp(
                eq, nurtureGainPerPhase * phasesToSettle
            )
            if (result.equipment != eq) {
                resultMap[eqId] = result.equipment
            }
        }
    }

    /** 装备孕养结算：单弟子装备孕养增量写入累积映射。 */
    fun settleNurtureInPlace(
        id: Int, tables: DiscipleTables,
        equipmentMap: Map<String, EquipmentInstance>,
        nurtureGainPerPhase: Double, phasesToSettle: Int,
        equipmentUpdates: MutableMap<String, EquipmentInstance>
    ) {
        listOf(
            tables.weaponIds[id], tables.armorIds[id],
            tables.bootsIds[id], tables.accessoryIds[id]
        ).filter { it.isNotEmpty() }.forEach { eqId ->
            val eq = equipmentMap[eqId] ?: return@forEach
            val result = EquipmentNurtureSystem.updateNurtureExp(
                eq, nurtureGainPerPhase * phasesToSettle
            )
            if (result.equipment != eq) {
                equipmentUpdates[eqId] = result.equipment
            }
        }
    }

    companion object {
        private const val TAG = "EquipmentNurtureService"
    }
}

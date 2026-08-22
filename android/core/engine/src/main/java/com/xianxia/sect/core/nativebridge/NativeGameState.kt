package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.StorageBag
import kotlinx.serialization.Serializable

/**
 * NativeGameState — 快照协议 DTO（C++ GameState 的 Kotlin 镜像）。
 *
 * 对应 C++ `gamecore::state::GameState`（models.h），JSON 字段名一一对应：
 * Kotlin 侧 kotlinx.serialization JSON 编码 ↔ C++ 侧 nlohmann/json。
 *
 * 用途（Kotlin→C++ 迁移批次 1+）：
 *   - 读档：Kotlin 从 Room 读状态 → 编码本 DTO → nativeImportState → C++
 *   - 存档/UI 镜像：nativeExportState → 解码本 DTO → GameStateStore / 存档链路
 *
 * 字段随 C++ 模型迁移逐步扩充（当前覆盖批次 1 第一子步：GameData 标量/
 * 简单集合 + 弟子/物品核心字段；嵌套对象后续批次追加）。
 */
@Serializable
data class NativeGameState(
    val gameData: GameData = GameData(),
    val disciples: List<Disciple> = emptyList(),
    val equipmentStacks: List<EquipmentStack> = emptyList(),
    val equipmentInstances: List<EquipmentInstance> = emptyList(),
    val manualStacks: List<ManualStack> = emptyList(),
    val manualInstances: List<ManualInstance> = emptyList(),
    val pills: List<Pill> = emptyList(),
    val materials: List<Material> = emptyList(),
    val herbs: List<Herb> = emptyList(),
    val seeds: List<Seed> = emptyList(),
    val storageBags: List<StorageBag> = emptyList()
)

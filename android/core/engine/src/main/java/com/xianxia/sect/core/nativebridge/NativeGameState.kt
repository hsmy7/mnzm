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
 * 用途：
 *   - 读档：Kotlin 从 Room 读状态 → 编码本 DTO → nativeImportState → C++
 *   - 存档/UI 镜像：nativeExportState → 解码本 DTO → GameStateStore / 存档链路
 *
 * 字段与 C++ 模型（models.h）同步扩充（当前覆盖 GameData 标量/
 * 简单集合 + 弟子/物品核心字段等）。
 */
@Serializable
data class NativeGameState(
    val gameData: GameData = GameData(),
    // AI 宗门弟子池（顶层字段——GameData.aiSectDisciples 为
    // @Transient 重型数据，不进 kotlinx 序列化；快照协议经本字段显式承载，
    // 与 C++ GameState.aiSectDisciples 一一对应，见 models.h GameState）。
    // 可空语义：null = 旧 .so 未导出（镜像不回写，Kotlin 侧保持权威）；
    // 非 null = C++ 导出值（镜像写回 gameData.aiSectDisciples）
    val aiSectDisciples: Map<String, List<Disciple>>? = null,
    // AI 宗门妖兽攻击域（Kotlin GameData 同名三字段 @Transient 不入
    // kotlinx 序列化——快照协议经顶层字段显式承载，与 C++ GameState 一一对应，
    // 语义同 aiSectDisciples：null = 旧 .so 未导出（镜像不回写保持 Kotlin 权威）；
    // 非 null = C++ 导出值（镜像写回 gameData 对应字段））
    val aiSectBeastDirectTargets: Map<String, List<String>>? = null,
    val aiSectBeastSkipCooldowns: Map<String, Int>? = null,
    val lockedBeastIds: Set<String>? = null,
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

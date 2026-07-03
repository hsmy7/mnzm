package com.xianxia.sect.core.engine.domain.settlement

import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.GameStateStore

/**
 * 轻量指纹快照 — 避免指纹检测时全量 deepCopy。
 *
 * 直接从 [GameStateStore] 的当前可读 API 取值，
 * 全部为引用拷贝，零 deepCopy 开销。
 *
 * 仅在指纹变化时（罕见）回退到完整 [MutableGameState] shadow。
 */
data class FingerprintSnapshot(
    val discipleTables: DiscipleTables,
    val gameData: GameData,
    val equipmentInstances: List<EquipmentInstance>
) {
    companion object {
        /**
         * 从 [GameStateStore] 的当前状态构建轻量快照。
         * 所有字段直接引用已有对象，不分配新内存。
         */
        fun take(store: GameStateStore): FingerprintSnapshot {
            return FingerprintSnapshot(
                discipleTables = store.discipleTables,
                gameData = store.gameData.value,
                equipmentInstances = store.equipmentInstances.value
            )
        }
    }
}

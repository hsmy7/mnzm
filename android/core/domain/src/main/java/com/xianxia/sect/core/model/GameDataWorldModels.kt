package com.xianxia.sect.core.model

import androidx.annotation.Keep
import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

// GameDataWorldModels.kt — 世界地图/宗门/仓库/探索（P-2 从 GameData.kt 拆分，同包模型，序列化字段不变）

data class WorldMapRenderData(
    val worldMapSects: List<WorldSect> = emptyList(),
    val cultivatorCaves: List<CultivatorCave> = emptyList(),
    val worldLevels: List<WorldLevel> = emptyList(),
    val secretRealm: SecretRealmState? = null
)

data class WorldMapDialogState(
    val showScout: Boolean = false,
    val selectedScoutSectId: String? = null,
    val showTrade: Boolean = false,
    val selectedTradeSectId: String? = null,
    val tradeItems: List<MerchantItem> = emptyList(),
    val showSectDiplomacy: Boolean = false,
    val selectedSectDiplomacySectId: String? = null
)

enum class WorldMapDialogType { SCOUT, TRADE }

// 世界宗门（轻量核心数据，用于地图渲染和游戏逻辑）
@Immutable
@Keep
@Serializable
data class WorldSect(
    @ProtoNumber(1) val id: String = "",
    @ProtoNumber(2) val name: String = "",
    @ProtoNumber(3) val level: Int = 0,
    @ProtoNumber(4) val levelName: String = "小型宗门",
    @ProtoNumber(5) val x: Float = 0f,
    @ProtoNumber(6) val y: Float = 0f,
    @ProtoNumber(7) val distance: Int = 0,
    @ProtoNumber(8) val isPlayerSect: Boolean = false,
    @ProtoNumber(9) val discovered: Boolean = false,
    @ProtoNumber(10) val isKnown: Boolean = false,
    @ProtoNumber(11) val relation: Int = 0,
    @ProtoNumber(12) val disciples: Map<Int, Int> = emptyMap(),
    @ProtoNumber(13) val maxRealm: Int = 9,
    @ProtoNumber(15) val isOccupied: Boolean = false,
    @ProtoNumber(16) val occupierTeamId: String = "",
    @ProtoNumber(17) val occupierTeamName: String = "",
    @ProtoNumber(27) val allianceId: String = "",
    @ProtoNumber(28) val allianceStartYear: Int = 0,
    @ProtoNumber(29) val isRighteous: Boolean = true,
    @ProtoNumber(31) val isPlayerOccupied: Boolean = false,
    @ProtoNumber(33) val isUnderAttack: Boolean = false,
    @ProtoNumber(34) val attackerSectId: String = "",
    @ProtoNumber(35) val occupierSectId: String = "",
    @ProtoNumber(38) val garrisonSlots: List<GarrisonSlot> = buildList {
        repeat(10) { index ->
            add(GarrisonSlot(index = index))
        }
    },
    @ProtoNumber(39) val occupierBattleTeamId: String = ""
)

@Keep
@Serializable
data class SectDetail(
    @ProtoNumber(1) val sectId: String = "",
    @ProtoNumber(2) val mineSlots: List<MineSlot> = emptyList(),
    @ProtoNumber(3) val occupationTime: Long = 0,
    @ProtoNumber(4) val isOwned: Boolean = false,
    @ProtoNumber(5) val expiryYear: Int = 0,
    @ProtoNumber(6) val expiryMonth: Int = 0,
    @ProtoNumber(7) val scoutInfo: SectScoutInfo = SectScoutInfo(),
    @ProtoNumber(8) val tradeItems: List<MerchantItem> = emptyList(),
    @ProtoNumber(9) val tradeLastRefreshYear: Int = 0,
    @ProtoNumber(10) val lastGiftYear: Int = 0,
    @ProtoNumber(11) val warehouse: SectWarehouse = SectWarehouse(),
    @ProtoNumber(12) val giftPreference: GiftPreferenceType = GiftPreferenceType.NONE,
    @ProtoNumber(13) val portraitRes: String = ""
)

@Keep
@Serializable
data class SectWarehouse(
    @ProtoNumber(1) val items: List<WarehouseItem> = emptyList(),
    @ProtoNumber(2) val spiritStones: Long = 0,
    @ProtoNumber(3) val midGradeSpiritStones: Long = 0,
    @ProtoNumber(4) val highGradeSpiritStones: Long = 0
)

@Keep
@Serializable
data class WarehouseItem(
    @ProtoNumber(1) val itemId: String = "",
    @ProtoNumber(2) val itemName: String = "",
    @ProtoNumber(3) val itemType: String = "",
    @ProtoNumber(4) val rarity: Int = 1,
    @ProtoNumber(5) val quantity: Int = 1
)

// 已探索宗门信息
@Keep
@Serializable
data class ExploredSectInfo(
    @ProtoNumber(1) val sectId: String = "",
    @ProtoNumber(2) val sectName: String = "",
    @ProtoNumber(3) val year: Int = 0,
    @ProtoNumber(4) val month: Int = 0,
    @ProtoNumber(5) val duration: Int = 0,
    @ProtoNumber(6) val memberIds: List<String> = emptyList(),
    @ProtoNumber(7) val memberNames: List<String> = emptyList(),
    @ProtoNumber(8) val events: List<String> = emptyList(),
    @ProtoNumber(9) val rewards: List<String> = emptyList(),
    @ProtoNumber(10) val battleCount: Int = 0,
    @ProtoNumber(11) val casualties: Int = 0,
    @ProtoNumber(12) val discipleCount: Int = 0,
    @ProtoNumber(13) val maxRealm: Int = 9
)

// 宗门侦查信息
@Keep
@Serializable
data class SectScoutInfo(
    @ProtoNumber(1) val sectId: String = "",
    @ProtoNumber(2) val sectName: String = "",
    @ProtoNumber(3) val scoutYear: Int = 0,
    @ProtoNumber(4) val scoutMonth: Int = 0,
    @ProtoNumber(5) val discipleCount: Int = 0,
    @ProtoNumber(6) val maxRealm: Int = 9,
    @ProtoNumber(7) val resources: Map<String, Int> = emptyMap(),
    @ProtoNumber(8) val isKnown: Boolean = false,
    @ProtoNumber(9) val disciples: Map<Int, Int> = emptyMap(),
    @ProtoNumber(10) val expiryYear: Int = 0,
    @ProtoNumber(11) val expiryMonth: Int = 0
)

// 灵矿槽位
@Keep
@Serializable
data class SpiritMineSlot(
    @ProtoNumber(1) val index: Int = 0,
    @ProtoNumber(2) val discipleId: String = "",
    @ProtoNumber(3) val discipleName: String = "",
    @ProtoNumber(4) val output: Int = 100,
    @ProtoNumber(5) val sectId: String = "",
    @ProtoNumber(8) val consecutiveMiningMonths: Int = 0,
    /**
     * 所属灵矿场建筑实例 ID。
     *
     * 用于建筑移除时按实例精确匹配槽位，替代旧的 `dropLast(3)` 按位置截断模式。
     * 旧存档加载时为空字符串，由 [com.xianxia.sect.core.engine.GameEngine.validateAndFixSpiritMineData]
     * 按建筑顺序回填。
     */
    @ProtoNumber(7) val buildingInstanceId: String = ""
) {
    val isActive: Boolean get() = discipleId.isNotEmpty()
}

@Keep
@Serializable
data class ResidenceSlot(
    @ProtoNumber(1) val buildingInstanceId: String = "",
    @ProtoNumber(2) val slotIndex: Int = 0,
    @ProtoNumber(3) val discipleId: String = "",
    @ProtoNumber(4) val discipleName: String = ""
) {
    val isActive: Boolean get() = discipleId.isNotEmpty()
}

@Keep
@Serializable
data class WarehouseGarrisonSlot(
    @ProtoNumber(1) val buildingInstanceId: String = "",
    @ProtoNumber(2) val discipleId: String = "",
    @ProtoNumber(3) val discipleName: String = "",
    @ProtoNumber(4) val sectId: String = "",
    @ProtoNumber(5) val slotIndex: Int = 0               // 新增字段放末尾，兼容旧存档的位置参数调用
) {
    val isActive: Boolean get() = discipleId.isNotEmpty()
}

@Keep
@Serializable
data class LibrarySlot(
    @ProtoNumber(1) val index: Int = 0,
    @ProtoNumber(4) val buildingInstanceId: String = "",   // 新增字段，默认值 "" 兼容旧存档
    @ProtoNumber(2) val discipleId: String = "",
    @ProtoNumber(3) val discipleName: String = ""
) {
    val isActive: Boolean get() = discipleId.isNotEmpty()
}

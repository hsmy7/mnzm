package com.xianxia.sect.data.cache

data class CacheKey(
    val type: String,
    val slot: Int,
    val id: String,
    val ttl: Long = DEFAULT_TTL
) {
    companion object {
        const val DEFAULT_TTL = 3600_000L
        const val TYPE_DISCIPLE = "disciple"
        const val TYPE_EQUIPMENT = "equipment"
        const val TYPE_MANUAL = "manual"
        const val TYPE_PILL = "pill"
        const val TYPE_MATERIAL = "material"
        const val TYPE_HERB = "herb"
        const val TYPE_SEED = "seed"
        const val TYPE_TEAM = "team"
        const val TYPE_GAME_DATA = "game_data"
        const val TYPE_BUILDING_SLOT = "building_slot"
        const val TYPE_BUILDING = "building"
        const val TYPE_EVENT = "event"
        const val TYPE_BATTLE_LOG = "battle_log"
        const val TYPE_ALLIANCE = "alliance"
        const val TYPE_ALCHEMY_SLOT = "alchemy_slot"

        fun fromString(key: String): CacheKey {
            val parts = key.split(":")
            return when (parts.size) {
                3 -> CacheKey(parts[0], parts[1].toIntOrNull() ?: 0, parts[2])
                2 -> CacheKey(parts[0], 0, parts[1])
                else -> CacheKey(key, 0, "")
            }
        }

        fun disciple(slot: Int, id: String) = CacheKey(TYPE_DISCIPLE, slot, id)
        fun equipment(slot: Int, id: String) = CacheKey(TYPE_EQUIPMENT, slot, id)
        fun manual(slot: Int, id: String) = CacheKey(TYPE_MANUAL, slot, id)
        fun pill(slot: Int, id: String) = CacheKey(TYPE_PILL, slot, id)
        fun material(slot: Int, id: String) = CacheKey(TYPE_MATERIAL, slot, id)
        fun herb(slot: Int, id: String) = CacheKey(TYPE_HERB, slot, id)
        fun seed(slot: Int, id: String) = CacheKey(TYPE_SEED, slot, id)
        fun team(slot: Int, id: String) = CacheKey(TYPE_TEAM, slot, id)
        fun gameData(slot: Int) = CacheKey(TYPE_GAME_DATA, slot, "current")
        fun buildingSlot(slot: Int, id: String) = CacheKey(TYPE_BUILDING_SLOT, slot, id)
        fun building(slot: Int, id: String) = CacheKey(TYPE_BUILDING, slot, id)
        fun event(slot: Int, id: String) = CacheKey(TYPE_EVENT, slot, id)
        fun battleLog(slot: Int, id: String) = CacheKey(TYPE_BATTLE_LOG, slot, id)
        fun alliance(slot: Int, id: String) = CacheKey(TYPE_ALLIANCE, slot, id)
        fun alchemySlot(slot: Int, id: String) = CacheKey(TYPE_ALCHEMY_SLOT, slot, id)
        
        fun forGameData(slot: Int) = CacheKey(TYPE_GAME_DATA, slot, "current")
        fun forDisciple(slot: Int, discipleId: String) = CacheKey(TYPE_DISCIPLE, slot, discipleId)
        fun forEquipment(slot: Int, equipmentId: String) = CacheKey(TYPE_EQUIPMENT, slot, equipmentId)
        fun forManual(slot: Int, manualId: String) = CacheKey(TYPE_MANUAL, slot, manualId)
        fun forPill(slot: Int, pillId: String) = CacheKey(TYPE_PILL, slot, pillId)
        fun forMaterial(slot: Int, materialId: String) = CacheKey(TYPE_MATERIAL, slot, materialId)
        fun forHerb(slot: Int, herbId: String) = CacheKey(TYPE_HERB, slot, herbId)
        fun forSeed(slot: Int, seedId: String) = CacheKey(TYPE_SEED, slot, seedId)
        fun forTeam(slot: Int, teamId: String) = CacheKey(TYPE_TEAM, slot, teamId)
        fun forBuildingSlot(slot: Int, buildingSlotId: String) = CacheKey(TYPE_BUILDING_SLOT, slot, buildingSlotId)
        fun forBuilding(slot: Int, buildingId: String) = CacheKey(TYPE_BUILDING, slot, buildingId)
        fun forEvent(slot: Int, eventId: String) = CacheKey(TYPE_EVENT, slot, eventId)
        fun forBattleLog(slot: Int, battleLogId: String) = CacheKey(TYPE_BATTLE_LOG, slot, battleLogId)
        fun forAlliance(slot: Int, allianceId: String) = CacheKey(TYPE_ALLIANCE, slot, allianceId)
        fun forAlchemySlot(slot: Int, alchemySlotId: String) = CacheKey(TYPE_ALCHEMY_SLOT, slot, alchemySlotId)
    }

    override fun toString(): String = "$type:$slot:$id"

    fun toByteArray(): ByteArray = toString().toByteArray(Charsets.UTF_8)
    
    fun withSlot(newSlot: Int): CacheKey = copy(slot = newSlot)
    
    fun withTtl(newTtl: Long): CacheKey = copy(ttl = newTtl)
    
    fun matchesSlot(targetSlot: Int): Boolean = slot == targetSlot
    
    fun matchesType(targetType: String): Boolean = type == targetType
    
    fun toSlotAwareKey(): SlotAwareCacheKey = SlotAwareCacheKey(type, slot, id, ttl)
}

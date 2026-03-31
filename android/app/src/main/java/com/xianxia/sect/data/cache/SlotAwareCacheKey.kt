package com.xianxia.sect.data.cache

data class SlotAwareCacheKey(
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
        
        fun forGameData(slot: Int) = SlotAwareCacheKey(TYPE_GAME_DATA, slot, "current")
        
        fun forDisciple(slot: Int, discipleId: String) = SlotAwareCacheKey(TYPE_DISCIPLE, slot, discipleId)
        
        fun forEquipment(slot: Int, equipmentId: String) = SlotAwareCacheKey(TYPE_EQUIPMENT, slot, equipmentId)
        
        fun forManual(slot: Int, manualId: String) = SlotAwareCacheKey(TYPE_MANUAL, slot, manualId)
        
        fun forPill(slot: Int, pillId: String) = SlotAwareCacheKey(TYPE_PILL, slot, pillId)
        
        fun forMaterial(slot: Int, materialId: String) = SlotAwareCacheKey(TYPE_MATERIAL, slot, materialId)
        
        fun forHerb(slot: Int, herbId: String) = SlotAwareCacheKey(TYPE_HERB, slot, herbId)
        
        fun forSeed(slot: Int, seedId: String) = SlotAwareCacheKey(TYPE_SEED, slot, seedId)
        
        fun forTeam(slot: Int, teamId: String) = SlotAwareCacheKey(TYPE_TEAM, slot, teamId)
        
        fun forBuildingSlot(slot: Int, buildingSlotId: String) = SlotAwareCacheKey(TYPE_BUILDING_SLOT, slot, buildingSlotId)
        
        fun forBuilding(slot: Int, buildingId: String) = SlotAwareCacheKey(TYPE_BUILDING, slot, buildingId)
        
        fun forEvent(slot: Int, eventId: String) = SlotAwareCacheKey(TYPE_EVENT, slot, eventId)
        
        fun forBattleLog(slot: Int, battleLogId: String) = SlotAwareCacheKey(TYPE_BATTLE_LOG, slot, battleLogId)
        
        fun forAlliance(slot: Int, allianceId: String) = SlotAwareCacheKey(TYPE_ALLIANCE, slot, allianceId)
        
        fun forAlchemySlot(slot: Int, alchemySlotId: String) = SlotAwareCacheKey(TYPE_ALCHEMY_SLOT, slot, alchemySlotId)
        
        fun fromString(key: String): SlotAwareCacheKey? {
            val parts = key.split(":")
            return when (parts.size) {
                3 -> SlotAwareCacheKey(parts[0], parts[1].toIntOrNull() ?: return null, parts[2])
                2 -> SlotAwareCacheKey(parts[0], 0, parts[1])
                else -> null
            }
        }
        
        fun fromLegacyKey(legacyKey: CacheKey, slot: Int): SlotAwareCacheKey {
            return SlotAwareCacheKey(legacyKey.type, legacyKey.slot.takeIf { it != 0 } ?: slot, legacyKey.id, legacyKey.ttl)
        }
        
        fun fromCacheKey(cacheKey: CacheKey): SlotAwareCacheKey {
            return SlotAwareCacheKey(cacheKey.type, cacheKey.slot, cacheKey.id, cacheKey.ttl)
        }
    }
    
    override fun toString(): String = "$type:$slot:$id"
    
    fun toByteArray(): ByteArray = toString().toByteArray(Charsets.UTF_8)
    
    fun toLegacyKey(): CacheKey = CacheKey(type, slot, id, ttl)
    
    fun withSlot(newSlot: Int): SlotAwareCacheKey = copy(slot = newSlot)
    
    fun withTtl(newTtl: Long): SlotAwareCacheKey = copy(ttl = newTtl)
    
    fun matchesSlot(targetSlot: Int): Boolean = slot == targetSlot
    
    fun matchesType(targetType: String): Boolean = type == targetType
}

class SlotIsolatedCacheIndex {
    private val slotIndices = mutableMapOf<Int, MutableSet<String>>()
    
    @Synchronized
    fun register(key: SlotAwareCacheKey) {
        slotIndices.getOrPut(key.slot) { mutableSetOf() }.add(key.toString())
    }
    
    @Synchronized
    fun unregister(key: SlotAwareCacheKey) {
        slotIndices[key.slot]?.remove(key.toString())
    }
    
    @Synchronized
    fun getKeysForSlot(slot: Int): Set<String> {
        return slotIndices[slot]?.toSet() ?: emptySet()
    }
    
    @Synchronized
    fun clearSlot(slot: Int): Int {
        val count = slotIndices[slot]?.size ?: 0
        slotIndices.remove(slot)
        return count
    }
    
    @Synchronized
    fun clearAll() {
        slotIndices.clear()
    }
    
    @Synchronized
    fun getSlotCount(): Int = slotIndices.size
    
    @Synchronized
    fun getTotalKeyCount(): Int = slotIndices.values.sumOf { it.size }
    
    @Synchronized
    fun hasSlot(slot: Int): Boolean = slotIndices.containsKey(slot) && slotIndices[slot]!!.isNotEmpty()
}

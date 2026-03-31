package com.xianxia.sect.core.util

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.EquipmentNurtureData
import com.xianxia.sect.core.model.StorageBagItem
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiscipleObjectPool @Inject constructor() {
    
    companion object {
        private const val MAX_POOL_SIZE = 100
        private const val DEFAULT_INITIAL_POOL_SIZE = 20
    }
    
    private val pool = ConcurrentLinkedQueue<MutableDisciple>()
    private val createdCount = AtomicInteger(0)
    private val reusedCount = AtomicInteger(0)
    private val returnedCount = AtomicInteger(0)
    private val poolSize = AtomicInteger(0)
    
    init {
        repeat(DEFAULT_INITIAL_POOL_SIZE) {
            pool.offer(MutableDisciple())
        }
    }
    
    fun acquire(): MutableDisciple {
        val obj = pool.poll()
        return if (obj != null) {
            poolSize.decrementAndGet()
            reusedCount.incrementAndGet()
            obj
        } else {
            createdCount.incrementAndGet()
            MutableDisciple()
        }
    }
    
    fun release(obj: MutableDisciple) {
        if (poolSize.get() < MAX_POOL_SIZE) {
            obj.reset()
            pool.offer(obj)
            poolSize.incrementAndGet()
            returnedCount.incrementAndGet()
        }
    }
    
    fun <T> use(block: (MutableDisciple) -> T): T {
        val obj = acquire()
        return try {
            block(obj)
        } finally {
            release(obj)
        }
    }
    
    fun copyFrom(disciple: Disciple): MutableDisciple {
        val obj = acquire()
        obj.copyFrom(disciple)
        return obj
    }
    
    fun <T> useFrom(disciple: Disciple, block: (MutableDisciple) -> T): T {
        val obj = copyFrom(disciple)
        return try {
            block(obj)
        } finally {
            release(obj)
        }
    }
    
    fun clear() {
        pool.clear()
        poolSize.set(0)
    }
    
    fun size(): Int = poolSize.get()
    
    fun stats(): DisciplePoolStats {
        return DisciplePoolStats(
            poolSize = poolSize.get(),
            createdCount = createdCount.get(),
            reusedCount = reusedCount.get(),
            returnedCount = returnedCount.get()
        )
    }
}

data class DisciplePoolStats(
    val poolSize: Int,
    val createdCount: Int,
    val reusedCount: Int,
    val returnedCount: Int
) {
    val reuseRate: Double
        get() = if (createdCount + reusedCount > 0) {
            reusedCount.toDouble() / (createdCount + reusedCount)
        } else 0.0
}

class MutableDisciple {
    var id: String = UUID.randomUUID().toString()
    var name: String = ""
    var realm: Int = 9
    var realmLayer: Int = 1
    var cultivation: Double = 0.0
    
    var spiritRootType: String = "metal"
    
    var age: Int = 16
    var lifespan: Int = 80
    var isAlive: Boolean = true
    
    var gender: String = "male"
    var partnerId: String? = null
    var partnerSectId: String? = null
    var parentId1: String? = null
    var parentId2: String? = null
    var lastChildYear: Int = 0
    var griefEndYear: Int? = null
    
    var weaponId: String? = null
    var armorId: String? = null
    var bootsId: String? = null
    var accessoryId: String? = null
    
    var manualIds: MutableList<String> = mutableListOf()
    var talentIds: MutableList<String> = mutableListOf()
    var manualMasteries: MutableMap<String, Int> = mutableMapOf()
    
    var weaponNurture: EquipmentNurtureData? = null
    var armorNurture: EquipmentNurtureData? = null
    var bootsNurture: EquipmentNurtureData? = null
    var accessoryNurture: EquipmentNurtureData? = null
    
    var spiritStones: Int = 0
    var soulPower: Int = 10
    
    var storageBagItems: MutableList<StorageBagItem> = mutableListOf()
    var storageBagSpiritStones: Long = 0
    
    var status: DiscipleStatus = DiscipleStatus.IDLE
    var statusData: MutableMap<String, String> = mutableMapOf()
    
    var cultivationSpeedBonus: Double = 1.0
    var cultivationSpeedDuration: Int = 0
    
    var pillPhysicalAttackBonus: Double = 0.0
    var pillMagicAttackBonus: Double = 0.0
    var pillPhysicalDefenseBonus: Double = 0.0
    var pillMagicDefenseBonus: Double = 0.0
    var pillHpBonus: Double = 0.0
    var pillMpBonus: Double = 0.0
    var pillSpeedBonus: Double = 0.0
    var pillEffectDuration: Int = 0
    
    var totalCultivation: Long = 0
    var breakthroughCount: Int = 0
    var breakthroughFailCount: Int = 0
    var battlesWon: Int = 0
    
    var intelligence: Int = 50
    var charm: Int = 50
    var loyalty: Int = 50
    var comprehension: Int = 50
    var artifactRefining: Int = 50
    var pillRefining: Int = 50
    var spiritPlanting: Int = 50
    var teaching: Int = 50
    var morality: Int = 50
    
    var salaryPaidCount: Int = 0
    var salaryMissedCount: Int = 0
    
    var recruitedMonth: Int = 0
    
    var hpVariance: Int = 0
    var mpVariance: Int = 0
    var physicalAttackVariance: Int = 0
    var magicAttackVariance: Int = 0
    var physicalDefenseVariance: Int = 0
    var magicDefenseVariance: Int = 0
    var speedVariance: Int = 0
    
    var baseHp: Int = 100
    var baseMp: Int = 50
    var basePhysicalAttack: Int = 7
    var baseMagicAttack: Int = 7
    var basePhysicalDefense: Int = 5
    var baseMagicDefense: Int = 3
    var baseSpeed: Int = 10
    
    var discipleType: String = "outer"
    
    var monthlyUsedPillIds: MutableList<String> = mutableListOf()
    var usedExtendLifePillIds: MutableList<String> = mutableListOf()
    
    var hasReviveEffect: Boolean = false
    var hasClearAllEffect: Boolean = false
    
    fun reset() {
        id = UUID.randomUUID().toString()
        name = ""
        realm = 9
        realmLayer = 1
        cultivation = 0.0
        spiritRootType = "metal"
        age = 16
        lifespan = 80
        isAlive = true
        gender = "male"
        partnerId = null
        partnerSectId = null
        parentId1 = null
        parentId2 = null
        lastChildYear = 0
        griefEndYear = null
        weaponId = null
        armorId = null
        bootsId = null
        accessoryId = null
        manualIds.clear()
        talentIds.clear()
        manualMasteries.clear()
        weaponNurture = null
        armorNurture = null
        bootsNurture = null
        accessoryNurture = null
        spiritStones = 0
        soulPower = 10
        storageBagItems.clear()
        storageBagSpiritStones = 0
        status = DiscipleStatus.IDLE
        statusData.clear()
        cultivationSpeedBonus = 1.0
        cultivationSpeedDuration = 0
        pillPhysicalAttackBonus = 0.0
        pillMagicAttackBonus = 0.0
        pillPhysicalDefenseBonus = 0.0
        pillMagicDefenseBonus = 0.0
        pillHpBonus = 0.0
        pillMpBonus = 0.0
        pillSpeedBonus = 0.0
        pillEffectDuration = 0
        totalCultivation = 0
        breakthroughCount = 0
        breakthroughFailCount = 0
        battlesWon = 0
        intelligence = 50
        charm = 50
        loyalty = 50
        comprehension = 50
        artifactRefining = 50
        pillRefining = 50
        spiritPlanting = 50
        teaching = 50
        morality = 50
        salaryPaidCount = 0
        salaryMissedCount = 0
        recruitedMonth = 0
        hpVariance = 0
        mpVariance = 0
        physicalAttackVariance = 0
        magicAttackVariance = 0
        physicalDefenseVariance = 0
        magicDefenseVariance = 0
        speedVariance = 0
        baseHp = 100
        baseMp = 50
        basePhysicalAttack = 7
        baseMagicAttack = 7
        basePhysicalDefense = 5
        baseMagicDefense = 3
        baseSpeed = 10
        discipleType = "outer"
        monthlyUsedPillIds.clear()
        usedExtendLifePillIds.clear()
        hasReviveEffect = false
        hasClearAllEffect = false
    }
    
    fun copyFrom(disciple: Disciple) {
        id = disciple.id
        name = disciple.name
        realm = disciple.realm
        realmLayer = disciple.realmLayer
        cultivation = disciple.cultivation
        spiritRootType = disciple.spiritRootType
        age = disciple.age
        lifespan = disciple.lifespan
        isAlive = disciple.isAlive
        gender = disciple.gender
        partnerId = disciple.partnerId
        partnerSectId = disciple.partnerSectId
        parentId1 = disciple.parentId1
        parentId2 = disciple.parentId2
        lastChildYear = disciple.lastChildYear
        griefEndYear = disciple.griefEndYear
        weaponId = disciple.weaponId
        armorId = disciple.armorId
        bootsId = disciple.bootsId
        accessoryId = disciple.accessoryId
        manualIds.clear()
        manualIds.addAll(disciple.manualIds)
        talentIds.clear()
        talentIds.addAll(disciple.talentIds)
        manualMasteries.clear()
        manualMasteries.putAll(disciple.manualMasteries)
        weaponNurture = disciple.weaponNurture
        armorNurture = disciple.armorNurture
        bootsNurture = disciple.bootsNurture
        accessoryNurture = disciple.accessoryNurture
        spiritStones = disciple.spiritStones
        soulPower = disciple.soulPower
        storageBagItems.clear()
        storageBagItems.addAll(disciple.storageBagItems)
        storageBagSpiritStones = disciple.storageBagSpiritStones
        status = disciple.status
        statusData.clear()
        statusData.putAll(disciple.statusData)
        cultivationSpeedBonus = disciple.cultivationSpeedBonus
        cultivationSpeedDuration = disciple.cultivationSpeedDuration
        pillPhysicalAttackBonus = disciple.pillPhysicalAttackBonus
        pillMagicAttackBonus = disciple.pillMagicAttackBonus
        pillPhysicalDefenseBonus = disciple.pillPhysicalDefenseBonus
        pillMagicDefenseBonus = disciple.pillMagicDefenseBonus
        pillHpBonus = disciple.pillHpBonus
        pillMpBonus = disciple.pillMpBonus
        pillSpeedBonus = disciple.pillSpeedBonus
        pillEffectDuration = disciple.pillEffectDuration
        totalCultivation = disciple.totalCultivation
        breakthroughCount = disciple.breakthroughCount
        breakthroughFailCount = disciple.breakthroughFailCount
        battlesWon = disciple.battlesWon
        intelligence = disciple.intelligence
        charm = disciple.charm
        loyalty = disciple.loyalty
        comprehension = disciple.comprehension
        artifactRefining = disciple.artifactRefining
        pillRefining = disciple.pillRefining
        spiritPlanting = disciple.spiritPlanting
        teaching = disciple.teaching
        morality = disciple.morality
        salaryPaidCount = disciple.salaryPaidCount
        salaryMissedCount = disciple.salaryMissedCount
        recruitedMonth = disciple.recruitedMonth
        hpVariance = disciple.hpVariance
        mpVariance = disciple.mpVariance
        physicalAttackVariance = disciple.physicalAttackVariance
        magicAttackVariance = disciple.magicAttackVariance
        physicalDefenseVariance = disciple.physicalDefenseVariance
        magicDefenseVariance = disciple.magicDefenseVariance
        speedVariance = disciple.speedVariance
        baseHp = disciple.baseHp
        baseMp = disciple.baseMp
        basePhysicalAttack = disciple.basePhysicalAttack
        baseMagicAttack = disciple.baseMagicAttack
        basePhysicalDefense = disciple.basePhysicalDefense
        baseMagicDefense = disciple.baseMagicDefense
        baseSpeed = disciple.baseSpeed
        discipleType = disciple.discipleType
        monthlyUsedPillIds.clear()
        monthlyUsedPillIds.addAll(disciple.monthlyUsedPillIds)
        usedExtendLifePillIds.clear()
        usedExtendLifePillIds.addAll(disciple.usedExtendLifePillIds)
        hasReviveEffect = disciple.hasReviveEffect
        hasClearAllEffect = disciple.hasClearAllEffect
    }
    
    fun toDisciple(): Disciple {
        return Disciple(
            id = id,
            name = name,
            realm = realm,
            realmLayer = realmLayer,
            cultivation = cultivation,
            spiritRootType = spiritRootType,
            age = age,
            lifespan = lifespan,
            isAlive = isAlive,
            gender = gender,
            partnerId = partnerId,
            partnerSectId = partnerSectId,
            parentId1 = parentId1,
            parentId2 = parentId2,
            lastChildYear = lastChildYear,
            griefEndYear = griefEndYear,
            weaponId = weaponId,
            armorId = armorId,
            bootsId = bootsId,
            accessoryId = accessoryId,
            manualIds = manualIds.toList(),
            talentIds = talentIds.toList(),
            manualMasteries = manualMasteries.toMap(),
            weaponNurture = weaponNurture,
            armorNurture = armorNurture,
            bootsNurture = bootsNurture,
            accessoryNurture = accessoryNurture,
            spiritStones = spiritStones,
            soulPower = soulPower,
            storageBagItems = storageBagItems.toList(),
            storageBagSpiritStones = storageBagSpiritStones,
            status = status,
            statusData = statusData.toMap(),
            cultivationSpeedBonus = cultivationSpeedBonus,
            cultivationSpeedDuration = cultivationSpeedDuration,
            pillPhysicalAttackBonus = pillPhysicalAttackBonus,
            pillMagicAttackBonus = pillMagicAttackBonus,
            pillPhysicalDefenseBonus = pillPhysicalDefenseBonus,
            pillMagicDefenseBonus = pillMagicDefenseBonus,
            pillHpBonus = pillHpBonus,
            pillMpBonus = pillMpBonus,
            pillSpeedBonus = pillSpeedBonus,
            pillEffectDuration = pillEffectDuration,
            totalCultivation = totalCultivation,
            breakthroughCount = breakthroughCount,
            breakthroughFailCount = breakthroughFailCount,
            battlesWon = battlesWon,
            intelligence = intelligence,
            charm = charm,
            loyalty = loyalty,
            comprehension = comprehension,
            artifactRefining = artifactRefining,
            pillRefining = pillRefining,
            spiritPlanting = spiritPlanting,
            teaching = teaching,
            morality = morality,
            salaryPaidCount = salaryPaidCount,
            salaryMissedCount = salaryMissedCount,
            recruitedMonth = recruitedMonth,
            hpVariance = hpVariance,
            mpVariance = mpVariance,
            physicalAttackVariance = physicalAttackVariance,
            magicAttackVariance = magicAttackVariance,
            physicalDefenseVariance = physicalDefenseVariance,
            magicDefenseVariance = magicDefenseVariance,
            speedVariance = speedVariance,
            baseHp = baseHp,
            baseMp = baseMp,
            basePhysicalAttack = basePhysicalAttack,
            baseMagicAttack = baseMagicAttack,
            basePhysicalDefense = basePhysicalDefense,
            baseMagicDefense = baseMagicDefense,
            baseSpeed = baseSpeed,
            discipleType = discipleType,
            monthlyUsedPillIds = monthlyUsedPillIds.toList(),
            usedExtendLifePillIds = usedExtendLifePillIds.toList(),
            hasReviveEffect = hasReviveEffect,
            hasClearAllEffect = hasClearAllEffect
        )
    }
}

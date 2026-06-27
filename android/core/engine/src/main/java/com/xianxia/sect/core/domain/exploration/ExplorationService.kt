package com.xianxia.sect.core.engine.domain.exploration

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator

import com.xianxia.sect.core.event.DeathEvent
import com.xianxia.sect.core.event.EventBusPort
import com.xianxia.sect.core.registry.BeastMaterialDatabase
import com.xianxia.sect.core.registry.TalentDatabase
import com.xianxia.sect.core.state.BattleResultUIData
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.PendingBeastAttack
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.engine.system.InventorySystem


import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt as mathSqrt
import kotlin.random.Random

@Singleton
class ExplorationService @Inject constructor(
    private val stateStore: GameStateStore,
    private val eventBus: EventBusPort,
    private val scopeProvider: CoroutineScopeProvider,
    private val battleSystem: BattleSystem,
    private val buildingConfigService: BuildingConfigService,
    private val inventorySystem: InventorySystem,
    private val cultivationService: com.xianxia.sect.core.engine.service.CultivationService
) {
    private val scope get() = scopeProvider.scope

    private val _pendingPatrolResults = mutableListOf<BattleResultUIData>()

    // 关卡刷新冷却：每3个月刷新一次（绝对月 = year * 12 + month）
    private var lastWorldLevelRefreshMonth: Int = 0

    fun consumePendingPatrolResults(): List<BattleResultUIData> {
        val results = _pendingPatrolResults.toList()
        _pendingPatrolResults.clear()
        return results
    }

    suspend fun processMonthlyWorldLevels(state: MutableGameState) {
        val data = state.gameData
        val year = data.gameYear
        val month = data.gameMonth

        // 清理过期关卡（每月都做）
        val remainingLevels = data.worldLevels.filter { !it.checkExpired(year, month) }

        // 每3个月刷新一次新关卡（数量1~6，持续4个月）
        val absoluteMonth = year * 12 + month
        val shouldRefresh = lastWorldLevelRefreshMonth == 0 || (absoluteMonth - lastWorldLevelRefreshMonth) >= 3

        if (shouldRefresh) {
            lastWorldLevelRefreshMonth = absoluteMonth
            val playerSect = data.worldMapSects.find { it.isPlayerSect } ?: return

            val edges = LevelGenerator.buildConnectionEdges(data.worldMapSects)
            val newLevels = LevelGenerator.generateWorldLevels(
                existingSects = data.worldMapSects,
                connectionEdges = edges,
                currentYear = year,
                currentMonth = month,
                existingLevels = remainingLevels
            )

            if (newLevels.isNotEmpty()) {
                state.gameData = data.copy(worldLevels = remainingLevels + newLevels)
            } else if (remainingLevels.size != data.worldLevels.size) {
                state.gameData = data.copy(worldLevels = remainingLevels)
            }
        } else if (remainingLevels.size != data.worldLevels.size) {
            state.gameData = data.copy(worldLevels = remainingLevels)
        }

        // 妖兽移动
        state.gameData = state.gameData.copy(
            worldLevels = moveBeasts(state.gameData)
        )

        // 妖兽攻击检测 → 暂存待处理列表
        detectBeastAttacks(state)

        // 巡视楼自动攻击
        processPatrolAttacks(state)
    }

    // ==================== 妖兽移动与攻击 ====================

    private fun moveBeasts(gd: GameData): List<WorldLevel> {
        val year = gd.gameYear; val month = gd.gameMonth
        val minX = GameConfig.WorldMap.BORDER_PADDING.toFloat()
        val maxX = (GameConfig.WorldMap.MAP_WIDTH -
            GameConfig.WorldMap.BORDER_PADDING).toFloat()
        val minY = GameConfig.WorldMap.BORDER_PADDING.toFloat()
        val maxY = (GameConfig.WorldMap.MAP_HEIGHT -
            GameConfig.WorldMap.BORDER_PADDING).toFloat()

        return gd.worldLevels.map { level ->
            if (level.type != LevelType.BEAST ||
                level.defeated || level.checkExpired(year, month)
            ) {
                level
            } else {
                val angle = Random.nextDouble() * 2 * Math.PI
                val dist = Random.nextDouble() *
                    GameConfig.WorldMap.BEAST_MOVE_DISTANCE
                level.copy(
                    x = (level.x + cos(angle) * dist).toFloat()
                        .coerceIn(minX, maxX),
                    y = (level.y + sin(angle) * dist).toFloat()
                        .coerceIn(minY, maxY)
                )
            }
        }
    }

    private fun detectBeastAttacks(state: MutableGameState) {
        val gd = state.gameData
        val year = gd.gameYear; val month = gd.gameMonth

        val targets = gd.worldMapSects.filter {
            it.isPlayerSect || it.isPlayerOccupied
        }
        if (targets.isEmpty()) return

        val pending = mutableListOf<PendingBeastAttack>()
        val radius = GameConfig.WorldMap.BEAST_ATTACK_RADIUS

        for (level in gd.worldLevels) {
            if (level.type != LevelType.BEAST ||
                level.defeated || level.checkExpired(year, month)
            ) continue

            var nearestSect: WorldSect? = null
            var nearestDist = Float.MAX_VALUE

            for (sect in targets) {
                val dx = level.x - sect.x
                val dy = level.y - sect.y
                val dist = mathSqrt(
                    (dx * dx + dy * dy).toDouble()
                ).toFloat()
                if (dist < nearestDist) {
                    nearestDist = dist; nearestSect = sect
                }
            }

            val sect = nearestSect ?: continue
            if (nearestDist >= radius) continue

            val prob = GameConfig.WorldMap.BEAST_ATTACK_BASE_PROB *
                (1.0 - nearestDist / radius)
            if (Random.nextDouble() < prob) {
                pending.add(PendingBeastAttack(
                    beastLevel = level,
                    targetSectId = sect.id,
                    targetSectName = sect.name,
                    distance = nearestDist
                ))
            }
        }

        if (pending.isNotEmpty()) {
            stateStore.setPendingBeastAttacks(pending)
        }
    }

    /**
     * 玩家选择"上交宝物"：扣除 20% 灵石（至少 2 万），妖兽退去。
     */
    fun resolveBeastAttackPayTribute(beastLevelId: String) {
        val gd = stateStore.gameData.value
        val level = gd.worldLevels.find { it.id == beastLevelId } ?: return
        val targetSect = gd.worldMapSects.find {
            it.isPlayerSect || it.isPlayerOccupied
        }
        val tribute = (gd.spiritStones *
            GameConfig.WorldMap.BEAST_TRIBUTE_RATIO).toLong()
            .coerceAtLeast(GameConfig.WorldMap.BEAST_TRIBUTE_MIN)

        scope.launch {
            stateStore.update {
                gameData = gameData.copy(
                    spiritStones = (gameData.spiritStones - tribute)
                        .coerceAtLeast(0L),
                    worldLevels = gameData.worldLevels.map {
                        if (it.id == beastLevelId)
                            it.copy(defeated = true) else it
                    }
                )
                battleLogs = (battleLogs + BattleLog(
                    year = gameData.gameYear,
                    month = gameData.gameMonth,
                    type = BattleType.PVE,
                    attackerName = level.beastName.ifEmpty { "妖兽" },
                    defenderName = targetSect?.name ?: "",
                    result = BattleResult.WIN,
                    details = "上交${tribute}灵石，妖兽退去"
                )).takeLast(GameConfig.Logs.MAX_BATTLE_LOGS)
            }
        }
    }

    /**
     * 玩家选择"知道了"：妖兽发动进攻，与宗门驻军战斗。
     */
    suspend fun resolveBeastAttackFight(beastLevelId: String) {
        val snapshot = stateStore.gameData.value
        val level = snapshot.worldLevels.find {
            it.id == beastLevelId
        } ?: return

        stateStore.update {
            resolveBeastFightInternal(beastLevelId, level)
        }
    }

    private suspend fun MutableGameState.resolveBeastFightInternal(
        beastLevelId: String, level: WorldLevel
    ) {
        val gd = gameData
        val targetSect = gd.worldMapSects.find {
            it.isPlayerSect || it.isPlayerOccupied
        } ?: return

        val garrisonIds = targetSect.garrisonSlots
            .filter { it.discipleId.isNotEmpty() }
            .map { it.discipleId }.toSet()

        // 战斗前全量结算驻军弟子
        if (garrisonIds.isNotEmpty()) {
            cultivationService.forceSettleDisciplesBeforeBattle(
                this, garrisonIds.toList()
            )
        }

        var disciples = discipleTables.assembleAll()
        val defenders = disciples.filter {
            it.id in garrisonIds && it.isAlive
        }

        // 标记妖兽已处理
        gameData = gd.copy(worldLevels = gd.worldLevels.map {
            if (it.id == beastLevelId) it.copy(defeated = true) else it
        })

        // 无驻军：直接掠夺
        if (defenders.isEmpty()) {
            val loot = computeLoot(this)
            applyMaterialLoot(this, loot)
            battleLogs = (battleLogs + BattleLog(
                year = gameData.gameYear,
                month = gameData.gameMonth,
                type = BattleType.PVE,
                attackerName = level.beastName.ifEmpty { "妖兽" },
                defenderName = if (targetSect.isPlayerSect)
                    "玩家宗门" else targetSect.name,
                result = BattleResult.LOSE,
                details = loot.toDetailString(level.beastName)
            )).takeLast(GameConfig.Logs.MAX_BATTLE_LOGS)
            if (gameData.patrolBattleResultPopup) {
                _pendingPatrolResults.add(BattleResultUIData(
                    battleLogId = "",
                    victory = false,
                    teamMembers = emptyList(),
                    rewards = emptyList(),
                    lootedItems = loot.toRewardItems(),
                    isBeastDefense = true
                ))
            }
            discipleTables.clear()
            disciples.forEach { discipleTables.insert(it) }
            return
        }

        // 执行战斗
        val equipMap = equipmentInstances.associateBy { it.id }
        val manMap = manualInstances.associateBy { it.id }
        val profMap = gameData.manualProficiencies.mapValues {
            (_, list) -> list.associateBy { it.manualId }
        }

        val battle = battleSystem.createBattle(
            defenders, equipMap, manMap,
            level.realm, level.count, level.beastName, profMap
        )
        val result = battleSystem.executeBattle(battle)

        // 更新弟子状态
        val hpMap = result.battle.team.associate {
            it.id to (it.hp to it.mp)
        }
        val survivorIds = result.battle.team
            .filter { !it.isDead }.map { it.id }.toSet()
        val deadDefenders = disciples.filter {
            it.id in garrisonIds && it.id !in survivorIds
        }

        disciples = disciples.map { d ->
            val (hp, mp) = hpMap[d.id] ?: return@map d
            if (d.id !in survivorIds) {
                d.copy(isAlive = false, status = DiscipleStatus.DEAD)
            } else {
                d.copy(combat = d.combat.copy(
                    currentHp = hp.coerceIn(0, d.maxHp),
                    currentMp = mp.coerceIn(0, d.maxMp)
                ))
            }
        }

        if (deadDefenders.isNotEmpty()) {
            disciples = DiscipleStatCalculator
                .applyGriefToRelatives(
                    disciples, deadDefenders,
                    gameData.gameYear
                )
        }

        // 清理阵亡弟子驻军槽位
        val deadIds = disciples.filter { !it.isAlive }
            .map { it.id }.toSet()
        if (deadIds.isNotEmpty()) {
            gameData = gameData.copy(
                worldMapSects = gameData.worldMapSects.map { sect ->
                    if (sect.id == targetSect.id) {
                        sect.copy(garrisonSlots =
                            sect.garrisonSlots.map { slot ->
                                if (slot.discipleId in deadIds)
                                    GarrisonSlot(index = slot.index)
                                else slot
                            })
                    } else sect
                }
            )
        }

        val allRewards = mutableListOf<BattleRewardItem>()

        if (result.victory) {
            // 防守胜利：复用巡逻塔奖励逻辑
            disciples = disciples.map { d ->
                if (d.id in survivorIds && d.isAlive) {
                    var m = d.copy(soulPower = d.soulPower + 1)
                    if (m.talentIds.any { id ->
                        TalentDatabase.getById(id)?.effects
                            ?.containsKey("winBattleRandomAttrPlus") == true
                    }) {
                        val r = kotlin.random.Random.nextInt(17)
                        val sk = m.skills; val cb = m.combat
                        when (r) {
                            0 -> sk.intelligence++
                            1 -> sk.comprehension++
                            2 -> sk.charm++; 3 -> sk.loyalty++
                            4 -> sk.artifactRefining++
                            5 -> sk.pillRefining++
                            6 -> sk.spiritPlanting++
                            7 -> sk.mining++; 8 -> sk.teaching++
                            9 -> sk.morality++
                            10 -> cb.baseHp++
                            11 -> cb.baseMp++
                            12 -> cb.basePhysicalAttack++
                            13 -> cb.baseMagicAttack++
                            14 -> cb.basePhysicalDefense++
                            15 -> cb.baseMagicDefense++
                            16 -> cb.baseSpeed++
                        }
                    }
                    m
                } else d
            }

            // 妖兽材料奖励
            val beastConfig = GameConfig.Beast.getType(
                level.beastType ?: 0
            )
            val tier = GameConfig.Realm.getMaxRarity(level.realm)
            for (i in 0 until level.count) {
                repeat(kotlin.random.Random.nextInt(1, 4)) {
                    val mat = BeastMaterialDatabase
                        .getRandomMaterialByBeastType(
                            beastConfig.name, tier
                        )
                    if (mat != null) {
                        val material = Material(
                            id = UUID.randomUUID().toString(),
                            name = mat.name, rarity = mat.rarity,
                            description = mat.description,
                            category = mat.materialCategory,
                            quantity = 1
                        )
                        val addR = inventorySystem.addMaterial(material)
                        if (addR.isSuccess) {
                            allRewards.add(BattleRewardItem(
                                itemId = material.id,
                                name = material.name,
                                quantity = 1,
                                rarity = material.rarity,
                                type = "material"
                            ))
                        }
                    }
                }
            }

            val sr = result.rewards["spiritStones"] ?: 0
            if (sr > 0) {
                gameData = gameData.copy(
                    spiritStones = gameData.spiritStones + sr.toLong()
                )
                allRewards.add(BattleRewardItem(
                    name = "灵石", quantity = sr,
                    rarity = 1, type = "spiritStones"
                ))
            }
        } else {
            // 防守失败：掠夺仓库
            val loot = computeLoot(this)
            applyMaterialLoot(this, loot)
        }

        // BattleLog
        val teamMems = result.battle.team.map { m ->
            BattleLogMember(
                id = m.id, name = m.name, realm = m.realm,
                realmName = m.realmName,
                hp = m.hp, maxHp = m.maxHp,
                mp = m.mp, maxMp = m.maxMp,
                isAlive = !m.isDead, portraitRes = m.portraitRes
            )
        }
        val enems = result.battle.beasts.map { b ->
            BattleLogEnemy(
                name = b.name, realm = b.realm,
                realmName = b.realmName,
                portraitRes = b.portraitRes
            )
        }
        val rds = result.log.rounds.map { r ->
            BattleLogRound(
                roundNumber = r.roundNumber,
                actions = r.actions.map { a ->
                    BattleLogAction(
                        type = a.type, attacker = a.attacker,
                        attackerType = a.attackerType,
                        target = a.target, damage = a.damage,
                        damageType = a.damageType,
                        isCrit = a.isCrit, isKill = a.isKill,
                        message = a.message
                    )
                }
            )
        }

        battleLogs = (battleLogs + BattleLog(
            year = gameData.gameYear,
            month = gameData.gameMonth,
            type = BattleType.PVE,
            attackerName = level.beastName.ifEmpty { "妖兽" },
            defenderName = if (targetSect.isPlayerSect)
                "玩家宗门" else targetSect.name,
            result = if (result.victory) BattleResult.WIN
                else BattleResult.LOSE,
            teamMembers = teamMems, enemies = enems,
            rounds = rds, turns = result.turnCount,
            teamCasualties = teamMems.count {
                !survivorIds.contains(it.id)
            },
            beastsDefeated = if (result.victory) level.count
                else result.battle.beasts.count { it.isDead },
            details = if (result.victory)
                "成功抵御${level.beastName}袭击"
                else "被${level.beastName}击败，宗门受损"
        )).takeLast(GameConfig.Logs.MAX_BATTLE_LOGS)

        // 弹窗（包含掠夺物品）
        if (gameData.patrolBattleResultPopup) {
            val looted = if (result.victory) emptyList()
                else computeLoot(this).toRewardItems()
            _pendingPatrolResults.add(BattleResultUIData(
                battleLogId = "",
                victory = result.victory,
                teamMembers = teamMems,
                rewards = allRewards,
                lootedItems = looted,
                isBeastDefense = true
            ))
        }

        discipleTables.clear()
        disciples.forEach { discipleTables.insert(it) }
    }

    // ==================== 仓库掠夺 ====================

    /**
     * 掠夺结果数据。
     */
    data class BeastLootData(
        val stolenSpiritStones: Long = 0L,
        val stolenBagCount: Int = 0,
        val stolenItems: List<LootedItem> = emptyList()
    ) {
        fun toDetailString(beastName: String): String {
            val parts = mutableListOf<String>()
            if (stolenSpiritStones > 0)
                parts.add("灵石${stolenSpiritStones}")
            stolenItems.forEach { parts.add("${it.name}x${it.count}") }
            if (stolenBagCount > 0)
                parts.add("储物袋x${stolenBagCount}")
            return if (parts.isEmpty())
                "被${beastName}袭击"
            else
                "被${beastName}掠夺：${parts.joinToString("、")}"
        }

        fun toRewardItems(): List<BattleRewardItem> {
            val items = mutableListOf<BattleRewardItem>()
            if (stolenSpiritStones > 0) {
                items.add(BattleRewardItem(
                    name = "灵石",
                    quantity = stolenSpiritStones.toInt(),
                    rarity = 1, type = "spiritStones"
                ))
            }
            stolenItems.forEach { item ->
                items.add(BattleRewardItem(
                    itemId = item.id, name = item.name,
                    quantity = item.count, rarity = item.rarity,
                    type = item.type
                ))
            }
            if (stolenBagCount > 0) {
                items.add(BattleRewardItem(
                    name = "储物袋",
                    quantity = stolenBagCount,
                    rarity = 1, type = "storageBag"
                ))
            }
            return items
        }
    }

    data class LootedItem(
        val id: String, val name: String,
        val type: String, val rarity: Int, val count: Int
    )

    /**
     * 计算掠夺物品（从 EntityStore 随机选取 30%）。
     */
    private fun computeLoot(state: MutableGameState): BeastLootData {
        val gd = state.gameData
        val itemUnit = GameConfig.WorldMap.SPIRIT_STONES_PER_ITEM
        val ratio = GameConfig.WorldMap.BEAST_LOOT_RATIO

        // 收集所有物品条目
        data class Entry(
            val type: String, val id: String,
            val name: String, val rarity: Int
        )
        val entries = mutableListOf<Entry>()

        // 灵石（20000 = 1 单位）
        val stoneUnits = (gd.spiritStones / itemUnit).toInt()
        repeat(stoneUnits) {
            entries.add(Entry("spiritStones", "", "灵石", 1))
        }

        // 储物袋
        state.storageBags.items.forEach { bag ->
            repeat(bag.quantity) {
                entries.add(Entry("storageBag", bag.id,
                    bag.name, bag.rarity))
            }
        }

        // 各物品类型
        @Suppress("UNCHECKED_CAST")
        fun <T> addItems(
            items: List<T>, type: String,
            nameFn: (T) -> String, idFn: (T) -> String,
            rarityFn: (T) -> Int, qtyFn: (T) -> Int
        ) {
            items.forEach { item ->
                repeat(qtyFn(item)) {
                    entries.add(Entry(type, idFn(item),
                        nameFn(item), rarityFn(item)))
                }
            }
        }

        addItems(state.materials.items, "material",
            { (it as Material).name },
            { (it as Material).id },
            { (it as Material).rarity },
            { (it as Material).quantity })
        addItems(state.pills.items, "pill",
            { (it as Pill).name }, { (it as Pill).id },
            { (it as Pill).rarity },
            { (it as Pill).quantity })
        addItems(state.herbs.items, "herb",
            { (it as Herb).name }, { (it as Herb).id },
            { (it as Herb).rarity },
            { (it as Herb).quantity })
        addItems(state.seeds.items, "seed",
            { (it as Seed).name }, { (it as Seed).id },
            { (it as Seed).rarity },
            { (it as Seed).quantity })
        addItems(state.equipmentStacks.items, "equipment",
            { (it as EquipmentStack).name },
            { (it as EquipmentStack).id },
            { (it as EquipmentStack).rarity },
            { (it as EquipmentStack).quantity })
        addItems(state.manualStacks.items, "manual",
            { (it as ManualStack).name },
            { (it as ManualStack).id },
            { (it as ManualStack).rarity },
            { (it as ManualStack).quantity })

        val stealCount = kotlin.math.ceil(
            entries.size * ratio
        ).toInt().coerceAtMost(entries.size)
        if (stealCount <= 0) return BeastLootData()

        val selected = entries.shuffled().take(stealCount)

        val stolenStones = selected.count {
            it.type == "spiritStones"
        } * itemUnit
        val stolenBags = selected.count { it.type == "storageBag" }
        val stolenItems = selected
            .filter { it.type !in listOf("spiritStones",
                "storageBag") }
            .groupBy { it.id to it.type }
            .map { (_, list) ->
                val first = list.first()
                LootedItem(first.id, first.name,
                    first.type, first.rarity, list.size)
            }

        return BeastLootData(stolenStones, stolenBags, stolenItems)
    }

    /**
     * 从 EntityStore 中扣除被掠夺的物品。
     */
    private fun applyMaterialLoot(
        state: MutableGameState, loot: BeastLootData
    ) {
        var gd = state.gameData
        // 扣除灵石
        if (loot.stolenSpiritStones > 0) {
            gd = gd.copy(spiritStones =
                (gd.spiritStones - loot.stolenSpiritStones)
                    .coerceAtLeast(0L))
        }

        // 扣除储物袋
        if (loot.stolenBagCount > 0) {
            var toRemove = loot.stolenBagCount
            state.storageBags.filterInPlace { bag ->
                if (toRemove <= 0) true
                else if (bag.quantity <= toRemove) {
                    toRemove -= bag.quantity; false
                } else {
                    val remaining = bag.quantity - toRemove
                    toRemove = 0
                    // 需要更新数量
                    true
                }
            }
            // 处理部分更新的情况
            if (toRemove > 0) {
                // 已全部移除
            } else if (loot.stolenBagCount > 0) {
                var remaining = loot.stolenBagCount
                state.storageBags.mapInPlace { bag ->
                    if (remaining <= 0) bag
                    else if (bag.quantity <= remaining) {
                        remaining -= bag.quantity
                        bag.copy(quantity = 0)
                    } else {
                        val updated = bag.copy(
                            quantity = bag.quantity - remaining
                        )
                        remaining = 0; updated
                    }
                }
                // 过滤掉 quantity=0 的
                state.storageBags.filterInPlace { it.quantity > 0 }
            }
        }

        // 扣除物品
        for (item in loot.stolenItems) {
            when (item.type) {
                "material" -> state.materials.update(item.id) {
                    val q = it.quantity - item.count
                    if (q > 0) it.copy(quantity = q) else it.copy(
                        quantity = 0
                    )
                }
                "pill" -> state.pills.update(item.id) {
                    val q = it.quantity - item.count
                    if (q > 0) it.copy(quantity = q) else it.copy(
                        quantity = 0
                    )
                }
                "herb" -> state.herbs.update(item.id) {
                    val q = it.quantity - item.count
                    if (q > 0) it.copy(quantity = q) else it.copy(
                        quantity = 0
                    )
                }
                "seed" -> state.seeds.update(item.id) {
                    val q = it.quantity - item.count
                    if (q > 0) it.copy(quantity = q) else it.copy(
                        quantity = 0
                    )
                }
                "equipment" -> state.equipmentStacks.update(
                    item.id
                ) {
                    val q = it.quantity - item.count
                    if (q > 0) it.copy(quantity = q) else it.copy(
                        quantity = 0
                    )
                }
                "manual" -> state.manualStacks.update(item.id) {
                    val q = it.quantity - item.count
                    if (q > 0) it.copy(quantity = q) else it.copy(
                        quantity = 0
                    )
                }
            }
        }

        // 过滤掉 quantity=0 的物品
        state.materials.filterInPlace { it.quantity > 0 }
        state.pills.filterInPlace { it.quantity > 0 }
        state.herbs.filterInPlace { it.quantity > 0 }
        state.seeds.filterInPlace { it.quantity > 0 }
        state.equipmentStacks.filterInPlace { it.quantity > 0 }
        state.manualStacks.filterInPlace { it.quantity > 0 }
        state.gameData = gd
    }

    private suspend fun processPatrolAttacks(state: MutableGameState) {
        var gd = state.gameData
        var disciples = state.discipleTables.assembleAll()
        val allSlots = gd.patrolSlots
        val configs = gd.patrolConfigs
        if (allSlots.isEmpty()) return

        val numTowers = gd.placedBuildings.count { it.displayName == "巡视楼" }
        val equipmentMap = state.equipmentInstances.associateBy { it.id }
        val manualMap = state.manualInstances.associateBy { it.id }
        val allProficiencies = gd.manualProficiencies.mapValues { (_, list) ->
            list.associateBy { it.manualId }
        }
        val year = gd.gameYear; val month = gd.gameMonth
        val claimedBeasts = mutableSetOf<String>()

        val slotsPerTower = buildingConfigService.getSlotCountByDisplayName("巡视楼")
        for (towerIndex in 0 until numTowers) {
            val config = configs.getOrElse(towerIndex) { PatrolConfig() }
            val start = towerIndex * slotsPerTower
            val end = (start + slotsPerTower).coerceAtMost(allSlots.size)
            val towerSlots = allSlots.subList(start, end).filter { it.discipleId.isNotEmpty() }
            if (towerSlots.isEmpty()) continue

            val towerDiscipleIds = towerSlots.map { it.discipleId }.toSet()
            val towerDisciples = disciples.filter { it.id in towerDiscipleIds && it.isAlive }
            if (towerDisciples.isEmpty()) continue

            // 满状态检查
            if (config.requireFullStatus) {
                val anyNotFull = towerDisciples.any {
                    it.combat.currentHp < it.maxHp || it.combat.currentMp < it.maxMp
                }
                if (anyNotFull) continue
            }

            // 找匹配的妖兽（排除已被其他塔选中的）
            val target = gd.worldLevels.firstOrNull {
                it.type == LevelType.BEAST &&
                !it.defeated &&
                !it.checkExpired(year, month) &&
                it.realm in config.targetRealms &&
                it.count <= config.maxBeastCount &&
                it.id !in claimedBeasts
            } ?: continue

            claimedBeasts.add(target.id)

            val battle = battleSystem.createBattle(
                disciples = towerDisciples,
                equipmentMap = equipmentMap,
                manualMap = manualMap,
                beastLevel = target.realm,
                beastCount = target.count,
                beastType = target.beastName,
                manualProficiencies = allProficiencies
            )
            val result = battleSystem.executeBattle(battle)

            // 更新弟子状态
            val hpMap = result.battle.team.associate { it.id to (it.hp to it.mp) }
            val survivorIds = result.battle.team.filter { !it.isDead }.map { it.id }.toSet()
            val deadDisciples = disciples.filter { it.id in towerDiscipleIds && it.id !in survivorIds }
            disciples = disciples.map { d ->
                val (hp, mp) = hpMap[d.id] ?: return@map d
                if (d.id !in survivorIds) {
                    d.copy(isAlive = false, status = DiscipleStatus.DEAD)
                } else {
                    d.copy(combat = d.combat.copy(
                        currentHp = hp.coerceIn(0, d.maxHp),
                        currentMp = mp.coerceIn(0, d.maxMp)
                    ))
                }
            }

            // 亲人逝世影响：为阵亡弟子的存活亲属设置悲痛期
            if (deadDisciples.isNotEmpty()) {
                disciples = DiscipleStatCalculator.applyGriefToRelatives(
                    disciples, deadDisciples, gd.gameYear
                )
            }

            // 构建 BattleLog
            val teamMembers = result.battle.team.map { m ->
                BattleLogMember(
                    id = m.id, name = m.name, realm = m.realm, realmName = m.realmName,
                    hp = m.hp, maxHp = m.maxHp, mp = m.mp, maxMp = m.maxMp,
                    isAlive = !m.isDead, portraitRes = m.portraitRes
                )
            }
            val enemies = result.battle.beasts.map { b ->
                BattleLogEnemy(name = b.name, realm = b.realm, realmName = b.realmName, portraitRes = b.portraitRes)
            }
            val rounds = result.log.rounds.map { r ->
                BattleLogRound(
                    roundNumber = r.roundNumber,
                    actions = r.actions.map { a ->
                        BattleLogAction(
                            type = a.type, attacker = a.attacker, attackerType = a.attackerType,
                            target = a.target, damage = a.damage, damageType = a.damageType,
                            isCrit = a.isCrit, isKill = a.isKill, message = a.message
                        )
                    }
                )
            }
            val log = BattleLog(
                year = gd.gameYear,
                month = gd.gameMonth,
                type = BattleType.PVE,
                attackerName = "巡视队伍",
                defenderName = target.beastName.ifEmpty { "妖兽" },
                result = if (result.victory) BattleResult.WIN else BattleResult.LOSE,
                teamMembers = teamMembers,
                enemies = enemies,
                rounds = rounds,
                turns = result.turnCount,
                teamCasualties = teamMembers.count { !survivorIds.contains(it.id) },
                beastsDefeated = if (result.victory) target.count else result.battle.beasts.count { it.isDead },
                details = if (result.victory) "巡视楼击败了${target.beastName}" else "巡视楼被${target.beastName}击败"
            )
            state.battleLogs = (state.battleLogs + log).takeLast(GameConfig.Logs.MAX_BATTLE_LOGS)

            // 奖励
            val allRewards = mutableListOf<BattleRewardItem>()

            // 清理阵亡弟子槽位（无论胜负都清理，避免战败时阵亡弟子残留）
            val deadIds = disciples.filter { !it.isAlive }.map { it.id }.toSet()
            if (deadIds.isNotEmpty()) {
                gd = gd.copy(
                    patrolSlots = gd.patrolSlots.map { slot ->
                        if (slot.discipleId in deadIds) PatrolSlot(index = slot.index) else slot
                    }
                )
            }

            // 标记妖兽已击败
            if (result.victory) {
                gd = gd.copy(
                    worldLevels = gd.worldLevels.map {
                        if (it.id == target.id) it.copy(defeated = true) else it
                    }
                )

                // 幸存弟子神魂+1，有天赋的随机属性+1
                disciples = disciples.map { d ->
                    if (d.id in survivorIds && d.isAlive) {
                        var modified = d.copy(soulPower = d.soulPower + 1)
                        if (modified.talentIds.any { id ->
                            TalentDatabase.getById(id)?.effects?.containsKey("winBattleRandomAttrPlus") == true
                        }) {
                            val r = kotlin.random.Random.nextInt(17)
                            val s = modified.skills
                            val c = modified.combat
                            when (r) {
                                0 -> s.intelligence++
                                1 -> s.comprehension++
                                2 -> s.charm++
                                3 -> s.loyalty++
                                4 -> s.artifactRefining++
                                5 -> s.pillRefining++
                                6 -> s.spiritPlanting++
                                7 -> s.mining++
                                8 -> s.teaching++
                                9 -> s.morality++
                                10 -> c.baseHp++
                                11 -> c.baseMp++
                                12 -> c.basePhysicalAttack++
                                13 -> c.baseMagicAttack++
                                14 -> c.basePhysicalDefense++
                                15 -> c.baseMagicDefense++
                                16 -> c.baseSpeed++
                            }
                        }
                        modified
                    } else d
                }

                // 妖兽材料奖励
                val beastConfig = GameConfig.Beast.getType(target.beastType ?: 0)
                val tier = GameConfig.Realm.getMaxRarity(target.realm)
                for (i in 0 until target.count) {
                    val materialCount = kotlin.random.Random.nextInt(1, 4)
                    repeat(materialCount) {
                        val beastMaterial = BeastMaterialDatabase.getRandomMaterialByBeastType(beastConfig.name, tier)
                        if (beastMaterial != null) {
                            val material = Material(
                                id = UUID.randomUUID().toString(),
                                name = beastMaterial.name,
                                rarity = beastMaterial.rarity,
                                description = beastMaterial.description,
                                category = beastMaterial.materialCategory,
                                quantity = 1
                            )
                            val addResult = inventorySystem.addMaterial(material)
                            if (addResult.isSuccess) {
                                allRewards.add(BattleRewardItem(
                                    itemId = material.id, name = material.name,
                                    quantity = 1, rarity = material.rarity, type = "material"
                                ))
                            }
                        }
                    }
                }

                // 灵石奖励
                val spiritStoneReward = result.rewards["spiritStones"] ?: 0
                if (spiritStoneReward > 0) {
                    gd = gd.copy(spiritStones = gd.spiritStones + spiritStoneReward.toLong())
                    allRewards.add(BattleRewardItem(name = "灵石", quantity = spiritStoneReward, rarity = 1, type = "spiritStones"))
                }
            }

            // 收集结算弹窗数据
            if (gd.patrolBattleResultPopup) {
                _pendingPatrolResults.add(BattleResultUIData(
                    battleLogId = log.id,
                    victory = result.victory,
                    teamMembers = teamMembers,
                    rewards = allRewards
                ))
            }
        }

        state.gameData = gd
        state.discipleTables.clear()
        disciples.forEach { state.discipleTables.insert(it) }
    }
    companion object {
        private const val TAG = "ExplorationService"
    }

    // ==================== StateFlow 暴露 ====================

    /**
     * Get exploration teams StateFlow
     */
    fun getTeams(): StateFlow<List<ExplorationTeam>> = stateStore.teams

    fun recallDiscipleFromTeam(teamId: String, discipleId: String): Boolean {
        val currentTeams = stateStore.teams.value
        val teamIndex = currentTeams.indexOfFirst { it.id == teamId }
        if (teamIndex < 0) return false

        val team = currentTeams[teamIndex]
        if (!team.memberIds.contains(discipleId)) return false

        val remainingMemberIds = team.memberIds.filter { it != discipleId }
        val remainingMemberNames = team.memberNames.toMutableList()
        val removedIndex = team.memberIds.indexOf(discipleId)
        if (removedIndex in remainingMemberNames.indices) {
            remainingMemberNames.removeAt(removedIndex)
        }

        if (remainingMemberIds.isEmpty()) {
            scope.launch { stateStore.update { this.teams = this.teams.filter { it.id != teamId } } }
        } else {
            val updatedTeam = team.copy(
                memberIds = remainingMemberIds,
                memberNames = remainingMemberNames
            )
            scope.launch { stateStore.update { this.teams = this.teams.toMutableList().also { it[teamIndex] = updatedTeam } } }
        }

        updateDiscipleStatus(discipleId, DiscipleStatus.IDLE)
        return true
    }

    /**
     * Complete exploration (success or failure)
     */
    fun completeExploration(teamId: String, success: Boolean, survivorIds: List<String>) {
        val currentTeams = stateStore.teams.value
        val teamIndex = currentTeams.indexOfFirst { it.id == teamId }
        if (teamIndex < 0) return

        val team = currentTeams[teamIndex]

        // Mark as completed
        val updatedTeam = team.copy(status = ExplorationStatus.COMPLETED)
        scope.launch { stateStore.update { this.teams = this.teams.toMutableList().also { it[teamIndex] = updatedTeam } } }

        // Reset survivor statuses, mark dead disciples
        team.memberIds.forEach { memberId ->
            if (survivorIds.contains(memberId)) {
                updateDiscipleStatus(memberId, DiscipleStatus.IDLE)
            } else {
                markDiscipleDead(memberId)
            }
        }
    }

    /**
     * Mark disciple as dead
     */
    private fun markDiscipleDead(discipleId: String) {
        val disciple = stateStore.disciples.value.find { it.id == discipleId }
        val gameYear = stateStore.gameData.value.gameYear
        scope.launch { stateStore.update {
            val currentList = discipleTables.assembleAll()
            val markedDead = currentList.map {
                if (it.id == discipleId) it.copy(isAlive = false, status = DiscipleStatus.DEAD) else it
            }
            val finalList = if (disciple != null) {
                DiscipleStatCalculator.applyGriefToRelatives(markedDead, listOf(disciple), gameYear)
            } else {
                markedDead
            }
            discipleTables.clear()
            finalList.forEach { discipleTables.insert(it) }
        } }
        disciple?.let { d ->
            eventBus.emitSync(DeathEvent(d.id, d.name, "探索阵亡"))
        }
    }

    private fun updateDiscipleStatus(discipleId: String, status: DiscipleStatus) {
        scope.launch { stateStore.update {
            val currentList = discipleTables.assembleAll()
            val updated = currentList.map {
                if (it.id == discipleId) it.copy(status = status) else it
            }
            discipleTables.clear()
            updated.forEach { discipleTables.insert(it) }
        } }
    }
}

package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.PillEffect
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.RecruitIntegrity
import com.xianxia.sect.core.model.RewardSelectedItem
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.model.recruitedMonth
import com.xianxia.sect.core.model.spiritStones
import com.xianxia.sect.core.model.storageBagItems
import com.xianxia.sect.core.model.storageBagSpiritStones
import com.xianxia.sect.core.state.GameNotification
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.materializeCaptiveGear
import com.xianxia.sect.core.nativebridge.GameCoreBridge
import com.xianxia.sect.core.util.StorageBagUtils
import com.xianxia.sect.core.model.BagStackedData

/**
 * 单用户定向补偿邮件（MailService 扩展，独立文件）。
 *
 * 拆分原因：MailService 类主体接近 detekt LargeClass（800 行）阈值，
 * 补偿邮件属独立运营配置，放独立文件保持 MailService 规模稳定；
 * stateStore/mailRepo 已放宽为 internal 供本扩展读取（三重防护）。
 */
// ── DiscipleFacadeImpl 拆分域 1/2（行为零变更） ──

private val TAG = DiscipleFacadeImpl.TAG
/** native 信封 reason：意外异常兜底（调用方回退 Kotlin 原实现） */
private val REASON_UNKNOWN = DiscipleFacadeImpl.REASON_UNKNOWN
/**
 * 手动招募的 native 执行（AUTHORITATIVE 单真相源）。
 *
 * @return native 已处理时的最终结果串（成功=newId；业务失败="" 且已弹提示）；
 *         null 表示 native 不可用/信封不可信，调用方回退 Kotlin 原实现
 */
@Suppress("ReturnCount")  // 信封校验链：调用/解析/业务失败/成功——逐级早退（与 ensureAuthoritativeNative 同款早退模式）
internal fun DiscipleFacadeImpl.tryNativeManualRecruit(discipleId: String): String? {
    val raw = runCatching { GameCoreBridge.nativeManualRecruitFromList(discipleId) }
        .getOrNull() ?: return null
    val envelope = parseManualRecruitEnvelope(raw) ?: return null
    if (!envelope.ok && envelope.reason != REASON_UNKNOWN) {
        // 业务失败：native 已按权威状态处理（上限/不存在/损坏条目已移除）
        // ——用户可见提示（与 Kotlin 原路径文案逐字一致）
        stateStore.update {
            pendingNotification = GameNotification.RecruitFailed(envelope.failureMessage())
        }
        DomainLog.w(TAG, "recruitDiscipleFromList: native ${envelope.reason} for $discipleId")
        return ""
    }
    if (!envelope.ok) return null  // REASON_UNKNOWN → 回退 Kotlin 原实现
    // 成功：先消费一次 C++ 前向增量（生产 tick ③ 的前置消费，幂等——C++
    // dirty 基线推进后 tick ③ 零变更），使镜像立即持有新弟子行——否则
    // 下方 lifeEvents 补写必因"镜像尚无该 id"跳过（lifeEvents 为 Kotlin
    // 类体属性不进协议，只能落镜像；Kotlin 原路径在事务内补写同生命周期）
    runCatching { gameEngineCore.stateSyncServiceRef.applyDirtyFromNative() }
    mirrorAppendJoinSectLifeEvent(envelope.newId, envelope.age)
    DomainLog.i(TAG, "recruitDiscipleFromList: native recruited $discipleId → id=${envelope.newId}")
    return envelope.newId
}

/** 镜像补写"加入宗门"日志（native 成功后；镜像滞后窗口（罕见）跳过——登记边界） */

internal fun DiscipleFacadeImpl.mirrorAppendJoinSectLifeEvent(newId: String, age: Int) {
    val intId = newId.toIntOrNull() ?: return
    stateStore.update {
        if (intId !in discipleTables.ids) return@update
        val events = discipleTables.lifeEvents.getOrDefault(intId, emptyList())
        discipleTables.lifeEvents[intId] = events + "${age}岁：加入宗门"
    }
}

/** Kotlin 侧手动招募原实现（native 不可用/UNKNOWN 时的双实现并行契约回退） */

internal fun DiscipleFacadeImpl.recruitDiscipleFromListLegacy(discipleId: String): String {
    var newId: String = ""
    stateStore.update {
        // 事务内检查招募上限（消除事务外读取的 TOCTOU 窗口）
        if (gameData.recruitCountThisMonth.coerceAtLeast(0) >= GameConfig.RECRUIT_MONTHLY_LIMIT) {
            DomainLog.w(TAG, "recruitDiscipleFromList: monthly limit reached " +
                "(${gameData.recruitCountThisMonth}/${GameConfig.RECRUIT_MONTHLY_LIMIT})")
            pendingNotification = GameNotification.RecruitFailed(
                "本月招募已达上限（${GameConfig.RECRUIT_MONTHLY_LIMIT}人）"
            )
            return@update
        }
        val disciple = gameData.recruitList.toList().find { it.id == discipleId }
        if (disciple == null) {
            DomainLog.w(TAG, "recruitDiscipleFromList: disciple $discipleId not in recruitList, " +
                "size=${gameData.recruitList.size}")
            pendingNotification = GameNotification.RecruitFailed("招募失败：该弟子已不在招募列表中")
            return@update
        }
        // ── 完整性校验：损坏条目同事务移除（幽灵立即消失，不再永久残留）──
        if (!RecruitIntegrity.isValidRecruit(disciple)) {
            DomainLog.w(TAG, "recruitDiscipleFromList: skipping corrupted disciple $discipleId: " +
                "name='${disciple.name}' age=${disciple.age} realm=${disciple.realm}")
            purgeCorruptedRecruit(discipleId, disciple.name)
            return@update
        }
        val currentMonthValue = gameData.gameYear * 12 + gameData.gameMonth
        val recruitedDisciple = disciple.copy(
            usage = disciple.usage.copy(recruitedMonth = currentMonthValue)
        )
        // 年龄-境界合理性软校验（不阻断：俘虏玩法允许年轻高境界）
        if (disciple.age < GameConfig.Realm.minReasonableAge(disciple.realm)) {
            DomainLog.w(TAG, "recruitDiscipleFromList: recruit ${disciple.name} age=${disciple.age} " +
                "realm=${disciple.realm} 低于境界最小合理年龄")
        }
        // 原子分配 ID + 写入组件表 + 加入宗门日志（消灭悬空窗口）
        newId = discipleTables.allocateAndInsert(recruitedDisciple)
        if (newId.isNotEmpty()) {
            val intId = newId.toIntOrNull()
            if (intId != null) {
                val events = discipleTables.lifeEvents.getOrDefault(intId, emptyList())
                discipleTables.lifeEvents[intId] = events + "${disciple.age}岁：加入宗门"
            }
            // 俘虏自带装备/功法落库为玩家实例（幂等；普通招募弟子无装备/功法字段，直接跳过）
            materializeCaptiveGear(recruitedDisciple, newId)
        }
        DomainLog.i(TAG, "recruitDiscipleFromList: recruited $discipleId → id=$newId")
        // 招募成功后同步移除同内容双胞胎（防"完全相同弟子"重复招募）
        gameData = gameData.copy(
            recruitList = gameData.recruitList.filter {
                it.id != discipleId && !RecruitIntegrity.isSamePerson(it, recruitedDisciple)
            },
            recruitCountThisMonth = gameData.recruitCountThisMonth + 1,
            // 年报新增弟子计数
            annualNewDisciples = gameData.annualNewDisciples + 1
        )
    }
    if (newId.isEmpty()) {
        DomainLog.w(TAG, "recruitDiscipleFromList: FAILED for $discipleId")
    }
    return newId
}


internal fun DiscipleFacadeImpl.rewardEquipment(discipleId: String, item: RewardSelectedItem) {
    stateStore.update {
        val stack = equipmentStacks.get(item.id)
        if (stack == null || stack.quantity < 1) return@update
        val id = discipleId.toIntOrNull()
        if (id == null || !discipleTables.ids.contains(id)) return@update
        val discipleRealm = discipleTables.realms[id]
        val canEquip = GameConfig.Realm.meetsRealmRequirement(discipleRealm, stack.minRealm)
        if (canEquip) {
            val slot = stack.slot
            val oldEquipId = equippedItemIdOf(id = id, slot = slot)
            if (oldEquipId.isNotEmpty()) {
                // 卸下的装备实例直接铸造入袋（容量无上限，永不失败），
                // 不再转仓库堆叠（不占仓库槽位、无溢出邮件路径）
                depositOldEquipmentToBag(id = id, oldEquipId = oldEquipId)
                clearEquipmentSlot(id = id, slot = slot)
            }
            consumeEquipmentStack(itemId = item.id, stack = stack)
            val instanceId = java.util.UUID.randomUUID().toString()
            equipmentInstances.add(stack.toInstance(id = instanceId, ownerId = discipleId, isEquipped = true))
            setEquipmentSlot(id = id, slot = slot, instanceId = instanceId)
        } else {
            consumeEquipmentStack(itemId = item.id, stack = stack)
            // 赏赐装备铸造袋条目（容量无上限，永不失败）——扣仓库数量后
            // 袋条目自带 stackedData（minRealm/slot 供取回重建），不再经仓库中转
            grantEquipmentToBag(discipleId = discipleId, item = item, stack = stack, id = id)
        }
    }
}

/** 当前装备 ID 读取 */

internal fun MutableGameState.equippedItemIdOf(id: Int, slot: EquipmentSlot): String = when (slot) {
    EquipmentSlot.WEAPON -> discipleTables.weaponIds[id]
    EquipmentSlot.ARMOR -> discipleTables.armorIds[id]
    EquipmentSlot.BOOTS -> discipleTables.bootsIds[id]
    EquipmentSlot.ACCESSORY -> discipleTables.accessoryIds[id]
    else -> ""
}

/** 旧装备卸装入袋：实例直接铸造入袋并防双持有 */

internal fun MutableGameState.depositOldEquipmentToBag(id: Int, oldEquipId: String) {
    val oldInstance = equipmentInstances.get(oldEquipId)
    if (oldInstance != null) {
        val updatedDisciple = discipleTables.assemble(id)
        discipleTables.storageBagItems[id] = StorageBagUtils.increaseItemQuantity(
            updatedDisciple.equipment.storageBagItems,
            StorageBagItem(
                itemId = oldEquipId, itemType = ITEM_TYPE_EQUIPMENT_INSTANCE,
                name = oldInstance.name, rarity = oldInstance.rarity, quantity = 1,
                obtainedYear = gameData.gameYear, obtainedMonth = gameData.gameMonth,
                equipmentInstance = oldInstance
            )
        )
        discipleTables.storageBagSpiritStones[id] = updatedDisciple.equipment.storageBagSpiritStones
        discipleTables.discipleSpiritStones[id] = updatedDisciple.equipment.spiritStones
        // 实例入袋后从实例表删除，防止双持有
        equipmentInstances = equipmentInstances.filter { it.id != oldEquipId }
    }
}

/** 装备槽位清空 */

internal fun MutableGameState.clearEquipmentSlot(id: Int, slot: EquipmentSlot) {
    when (slot) {
        EquipmentSlot.WEAPON -> discipleTables.weaponIds[id] = ""
        EquipmentSlot.ARMOR -> discipleTables.armorIds[id] = ""
        EquipmentSlot.BOOTS -> discipleTables.bootsIds[id] = ""
        EquipmentSlot.ACCESSORY -> discipleTables.accessoryIds[id] = ""
        else -> {}
    }
}

/** 装备槽位写入 */

internal fun MutableGameState.setEquipmentSlot(id: Int, slot: EquipmentSlot, instanceId: String) {
    when (slot) {
        EquipmentSlot.WEAPON -> discipleTables.weaponIds[id] = instanceId
        EquipmentSlot.ARMOR -> discipleTables.armorIds[id] = instanceId
        EquipmentSlot.BOOTS -> discipleTables.bootsIds[id] = instanceId
        EquipmentSlot.ACCESSORY -> discipleTables.accessoryIds[id] = instanceId
        else -> {}
    }
}

/** 仓库装备堆叠消耗 */

internal fun MutableGameState.consumeEquipmentStack(itemId: String, stack: EquipmentStack) {
    if (stack.quantity > 1) {
        equipmentStacks.update(itemId) { it.copy(quantity = it.quantity - 1) }
    } else {
        equipmentStacks.remove(itemId)
    }
}

/** 赏赐装备铸造袋条目：扣仓库数量后袋条目自带 stackedData */

@Suppress("UnusedParameter")
internal fun MutableGameState.grantEquipmentToBag(
    discipleId: String,
    item: RewardSelectedItem,
    stack: EquipmentStack,
    id: Int
) {
    discipleTables.storageBagItems[id] = StorageBagUtils.increaseItemQuantity(
        discipleTables.storageBagItems[id],
        StorageBagItem(itemId = item.id, itemType = ITEM_TYPE_EQUIPMENT_STACK,
            name = stack.name, rarity = stack.rarity, quantity = 1,
            obtainedYear = gameData.gameYear, obtainedMonth = gameData.gameMonth,
            forgetYear = gameData.gameYear, forgetMonth = gameData.gameMonth,
            forgetPhase = gameData.gamePhase,
            stackedData = BagStackedData(minRealm = stack.minRealm, slot = stack.slot.name))
    )
}

internal fun DiscipleFacadeImpl.rewardManual(discipleId: String, item: RewardSelectedItem) {
    stateStore.update {
        val stack = manualStacks.get(item.id)
        if (stack == null || stack.quantity < 1) return@update
        val id = discipleId.toIntOrNull()
        if (id == null || !discipleTables.ids.contains(id)) return@update
        val discipleRealm = discipleTables.realms[id]
        val currentManualIds = discipleTables.manualIds[id]
        val canLearn = GameConfig.Realm.meetsRealmRequirement(discipleRealm, stack.minRealm) &&
            currentManualIds.size < DiscipleStatCalculator.getMaxManualSlots(discipleTables.assemble(id)) &&
            !(stack.type == ManualType.MIND && currentManualIds.any { manualInstances.get(it)?.type == ManualType
                .MIND }) &&
            !currentManualIds.any { manualInstances.get(it)?.name == stack.name }
        if (canLearn) {
            if (stack.quantity <= 1) manualStacks.remove(item.id)
            else manualStacks.update(item.id) { it.copy(quantity = stack.quantity - 1) }
            val instanceId = java.util.UUID.randomUUID().toString()
            manualInstances.add(stack.toInstance(id = instanceId, ownerId = discipleId, isLearned = true))
            discipleTables.manualIds[id] = currentManualIds + instanceId
        } else {
            if (stack.quantity <= 1) manualStacks.remove(item.id)
            else manualStacks.update(item.id) { it.copy(quantity = stack.quantity - 1) }
            // 赏赐功法铸造袋条目（容量无上限，永不失败）——扣仓库数量后
            // 袋条目自带 stackedData（minRealm/manualType 供取回重建）
            discipleTables.storageBagItems[id] = StorageBagUtils.increaseItemQuantity(
                discipleTables.storageBagItems[id],
                StorageBagItem(itemId = item.id, itemType = ITEM_TYPE_MANUAL_STACK,
                    name = stack.name, rarity = stack.rarity, quantity = 1,
                    obtainedYear = gameData.gameYear, obtainedMonth = gameData.gameMonth,
                    forgetYear = gameData.gameYear, forgetMonth = gameData.gameMonth,
                    forgetPhase = gameData.gamePhase,
                    stackedData = BagStackedData(minRealm = stack.minRealm, manualType = stack.type.name))
            )
        }
    }
}


/** 修炼值丹药效果 */
internal fun MutableGameState.applyCultivationAddEffect(id: Int, effect: PillEffect) {
    discipleTables.cultivations[id] = discipleTables.cultivations[id] + effect.cultivationAdd
}

/** 功法经验丹药效果 */

internal fun MutableGameState.applySkillExpEffect(id: Int, effect: PillEffect) {
    discipleTables.manualMasteries[id] = discipleTables.manualMasteries[id].mapValues { (_, v) ->
        (v + effect.skillExpAdd).coerceAtMost(10000)
    }
}

/** 延寿丹药效果 */

internal fun MutableGameState.applyExtendLifeEffect(id: Int, effect: PillEffect, pill: Pill) {
    discipleTables.lifespans[id] = discipleTables.lifespans[id] + effect.extendLife
    val usedExtendLife = discipleTables.usedExtendLifePillTypes[id]
    if (pill.pillType !in usedExtendLife) {
        discipleTables.usedExtendLifePillTypes[id] = usedExtendLife + pill.pillType
    }
}

/** 永久基础属性丹效果：技能属性 + 道德触发偷盗判定 + 记录使用 */

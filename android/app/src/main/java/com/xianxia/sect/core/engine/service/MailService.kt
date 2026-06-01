package com.xianxia.sect.core.engine.service

import android.util.Log
import com.xianxia.sect.BuildConfig
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.config.BuiltinMailConfig
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.engine.RedeemCodeManager
import com.xianxia.sect.core.engine.system.GameSystem
import com.xianxia.sect.core.engine.system.SystemPriority
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.registry.ItemDatabase
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.data.local.ClaimedMailDao
import com.xianxia.sect.data.local.MailDao
import com.xianxia.sect.network.SecureHttpClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlinx.serialization.encodeToString
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

sealed class ClaimResult {
    data class Success(val claimedAttachments: List<MailAttachment>) : ClaimResult()
    data object AlreadyClaimed : ClaimResult()
    data object Expired : ClaimResult()
    data object MailNotFound : ClaimResult()
    data class CapacityInsufficient(val message: String) : ClaimResult()
}

data class MarkAllReadResult(
    val claimedCount: Int = 0,
    val skippedCount: Int = 0,
    val skipReasons: List<String> = emptyList()
)

@SystemPriority(order = 960)
@Singleton
class MailService @Inject constructor(
    private val mailDao: MailDao,
    private val claimedMailDao: ClaimedMailDao,
    private val stateStore: GameStateStore,
    private val inventoryConfig: InventoryConfig,
    private val secureClient: SecureHttpClient,
    @ApplicationContext private val appContext: android.content.Context
) : GameSystem {
    override val systemName: String = "MailService"

    companion object {
        private const val TAG = "MailService"
        const val MAX_MAILS_PER_SLOT = 1000
        private const val EXPIRE_DAYS = 30L
        private const val EXPIRE_MS = EXPIRE_DAYS * 24 * 60 * 60 * 1000L
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    }

    private val slotMutexes = mutableMapOf<Int, Mutex>()

    private fun getMutex(slotId: Int): Mutex {
        return slotMutexes.getOrPut(slotId) { Mutex() }
    }

    override fun initialize() {
        Log.d(TAG, "MailService initialized")
    }

    override fun release() {
        Log.d(TAG, "MailService released")
    }

    override suspend fun clearForSlot(slotId: Int) {
        getMutex(slotId).withLock {
            Log.d(TAG, "Clearing mail cache for slot $slotId")
        }
    }

    override suspend fun onMonthTick(state: MutableGameState) {
        val slotId = state.gameData.slotId
        try {
            fetchOnlineMails(slotId)
            cleanExpired(slotId)
        } catch (e: Exception) {
            Log.e(TAG, "Error in onMonthTick for slot $slotId", e)
        }
    }

    suspend fun fetchOnlineMails(slotId: Int) {
        try {
            val request = Request.Builder()
                .url("${BuildConfig.API_BASE_URL}mail/list?version=${BuildConfig.VERSION_CODE}")
                .get()
                .build()

            val response = secureClient.execute(request)
            val body = response.body?.string() ?: return

            val apiResponse = json.decodeFromString<MailListApiResponse>(body)
            apiResponse.mails.forEach { mailData ->
                if (!mailDao.existsByRemoteId(mailData.remoteId)) {
                    val now = System.currentTimeMillis()
                    val entity = MailEntity(
                        id = java.util.UUID.randomUUID().toString(),
                        slotId = slotId,
                        source = "online",
                        mailType = mailData.type,
                        title = mailData.title,
                        content = mailData.content,
                        senderName = "天道意志",
                        sendTime = mailData.sendTime,
                        expireTime = mailData.expireTime.coerceAtLeast(now + EXPIRE_MS),
                        hasAttachment = mailData.attachments.isNotEmpty(),
                        attachments = json.encodeToString(serializer<List<MailAttachment>>(), mailData.attachments),
                        remoteMailId = mailData.remoteId
                    )
                    mailDao.insertWithEnforceLimit(entity, MAX_MAILS_PER_SLOT)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch online mails for slot $slotId", e)
        }
    }

    suspend fun loadBuiltinMails(slotId: Int) {
        val now = System.currentTimeMillis()
        BuiltinMailConfig.mails.forEach { builtinMail ->
            val globalId = "builtin:${builtinMail.id}"
            if (!claimedMailDao.isClaimed(globalId, slotId)) {
                val existingMails = mailDao.getActiveMails(slotId, now).first()
                val alreadyInserted = existingMails.any { it.source == "builtin" && it.id == builtinMail.id }
                if (!alreadyInserted) {
                    val entity = MailEntity(
                        id = builtinMail.id,
                        slotId = slotId,
                        source = "builtin",
                        mailType = builtinMail.mailType,
                        title = builtinMail.title,
                        content = builtinMail.content,
                        senderName = "天道意志",
                        sendTime = now,
                        expireTime = now + EXPIRE_MS,
                        hasAttachment = builtinMail.attachments.isNotEmpty(),
                        attachments = json.encodeToString(serializer<List<MailAttachment>>(), builtinMail.attachments)
                    )
                    mailDao.insertWithEnforceLimit(entity, MAX_MAILS_PER_SLOT)
                }
            }
        }
    }

    suspend fun claimAttachment(mailId: String, slotId: Int): ClaimResult {
        return getMutex(slotId).withLock {
            val mail = mailDao.getById(mailId) ?: return ClaimResult.MailNotFound
            val now = System.currentTimeMillis()
            if (mail.expireTime <= now) return ClaimResult.Expired

            val mailGlobalId = if (mail.remoteMailId != null) {
                "remote:${mail.remoteMailId}"
            } else {
                "builtin:${mail.id}"
            }

            if (claimedMailDao.isClaimed(mailGlobalId, slotId)) {
                return ClaimResult.AlreadyClaimed
            }

            val attachments: List<MailAttachment> = try {
                json.decodeFromString(mail.attachments)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse attachments for mail $mailId", e)
                return ClaimResult.Success(emptyList())
            }

            // 容量检查（在审计记录写入之前，容量不足时可自由重试）
            if (attachments.isNotEmpty()) {
                val capacityCheck = ensureCapacity(attachments, slotId)
                if (capacityCheck != null) {
                    return ClaimResult.CapacityInsufficient(capacityCheck)
                }
            }

            // 容量通过后才写入审计记录（危险操作前置 — 此时发放一定会成功）
            try {
                claimedMailDao.insert(
                    ClaimedMailRecord(
                        mailGlobalId = mailGlobalId,
                        slotId = slotId,
                        claimedTime = now
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Concurrent claim detected for $mailGlobalId slot $slotId", e)
                return ClaimResult.AlreadyClaimed
            }

            if (attachments.isNotEmpty()) {
                try {
                    distributeAttachments(attachments)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to distribute attachments for mail $mailId, audit record exists", e)
                    return ClaimResult.Success(emptyList())
                }
            }

            mailDao.update(mail.copy(attachmentClaimed = true, isRead = true))
            ClaimResult.Success(attachments)
        }
    }

    suspend fun markAllAsRead(slotId: Int): MarkAllReadResult {
        return getMutex(slotId).withLock {
            val now = System.currentTimeMillis()
            val mails = mailDao.getActiveMails(slotId, now).first()

            var claimedCount = 0
            var skippedCount = 0
            val skipReasons = mutableListOf<String>()

            mails.filter { !it.isRead || (it.hasAttachment && !it.attachmentClaimed) }.forEach { mail ->
                if (mail.hasAttachment && !mail.attachmentClaimed) {
                    when (val result = claimAttachmentInternal(mail, slotId, now)) {
                        is ClaimResult.Success -> claimedCount++
                        is ClaimResult.CapacityInsufficient -> {
                            skippedCount++
                            skipReasons.add(result.message)
                        }
                        else -> {}
                    }
                } else if (!mail.isRead) {
                    mailDao.update(mail.copy(isRead = true))
                }
            }

            MarkAllReadResult(claimedCount, skippedCount, skipReasons)
        }
    }

    private suspend fun claimAttachmentInternal(mail: MailEntity, slotId: Int, now: Long): ClaimResult {
        if (mail.expireTime <= now) return ClaimResult.Expired

        val mailGlobalId = if (mail.remoteMailId != null) {
            "remote:${mail.remoteMailId}"
        } else {
            "builtin:${mail.id}"
        }

        if (claimedMailDao.isClaimed(mailGlobalId, slotId)) {
            return ClaimResult.AlreadyClaimed
        }

        val attachments: List<MailAttachment> = try {
            json.decodeFromString(mail.attachments)
        } catch (e: Exception) {
            return ClaimResult.Success(emptyList())
        }

        // 容量检查（在审计记录写入之前，容量不足时可自由重试）
        if (attachments.isNotEmpty()) {
            val capacityCheck = ensureCapacity(attachments, slotId)
            if (capacityCheck != null) {
                return ClaimResult.CapacityInsufficient(capacityCheck)
            }
        }

        // 容量通过后才写入审计记录
        try {
            claimedMailDao.insert(
                ClaimedMailRecord(
                    mailGlobalId = mailGlobalId,
                    slotId = slotId,
                    claimedTime = now
                )
            )
        } catch (e: Exception) {
            return ClaimResult.AlreadyClaimed
        }

        if (attachments.isNotEmpty()) {
            try {
                distributeAttachments(attachments)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to distribute attachments for mail ${mail.id}", e)
                return ClaimResult.Success(emptyList())
            }
        }

        mailDao.update(mail.copy(attachmentClaimed = true, isRead = true))
        return ClaimResult.Success(attachments)
    }

    /**
     * 确保有足够容量领取附件。容量不足时尝试级联清理邮件腾空间：
     * 1) 删除已读已领邮件
     * 2) 删除无附件邮件（无物品损失）
     * 仍不足则返回错误消息。
     */
    private suspend fun ensureCapacity(attachments: List<MailAttachment>, slotId: Int): String? {
        val data = stateStore.gameData.value
        val disciples = stateStore.disciples.value

        for (attachment in attachments) {
            when (attachment.type) {
                "spiritStones", "spiritHerbs", "storageBag" -> {}
                "equipment", "pill", "material", "herb", "seed" -> {
                    var totalItems = stateStore.equipmentStacks.value.size +
                            stateStore.manualStacks.value.size +
                            stateStore.pills.value.size +
                            stateStore.materials.value.size +
                            stateStore.herbs.value.size +
                            stateStore.seeds.value.size
                    val warehouseCount = data.placedBuildings.count { it.displayName == "仓库" }
                    val maxCap = GameConfig.Warehouse.BASE_CAPACITY +
                            warehouseCount * GameConfig.Warehouse.CAPACITY_PER_BUILDING

                    if (totalItems >= maxCap) {
                        // 级联清理：先删已读已领邮件
                        mailDao.deleteAllReadAndClaimed(slotId)
                        totalItems = stateStore.equipmentStacks.value.size +
                                stateStore.manualStacks.value.size +
                                stateStore.pills.value.size +
                                stateStore.materials.value.size +
                                stateStore.herbs.value.size +
                                stateStore.seeds.value.size
                        if (totalItems >= maxCap) {
                            // 仍不足：删无附件邮件（无物品损失）
                            mailDao.deleteMailsWithoutAttachments(slotId)
                            totalItems = stateStore.equipmentStacks.value.size +
                                    stateStore.manualStacks.value.size +
                                    stateStore.pills.value.size +
                                    stateStore.materials.value.size +
                                    stateStore.herbs.value.size +
                                    stateStore.seeds.value.size
                            if (totalItems >= maxCap) {
                                return "仓库空间不足，请清理后再领取"
                            }
                        }
                    }
                }
                "disciple" -> {
                    val aliveCount = disciples.count { it.isAlive }
                    if (aliveCount >= GameConfig.Disciple.MAX_DISCIPLES) {
                        return "宗门弟子已满，无法领取弟子"
                    }
                }
            }
        }
        return null
    }

    private suspend fun distributeAttachments(attachments: List<MailAttachment>) {
        stateStore.update {
            attachments.forEach { attachment ->
                when (attachment.type) {
                    "spiritStones" -> {
                        gameData = gameData.copy(
                            spiritStones = gameData.spiritStones + attachment.quantity
                        )
                    }
                    "spiritHerbs" -> {
                        gameData = gameData.copy(
                            spiritHerbs = gameData.spiritHerbs + attachment.quantity
                        )
                    }
                    "equipment" -> {
                        val qty = attachment.quantity.coerceAtLeast(1)
                        val newEquipment = EquipmentDatabase.generateRandom(
                            minRarity = attachment.rarity,
                            maxRarity = attachment.rarity
                        ).copy(quantity = qty)
                        val existing = equipmentStacks.find {
                            it.name == newEquipment.name && it.rarity == newEquipment.rarity && it.slot == newEquipment.slot
                        }
                        if (existing != null) {
                            val newQty = (existing.quantity + newEquipment.quantity)
                                .coerceAtMost(inventoryConfig.getMaxStackSize("equipment_stack"))
                            equipmentStacks = equipmentStacks.map {
                                if (it.id == existing.id) it.copy(quantity = newQty) else it
                            }
                        } else {
                            equipmentStacks = equipmentStacks + newEquipment
                        }
                    }
                    "pill" -> {
                        val qty = attachment.quantity.coerceAtLeast(1)
                        val pill = ItemDatabase.generateRandomPill(
                            minRarity = attachment.rarity,
                            maxRarity = attachment.rarity
                        ).copy(quantity = qty)
                        val existing = pills.find {
                            it.name == pill.name && it.rarity == pill.rarity && it.category == pill.category
                        }
                        if (existing != null) {
                            val newQty = (existing.quantity + pill.quantity)
                                .coerceAtMost(inventoryConfig.getMaxStackSize("pill"))
                            pills = pills.map { if (it.id == existing.id) it.copy(quantity = newQty) else it }
                        } else {
                            pills = pills + pill
                        }
                    }
                    "material" -> {
                        val qty = attachment.quantity.coerceAtLeast(1)
                        val material = ItemDatabase.generateRandomMaterial(
                            minRarity = attachment.rarity,
                            maxRarity = attachment.rarity
                        ).copy(quantity = qty)
                        val existing = materials.find {
                            it.name == material.name && it.rarity == material.rarity && it.category == material.category
                        }
                        if (existing != null) {
                            val newQty = (existing.quantity + material.quantity)
                                .coerceAtMost(inventoryConfig.getMaxStackSize("material"))
                            materials = materials.map {
                                if (it.id == existing.id) it.copy(quantity = newQty) else it
                            }
                        } else {
                            materials = materials + material
                        }
                    }
                    "herb" -> {
                        val qty = attachment.quantity.coerceAtLeast(1)
                        val herbTemplate = HerbDatabase.generateRandomHerb(
                            minRarity = attachment.rarity,
                            maxRarity = attachment.rarity
                        )
                        val herb = Herb(
                            id = java.util.UUID.randomUUID().toString(),
                            name = herbTemplate.name,
                            rarity = herbTemplate.rarity,
                            description = herbTemplate.description,
                            category = herbTemplate.category,
                            quantity = qty
                        )
                        val existing = herbs.find {
                            it.name == herb.name && it.rarity == herb.rarity && it.category == herb.category
                        }
                        if (existing != null) {
                            val newQty = (existing.quantity + herb.quantity)
                                .coerceAtMost(inventoryConfig.getMaxStackSize("herb"))
                            herbs = herbs.map { if (it.id == existing.id) it.copy(quantity = newQty) else it }
                        } else {
                            herbs = herbs + herb
                        }
                    }
                    "seed" -> {
                        val qty = attachment.quantity.coerceAtLeast(1)
                        val seedTemplate = HerbDatabase.generateRandomSeed(
                            minRarity = attachment.rarity,
                            maxRarity = attachment.rarity
                        )
                        val seed = Seed(
                            id = java.util.UUID.randomUUID().toString(),
                            name = seedTemplate.name,
                            rarity = seedTemplate.rarity,
                            description = seedTemplate.description,
                            growTime = seedTemplate.growTime,
                            yield = seedTemplate.yield,
                            quantity = qty
                        )
                        val existing = seeds.find {
                            it.name == seed.name && it.rarity == seed.rarity && it.growTime == seed.growTime
                        }
                        if (existing != null) {
                            val newQty = (existing.quantity + seed.quantity)
                                .coerceAtMost(inventoryConfig.getMaxStackSize("seed"))
                            seeds = seeds.map { if (it.id == existing.id) it.copy(quantity = newQty) else it }
                        } else {
                            seeds = seeds + seed
                        }
                    }
                    "disciple" -> {
                        val currentMonthValue = gameData.gameYear * 12 + gameData.gameMonth
                        val usedNames = disciples.map { it.name }.toMutableSet()
                        repeat(attachment.quantity.coerceAtLeast(1)) {
                            val disciple = RedeemCodeManager.generateDisciple(null, usedNames)
                            disciple.usage.recruitedMonth = currentMonthValue
                            disciples = disciples + disciple
                            usedNames.add(disciple.name)
                        }
                    }
                    "storageBag" -> {
                        val qty = attachment.quantity.coerceAtLeast(1)
                        val bag = StorageBag(
                            id = java.util.UUID.randomUUID().toString(),
                            name = StorageBag.TIER_NAMES.getOrElse(attachment.rarity.coerceIn(1, 6) - 1) { "凡品储物袋" },
                            rarity = attachment.rarity.coerceIn(1, 6),
                            quantity = qty
                        )
                        storageBags = storageBags + bag
                    }
                }
            }
        }
    }

    suspend fun markAsRead(mailId: String) {
        val mail = mailDao.getById(mailId) ?: return
        if (!mail.isRead) {
            mailDao.update(mail.copy(isRead = true))
        }
    }

    suspend fun deleteMail(mailId: String, slotId: Int) {
        val mail = mailDao.getById(mailId) ?: return
        if (mail.hasAttachment && !mail.attachmentClaimed) return
        mailDao.deleteById(mailId)
    }

    suspend fun deleteAllReadAndClaimed(slotId: Int) {
        mailDao.deleteAllReadAndClaimed(slotId)
    }

    suspend fun cleanExpired(slotId: Int) {
        val now = System.currentTimeMillis()
        mailDao.deleteExpired(slotId, now)
        try {
            claimedMailDao.deleteOrphanedForExpiredMails(slotId, now)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clean orphaned claimed records for slot $slotId", e)
        }
    }

    fun getActiveMails(slotId: Int): Flow<List<MailEntity>> {
        return mailDao.getActiveMails(slotId, System.currentTimeMillis())
    }

    fun getUnreadCount(slotId: Int): Flow<Int> {
        return mailDao.getUnreadCount(slotId, System.currentTimeMillis())
    }

    suspend fun initializeForSlot(slotId: Int) {
        try {
            fetchOnlineMails(slotId)
            loadBuiltinMails(slotId)
            cleanExpired(slotId)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing mail for slot $slotId", e)
        }
    }
}

@Serializable
data class MailListApiResponse(
    val mails: List<MailApiData> = emptyList()
)

@Serializable
data class MailApiData(
    val remoteId: String = "",
    val title: String = "",
    val content: String = "",
    val type: String = "reward",
    val sendTime: Long = 0,
    val expireTime: Long = 0,
    val attachments: List<MailAttachment> = emptyList()
)

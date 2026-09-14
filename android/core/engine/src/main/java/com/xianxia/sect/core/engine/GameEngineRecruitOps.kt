package com.xianxia.sect.core.engine

import kotlinx.coroutines.flow.map
import com.xianxia.sect.core.model.RecruitIntegrity
import com.xianxia.sect.core.model.recruitedMonth
import com.xianxia.sect.core.state.GameNotification
import com.xianxia.sect.core.state.materializeCaptiveGear
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.nativebridge.ActionIds
import com.xianxia.sect.core.nativebridge.GameCoreBridge
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps.params
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.nativebridge.StateSyncService
import kotlinx.serialization.json.put


// ── Cross-domain: Recruit ───────────────────────────────────────────

suspend fun GameEngine.recruitAllFromList(): Int {
    return engineContextDispatcher.withEngineContext {
        // AUTHORITATIVE：一键招募下沉 C++ 单真相源（与自动招募/手动单招同侧）——
        // C++ 直接招募入宗，下一 tick 前向 diff 推送镜像，消除"Kotlin 镜像修改
        // vs C++ 权威结算"竞态与反向回导失败窗口。native 不可用/信封 UNKNOWN
        // 时回退 Kotlin 原实现（双实现并行契约）。
        if (NativeEngineFlag.authoritative && GameCoreBridge.isLoaded) {
            val nativeResult = tryNativeRecruitAll()
            if (nativeResult != null) return@withEngineContext nativeResult
        }
        recruitAllFromListLegacy()
    }
}

/** 一键招募的 native 执行（返回 null 表示 native 不可用/信封不可信，回退 Kotlin） */
@Suppress("ReturnCount")  // 信封校验链：调用/解析/业务失败/成功——逐级早退（与 tryNativeManualRecruit 同款）
private suspend fun GameEngine.tryNativeRecruitAll(): Int? {
    val raw = runCatching { GameCoreBridge.nativeRecruitAllFromList() }.getOrNull()
        ?: return null
    val envelope = parseRecruitAllEnvelope(raw) ?: return null
    if (!envelope.ok && envelope.reason != RECRUIT_ENVELOPE_REASON_UNKNOWN) {
        // MONTHLY_LIMIT：与 Kotlin 原路径一致的提示
        stateStore.update {
            pendingNotification = GameNotification.RecruitFailed(
                "本月招募已达上限（${GameConfig.RECRUIT_MONTHLY_LIMIT}人）"
            )
        }
        DomainLog.w("GameEngine", "recruitAllFromList: native monthly limit reached")
        return 0
    }
    if (!envelope.ok) return null  // UNKNOWN → 回退 Kotlin 原实现
    DomainLog.i("GameEngine", "recruitAllFromList: native recruited ${envelope.count} disciples")
    return envelope.count
}

/** Kotlin 侧一键招募原实现（native 不可用/UNKNOWN 时回退——双实现并行契约） */
private suspend fun GameEngine.recruitAllFromListLegacy(): Int {
    return engineContextDispatcher.withEngineContext {
        var recruited = 0
        stateStore.update {
            // 事务开头净化：损坏/重复/残留条目同事务移除（与点击招募一致），
            // 防一键招募时幽灵/双胞胎入宗门
            val sanitized = com.xianxia.sect.core.engine.service.RecruitService.sanitizeRecruitList(this)
            if (sanitized > 0) {
                DomainLog.w("GameEngine", "recruitAllFromList: 净化 recruitList $sanitized 条异常条目")
            }
            val count = gameData.recruitCountThisMonth.coerceAtLeast(0)
            val remaining = GameConfig.RECRUIT_MONTHLY_LIMIT - count
            if (remaining <= 0) {
                DomainLog.i("GameEngine",
                    "recruitAllFromList: monthly limit reached ($count/${GameConfig.RECRUIT_MONTHLY_LIMIT})")
                pendingNotification = GameNotification.RecruitFailed(
                    "本月招募已达上限（${GameConfig.RECRUIT_MONTHLY_LIMIT}人）")
                return@update
            }

            // 净化已移除损坏条目并去重，此处过滤为防御纵深
            val validRecruits = gameData.recruitList.filter(RecruitIntegrity::isValidRecruit)
            if (validRecruits.isEmpty()) {
                // 净化后损坏条目理论上不可达（防御保留）
                if (gameData.recruitList.isNotEmpty()) DomainLog.w("GameEngine",
                    "recruitAllFromList: all ${gameData.recruitList.size} recruits are corrupted, skipped")
                return@update
            }

            val takeCount = minOf(validRecruits.size, remaining)
            val toRecruit = validRecruits.take(takeCount)
            // 按 id 移除已招募条目（而非 data class 全字段 equals，
            // 防同 id 不同内容的残余条目存活）
            val toRecruitIds = toRecruit.map { it.id }.toSet()
            val keepInList = gameData.recruitList.filter { it.id !in toRecruitIds }

            val droppedCount = gameData.recruitList.size - validRecruits.size
            val currentMonth = gameData.gameYear * 12 + gameData.gameMonth
            recruited = toRecruit.count { disciple ->
                val newId = discipleTables.allocateAndInsert(disciple.copy(
                    usage = disciple.usage.copy(recruitedMonth = currentMonth))
                    .also { it.lifeEvents = listOf("${disciple.age}岁：加入宗门") })
                // 俘虏自带装备/功法落库为玩家实例（幂等）
                if (newId.isNotEmpty()) materializeCaptiveGear(disciple, newId)
                newId.isNotEmpty()
            }
            gameData = gameData.copy(
                recruitList = keepInList,
                recruitCountThisMonth = gameData.recruitCountThisMonth + recruited,
                // 年报新增弟子计数（一键全招同样计入）
                annualNewDisciples = gameData.annualNewDisciples + recruited
            )
            if (droppedCount > 0 || takeCount < validRecruits.size) {
                DomainLog.w("GameEngine", "recruitAllFromList: dropped $droppedCount corrupted, " +
                    "recruited $recruited, ${keepInList.size} remain (monthly limit)")
            } else {
                DomainLog.i("GameEngine", "recruitAllFromList: recruited $recruited disciples")
            }
        }
        return@withEngineContext recruited
    }
}

fun GameEngine.removeFromRecruitList(discipleId: String) {
    gameEngineCore.launchInScope {
        // batch-16 native 臂：招募列表移除（RECRUIT_REMOVE_TX——按 id 全量
        // 过滤幂等，零 RNG）。AUTHORITATIVE 门控 + 镜像服务可空局部判空
        //（handover findings 13：测试 mock 未 stub stateSyncServiceRef 时
        // null → 回退，防调用点内非空检查 NPE）；失败信封/降级 → Kotlin
        // 原路径（双实现并行契约，回退臂语义与下沉前逐字一致）。
        if (NativeEngineFlag.authoritative) {
            val sync: StateSyncService? = gameEngineCore.stateSyncServiceRef
            if (sync != null) {
                val data = GameEngineNativeOps.tryExecuteNative(
                    stateSyncService = sync,
                    actionId = ActionIds.RECRUIT_REMOVE_TX,
                    paramsJson = params {
                        put("discipleId", discipleId)
                    }
                )
                if (data != null) return@launchInScope
            }
        }
        stateStore.update {
            gameData = gameData.copy(
                recruitList = gameData.recruitList.toList().filter { it.id != discipleId })
        }
    }
}

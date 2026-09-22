package com.xianxia.sect.ui.game.saveload

import com.xianxia.sect.data.StorageConstants
import com.xianxia.sect.data.cloud.SlotLedgerSnapshot
import com.xianxia.sect.data.cloud.UploadLedger
import com.xianxia.sect.data.model.SaveSlot

/**
 * 迁移引导的界面态模型（SR-6，主菜单迁移卡的数据面）。
 *
 * 与判定分离：矩阵本体在 `core:data` 的
 * [com.xianxia.sect.data.cloud.SaveMigrationPlanner]（纯函数、零 IO），
 * 本文件只把它 output 翻成玩家看得见的行。
 */

/** 迁移引导阶段（迁移卡的总状态） */
enum class MigrationPhase {
    /** 无可引导内容：新设备，或本地根本没有存量档 */
    IDLE,

    /** 检测到待迁槽，等玩家显式开始（**此阶段零云请求**） */
    READY,

    /** 玩家已点开始，逐槽排队中（TapTap 创建/更新共享 1 次/分钟 ⇒ 多槽必然耗时数分钟） */
    RUNNING,

    /** 全部收口 */
    DONE,

    /** 有槽位失败且不再自动重试（如实分列，不谎报"已迁移"） */
    PARTIAL_FAILED,

    /** 云服务不可达 / 未登录：原因如实带回，不静默降级成"无档可迁" */
    SERVICE_UNAVAILABLE
}

/** 单槽迁移状态（迁移卡行内文案依据） */
enum class SlotMigrationStatus {
    /** 本机有存量档、云上还没有 ⇒ 待上云（引导的主目标） */
    AWAITING_UPLOAD,

    /** 已入队，等上传确认到账 */
    UPLOADING,

    /** 上云失败且不再自动重试 */
    UPLOAD_FAILED,

    /** 本机与云端各有内容，等玩家二选一（禁止静默覆盖，方案 §2/§6） */
    NEEDS_DECISION,

    /** 本机无档、云端有档 ⇒ 待下载到本机（方案矩阵第三格"直接云档"） */
    CLOUD_ONLY,

    /** 本机该槽读取失败（损坏）：既不上传也不当空档 */
    BLOCKED_CORRUPT,

    /** 已收口：上云确认到账，或玩家已裁决以云端为准 */
    MIGRATED
}

/** 迁移卡的一行 */
data class MigrationSlotRow(
    val slot: Int,
    /** "宗门 · 第X年Y月" */
    val label: String,
    val status: SlotMigrationStatus,
    /** 失败原因 / 冲突来由（玩家看得懂的原文，不是日志术语） */
    val detail: String? = null
)

/** 迁移引导界面态 */
data class MigrationUiState(
    val phase: MigrationPhase = MigrationPhase.IDLE,
    val rows: List<MigrationSlotRow> = emptyList(),
    /** 仍需玩家动作的槽数（完成率的母数构成） */
    val pendingTotal: Int = 0,
    /** 已收口槽数 */
    val migratedTotal: Int = 0,
    /** 阶段级提示（云服务不可达的原因、熔断等） */
    val notice: String? = null,
    /** 存量单档 `mnzm_cloud_save` 在云端存在（SR-3 报告 §4.1 移交本批的迁移源） */
    val legacyArchivePresent: Boolean = false,
    /** 待下载到本机的云端槽位数（矩阵第三格） */
    val cloudOnlyTotal: Int = 0,
    /** 「启用云存档」可用：LEGACY 下所有可引导槽均已收口（§2.4 玩家确认式升档） */
    val canEnableCloudSave: Boolean = false
) {
    /**
     * 迁移卡是否出现：有可迁内容就常驻（弹窗才需要"只弹一次"的抑制位，常驻卡不需要）。
     *
     * 全部收口 **且** 云存档已启用（`canEnableCloudSave` 为假即代表已离开 LEGACY 或仍有待办）
     * 且无存量单档待取回 ⇒ 引导完成，卡片消失（方案 §4 SR-6 的收口态）。
     */
    val visible: Boolean
        get() = (rows.isNotEmpty() || phase == MigrationPhase.SERVICE_UNAVAILABLE) &&
            !(allMigrated && !canEnableCloudSave && !legacyArchivePresent && cloudOnlyTotal == 0)

    /** 所有可引导槽均已收口 */
    val allMigrated: Boolean
        get() = rows.isNotEmpty() && rows.all { it.status == SlotMigrationStatus.MIGRATED }
}

/** 运行期即时态（入队中/失败/待裁决——这些不在 MMKV 里，只属于本次引导） */
internal data class MigrationOverlay(val status: SlotMigrationStatus, val detail: String? = null)

/** 可引导槽（排除 slot 0——它是 `getSaveSlots` 无条件插入的云会话入口卡，勘察 F3） */
internal fun migratableSlots(slots: List<SaveSlot>): List<SaveSlot> =
    slots.filter { it.slot != StorageConstants.CLOUD_SAVE_SLOT }

internal fun SaveSlot.migrationLabel(): String = "$sectName · $displayTime"

/** 读一个槽的本端上传序号（矩阵入参；序号语义唯一来源，IN2 零时钟） */
internal fun SaveSlot.ledgerSnapshot(ledger: UploadLedger): SlotLedgerSnapshot = SlotLedgerSnapshot(
    lastLocalSaveId = ledger.lastLocalSaveId(slot),
    lastConfirmedCloudId = ledger.lastConfirmedCloudId(slot),
    pendingSaveId = ledger.pendingSaveId(slot)
)

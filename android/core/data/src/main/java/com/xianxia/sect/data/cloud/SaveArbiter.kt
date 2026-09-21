package com.xianxia.sect.data.cloud

/**
 * 进度"谁新"仲裁 verdict（SR-0 §4.1 状态模型）。
 *
 * IN2：判定**只看保存序号/脏标志**，永不比较墙钟或 mtime。
 */
enum class ArbitrationVerdict {
    /** L==C==W：两端一致，无需动作 */
    IN_SYNC,

    /** 本地净（L==C）且云端更新（W>C）：直接走云下载 */
    LOCAL_BEHIND,

    /** 仅本地新（L>C 且云端没有别的端的新进度）：队列重试上传即可，非冲突 */
    UPLOAD_PENDING,

    /** 真冲突（L>C 且云端有另一端的新进度 W>C 且 W≠L）：必须弹窗二选一，禁止静默覆盖 */
    CONFLICT
}

/**
 * 脏标志仲裁纯函数（SR-2 仲裁重写的唯一"谁新"入口，JVM 可直测）。
 *
 * 输入（SR-0 §4.1 记号）：
 * - `lastLocalSaveId`（L）：本地最新保存序号（[UploadLedger] per-slot 记账）；
 * - `lastConfirmedCloudId`（C）：本地已确认上云的序号；
 * - `cloudSaveId`（W）：云端实际保存序号（extra JSON `saveId` 回带）；**null = W 未知**
 *   （存量档无该字段/查询失败）。
 *
 * 判定表（SR-0 §4.3 U1-U11 逐例单测锚定）：
 * - L==C：W>C → LOCAL_BEHIND（U2/U7）；W<=C → IN_SYNC（U1/U5/U6：云落后视为已确认旧态）；
 * - L>C：W<=C → UPLOAD_PENDING（U3，仅本地新≠冲突）；W==L → UPLOAD_PENDING（U9，
 *   确认回填竞态：云端已是本档，幂等重传收敛）；其余（W>C 且 W≠L，含 W>L 与 C<W<L）→
 *   CONFLICT（U4/U8：本地与云各有新进度）；
 * - L<C（U10 非法态：确认写入先于本地序号写入的中断窗）→ IN_SYNC + 由
 *   [UploadLedger.normalizeIfNeeded] 自愈并遥测——防御性自愈，不升级为冲突/误传；
 * - W 未知（null，U11）：保守退化为按 W==C 重算——**失败封闭**：查询失败不得把
 *   UPLOAD_PENDING 升级成"云无档"而静默上传，也不得凭空制造冲突。
 */
object SaveArbiter {

    fun arbitrate(lastLocalSaveId: Long, lastConfirmedCloudId: Long, cloudSaveId: Long?): ArbitrationVerdict {
        val l = lastLocalSaveId
        val c = lastConfirmedCloudId
        if (l < c) return ArbitrationVerdict.IN_SYNC // U10 非法态：自愈归 IN_SYNC（ledger 负责修复）
        val w = cloudSaveId ?: c // U11：W 未知 → 保守按 W==C 重算
        return when {
            l == c -> if (w > c) ArbitrationVerdict.LOCAL_BEHIND else ArbitrationVerdict.IN_SYNC
            else -> when {
                w <= c -> ArbitrationVerdict.UPLOAD_PENDING // U3：仅本地新
                w == l -> ArbitrationVerdict.UPLOAD_PENDING // U9：云端已是本档（确认回填竞态）
                else -> ArbitrationVerdict.CONFLICT // U4/U8：双端各有新进度
            }
        }
    }
}

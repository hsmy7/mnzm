package com.xianxia.sect.data.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 脏标志仲裁纯函数单测——SR-0 侦察报告 §4.3 A 清单（U1-U11）逐例锚定。
 *
 * IN2：所有用例输入只有保存序号三元组 (L, C, W)，**零时钟输入**；
 * 用例编号与 SR-0 报告一一对应，双设备剧本 S1-S10 真机部分归 SR-3。
 */
class SaveArbiterTest {

    private fun arbitrate(l: Long, c: Long, w: Long?) =
        SaveArbiter.arbitrate(lastLocalSaveId = l, lastConfirmedCloudId = c, cloudSaveId = w)

    // ── A. verdict 纯函数（SR-0 §4.3 A 表逐行）──

    @Test
    fun `U1 - 基线净态 L3 C3 W3 = IN_SYNC`() {
        assertEquals(ArbitrationVerdict.IN_SYNC, arbitrate(3, 3, 3))
    }

    @Test
    fun `U2 - 本地净云端新 L3 C3 W4 = LOCAL_BEHIND（云新下载）`() {
        assertEquals(ArbitrationVerdict.LOCAL_BEHIND, arbitrate(3, 3, 4))
    }

    @Test
    fun `U3 - 仅本地新 L4 C3 W3 = UPLOAD_PENDING（仅本地新不等于冲突）`() {
        assertEquals(ArbitrationVerdict.UPLOAD_PENDING, arbitrate(4, 3, 3))
    }

    @Test
    fun `U4 - 双端各有新 L4 C3 W5 = CONFLICT（弹窗二选一）`() {
        assertEquals(ArbitrationVerdict.CONFLICT, arbitrate(4, 3, 5))
    }

    @Test
    fun `U5 - 云落后视为已确认旧态 L4 C4 W3 = IN_SYNC（确认语义）`() {
        assertEquals(ArbitrationVerdict.IN_SYNC, arbitrate(4, 4, 3))
    }

    @Test
    fun `U6 - 双无 L0 C0 W0 = IN_SYNC（新游戏）`() {
        assertEquals(ArbitrationVerdict.IN_SYNC, arbitrate(0, 0, 0))
    }

    @Test
    fun `U7 - 首次云接入 L0 C0 W3 = LOCAL_BEHIND`() {
        assertEquals(ArbitrationVerdict.LOCAL_BEHIND, arbitrate(0, 0, 3))
    }

    @Test
    fun `U8 - 跨多版仍冲突 L5 C3 W4 = CONFLICT（不因差距大而静默）`() {
        // W=4：云端有另一端的新进度（C<W<L 的他端推进形态）
        assertEquals(ArbitrationVerdict.CONFLICT, arbitrate(5, 3, 4))
    }

    @Test
    fun `U9 - 确认回填竞态 W等于L L4 C3 W4 = UPLOAD_PENDING（幂等重传收敛，不得误报冲突）`() {
        assertEquals(ArbitrationVerdict.UPLOAD_PENDING, arbitrate(4, 3, 4))
    }

    @Test
    fun `U10 - 非法态 L小于C 判 IN_SYNC（自愈归一由 UploadLedger_normalizeIfNeeded 负责）`() {
        // 确认写入先于本地序号写入的中断窗：防御性 IN_SYNC，不升级冲突/不误传
        assertEquals(ArbitrationVerdict.IN_SYNC, arbitrate(2, 5, 2))
        assertEquals(ArbitrationVerdict.IN_SYNC, arbitrate(2, 5, 6))
    }

    @Test
    fun `U11 - W未知null 保守退化按W等于C重算（失败封闭：不得升级冲突也不得误判云无档）`() {
        // 退化 (4,3,W=3) = UPLOAD_PENDING —— 不得因查询失败把待传判成"云无档"静默上传他端档
        assertEquals(ArbitrationVerdict.UPLOAD_PENDING, arbitrate(4, 3, null))
        // 退化 (3,3,W=3) = IN_SYNC
        assertEquals(ArbitrationVerdict.IN_SYNC, arbitrate(3, 3, null))
        // 退化 (5,3,W=3) = UPLOAD_PENDING —— 关键：null 不得凭空制造 CONFLICT
        assertEquals(ArbitrationVerdict.UPLOAD_PENDING, arbitrate(5, 3, null))
        // 退化 (3,3,W=3) 云端未知时也不得判 LOCAL_BEHIND（无从得知云新）
        assertEquals(ArbitrationVerdict.IN_SYNC, arbitrate(3, 3, null))
    }

    // ── B. IN2 无时钟结构性守卫 ──

    @Test
    fun `IN2 - 相同三元组判定与调用时序无关（纯函数幂等）`() {
        val first = arbitrate(4, 3, 5)
        val second = arbitrate(4, 3, 5)
        assertEquals(first, second)
        assertEquals(ArbitrationVerdict.CONFLICT, first)
    }
}

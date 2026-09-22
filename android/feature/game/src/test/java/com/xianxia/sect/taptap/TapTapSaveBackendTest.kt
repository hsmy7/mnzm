package com.xianxia.sect.taptap

import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.data.cloud.SaveBackendError
import com.xianxia.sect.data.model.SaveData
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TapTapSaveBackend 纯映射单测（槽位命名 / extra saveId / 错误码分类）——
 * 不触 SDK（反射桥在 JVM 沙箱无 SDK 可探测，传输面归 SR-3 真机硬门）。
 * Robolectric：extra JSON 走 org.json（本地 JVM 单测无 Robolectric 时是 android.jar 桩）。
 */
@RunWith(RobolectricTestRunner::class)
class TapTapSaveBackendTest {

    /** extra 构造用的最小存档（year/month/sect/stones 四字段驱动摘要与 JSON） */
    private fun sampleSaveData() = SaveData(
        gameData = GameData(gameYear = 12, gameMonth = 7, sectName = "青云宗", spiritStones = 1234L),
        disciples = emptyList(),
        pills = emptyList(),
        materials = emptyList(),
        herbs = emptyList(),
        seeds = emptyList()
    )

    // ── 槽位 ↔ 云端命名（SR-0 §3.4：slot_N + 存量单档）──

    @Test
    fun `archiveNameFor - slot0 映射存量单档 1至6 映射 slot_N`() {
        assertEquals("mnzm_cloud_save", TapTapSaveBackend.archiveNameFor(0))
        assertEquals("slot_1", TapTapSaveBackend.archiveNameFor(1))
        assertEquals("slot_6", TapTapSaveBackend.archiveNameFor(6))
    }

    @Test
    fun `slotFromArchiveName - 槽位命名可逆且非法命名返回 null`() {
        assertEquals(0, TapTapSaveBackend.slotFromArchiveName("mnzm_cloud_save"))
        assertEquals(3, TapTapSaveBackend.slotFromArchiveName("slot_3"))
        assertNull(TapTapSaveBackend.slotFromArchiveName("slot_0"))
        assertNull(TapTapSaveBackend.slotFromArchiveName("slot_7"))
        assertNull(TapTapSaveBackend.slotFromArchiveName("slot_abc"))
        // 其他设备/历史遗留命名：保留不动（oneTimeCleanup 同纪律），不参与槽位映射
        assertNull(TapTapSaveBackend.slotFromArchiveName("some_other_device_save"))
    }

    // ── extra JSON saveId（云端 W 回带；存量档无字段 = null，U11 保守退化）──

    @Test
    fun `parseSaveId - 有字段取值 无字段与非法 JSON 均为 null`() {
        assertEquals(42L, TapTapSaveBackend.parseSaveId("""{"year":3,"saveId":42}"""))
        assertNull(TapTapSaveBackend.parseSaveId("""{"year":3}""")) // 存量档
        assertNull(TapTapSaveBackend.parseSaveId("""{"saveId":0}""")) // 0 视为无
        assertNull(TapTapSaveBackend.parseSaveId("not json"))
        assertNull(TapTapSaveBackend.parseSaveId("{}"))
    }

    @Test
    fun `buildSummaryAndExtra - 现役协议字段保持且新增 saveId`() {
        val saveData = SaveData(
            gameData = GameData(gameYear = 12, gameMonth = 7, sectName = "青云宗", spiritStones = 1234L),
            disciples = emptyList(),
            pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(),
            seeds = emptyList()
        )
        val (summary, extra) = TapTapSaveBackend.buildSummaryAndExtra(saveData, saveId = 9L)
        assertEquals("第12年7月 青云宗", summary)
        org.json.JSONObject(extra).let { json ->
            assertEquals(12, json.getInt("year"))
            assertEquals(7, json.getInt("month"))
            assertEquals("青云宗", json.getString("sect"))
            assertEquals(0, json.getInt("disciples"))
            assertEquals(1234L, json.getLong("stones"))
            assertEquals(9L, json.getLong("saveId"))
            assertEquals(true, json.has("version"))
        }
    }

    // ── SR-5 C7：载荷签名写读 extra（向后兼容方向 = 无签名不写键，存量档不报错）──

    @Test
    fun `buildSummaryAndExtra - 有签名才写 sig 与 sigVer 两键`() {
        val saveData = sampleSaveData()

        val withSig = org.json.JSONObject(
            TapTapSaveBackend.buildSummaryAndExtra(saveData, saveId = 9L, signature = "ab12").second
        )
        assertEquals("ab12", withSig.getString("sig"))
        assertEquals("hmac-sha256-v1", withSig.getString("sigVer"))
        assertEquals("saveId 判据零变化", 9L, withSig.getLong("saveId"))

        val withoutSig = org.json.JSONObject(
            TapTapSaveBackend.buildSummaryAndExtra(saveData, saveId = 9L, signature = null).second
        )
        org.junit.Assert.assertFalse("密钥不可得时不得写空签名键", withoutSig.has("sig"))
        org.junit.Assert.assertFalse(withoutSig.has("sigVer"))
    }

    @Test
    fun `parseSignature - 有值回带 无键与非法 JSON 均 null`() {
        assertEquals("ab12", TapTapSaveBackend.parseSignature("""{"saveId":9,"sig":"ab12"}"""))
        assertNull(TapTapSaveBackend.parseSignature("""{"saveId":9}""")) // 存量档无签名
        assertNull(TapTapSaveBackend.parseSignature("""{"sig":""}""")) // 空串视为无
        assertNull(TapTapSaveBackend.parseSignature("not json"))
        assertNull(TapTapSaveBackend.parseSignature(null))
    }

    // ── extra JSON 摘要解析（SR-3 槽位列表选档 UI；缺字段降级 null 非错误）──

    @Test
    fun `parseSummary - 现役协议字段直映射`() {
        val summary = TapTapSaveBackend.parseSummary(
            """{"year":12,"month":7,"sect":"青云宗","disciples":35,"stones":999,"version":"1.2.3","saveId":4}"""
        )
        assertEquals(12, summary?.gameYear)
        assertEquals(7, summary?.gameMonth)
        assertEquals("青云宗", summary?.sectName)
        assertEquals(35, summary?.discipleCount)
        assertEquals(999L, summary?.spiritStones)
        assertEquals("1.2.3", summary?.appVersion)
    }

    @Test
    fun `parseSummary - 缺失空与全空摘要均降级 null`() {
        assertNull(TapTapSaveBackend.parseSummary(null))
        assertNull(TapTapSaveBackend.parseSummary(""))
        assertNull(TapTapSaveBackend.parseSummary("{}")) // 全空 = 无真实摘要（最终一致性延迟常态）
        assertNull(TapTapSaveBackend.parseSummary("not json"))
    }

    // ── 错误码分类（SR-0 §2.4 归组 → 队列退避/熔断决策面）──

    @Test
    fun `classify - TapTap 错误码按 SR-0 归组映射`() {
        fun err(code: String) = RuntimeException("TapTap cloud save error [$code]: x")
        assertEquals(SaveBackendError.RATE_LIMITED, TapTapSaveBackend.classify(err("400001")))
        assertEquals(SaveBackendError.TOKEN_EXPIRED, TapTapSaveBackend.classify(err("400006")))
        assertEquals(SaveBackendError.CONCURRENT, TapTapSaveBackend.classify(err("400007")))
        assertEquals(SaveBackendError.ARCHIVE_MISSING, TapTapSaveBackend.classify(err("400002")))
        assertEquals(SaveBackendError.SIZE_LIMIT, TapTapSaveBackend.classify(err("400000")))
        assertEquals(SaveBackendError.SIZE_LIMIT, TapTapSaveBackend.classify(err("400009")))
        assertEquals(SaveBackendError.QUOTA_EXCEEDED, TapTapSaveBackend.classify(err("400003")))
        assertEquals(SaveBackendError.QUOTA_EXCEEDED, TapTapSaveBackend.classify(err("400005")))
        assertEquals(
            SaveBackendError.AUTH_REQUIRED,
            TapTapSaveBackend.classify(RuntimeException("x [300001] not login"))
        )
        assertEquals(
            SaveBackendError.SDK_UNAVAILABLE,
            TapTapSaveBackend.classify(RuntimeException("x [400100] not ready"))
        )
    }

    @Test
    fun `classify - 超时异常与未知消息`() {
        assertEquals(
            SaveBackendError.TIMEOUT,
            TapTapSaveBackend.classify(CloudSaveOperationTimeoutException("云存档操作超时"))
        )
        assertEquals(SaveBackendError.UNKNOWN, TapTapSaveBackend.classify(RuntimeException()))
        assertEquals(SaveBackendError.NETWORK, TapTapSaveBackend.classify(RuntimeException("connection reset")))
    }
}

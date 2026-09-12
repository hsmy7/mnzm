package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.engine.RedeemCodeManager
import com.xianxia.sect.core.model.MailAttachment
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import com.xianxia.sect.core.util.SpiritRootGenerator
import com.xianxia.sect.core.util.asKotlinRandom
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * DiffRedeemCodeTest — 兑换码与邮件附件跨语言差分对拍。
 *
 * 守护目标：C++ gamecore::system::redeem_code（格式校验 / 灵根生成 /
 * java.util.Random 洗牌）与 Kotlin RedeemCodeManager / SpiritRootGenerator
 * 语义逐位一致（同种子 MAIL 分区 RNG）。
 *
 * Kotlin 基准：真实 RedeemCodeManager（object）/ SpiritRootGenerator（object）。
 *
 * 已知边界（private 方法 + 注册表依赖）：resolveSpiritRoot 配置分支 /
 * generateVariance / rollBySpiritRootCount / resolveAgeAndLifespan 由 C++ GTest
 * 独立覆盖；名字/体质/词条/天赋生成（NameService/PhysiqueDatabase 等注册表）
 * 与服务器验证保留 Kotlin。
 *
 * 前置：桌面 JNI 已构建并注入 `-Dgamecore.jni.path`；未注入时跳过。
 */
class DiffRedeemCodeTest {

    private val json = Json { encodeDefaults = true }

    private fun freshCore(seed: Long) {
        DiffRngBridge.nativeDestroy()
        DiffRngBridge.nativeCoreInit()
        DiffRngBridge.nativeCoreRngInitSeed(seed)
    }

    private fun kotlinRng(seed: Long): GameRngManager =
        GameRngManager().also { it.initSystemSeed(seed) }

    private fun cppExec(actionId: Int, params: JsonObject): JsonObject {
        val result = DiffRngBridge.nativeCoreExecute(
            actionId, json.encodeToString(JsonObject.serializer(), params).encodeToByteArray()
        ).decodeToString()
        return json.parseToJsonElement(result) as JsonObject
    }

    private fun assertSuccess(result: JsonObject) {
        assertEquals("C++ 响应: $result", "success", result["status"]!!.jsonPrimitive.content)
    }

    private fun str(el: kotlinx.serialization.json.JsonElement): String = el.jsonPrimitive.content
    private fun bool(el: kotlinx.serialization.json.JsonElement): Boolean = el.jsonPrimitive.content.toBoolean()

    @Test
    fun `validate input matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        freshCore(42)
        for (code in listOf("ABC123", "  ABC123  ", "AB", "", "   ", "ABC 123", "ABC_123",
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ", "修仙码", "\u3000TEST\u3000")) {
            val kotlinResult = RedeemCodeManager.validateInput(code)
            val kotlinValid = kotlinResult == null
            val kotlinMessage = kotlinResult?.message ?: ""
            val r = cppExec(ActionIds.REDEEM_VALIDATE_INPUT, buildJsonObject {
                put("code", code)
            })
            assertSuccess(r)
            val d = r["data"]!!.jsonObject
            assertEquals("code=[$code] valid", kotlinValid, bool(d.getValue("valid")))
            if (!kotlinValid) {
                assertEquals("code=[$code] message", kotlinMessage, str(d.getValue("message")))
            }
        }
    }

    @Test
    fun `spirit root generate matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        for (seed in listOf(42L, 7L, 99L)) {
            freshCore(seed)
            // Kotlin：SpiritRootGenerator.generate（MAIL 分区 asKotlinRandom）
            val kotlinRoot = SpiritRootGenerator.generate(
                kotlinRng(seed).getRng(RngPartition.MAIL).asKotlinRandom())
            val r = cppExec(ActionIds.REDEEM_ROLL_SPIRIT_ROOT, buildJsonObject { })
            assertSuccess(r)
            val cppRoot = str(r["data"]!!.jsonObject.getValue("spiritRoot"))
            assertEquals("seed=$seed 灵根串", kotlinRoot, cppRoot)
        }
    }

    @Test
    fun `mail attachment encode matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        freshCore(42)
        val attachments = listOf(
            MailAttachment(type = "storageBag", name = "地品储物袋", quantity = 10, rarity = 5),
            MailAttachment(type = "equipment", name = "木剑", quantity = 1, rarity = 2,
                itemId = "eq-1", extra = mapOf("source" to "redeem")),
            MailAttachment(type = "spiritStones", name = "灵石", quantity = 100),
        )
        val kotlinEncoded = json.encodeToString(ListSerializer(MailAttachment.serializer()), attachments)
        val r = cppExec(ActionIds.MAIL_ATTACHMENT_ENCODE, buildJsonObject {
            put("attachments", buildJsonArray {
                for (a in attachments) {
                    add(buildJsonObject {
                        put("type", a.type); put("name", a.name)
                        put("quantity", a.quantity); put("rarity", a.rarity)
                        if (a.itemId != null) put("itemId", a.itemId)
                        put("extra", buildJsonObject {
                            for ((k, v) in a.extra) put(k, v)
                        })
                    })
                }
            })
        })
        assertSuccess(r)
        val cppEncoded = str(r["data"]!!.jsonObject.getValue("encoded"))
        assertEquals("附件 JSON 字符串", kotlinEncoded, cppEncoded)
        assertTrue("非空", cppEncoded.startsWith("["))
    }
}

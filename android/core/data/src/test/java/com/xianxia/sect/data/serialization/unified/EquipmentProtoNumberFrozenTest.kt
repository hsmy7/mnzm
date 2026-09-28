package com.xianxia.sect.data.serialization.unified

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.data.model.SaveData
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

/**
 * E1 存档编号冻结守卫（装备系统重构 B0；冻结表唯一真源 = 方案 §四 WP0 +
 * `docs/design/equipment-batches/IMPLEMENTATION-BATCHES.md` §1）。
 *
 * 冻结语义：
 * - **装备段**：`weaponId(17)` 复用为六部位的武器部位列（唯一复用号）；仓储列 28/30/31 不动。
 * - **六部位新增段**：`headId(112)/bodyId(113)/handsId(114)/feetId(115)/legsId(116)`
 *   （按显示序 头/身/手/脚/武/腿）+ `innateDamageType(117)`，只声明占号、不写入/不读取。
 * - **存量退役号**（reserved）不得被任何 `@ProtoNumber` 重新占用。
 * - **退役在册号**（18/19/20、24..27、47、98/99）在退役批落地前不得改指向（防 wire 漂移）。
 * - **SaveData**：`equipmentStacks(53)` 保持 53 锚点，其他字段不得抢占。
 * - **默认弟子序列化字节流不得出现 112..117**（B0 契约 = 声明占号，接线分属 B1(117)/B3(112..116)）。
 *
 * 编号新增/变更的唯一合法路径：先改方案 §四 WP0 + 批次文档 §1 E1 冻结表并登记，
 * 再同步本守卫——禁止临时新增编号（E1）。
 */
class EquipmentProtoNumberFrozenTest {

    /** [DiscipleSurrogate][com.xianxia.sect.core.model.DiscipleSerializer] 是私有嵌套类，经运行时反射取属性→编号映射 */
    private val surrogate: KClass<*> =
        Class.forName("com.xianxia.sect.core.model.DiscipleSerializer\$DiscipleSurrogate").kotlin

    /** 属性名 → @ProtoNumber 编号（缺注解直接报错：云存档面禁隐式编号） */
    private fun protoNumbers(clazz: KClass<*>): Map<String, Int> =
        clazz.memberProperties.associateBy(
            keySelector = { it.name },
            valueTransform = { prop ->
                prop.isAccessible = true
                prop.findAnnotation<ProtoNumber>()?.number
                    ?: error("${clazz.simpleName}.${prop.name} 缺 @ProtoNumber（云存档面禁隐式编号）")
            }
        )

    @Test
    fun `equipment section and new section match the frozen table`() {
        val numbers = protoNumbers(surrogate)
        val frozen = mapOf(
            // 装备段：17 = 六部位唯一复用号（武器部位列）；28/30/31 仓储列不动
            "weaponId" to 17,
            "spiritStones" to 28,
            "storageBagItems" to 30,
            "storageBagSpiritStones" to 31,
            // 六部位新增段（显示序 头/身/手/脚/武/腿）+ 固有伤害属性
            "headId" to 112,
            "bodyId" to 113,
            "handsId" to 114,
            "feetId" to 115,
            "legsId" to 116,
            "innateDamageType" to 117
        )
        val missing = frozen.keys.filter { it !in numbers }
        val mismatched = frozen.mapNotNull { (name, number) ->
            if (numbers[name] != number) "$name 期望 $number 实际 ${numbers[name]}" else null
        }
        assertTrue(
            "冻结表声明缺失（不得删除占号声明，缺=$missing）。\n" +
                "处置：编号冻结于方案 §四 WP0 / 批次文档 §1 E1；确需调整先改冻结表并登记，再同步本守卫。",
            missing.isEmpty()
        )
        assertTrue(
            "属性→编号与冻结表不一致：\n$mismatched\n" +
                "处置：编号是存档 wire 契约，禁改号；确需变更先在方案 §四 WP0 + 批次文档 §1 E1 登记后再同步本表。",
            mismatched.isEmpty()
        )
    }

    @Test
    fun `existing reserved proto numbers stay unclaimed`() {
        val claimed = protoNumbers(surrogate).values.toSet()
        val reserved = listOf(7, 8, 11, 12, 13, 14, 15, 16, 22, 29, 50, 76, 88, 93, 95, 102, 104, 105, 110)
        val reused = reserved.filter { it in claimed }
        assertTrue(
            "存量退役号 $reused 被 @ProtoNumber 重新占用（reserved 禁复用——旧档字节会按新语义解码）。\n" +
                "处置：登记见 DiscipleSerializer.kt 各 reserved 注释；新增字段一律取冻结表未占用号。",
            reused.isEmpty()
        )
    }

    @Test
    fun `planned retirement numbers stay pointed at legacy properties`() {
        val numbers = protoNumbers(surrogate)
        val legacy = mapOf(
            "armorId" to 18,
            "bootsId" to 19,
            "accessoryId" to 20,
            "weaponNurture" to 24,
            "armorNurture" to 25,
            "bootsNurture" to 26,
            "accessoryNurture" to 27,
            "pillNurtureSpeedBonus" to 47,
            "equipmentNurturingCompletionMonth" to 98,
            "equipmentNurturingCompletionPhase" to 99
        )
        val drifted = legacy.mapNotNull { (name, number) ->
            if (numbers[name] != number) "$name 期望 $number 实际 ${numbers[name]}" else null
        }
        assertTrue(
            "E1 退役在册号被改指向：\n$drifted\n" +
                "处置：退役在册号在退役批落地前禁改指向（47→B2，其余→B3）、退役后禁复用。",
            drifted.isEmpty()
        )
    }

    @Test
    fun `SaveData equipmentStacks keeps tag 53 for planned retirement`() {
        val numbers = protoNumbers(SaveData::class)
        assertEquals(
            "equipmentStacks 应保持 @ProtoNumber(53)（E1 冻结表：B3 删堆叠批退役）",
            53,
            numbers["equipmentStacks"]
        )
        val otherClaimants = numbers.filterValues { it == 53 }.keys - "equipmentStacks"
        assertTrue(
            "SaveData 字段 $otherClaimants 不得占用 53（equipmentStacks 退役预留号，退役后禁复用）",
            otherClaimants.isEmpty()
        )
    }

    @Test
    fun `new frozen section stays unwired in saved bytes`() {
        val bytes = ProtoBuf.encodeToByteArray(Disciple.serializer(), Disciple(id = "b0", name = "编号冻结守卫"))
        val newSection = setOf(112, 113, 114, 115, 116, 117)
        val leaked = topLevelFieldNumbers(bytes).intersect(newSection)
        assertTrue(
            "新增段字段 $leaked 出现在存档字节流（B0 契约 = 只声明占号，不写入/不读取）。\n" +
                "排查：buildSurrogate/with*Values 是否误接线，或新字段被加 @EncodeDefault(ALWAYS)；接线属 B1(117)/B3(112..116)。",
            leaked.isEmpty()
        )
    }

    /** 解析 Protobuf 顶层字段号（只解析第一层，不受消息体内容干扰） */
    private fun topLevelFieldNumbers(bytes: ByteArray): Set<Int> {
        var index = 0
        fun readVarint(): Long {
            var shift = 0
            var value = 0L
            while (index < bytes.size) {
                val byte = bytes[index].toInt()
                value = value or ((byte and 0x7F).toLong() shl shift)
                index++
                if (byte and 0x80 == 0) return value
                shift += 7
            }
            error("varint 越过字节流末尾（index=$index size=${bytes.size}）")
        }
        val numbers = mutableSetOf<Int>()
        while (index < bytes.size) {
            val tag = readVarint()
            numbers += ((tag ushr 3) and Int.MAX_VALUE.toLong()).toInt()
            when ((tag and 0x7L).toInt()) {
                0 -> readVarint()
                1 -> index += 8
                // 复合赋值会先取旧 index 再求值 RHS（readVarint 内部推进 index），
                // 必须先落局部变量再跳，否则写入「旧+新」错值导致解析失步
                2 -> {
                    val length = readVarint().toInt()
                    index += length
                }
                5 -> index += 4
                else -> error("未知 wire type：tag=$tag index=$index size=${bytes.size}")
            }
        }
        return numbers
    }
}

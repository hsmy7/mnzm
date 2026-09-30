package com.xianxia.sect.data.serialization.unified

import com.xianxia.sect.data.model.SaveData
import kotlinx.serialization.protobuf.ProtoNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

/**
 * E1 存档编号冻结守卫（装备重构 B0 定稿 / B1 增量接线）。
 *
 * 冻结表（方案 §四 WP0 / 批次文档 §1 E1）：
 * - **装备段**：`weaponId(17)` = 六部位唯一复用号（武器部位列）；`18/19/20/24..27/47/98/99`
 *   退役在册（退役批落地前禁改指向）。
 * - **六部位新增段**：`headId(112)/bodyId(113)/handsId(114)/feetId(115)/legsId(116)`
 *   （按显示序 头/身/手/脚/武/腿），**B3 接线**，只声明占号、不写入/不读取。
 * - **B1 属性单列段**（本批接线）：`baseAttack(118)/baseDefense(119)/attackVariance(120)/`
 *   `defenseVariance(121)/pillAttackBonus(122)/pillDefenseBonus(123)` + `innateDamageType(117)`
 *   （B0 占号、B1 接线，String = DamageType.name）。
 * - **旧双列归一化源**（B1 起只读不写）：`69..72`（物法攻防基值）、`62..65`（物法方差，均值
 *   归一）、`36..39`（丹药四加成，取和归一）——旧档读取兼容面，禁复用号。
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
            // 六部位新增段（显示序 头/身/手/脚/武/腿；B3 接线）
            "headId" to 112,
            "bodyId" to 113,
            "handsId" to 114,
            "feetId" to 115,
            "legsId" to 116,
            // B1 属性单列段（本批接线）
            "innateDamageType" to 117,
            "baseAttack" to 118,
            "baseDefense" to 119,
            "attackVariance" to 120,
            "defenseVariance" to 121,
            "pillAttackBonus" to 122,
            "pillDefenseBonus" to 123
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
    fun `b1 legacy columns stay pointed at normalization sources`() {
        val numbers = protoNumbers(surrogate)
        val legacy = mapOf(
            // 旧物法攻防基值（69..72）——只读归一化源，写入面已迁 baseAttack(118)/baseDefense(119)
            "basePhysicalAttack" to 69,
            "baseMagicAttack" to 70,
            "basePhysicalDefense" to 71,
            "baseMagicDefense" to 72,
            // 旧物法方差（62..65）——均值归一化源，写入面已迁 attackVariance(120)/defenseVariance(121)
            "physicalAttackVariance" to 62,
            "magicAttackVariance" to 63,
            "physicalDefenseVariance" to 64,
            "magicDefenseVariance" to 65,
            // 旧丹药四加成（36..39）——取和归一化源，写入面已迁 pillAttackBonus(122)/pillDefenseBonus(123)
            "pillPhysicalAttackBonus" to 36,
            "pillMagicAttackBonus" to 37,
            "pillPhysicalDefenseBonus" to 38,
            "pillMagicDefenseBonus" to 39
        )
        val drifted = legacy.mapNotNull { (name, number) ->
            if (numbers[name] != number) "$name 期望 $number 实际 ${numbers[name]}" else null
        }
        assertTrue(
            "B1 旧双列归一化源号被改指向：\n$drifted\n" +
                "处置：归一化源号是旧档读取兼容面，禁改号、禁复用；全部旧档迁移完成后方可按退役流程清理。",
            drifted.isEmpty()
        )
    }

    @Test
    fun `existing reserved proto numbers stay unclaimed`() {
        val claimed = protoNumbers(surrogate).values.toSet()
        // 47 = pillNurtureSpeedBonus（EQ-B2 孕养丹退役批退役）；
        // 98/99 = equipmentNurturingCompletionMonth/Phase（B3 孕养 checkpoint 退役，属性已删）
        val reserved = listOf(
            7, 8, 11, 12, 13, 14, 15, 16, 22, 29, 47, 50, 76, 88, 93, 95, 102, 104, 105, 110,
            98, 99
        )
        val reused = reserved.filter { it in claimed }
        assertTrue(
            "存量退役号 $reused 被 @ProtoNumber 重新占用（reserved 禁复用——旧档字节会按新语义解码）。\n" +
                "处置：登记见 DiscipleSerializer.kt 各 reserved 注释；新增字段一律取冻结表未占用号。",
            reused.isEmpty()
        )
    }

    @Test
    fun `planned retirement numbers stay pointed at legacy properties`() {
        // B3 已落地：18/19/20/24..27（旧四槽 + 四 nurture）随 B3 退役但保留
        // @Deprecated 声明供旧档反序列化（HANDOVER-B3 §二 写面 B），仍在册指向；
        // 98/99（孕养 checkpoint 两列）已随 B3 **整属性删除** → 移入 reserved 禁复用面。
        val numbers = protoNumbers(surrogate)
        val legacy = mapOf(
            "armorId" to 18,
            "bootsId" to 19,
            "accessoryId" to 20,
            "weaponNurture" to 24,
            "armorNurture" to 25,
            "bootsNurture" to 26,
            "accessoryNurture" to 27
        )
        val drifted = legacy.mapNotNull { (name, number) ->
            if (numbers[name] != number) "$name 期望 $number 实际 ${numbers[name]}" else null
        }
        assertTrue(
            "E1 退役在册号被改指向：\n$drifted\n" +
                "处置：退役在册号在退役批落地前禁改指向（其余→B3）、退役后禁复用。",
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
    fun `b3 section is wired in write path`() {
        // B3 装备体系替换批（EQ-B3）接线后语义翻转：112..116 六部位列**必须**在
        // buildSurrogate（唯一写入口）写入路径被引用；退役段 18/19/20/24..27
        // （armorId/bootsId/accessoryId/四 nurture）**禁**再出现在写入路径；
        // 117（innateDamageType）维持 B1 接线断言。
        val serializerFile = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .map { File(it, "core/domain/src/main/java/com/xianxia/sect/core/model/" +
                "DiscipleSerializer.kt") }
            .firstOrNull { it.exists() }
        val source = serializerFile?.readText()
            ?: error("DiscipleSerializer.kt 未找到（cwd=" + System.getProperty("user.dir") + "）")
        val writeSegment = source.substringAfter("private fun buildSurrogate")
            .substringBefore("override fun deserialize")
        val b3Fields = listOf("headId", "bodyId", "handsId", "feetId", "legsId")
        val missing = b3Fields.filter { field ->
            !Regex("^[^\\n]*[=(,]\\s*$field\\b", RegexOption.MULTILINE).containsMatchIn(writeSegment)
        }
        assertTrue(
            "B3 六部位列 $missing 未在 buildSurrogate 写入路径接线（EQ-B3 必写）。\n" +
                "排查：withEquipmentUsageFields 是否漏搬运 112..116。",
            missing.isEmpty()
        )
        val retired = listOf("armorId", "bootsId", "accessoryId",
            "weaponNurture", "armorNurture", "bootsNurture", "accessoryNurture")
        val stillWritten = retired.filter { field ->
            Regex("value\\.$field\\b").containsMatchIn(writeSegment)
        }
        assertTrue(
            "退役装备字段 $stillWritten 仍在 buildSurrogate 写入路径（B3 已退役，禁写）。\n" +
                "排查：withEquipmentUsageFields 是否残留旧四槽/nurture 搬运。",
            stillWritten.isEmpty()
        )
        // 117（innateDamageType）必须已接线（B1 契约，写面反向断言）
        assertTrue(
            "innateDamageType 未在 buildSurrogate 写入路径引用（B1 接线缺失）",
            Regex("innateDamageType\\s*=").containsMatchIn(writeSegment)
        )
    }
}

package com.xianxia.sect.data.serialization.unified

import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.data.model.SaveData
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.EncodeDefault
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.KClass
import kotlin.reflect.KProperty
import kotlin.reflect.KType
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

/**
 * 守卫测试：确保所有需要云存档序列化的域类型字段都有 [ProtoNumber] 注解。
 *
 * 当新增 GameData/SaveData 或嵌套 [Serializable] 类的字段时，此测试会失败，
 * 并提示开发者添加 @ProtoNumber。
 *
 * ## 设计原则
 * - 运行时字段应直接标注 @kotlinx.serialization.Transient 排除，不应加入 EXCLUDED_FIELDS
 * - EXCLUDED_FIELDS 仅为无法标注 @Transient 的 computed property 提供逃生门
 * - 新增字段时优先加 @ProtoNumber，其次 @Transient，最后才考虑 EXCLUDED_FIELDS
 */
class ProtoNumberCoverageTest {

    /** 已处理过的类，防止递归循环 */
    private val visitedClasses = mutableSetOf<KClass<*>>()

    /**
     * 不可用 @Transient 排除的字段（如 computed property），
     * 仍需要手动加入此列表，并注明原因。
     *
     * 所有可添加 @Transient 的运行时字段应直接标注 @kotlinx.serialization.Transient，
     * 不应写入此表。此表仅为无法标注 @Transient 的字段提供逃生门。
     */
    private val EXCLUDED_FIELDS = mapOf(
        // GameData 计算属性（仅 getter，无 backing field → 无 @Transient）
        "displayTime" to "格式化显示时间 getter",
        "worldMap" to "世界地图聚合 getter",
        "buildings" to "建筑状态聚合 getter",
        "economy" to "经济状态聚合 getter",
        "organization" to "组织架构聚合 getter",
        "exploration" to "探索状态聚合 getter",
        "isPlayerProtected" to "玩家保护状态计算 getter",
        "playerProtectionRemainingYears" to "玩家保护剩余年数计算 getter",
    )

    @Test
    fun `all GameData fields have ProtoNumber annotation`() {
        val errors = mutableListOf<String>()
        visitedClasses.clear()
        checkProtoNumberCoverage("GameData", GameData::class, errors, "")
        assertTrue(
            buildErrorMessage("GameData", errors),
            errors.isEmpty()
        )
    }

    @Test
    fun `all SaveData fields have ProtoNumber annotation`() {
        val errors = mutableListOf<String>()
        visitedClasses.clear()
        checkProtoNumberCoverage("SaveData", SaveData::class, errors, "")
        assertTrue(
            buildErrorMessage("SaveData", errors),
            errors.isEmpty()
        )
    }

    @Test
    fun `GameData non-zero-default ProtoNumber fields have EncodeDefault annotation`() {
        val errors = mutableListOf<String>()
        visitedClasses.clear()
        val defaultInstance = GameData()
        checkEncodeDefaultCoverage("GameData", GameData::class, errors, defaultInstance, "")
        assertTrue(
            "以下 @ProtoNumber 字段默认值非零值，缺少 @EncodeDefault(ALWAYS)：\n" +
            errors.joinToString("\n"),
            errors.isEmpty()
        )
    }

    @Test
    fun `SaveData non-zero-default ProtoNumber fields have EncodeDefault annotation`() {
        val errors = mutableListOf<String>()
        visitedClasses.clear()
        val defaultSaveData = SaveData(
            gameData = GameData(),
            disciples = emptyList(), pills = emptyList(),
            materials = emptyList(), herbs = emptyList(),
            seeds = emptyList(), teams = emptyList()
        )
        checkEncodeDefaultCoverage("SaveData", SaveData::class, errors, defaultSaveData, "")
        assertTrue(
            "以下 @ProtoNumber 字段默认值非零值，缺少 @EncodeDefault(ALWAYS)：\n" +
            errors.joinToString("\n"),
            errors.isEmpty()
        )
    }

    // ── 递归检查 @ProtoNumber 覆盖 ─────────────────────────────────────

    /**
     * 递归检查 [clazz] 及其嵌套 [Serializable] 类的所有字段是否标注了 [ProtoNumber]。
     *
     * 仅沿 [ProtoNumber] 标注的属性递归（跳过计算属性等非 ProtoNumber 字段的嵌套类型）。
     */
    private fun checkProtoNumberCoverage(
        className: String,
        clazz: KClass<*>,
        errors: MutableList<String>,
        prefix: String
    ) {
        if (clazz in visitedClasses) return
        visitedClasses.add(clazz)
        if (clazz.qualifiedName?.startsWith("kotlin") == true) return

        for (prop in clazz.memberProperties) {
            val fullName = "$prefix${prop.name}"
            if (prop.annotations.any { it is Transient }) continue
            if (fullName in EXCLUDED_FIELDS || prop.name in EXCLUDED_FIELDS) continue
            val hasProtoNumber = prop.annotations.any { it is ProtoNumber }
            if (!hasProtoNumber) {
                errors.add("${className}.${fullName}: ${prop.returnType}")
            } else {
                // 仅沿 @ProtoNumber 属性递归，跳过计算属性/非序列化字段
                collectNestedSerializableClasses(prop.returnType).forEach { nested ->
                    checkProtoNumberCoverage(
                        className, nested, errors, "$fullName."
                    )
                }
            }
        }
    }

    /**
     * 从属性类型中提取所有 [Serializable] 嵌套类（含泛型参数中的）。
     */
    private fun collectNestedSerializableClasses(type: KType): Set<KClass<*>> {
        val result = mutableSetOf<KClass<*>>()
        val classifier = type.classifier as? KClass<*> ?: return result
        if (classifier.qualifiedName?.startsWith("kotlin") == true) return result
        if (classifier.java.isEnum) return result
        if (classifier.java.name.startsWith("java.lang")) return result

        // 检查类型本身（跳过已知非序列化类型）
        if (classifier in SKIP_TYPES) return result
        if (classifier.annotations.any { it is Serializable }) {
            result.add(classifier)
        }
        // 检查泛型参数
        for (arg in type.arguments) {
            val argClass = arg.type?.classifier as? KClass<*> ?: continue
            if (argClass.qualifiedName?.startsWith("kotlin") == true) continue
            if (argClass !in SKIP_TYPES && argClass.annotations.any { it is Serializable }) {
                result.add(argClass)
            }
        }
        return result
    }

    // ── 递归检查 @EncodeDefault 覆盖 ───────────────────────────────────

    /**
     * 递归检查 [clazz] 及其嵌套 [Serializable] 类的 [ProtoNumber] 字段，
     * 如果默认值非零值且缺少 [EncodeDefault] 则报错。
     *
     * 仅对直接 @ProtoNumber 属性递归（非集合），集合类型元素的默认值为空集合，
     * 无 @EncodeDefault 问题。
     */
    private fun checkEncodeDefaultCoverage(
        className: String,
        clazz: KClass<*>,
        errors: MutableList<String>,
        instance: Any,
        prefix: String
    ) {
        if (clazz in visitedClasses) return
        visitedClasses.add(clazz)
        if (clazz.qualifiedName?.startsWith("kotlin") == true) return

        for (prop in clazz.memberProperties) {
            val fullName = "$prefix${prop.name}"
            if (prop.annotations.any { it is Transient }) continue
            if (fullName in EXCLUDED_FIELDS || prop.name in EXCLUDED_FIELDS) continue
            if (prop.annotations.none { it is ProtoNumber }) continue
            if (prop.annotations.any { it is EncodeDefault }) continue

            prop.isAccessible = true
            val value = prop.getter.call(instance)
            if (isNonZeroDefault(value)) {
                errors.add("${className}.${fullName}: 默认值=$value 非零值，请添加 @EncodeDefault(EncodeDefault.Mode.ALWAYS)")
            }

            // 仅沿直接引用的 @Serializable 类型递归（非集合/Map），集合类型的默认值是空集合
            if (value != null && value !is Collection<*> && value !is Map<*, *>) {
                collectNestedSerializableClasses(prop.returnType).forEach { nested ->
                    checkEncodeDefaultCoverage(
                        className, nested, errors, value, "$fullName."
                    )
                }
            }
        }
    }

    // ── 辅助方法 ───────────────────────────────────────────────────────

    /** 类型/类的跳过列表 — 非序列化实体或枚举 */
    private val SKIP_TYPES = setOf(
        java.lang.String::class,
        java.util.UUID::class,
    )

    /** @return true if [value] is a non-zero default for its type */
    private fun isNonZeroDefault(value: Any?): Boolean {
        if (value == null) return false
        return when (value) {
            is Int -> value != 0
            is Long -> value != 0L
            is Float -> value != 0f
            is Double -> value != 0.0
            is Short -> value != 0.toShort()
            is Byte -> value != 0.toByte()
            is Boolean -> value
            is String -> value.isNotEmpty()
            is List<*> -> value.isNotEmpty()
            is Set<*> -> value.isNotEmpty()
            is Map<*, *> -> value.isNotEmpty()
            else -> false  // 无法检测复杂类型默认值，保守返回 false
        }
    }

    private fun buildErrorMessage(className: String, errors: List<String>): String {
        if (errors.isEmpty()) return ""
        val sb = StringBuilder()
        sb.appendLine("========================================")
        sb.appendLine("$className 中以下字段缺少 @ProtoNumber 注解：")
        sb.appendLine("========================================")
        sb.appendLine()
        errors.forEach { sb.appendLine("  - $it") }
        sb.appendLine()
        sb.appendLine("请为每个字段添加 @ProtoNumber(n)，其中 n 为全局唯一的编号。")
        sb.appendLine("如该字段不应参与云存档序列化，请标注 @kotlinx.serialization.Transient。")
        sb.appendLine("仅对无法标注 @Transient 的 computed property，才加入 EXCLUDED_FIELDS。")
        sb.appendLine("========================================")
        return sb.toString()
    }
}

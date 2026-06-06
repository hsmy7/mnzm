package com.xianxia.sect.core.state

/**
 * 标记 @Embedded 字段需要对外暴露委托属性。
 *
 * KSP 处理器 [DelegateFieldProcessor] 读取此注解，自动生成扩展属性：
 * ```kotlin
 * var Disciple.baseHp: Int
 *     get() = combat.baseHp
 *     set(value) { combat.baseHp = value }
 * ```
 *
 * 用法：加在 Disciple 的 @Embedded 字段上：
 * ```kotlin
 * @Embedded
 * @ExposeDelegates("baseHp", "baseMp", ...)
 * var combat: CombatAttributes = CombatAttributes()
 * ```
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
annotation class ExposeDelegates(vararg val properties: String)

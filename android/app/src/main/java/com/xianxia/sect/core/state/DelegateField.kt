package com.xianxia.sect.core.state

/**
 * 标注 Disciple 的委托属性，由 KSP 处理器自动生成 getter/setter。
 *
 * 替代手工编写的 67 个委托属性模式：
 * ```
 * var baseHp: Int get() = combat.baseHp; set(value) { combat.baseHp = value }
 * ```
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class DelegateField(
    val source: String,
    val property: String = ""
)

package com.xianxia.sect.core.engine.annotation

/**
 * 标记被 UI/结算调用的 Service 类。
 *
 * 边界规则：
 * - Service 持有临时业务状态
 * - Service 不得在 tick 中被直接调用
 * - Service 由 ViewModel/Facade 驱动
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class GameService(val name: String)

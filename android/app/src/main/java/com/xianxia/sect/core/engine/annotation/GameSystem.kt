package com.xianxia.sect.core.engine.annotation

/**
 * 标记每 tick 自动执行的 System 类。
 *
 * 边界规则：
 * - System 之间不得直接调用（通过 EventBus 通知）
 * - System 不持有业务状态
 * - System 由 GameEngineCore.tickInternal() 驱动
 *
 * 注意：命名为 AutoTickSystem 而非 GameSystem，
 * 避免与 core.engine.system.GameSystem 接口同名冲突。
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class AutoTickSystem(val name: String)

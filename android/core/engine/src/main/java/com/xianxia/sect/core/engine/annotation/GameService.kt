package com.xianxia.sect.core.engine.annotation

/**
 * 游戏领域服务标注（对标 Godot ClassDB 注册语义——降级版）。
 *
 * 2026-08-13 批次 4：新增 [category] 元数据（可选，按包路径归属——
 * service / engine.service / engine.domain / domain / engine），
 * 供 [com.xianxia.sect.core.registry.GameSystemRegistry] 与监视器
 * 面板枚举消费；[name] 保持既有显示名语义不变。
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class GameService(
    val name: String,
    val category: String = ""
)

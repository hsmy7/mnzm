package com.xianxia.sect.core.registry

import java.util.concurrent.ConcurrentHashMap

/**
 * 注册元数据（对标 Godot ClassDB 注册条目——降级版：只做静态注册中心，
 * 不做 KSP/反射序列化——存档已用 ProtoBuf，反射序列化无需求）。
 *
 * @param name 系统名称（= @GameService.name）
 * @param category 类别（= 包路径归属：service / engine.service / engine.domain / domain / engine）
 * @param className 实现类简单名（守卫测试以源码扫描为锚比对）
 */
data class GameSystemInfo(
    val name: String,
    val category: String,
    val className: String
)

/**
 * 游戏系统注册中心（2026-08-13 批次 4，对标 Godot ClassDB）。
 *
 * 静态注册（[GameSystemRegistryDefaults.registerAll] 一次性登记全部
 * @GameService 系统）；消费方：开发者监视器面板枚举系统清单、
 * [GameSystemRegistryCoverageTest] 守卫（新增 @GameService 未注册即失败）。
 * 线程安全：注册期单线程 + ConcurrentHashMap 读侧并发安全。
 */
object GameSystemRegistry {

    private val systems = ConcurrentHashMap<String, GameSystemInfo>()

    /**
     * 注册一个系统（幂等由 registerAll 保证；重复注册同名抛错防覆盖）。
     *
     * @param name 系统名称（= @GameService.name）
     * @param category 类别
     * @param className 实现类简单名
     */
    fun register(name: String, category: String, className: String) {
        // 空名校验（对抗性审查 2026-08-13 边界#11）：空名可被 find("") 意外命中
        check(name.isNotBlank() && className.isNotBlank()) {
            "GameSystem 注册名称/类名不得为空: name='$name' className='$className'"
        }
        val prev = systems.putIfAbsent(name, GameSystemInfo(name, category, className))
        check(prev == null) { "GameSystem 重复注册: $name" }
    }

    /** 全部已注册系统（按名称排序） */
    fun all(): List<GameSystemInfo> = systems.values.sortedBy { it.name }

    /** 按名称查询 */
    fun find(name: String): GameSystemInfo? = systems[name]

    /** 按类别过滤（名称排序） */
    fun byCategory(category: String): List<GameSystemInfo> =
        all().filter { it.category == category }

    /** 已注册数量 */
    fun size(): Int = systems.size

    /** 清空（测试专用） */
    fun clear() {
        systems.clear()
    }
}

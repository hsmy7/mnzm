package com.xianxia.sect.core.gameview

import com.xianxia.sect.core.model.GameData
import java.lang.reflect.Modifier
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.elementNames

/**
 * GameDataTransientFace —— gameData 的 @Transient 运行态字段面（b02 发现 11 根治）。
 *
 * ## 不变量（结构性保证）
 * **镜像/快照应用永不触碰 @Transient 面**：这些字段不进任何 JSON（kotlinx
 * 序列化排除 ⇒ 不在 C++ 协议面、不在存档面、不在 GameView 信封面），是 Kotlin
 * 侧会话运行态。任何"从快照/变更集重建 gameData"的路径（增量字段级应用、
 * 旧全量 JSON 往返、全量快照替换）在落值前必须以事务前值经 [carryOver] 整体
 * 承载该面。
 *
 * ## 为什么用序列化面差集而不是注解反射（实测教训）
 * 直觉做法是反射扫 `@Transient` 注解，但**在本工程实测失效**：GameData 字段是
 * 主构造器 `var` 参数，`kotlinx.serialization.Transient` 的 `@Target` 含
 * `PROPERTY`/`VALUE_PARAMETER` 但**不含 `FIELD`**，Kotlin 编译器不把该注解写到
 * JVM 字段上——实测 `declaredFields` 150 个中带该注解者为 **0**，构造器参数注解
 * 亦为空且 `isParamNamePresent=false`（未开 `-parameters`，参数名不可得）。
 * 注解反射路线会静默退化为"空面"：carryOver 变 no-op、缺陷回归、且守卫与实现
 * 同源同错而看不见。
 *
 * ⇒ 以 **kotlinx 序列化 descriptor 为权威**：`@Transient` 字段必不出现在
 * `serializer().descriptor.elementNames` 中。取
 * `实例字段集 − descriptor 元素名集` 的差集即该面（本工程实测精确得 9 个字段、
 * 双向无残余；**B19 删两死列 `battleTeam` / `aiBattleTeams` 后为 7 个**——差集法
 * 自动收缩，无需改本实现）。**新增 @Transient 字段自动纳管**（进不了 descriptor 即自动
 * 落入差集），把一次性修复变成结构性护栏；且不引入 kotlin-reflect 生产依赖。
 *
 * ## 线程契约
 * 与 StateSyncService 镜像应用同线程（引擎线程事务内）；枚举结果 PUBLICATION
 * 惰性初始化，进程内只算一次。
 */
internal object GameDataTransientFace {

    /**
     * @Transient 字段集：`GameData` 实例字段集 − 序列化 descriptor 元素名集。
     *
     * 过滤规则：仅取非 static 实例字段，剔除合成字段（`$` 前缀）、伴生对象、
     * 以及 `serializer` 相关合成成员；余下与 descriptor 求差。
     */
    @OptIn(ExperimentalSerializationApi::class)
    private val transientFields: List<java.lang.reflect.Field> = run {
        val serializedNames: Set<String> =
            GameData.serializer().descriptor.elementNames.toSet()
        GameData::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .filterNot { it.isSynthetic }
            .filterNot { it.name.startsWith("$") }
            .filterNot { it.name in setOf("Companion", "serializer") }
            .filterNot { it.name in serializedNames }
            .onEach { it.isAccessible = true }
    }

    /** @Transient 字段名集（守卫测试与登记用）。 */
    val fieldNames: Set<String> = transientFields.map { it.name }.toSet()

    /**
     * 把 [before]（事务前 gameData）的 @Transient 运行态值整体搬到 [after]
     * （由快照/变更集重建的新实例）。镜像三臂（字段级/旧 JSON 往返/全量快照）
     * 在落值前统一调用；顶层显式携带（如 C++ 导出的 aiSectDisciples 族）
     * 在本调用**之后**覆盖——携带优先、其余保留。
     */
    fun carryOver(before: GameData, after: GameData) {
        for (field in transientFields) field.set(after, field.get(before))
    }
}

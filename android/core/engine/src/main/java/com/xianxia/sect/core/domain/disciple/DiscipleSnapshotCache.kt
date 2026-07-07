package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.DiscipleSnapshot
import com.xianxia.sect.core.state.DiscipleTables
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 弟子属性快照缓存 — 加载阶段预热，UI 层 O(1) 读取。
 *
 * 作用：
 * - 加载阶段调用 [prewarm] 从 [DiscipleTables] 批量构建快照
 * - UI 层通过 [get] / [getAll] / [getById] 直接读取，避免实时 assemble() 和计算
 * - 游戏内数据变化时调用 [invalidate]，下次读取时从 tables 重建
 *
 * 线程安全：快照构建在 [prewarm] 中完成，构建后为不可变 Map，后续读操作无需同步。
 */
@Singleton
class DiscipleSnapshotCache @Inject constructor() {

    @Volatile private var snapshotMap: Map<Int, DiscipleSnapshot> = emptyMap()
    @Volatile private var snapshotList: List<DiscipleSnapshot> = emptyList()
    @Volatile private var version: Int = 0

    /**
     * 从 [DiscipleTables] 批量构建快照。
     * 在存档加载后 / 新游戏初始化后调用一次。
     * O(n) 遍历约 5-15ms（100-500 弟子）。
     */
    fun prewarm(tables: DiscipleTables) {
        val list = DiscipleSnapshot.buildAll(tables)
        val map = list.associateBy { it.idInt }
        snapshotList = list
        snapshotMap = map
        version++
    }

    /** 获取所有存活弟子快照（用于列表渲染）。 */
    fun getAll(): List<DiscipleSnapshot> = snapshotList

    /** 按 Int ID 查找快照，不存在返回 null。 */
    fun get(idInt: Int): DiscipleSnapshot? = snapshotMap[idInt]

    /** 缓存是否有数据。 */
    val isReady: Boolean get() = snapshotList.isNotEmpty()

    /** 当前缓存版本号（每次 prewarm 后递增）。 */
    val currentVersion: Int get() = version

    /** 弟子数量。 */
    val size: Int get() = snapshotList.size
}

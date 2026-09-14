package com.xianxia.sect.core.state

import android.util.Log
import com.xianxia.sect.core.model.Disciple

private const val TAG = "DiscipleTables"

/** 升序校验：读档路径可能非升序，失序退化为全量组装 */
internal fun isPrevSnapshotSorted(prevSnapshot: List<Disciple>, tag: String): Boolean {
    var prevSorted = true
    var lastId = -1
    for (d in prevSnapshot) {
        val id = d.id.toIntOrNull()
        if (id == null || id < lastId) { prevSorted = false; break }
        lastId = id
    }
    if (!prevSorted) {
        Log.w(TAG, "$tag: prevSnapshot 非升序（读档路径），退化为全量组装")
    }
    return prevSorted
}

/** prevSnapshot → id 映射，供 patch 复用 prev 子对象引用 */
internal fun buildPrevById(prevSnapshot: List<Disciple>): HashMap<Int, Disciple> {
    val prevById = HashMap<Int, Disciple>(prevSnapshot.size * 2)
    for (d in prevSnapshot) {
        d.id.toIntOrNull()?.let { prevById[it] = d }
    }
    return prevById
}

/** 双指针归并：prevSnapshot（id 升序）∪ changedMap（id 升序） */
internal fun mergePatchedSnapshots(
    prevSnapshot: List<Disciple>,
    changedIds: Set<Int>,
    changedMap: Map<Int, Disciple>
): List<Disciple> {
    // 已移除/幽灵弟子 id：changedIds 中存在但组装失败的——归并时必须从
    // prevSnapshot 中剔除（否则陈尸残留）
    val removedIds = changedIds.filter { it !in changedMap }.toHashSet()

    return mergeSortedSnapshotsById(prevSnapshot, changedMap, removedIds)
}

/**
 * 双指针归并共享实现（assembleAllIncremental / mergePatchedSnapshots 共用）：
 * prevSnapshot（id 升序）∪ changedMap（id 升序）线性归并，未变弟子复用旧对象引用。
 * changedIds 中组装失败（被移除/幽灵）的 id 经 [removedIds] 从 prevSnapshot 剔除（防陈尸残留）。
 */
internal fun mergeSortedSnapshotsById(
prevSnapshot: List<Disciple>,
changedMap: Map<Int, Disciple>,
removedIds: Set<Int>
): List<Disciple> {
val result = ArrayList<Disciple>(prevSnapshot.size + changedMap.size)
var i = 0
val prevSize = prevSnapshot.size
for ((id, disciple) in changedMap.entries.sortedBy { it.key }) {
    // 复制 prevSnapshot 中 id < 当前变更 id 的未变弟子（剔除已移除 id）
    while (i < prevSize) {
        val prevId = prevSnapshot[i].id.toIntOrNull()
        if (prevId == null || prevId >= id) break
        if (prevId !in removedIds) result.add(prevSnapshot[i])
        i++
    }
    // 跳过 prevSnapshot 中与变更 id 重合的旧条目（id 唯一，最多一个）
    while (i < prevSize) {
        val prevId = prevSnapshot[i].id.toIntOrNull()
        if (prevId != id) break
        i++
    }
    result.add(disciple)
}
// 追加尾部未变弟子（剔除已移除 id）
while (i < prevSize) {
    val prevId = prevSnapshot[i].id.toIntOrNull()
    if (prevId == null || prevId !in removedIds) result.add(prevSnapshot[i])
    i++
}
return result
}

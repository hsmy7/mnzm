package com.xianxia.sect.ui.game.dialogs

/**
 * 历战卡片轮转状态机（纯 Kotlin、零 Android 依赖、可单测）。
 *
 * 维护当前主卡片索引与循环槽位计算；[next]/[prev] 在卡片
 * 不足两张时不改变索引（翻页按钮由 [canFlip] 禁用）。
 */
class LizhanCarouselState(private var itemCount: Int) {

    /** 当前主卡片索引（[0, itemCount)） */
    var currentIndex: Int = 0
        private set

    /** 是否可翻页（卡片数 > 1） */
    val canFlip: Boolean get() = itemCount > 1

    /** 向前翻页（右翻：主卡片向右移出，右侧副卡片轮转至中间）
     */
    fun next(): Int {
        if (!canFlip) return currentIndex
        currentIndex = (currentIndex + 1) % itemCount
        return currentIndex
    }

    /** 向后翻页（左翻：主卡片向左移出，左侧副卡片轮转至中间）
     */
    fun prev(): Int {
        if (!canFlip) return currentIndex
        currentIndex = (currentIndex - 1 + itemCount) % itemCount
        return currentIndex
    }

    /**
     * 相对槽位 → 实际卡片索引（环绕计算，负数安全）。
     * @param relative 相对主卡片偏移（-1=左副卡，0=主卡，+1=右副卡）
     */
    fun slotIndex(relative: Int): Int {
        if (itemCount <= 0) return 0
        return ((currentIndex + relative) % itemCount + itemCount) % itemCount
    }

    /** 列表长度变化后收敛索引（越界钳制为环绕取模）；空列表时归零 */
    fun updateItemCount(newCount: Int) {
        itemCount = newCount
        if (newCount == 0) {
            currentIndex = 0
        } else if (currentIndex >= newCount) {
            currentIndex = currentIndex % newCount
        }
    }
}

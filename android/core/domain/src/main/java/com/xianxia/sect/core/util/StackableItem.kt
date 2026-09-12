package com.xianxia.sect.core.util

interface StackableItem {
    val id: String
    val name: String
    val rarity: Int
    val quantity: Int
    val isLocked: Boolean
    fun withQuantity(newQuantity: Int): StackableItem

    /**
     * 复制物品并替换 id。
     *
     * 供 [com.xianxia.sect.core.state.StackableItemStore.add] 分块创建新堆叠时使用：
     * 单次添加数量超过 maxStack 会逐块生成多个堆叠，若都复用原 item.id 会破坏
     * id 唯一性（EntityStore 索引 / DB 主键 REPLACE 去重）导致仓库物品消失。
     */
    fun withNewId(newId: String): StackableItem
}

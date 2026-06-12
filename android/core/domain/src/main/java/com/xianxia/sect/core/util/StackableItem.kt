package com.xianxia.sect.core.util

interface StackableItem {
    val id: String
    val name: String
    val rarity: Int
    val quantity: Int
    val isLocked: Boolean
    fun withQuantity(newQuantity: Int): StackableItem
}

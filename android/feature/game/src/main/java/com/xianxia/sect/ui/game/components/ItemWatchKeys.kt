package com.xianxia.sect.ui.game.components

import com.xianxia.sect.core.model.BattleRewardItem
import com.xianxia.sect.core.model.MailAttachment
import com.xianxia.sect.core.model.MerchantItem
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.util.WATCHABLE_ITEM_TYPES
import com.xianxia.sect.core.util.normalizeItemType
import com.xianxia.sect.core.util.watchKey

/**
 * 从任意物品对象解析关注键（"type:name"）；灵石/储物袋/未知类型返回 null（不可关注）。
 * 覆盖 ItemDetailDialog 全部分派类型；未来新增物品类别仅需追加分支。
 */
fun watchKeyOf(item: Any?): String? = when (item) {
    is EquipmentDatabase.EquipmentTemplate -> watchKey("equipment", item.name)
    is ManualDatabase.ManualTemplate -> watchKey("manual", item.name)
    is MerchantItem -> {
        val type = normalizeItemType(item.type)
        if (type in WATCHABLE_ITEM_TYPES) watchKey(type, item.name) else null
    }
    is MailAttachment -> {
        val type = normalizeItemType(item.type)
        if (type in WATCHABLE_ITEM_TYPES) watchKey(type, item.name) else null
    }
    is BattleRewardItem -> {
        val type = normalizeItemType(item.type)
        if (type in WATCHABLE_ITEM_TYPES) watchKey(type, item.name) else null
    }
    is StorageBagItem -> {
        val type = normalizeItemType(item.itemType)
        if (type in WATCHABLE_ITEM_TYPES) watchKey(type, item.name) else null
    }
    is com.xianxia.sect.core.model.GameItem -> item.watchKey()
    else -> null
}

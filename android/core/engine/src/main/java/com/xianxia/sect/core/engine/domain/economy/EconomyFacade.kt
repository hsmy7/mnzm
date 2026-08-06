package com.xianxia.sect.core.engine.domain.economy

import com.xianxia.sect.core.engine.domain.inventory.InventoryFacade
import com.xianxia.sect.core.engine.domain.save.SaveFacade
import com.xianxia.sect.core.engine.service.AutoBuyService
import com.xianxia.sect.core.engine.service.MailService
import com.xianxia.sect.core.engine.service.RedeemCodeService
import com.xianxia.sect.core.wallet.SpiritStoneWallet

/**
 * 经济域服务归组门面（D1 拆分 GameEngine 构造依赖，2026-08-05）。
 * 仅聚合服务引用供 GameEngine 访问器转发，方法体保留在 GameEngine/各 Service。
 */
interface EconomyFacade {
    val spiritStoneWallet: SpiritStoneWallet
    val inventoryFacade: InventoryFacade
    val autoBuyService: AutoBuyService
    val redeemCodeService: RedeemCodeService
    val mailService: MailService
    val saveFacade: SaveFacade
}

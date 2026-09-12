package com.xianxia.sect.core.engine.domain.economy

import com.xianxia.sect.core.engine.domain.inventory.InventoryFacade
import com.xianxia.sect.core.engine.domain.save.SaveFacade
import com.xianxia.sect.core.engine.service.AutoBuyService
import com.xianxia.sect.core.engine.service.MailService
import com.xianxia.sect.core.engine.service.RedeemCodeService
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import javax.inject.Inject
import javax.inject.Singleton

/** 经济域服务归组实现（D1，GameEngine 构造 33→7）。 */
@Singleton
class EconomyFacadeImpl @Inject constructor(
    override val spiritStoneWallet: SpiritStoneWallet,
    override val inventoryFacade: InventoryFacade,
    override val autoBuyService: AutoBuyService,
    override val redeemCodeService: RedeemCodeService,
    override val mailService: MailService,
    override val saveFacade: SaveFacade
) : EconomyFacade

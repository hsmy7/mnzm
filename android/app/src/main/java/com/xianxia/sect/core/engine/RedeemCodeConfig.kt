package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.RedeemCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
data class RedeemCodeConfig(
    @ProtoNumber(1) val codes: List<RedeemCodeProto> = emptyList()
)

@Serializable
data class RedeemCodeProto(
    @ProtoNumber(1) val code: String,
    @ProtoNumber(2) val rewardType: String,
    @ProtoNumber(3) val quantity: Int = 1,
    @ProtoNumber(4) val maxUses: Int = 1,
    @ProtoNumber(5) val rarity: Int = 1,
    @ProtoNumber(6) val expireYear: Int? = null,
    @ProtoNumber(7) val expireMonth: Int? = null,
    @ProtoNumber(8) val isEnabled: Boolean = true
)

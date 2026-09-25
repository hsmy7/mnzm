package com.xianxia.sect.core.model

import androidx.annotation.Keep
import kotlinx.serialization.Serializable

/**
 * 兑换码奖励类型。
 *
 * 兑换码是**资源发放**通道，不是弟子获取通道：角色类奖励一律以 [FRAGMENT] 发放
 * 角色模板碎片，弟子实例只能由角色模板实例化产生。
 * 本枚举是编译期产物，不落 Room 表也不进 ProtoBuf（兑换码表在
 * `RedeemCodeManager.predefinedCodes` 与远端校验结果中，均不持久化），
 * 因此增删取值不涉及存档迁移。
 */
@Keep
@Serializable
enum class RedeemRewardType {
    SPIRIT_STONES,
    EQUIPMENT,
    MANUAL,
    PILL,
    MATERIAL,
    HERB,
    SEED,

    /** 角色碎片：按 [RedeemCode.templateId] 发放，入账走寻访域碎片门面 */
    FRAGMENT,
    MANUAL_PACK
}

@Keep
@Serializable
data class RedeemCode(
    val code: String,
    val rewardType: RedeemRewardType,
    val quantity: Int = 1,
    val rarity: Int = 1,
    val maxUses: Int = 1,
    val usedCount: Int = 0,
    val expireYear: Int? = null,
    val expireMonth: Int? = null,
    val isEnabled: Boolean = true,
    /** [RedeemRewardType.FRAGMENT] 的角色模板 id，取值域见 `CharacterTemplateDb.ids`；其余奖励类型为 null */
    val templateId: String? = null
) {
    val isExhausted: Boolean
        get() = usedCount >= maxUses
}

@Keep
@Serializable
data class RedeemResult(
    val success: Boolean,
    val message: String,
    val rewards: List<RewardSelectedItem> = emptyList(),
    /** true=仓库容量不足导致兑换未生效（兑换码未标记已用，清理后可重试）；UI 应弹容量提示框 */
    val capacityInsufficient: Boolean = false
)

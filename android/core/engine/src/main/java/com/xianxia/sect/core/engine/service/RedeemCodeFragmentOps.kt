package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.REWARD_TYPE_FRAGMENT
import com.xianxia.sect.core.engine.domain.gacha.GachaFacade
import com.xianxia.sect.core.engine.domain.gacha.GachaGrantResult
import com.xianxia.sect.core.model.CharacterTemplateDb
import com.xianxia.sect.core.model.RewardSelectedItem
import com.xianxia.sect.core.state.CriticalSaveKind
import com.xianxia.sect.core.util.DomainLog

// ── 兑换码角色碎片入账域（自 RedeemCodeService 拆出） ────────────────────────
//
// 拆分原因：本域是 G08 新增的独立职责（碎片一律经 [GachaFacade.grantFragments] 入账），
// 与服务主体的「校验 → 服务端/本地双臂 → 仓库发放」链路解耦；留在类内会把
// RedeemCodeService 推过 detekt TooManyFunctions。gachaFacade 已放宽为 internal 供本文件读取。

private val TAG = RedeemCodeService.TAG

/**
 * 角色模板 id 是否落在 [CharacterTemplateDb] 的取值域内。
 *
 * 服务端下发的碎片奖励在**任何写入之前**用本函数整单校验：错配即拒发且不消耗兑换码，
 * 避免「码已核销、碎片未到账」的静默损失。
 */
internal fun RedeemCodeService.isKnownCharacterTemplate(templateId: String?): Boolean =
    templateId != null && CharacterTemplateDb.byId(templateId) != null

/**
 * 单条角色碎片入账：一律经 [GachaFacade.grantFragments]，本域不写碎片账本。
 * 入账成功（含满 100 自动升星）即请求关键事件落盘（方案 §2.5 不可逆消耗类）。
 *
 * @param templateId 角色模板 id（调用方已校验存在于 [CharacterTemplateDb]）
 * @param quantity 碎片数量，非正值按 1 发放
 */
internal suspend fun RedeemCodeService.grantFragmentReward(templateId: String, quantity: Int) {
    val result = gachaFacade.grantFragments(templateId, quantity.coerceAtLeast(1))
    if (result is GachaGrantResult.Granted) {
        DomainLog.i(TAG, "Fragment granted via gacha facade: template=$templateId, quantity=$quantity")
        criticalSaveEvents.notify(CriticalSaveKind.IRREVERSIBLE_CONSUME)
    } else {
        DomainLog.w(TAG, "Fragment grant not completed: template=$templateId, quantity=$quantity, result=$result")
    }
}

/**
 * 服务端下发的碎片奖励入账（兑换码标记已用之后执行）。
 *
 * @param rewards 服务端奖励清单，只取 [REWARD_TYPE_FRAGMENT] 条目
 */
internal suspend fun RedeemCodeService.grantApiFragmentRewards(rewards: List<RedeemApiReward>) {
    rewards.filter { it.type == REWARD_TYPE_FRAGMENT }.forEach { reward ->
        grantFragmentReward(reward.templateId.orEmpty(), reward.quantity)
    }
}

/**
 * 本地兑换的角色碎片入账（兑换码标记已用之后执行）。
 *
 * @param rewards `RedeemCodeManager.generateReward` 产出的奖励条目，碎片条目 `id` 即 templateId
 */
internal suspend fun RedeemCodeService.grantFragmentRewards(rewards: List<RewardSelectedItem>) {
    rewards.filter { it.type == REWARD_TYPE_FRAGMENT }.forEach { reward ->
        grantFragmentReward(reward.id, reward.quantity)
    }
}

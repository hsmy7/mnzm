package com.xianxia.sect.core.state.reversechannel

import com.xianxia.sect.core.state.ReverseChannelPolicy

/**
 * 反向通道关闭数据的**条目构造助手**（W4-00 并行前置批新增）。
 *
 * 存在理由：关闭单元条目若写全限定名（`ReverseChannelPolicy.ClosedUnit(
 * ReverseChannelPolicy.Domain.X, ReverseChannelPolicy.Kind.GAME_DATA_FIELD, "f")`）
 * 会超出 detekt `MaxLineLength: 120`。四个分批数据文件共用本助手，从而保持
 * 「一行一条目」的可读形态，同时各批仍只写自己那个文件。
 *
 * 🔴 本文件**无人需要修改**（条目形状固定）⇒ 不构成并行冲突面。
 */

/**
 * 构造一个 **gameData 字段级**关闭单元。
 *
 * @param domain 归属域（回滚粒度）
 * @param name gameData JSON 键名
 * @return 关闭单元
 */
internal fun gameDataField(domain: ReverseChannelPolicy.Domain, name: String): ReverseChannelPolicy.ClosedUnit =
    ReverseChannelPolicy.ClosedUnit(domain, ReverseChannelPolicy.Kind.GAME_DATA_FIELD, name)

/**
 * 构造一个**顶层 `@Transient` 段**关闭单元。
 *
 * @param domain 归属域（回滚粒度）
 * @param name 段名（如 `lockedBeastIds`）
 * @return 关闭单元
 */
internal fun topLevelSection(domain: ReverseChannelPolicy.Domain, name: String): ReverseChannelPolicy.ClosedUnit =
    ReverseChannelPolicy.ClosedUnit(domain, ReverseChannelPolicy.Kind.TOP_LEVEL_SECTION, name)

/**
 * 构造**弟子通道**关闭单元（w3-13：通道整体关闭用；协议名 = `disciples`）。
 */
internal fun discipleChannel(domain: ReverseChannelPolicy.Domain): ReverseChannelPolicy.ClosedUnit =
    ReverseChannelPolicy.ClosedUnit(
        domain,
        ReverseChannelPolicy.Kind.DISCIPLE_CHANNEL,
        ReverseChannelPolicy.DISCIPLE_CHANNEL_NAME
    )

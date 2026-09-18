package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.proto.gameview.DiscipleRow

/**
 * MirrorDiscipleRowFixture — 弟子 → `DiscipleRow` typed 行编码夹具
 * （[MirrorProtoFeedEquivalenceTest] 的新臂载荷构造）。
 *
 * 与 [MirrorProtoFeedFixture] 同事实源：两臂都从同一个 Disciple 实例派生，
 * 本对象只负责"域模型 → proto typed 行"这一半映射，口径与 C++ 编码器
 * `gameview_encode.h` 的 `kDiscipleRowFields` 表逐项对应。
 */
internal object MirrorDiscipleRowFixture {

    private val json = MirrorProtoFeedFixture.json

    /**
     * 弟子 → `DiscipleRow` typed 行（109 协议字段逐字段）。
     *
     * **恒设全部字段**（与 C++ 编码器 emit-always 同规）：漏设任一字段即
     * presence=false → 解码侧按域默认补位 → 两臂弟子不再全等，本守卫即红——
     * 故本函数同时是 proto 契约字段覆盖度的钉。
     */
    fun toGameViewRow(d: Disciple): DiscipleRow =
        // R2.4/B09 起：编码器单源 = 生产面 GameViewDiscipleRows.toRow
        // （列级合并的基线行同一实现——夹具与生产防双表漂移）
        com.xianxia.sect.core.gameview.GameViewDiscipleRows.toRow(d, json)

    /** 新弟子行（与 [MirrorProtoFeedFixture.discipleUpsertsJson] 的 `fresh` 片段同值：全字段 emit-always）。 */
    fun newDiscipleRow(): DiscipleRow = toGameViewRow(MirrorProtoFeedFixture.newDisciple())
}

package com.xianxia.sect.core.gameview

import com.xianxia.sect.core.engine.AgedDeathDraft
import com.xianxia.sect.core.engine.BereavementDraft
import com.xianxia.sect.core.model.SecretRealmBackpack

/**
 * GameViewStreamEvent — proto 块 3 `eventFeed` 的 Kotlin 解码产物
 * （重构方案 R2.4：月/年 JSON 信封并入事件流）。
 *
 * C++ GameCore 在月结/年结（与每旬突破收割）时把平台效应草稿/事件入队，
 * 随 GameView 信封的 `repeated ViewEvent eventFeed` 入流；本类型是 codec
 * 解码后的**typed 载荷**（detailJson 的 v1 过渡编码在此一处解析，与
 * `collectionChange.upsertsJson` 同族的过渡边界），供残留执行器与
 * processMonthYearChange 消费——执行器零 JSON 解析由此结构保证。
 *
 * @property kind 事件种类（proto `ViewEventType` 镜像；proto 枚举号冻结）
 * @property gameYear 事件发生的游戏年（C++ 入队时点）
 * @property gameMonth 事件发生的游戏月
 * @property payload typed 明细；UNKNOWN 种类（前向兼容）为 null
 */
internal data class GameViewStreamEvent(
    val kind: Kind,
    val gameYear: Int,
    val gameMonth: Int,
    val payload: Payload?,
) {
    enum class Kind {
        MONTH_SETTLED,
        YEAR_SETTLED,
        BREAKTHROUGH,
        DEATH,
        PURCHASE,
        SECRET_REALM_CLOSED,
        UNKNOWN,   // 未登记种类（老 Kotlin 收新 C++ 的只增枚举）——宽松忽略
    }

    /** 事件专属结构化明细（typed 化载荷；detailJson 只在 codec 层解析） */
    internal sealed class Payload {

        /** 月结完成：政策自动禁用清单 + 征伐环没收 sectId 集（checkpointAllProduction / 建筑没收消费面） */
        internal data class MonthSettled(
            val disabledPolicies: List<String>,
            val seizedSectBuildings: List<String>,
        ) : Payload()

        /** 年结完成：丧亲事件草稿（lifeEvents 瞬态列写入面） */
        internal data class YearSettled(
            val bereavements: List<BereavementDraft>,
        ) : Payload()

        /** 突破事件（消息栏已有 gameEventRecords 载体；本流为 UI/未来消费者的观测面） */
        internal data class Breakthrough(
            val discipleId: String,
            val summary: String,
        ) : Payload()

        /** 死亡事件：死亡链平台效应草稿（袋物品物化/DAO 清理/DeathEvent） */
        internal data class Death(
            val draft: AgedDeathDraft,
        ) : Payload()

        /** 弟子智能购买日志草稿（lifeEvents 瞬态列写入面） */
        internal data class Purchase(
            val discipleId: String,
            val itemName: String,
            val age: Int,
        ) : Payload()

        /** 秘境到期关闭：关闭邮件附件 + gate release 消费面 */
        internal data class SecretRealmClosed(
            val memberIds: List<String>,
            val backpack: SecretRealmBackpack,
        ) : Payload()
    }
}

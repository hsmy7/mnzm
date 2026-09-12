package com.xianxia.sect.core.model

/**
 * statusData 派生数据 key 常量（core/domain 单一来源，engine 推导与 UI 消费共用）。
 *
 * statusData 是 [DiscipleAggregate.statusData] 的 Map 存储，除以下派生 key 外，
 * 还包含血炼 buildingId、思过 reflectionStartYear/reflectionEndYear 等既有 key——
 * 修改必须走定向 `+`/`-`，禁止整体覆盖。
 */

/** MANAGING 状态的职位文案 key（DiscipleStatusService 推导写入，UI statusText 消费） */
const val POSITION_NAME_KEY = "positionName"

/** resolvePositionName 解析失败时的兜底职位文案 */
const val MANAGING_FALLBACK = "管理中"

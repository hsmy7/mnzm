#!/usr/bin/env node
/**
 * action-catalog/w4a.mjs — **W4-A 批次独占**的 ActionId 清单（弟子与建设轴）。
 *
 * ## 所有权（docs/parallel-batches-w4/README.md §3.1 项 1 / §5.1）
 * 🔴 **只有 W4-A 可写本文件**。另两批（W4-B / W4-C）与任何其他工作一律不得改本文件。
 * 新增动作 = 在本文件 `CATALOG` 数组**末尾**追加一行；随后运行
 * `node scripts/gen-action-ids.mjs` 重生成两份产物（`action_ids.h` / `ActionIds.kt`）。
 *
 * ## 预分配段（README §6；段内用不满则余量留空，**禁止跨批复用**）
 *   1740–1749  w3-01 弟子操作面（属性/改名/类型直改、赏赐/服药、功法替换、血槽清理）
 *   1750–1759  w3-02 弟子生命周期第二波（婚姻提议审批/拒绝、槽位清理、lifeEvent）
 *   1810–1819  w3-09 建筑/道路残差（拆除槽位残差、月变没收、放置槽位、道路回执）
 *   1820–1829  w3-10 生产残差（自动续炼槽位、仓库直读）
 *   1850–1854  RNG 阶段 3·弟子侧（`DiscipleChatDialog` 对话效果）— **条件段**：
 *              仅当 ADR 阶段 3 判定"需 C++ 事务签发结果"时启用；默认走形参化/分区化，不占段
 *
 * ## 纪律
 * - id 全局唯一（生成器有重复即断言失败）；**不得**占用他人段（有区间重叠断言）
 * - 条目结构：`{ id: number, name: string, desc: string }`
 * - 追加式改动；改动后必须 `node scripts/gen-action-ids.mjs && git diff --exit-code` 自证无漂移
 */
export const CATALOG = [
  // ── 1740–1749 · w3-01 弟子操作面 ──
  { id: 1740, name: 'DISCIPLE_OP_RENAME', desc: '弟子改名事务（names行写+招募列表isSamePerson同人净化——按改名前身份）' },
  { id: 1741, name: 'DISCIPLE_OP_CHANGE_TYPE', desc: '弟子类型直改事务（discipleTypes行写；状态推导由Kotlin调用方原序执行）' },
  { id: 1742, name: 'DISCIPLE_OP_TOGGLE_FOLLOW', desc: '弟子关注切换事务（statusData["followed"]翻转；返回followedAfter）' },
  { id: 1743, name: 'DISCIPLE_OP_REWARD_ITEM', desc: '赏赐物品事务（pill/material/herb/seed四路合一：扣仓库+生效或入袋同一事务；pill走facade丹药链）' },
  { id: 1744, name: 'DISCIPLE_OP_USE_PILL', desc: '服药事务（canUsePill资格链+扣仓库+facade丹药链+服药日志草稿；moralityAfter回传供偷盗钩子判定）' },
  { id: 1745, name: 'DISCIPLE_OP_REPLACE_MANUAL', desc: '功法替换事务（七链校验+堆叠扣减+实例铸造+熟练度清理+旧实例入袋+替换日志草稿）' },
  { id: 1746, name: 'DISCIPLE_OP_START_BLOOD_REFINEMENT', desc: '血炼启动原子事务（灵石/材料/排他校验链+11类槽位清理+进度写入+REFINING状态）' },
  { id: 1747, name: 'DISCIPLE_OP_SYNC_STATUS', desc: '单弟子状态派生同步事务（14 flag推导+positionName定向写删——派生列唯一计算方）' },
  { id: 1748, name: 'DISCIPLE_OP_SYNC_ALL_STATUSES', desc: '全量弟子状态派生同步事务（含fixInvalidMiningSlots前置自愈）' },

  // ── 1750–1759 · w3-02 弟子生命周期第二波 ──
  { id: 1750, name: 'DISCIPLE_LIFECYCLE_MARRY_REJECT', desc: '婚姻拒绝事务（MARRIAGE拒绝事件直写；零弟子表写入/零RNG/无失败臂；提议移除留Kotlin运行态）' },
  // （婚姻批准接线走 batch-14 就绪地基 1592，不占新号）

  // ── 1810–1819 · w3-09 建筑/道路残差 ──
  { id: 1810, name: 'BUILDING_RESIDUAL_CLEAR', desc: '建筑拆除/没收槽位清扫事务（十类槽位按槽组清除+长老殿末座判定+监牢/任务阁特例+REFINING破除；槽组知识由Kotlin组装传入）' },
  { id: 1811, name: 'BUILDING_PLACE_SLOTS', desc: '建筑放置槽位派生事务（SlotGroup.createSlots写段等价：八集合建槽+每塔一份PatrolConfig；生产槽id由Kotlin UUID生成传入）' },
  // （道路残差已由 batch-07 road_tx 下沉 + A1 关闭 roads 单元——不占新号）

  // ── 1820–1829 · w3-10 生产残差 ──
  // （A4 核对收口：自动续炼链已由 C++ 月结直辖（production.h batch-17/18
  //  地基），Kotlin 链为回退臂；对齐窗口删除挂 W4-D/S4（调用点在宿主文件
  //  族）⇒ 本段空置，零新号）

  // ── 1850–1854 · RNG 阶段 3·弟子侧（条件段） ──
  // （条件启用，默认空置）
];

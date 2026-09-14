#!/usr/bin/env node
/**
 * action-catalog/core.mjs — 既有 ActionId 清单（批次 0–21 累计 **171 条**，段 1000–1734）。
 *
 * 本文件由 W4-00（并行前置批）从 scripts/gen-action-ids.mjs 的 ACTION_CATALOG **原样迁出**——
 * 条目内容逐字节不变，仅改变所在文件（零行为变更）。
 *
 * ## 所有权（W4 三批次并行协议，见 docs/parallel-batches-w4/README.md §3.1 项 1）
 * 🔴 **本文件在 W4-00 之后冻结**——三批次一律不得改本文件：
 *    新增 ActionId 只能写自己批次的清单文件（w4a.mjs / w4b.mjs / w4c.mjs），
 *    段号见 docs/parallel-batches-w4/README.md §6。
 *    确需修订既有条目（改名/改描述/删条目）⇒ 找收口人串行化，不得自行修改。
 *
 * ## 条目结构
 *   { id: number, name: string, desc: string }
 *   id 分配：1000 起为业务动作段；系统动作（生命周期/tick/快照）不走 execute，无需占位。
 */

export const CATALOG = [
  // ── 经济（SpiritStoneWallet） ──
  { id: 1000, name: 'WALLET_ADD', desc: '灵石增加（事务内）' },
  { id: 1001, name: 'WALLET_DEDUCT', desc: '灵石扣除（含自动售卖补差价）' },
  { id: 1002, name: 'WALLET_BATCH', desc: '灵石批量变更（原子）' },
  { id: 1003, name: 'WALLET_BALANCE', desc: '灵石余额查询（按品阶）' },
  { id: 1004, name: 'WALLET_TOTAL_SELL_VALUE', desc: '全部品阶按售卖价总价值' },

  // ── 库存（InventorySystem） ──
  // 注：1011 INV_ADD_EQUIPMENT_INSTANCE / 1013 INV_ADD_MANUAL_INSTANCE 已于
  // W4-00 删除（死导出）——两者声明一直无 handler（落 handleInventory 的 default
  // → UNKNOWN_ACTION），且全仓零调用方：实例轨的新增走 Kotlin
  // `InventorySystem.addEquipmentInstance` / `addManualInstance` 直调，不经 ActionId。
  // 由新守卫 `test/dispatch_guard_test.cpp` 首跑抓出（docs/parallel-batches-w4/README.md §3.1 项 11）。
  { id: 1010, name: 'INV_ADD_EQUIPMENT_STACK', desc: '添加装备堆叠（合并+溢出转邮件）' },
  { id: 1012, name: 'INV_ADD_MANUAL_STACK', desc: '添加功法堆叠' },
  { id: 1014, name: 'INV_ADD_PILL', desc: '添加丹药（按品阶合并）' },
  { id: 1015, name: 'INV_ADD_MATERIAL', desc: '添加材料' },
  { id: 1016, name: 'INV_ADD_HERB', desc: '添加草药' },
  { id: 1017, name: 'INV_ADD_SEED', desc: '添加种子' },
  { id: 1018, name: 'INV_ADD_STORAGE_BAG', desc: '添加储物袋' },
  { id: 1019, name: 'INV_REMOVE_EQUIPMENT', desc: '移除装备（按 id+数量）' },
  { id: 1020, name: 'INV_REMOVE_MANUAL', desc: '移除功法' },
  { id: 1021, name: 'INV_REMOVE_PILL', desc: '移除丹药' },
  { id: 1022, name: 'INV_REMOVE_MATERIAL', desc: '移除材料' },
  { id: 1023, name: 'INV_REMOVE_HERB', desc: '移除草药' },
  { id: 1024, name: 'INV_REMOVE_SEED', desc: '移除种子' },
  { id: 1025, name: 'INV_CAN_ADD_ITEM', desc: '仓库是否有空余槽位' },
  { id: 1026, name: 'INV_CAPACITY_INFO', desc: '仓库容量信息' },
  { id: 1027, name: 'INV_CONSOLIDATE', desc: '仓库堆叠合并（批 8-3）' },
  { id: 1028, name: 'INV_SORT', desc: '仓库整理=合并+排序（含实例轨道）' },
  { id: 1029, name: 'INV_TOGGLE_LOCK', desc: '堆叠锁定翻转（按 id+类型）' },

  // ── 灵田收获 ──
  { id: 1030, name: 'SPIRIT_FIELD_HARVEST', desc: '灵田月度收获（成熟判定+续种+年度报告）' },

  // ── 弟子属性/修炼/突破/生命周期 ──
  { id: 1100, name: 'DISCIPLE_BASE_STATS', desc: '弟子基础属性乘区法计算' },
  { id: 1101, name: 'DISCIPLE_CULTIVATION_PER_PHASE', desc: '每旬修炼速度（乘区法）' },
  { id: 1102, name: 'DISCIPLE_BREAKTHROUGH_CHANCE', desc: '突破概率（乘区法）' },
  { id: 1103, name: 'DISCIPLE_MAX_AGE', desc: '弟子最大寿元' },
  { id: 1104, name: 'DISCIPLE_CHECKPOINT', desc: '修炼检查点同步' },
  { id: 1105, name: 'DISCIPLE_ACCUMULATE_CULTIVATION', desc: '每旬修炼累积（钳制上限）' },
  { id: 1106, name: 'DISCIPLE_AGE', desc: '弟子老化（年龄+1/5岁回正/寿元判定）' },
  { id: 1107, name: 'DISCIPLE_BREAKTHROUGH', desc: '突破执行（连续突破循环）' },
  { id: 1108, name: 'DISCIPLE_ESTIMATE_BREAKTHROUGH_MONTH', desc: '突破完成月份预估' },

  // ── 战斗 ──
  { id: 1200, name: 'BATTLE_FINAL_DAMAGE', desc: '乘区法最终伤害计算' },
  { id: 1201, name: 'BATTLE_REALM_GAP_FACTORS', desc: '境界压制三因子' },
  { id: 1202, name: 'BATTLE_CHECK_INSTANT_KILL', desc: '跨境界斩杀判定' },
  { id: 1203, name: 'BATTLE_DODGE_CHANCE', desc: '闪避概率' },
  { id: 1204, name: 'BATTLE_SHIELD_ABSORPTION', desc: '护盾吸收' },
  { id: 1205, name: 'BATTLE_DOT', desc: 'DoT 持续伤害结算' },
  { id: 1206, name: 'BATTLE_COOLDOWN_UPDATE', desc: '技能冷却更新' },

  // ── 内政 ──
  { id: 1300, name: 'GOV_POLICY_COSTS', desc: '政策月度成本（三模式扣除）' },
  { id: 1301, name: 'GOV_POLICY_MONTHLY_EFFECTS', desc: '政策月度忠诚/道德效果' },
  { id: 1302, name: 'GOV_SPIRIT_MINE_MONTHLY', desc: '灵矿月度产出（时间戳差分）' },
  { id: 1303, name: 'GOV_ANNUAL_SALARY', desc: '年度年俸发放' },
  { id: 1304, name: 'GOV_ZONE_CALCULATE', desc: '乘区法通用计算' },

  // ── 探索/世界关卡 ──
  { id: 1400, name: 'WORLD_LEVEL_MONTHLY', desc: '世界关卡月度处理（清理+刷新+移动）' },
  { id: 1401, name: 'WORLD_LEVEL_CHECK_EXPIRED', desc: '关卡过期判定' },

  // ── LevelGenerator（世界关卡生成） ──
  { id: 1402, name: 'LEVEL_SELECT_BEAST_REALM', desc: '按年份加权随机选取妖兽境界' },
  { id: 1403, name: 'LEVEL_GENERATE_LEVELS', desc: '生成世界关卡（妖兽/洞府，含属性预生成）' },

  // ── DiscipleDeathHandler（死亡物化） ──
  { id: 1404, name: 'DISCIPLE_MARK_DEAD', desc: '标记弟子死亡（isAlive/status/deathYears + 年死亡计数 + 装备断言）' },
  { id: 1405, name: 'DISCIPLE_BACKFILL_DEATH_YEARS', desc: '列表 copy 模式补写 deathYears（replaceAll 清空后恢复）' },

  // ── SecretRealm（远古秘境状态机核心） ──
  { id: 1406, name: 'SECRET_REALM_PLAYER_AVG_REALM', desc: '存活成员平均境界（全灭取上限）' },
  { id: 1407, name: 'SECRET_REALM_ROLL_BEAST_REALM', desc: '秘境妖兽境界随机 [avg-1, avg+2] clamp 0..9' },
  { id: 1408, name: 'SECRET_REALM_GENERATE_BEAST_EVENT', desc: '生成遭遇妖兽事件（类型/境界/层数/数量）' },
  { id: 1409, name: 'SECRET_REALM_ROLL_NEXT_EVENT', desc: '方向选择后下一事件（一次 nextDouble 分段判定）' },
  { id: 1410, name: 'SECRET_REALM_BUILD_BEAST_STATS', desc: '妖兽最终属性预生成（层数倍率+随机方差+偷袭减血）' },
  { id: 1411, name: 'SECRET_REALM_ROLL_BEAST_LOOT', desc: '妖兽战斗胜利掉落（加权选取，每只 2 材料）' },
  { id: 1412, name: 'SECRET_REALM_GENERATE_RUINS_TREASURE', desc: '遗迹秘宝描述符生成（候选模板列表参数化）' },
  { id: 1413, name: 'SECRET_REALM_RESOLVE_RUINS', desc: '遗迹探索结算（离开/搜寻判定+结果文本+方向事件）' },
  { id: 1414, name: 'SECRET_REALM_LOOT_LOSS', desc: '战斗失败丢失背包物品（比例+洗牌选取）' },
  { id: 1415, name: 'SECRET_REALM_AI_DISPATCH', desc: 'AI 宗门探索队伍派遣（存活境界最高 4 名）' },
  { id: 1416, name: 'SECRET_REALM_FIND_POSITION', desc: '秘境空闲位置寻找（避宗门随机+兜底最远扫描）' },
  { id: 1417, name: 'SECRET_REALM_STAMINA', desc: '选择选项后体力计算（非法消耗 clamp 防篡改）' },
  { id: 1418, name: 'SECRET_REALM_YEARLY_SPAWN_CHECK', desc: '年变现世冷却判据（负冷却 clamp 防篡改）' },
  { id: 1419, name: 'SECRET_REALM_ROLL_SPRITE', desc: '秘境精灵变体随机（1×nextInt）' },

  // ── 外交（决策引擎/战力/品阶曲线/宗门交易） ──
  { id: 1420, name: 'SECT_DECISION_CHANCE', desc: 'AI 四因素加权判定概率（攻击/结盟/附属）' },
  { id: 1421, name: 'SECT_DECISION_BREAKAWAY', desc: '附属脱离概率（战力/丢失/胜负/好感度反向）' },
  { id: 1422, name: 'SECT_POWER_DISCIPLE', desc: '弟子战力（永久基础属性公式）' },
  { id: 1423, name: 'SECT_POWER_BEAST', desc: '妖兽战力（同公式 + 防篡改 clamp）' },
  { id: 1424, name: 'SECT_POWER_FINGERPRINT', desc: '永久基础属性缓存指纹（Java hashCode）' },
  { id: 1425, name: 'SECT_RARITY_ROLL', desc: '品阶时间曲线抽样（1×nextDouble）' },
  { id: 1426, name: 'SECT_RARITY_MAX', desc: '年份可出最高品阶' },
  { id: 1427, name: 'SECT_RARITY_PITY', desc: '年份保底品阶（下一分段）' },
  { id: 1428, name: 'SECT_RARITY_WEIGHTS', desc: '年份品阶权重表（归一化）' },
  { id: 1429, name: 'SECT_TRADE_SEED', desc: '宗门交易确定性种子（sectId.hashCode + year）' },
  { id: 1430, name: 'SECT_TRADE_STOCK', desc: '商品库存量抽样（消耗品/耐用品两档曲线）' },
  { id: 1431, name: 'SECT_TRADE_PRICE', desc: '商品价格波动（±20% 一位小数截断）' },
  { id: 1432, name: 'SECT_TRADE_SPIRIT_STONE', desc: '灵石商品映射（上品/中品 + 年份上限判定）' },

  // ── 11 槽分配清理（DiscipleSlotCleanup） ──
  { id: 1433, name: 'SLOT_CLEAR_ALL', desc: '清除弟子全部槽位引用（11 类槽位纯数据变换）' },

  // ── 兑换码 + 邮件附件（RedeemCodeManager） ──
  { id: 1434, name: 'REDEEM_VALIDATE_INPUT', desc: '兑换码格式校验（trim/长度/字符集）' },
  { id: 1435, name: 'REDEEM_ROLL_SPIRIT_ROOT', desc: '灵根类型解析（配置/数量随机/权重生成 + java.util.Random 洗牌）' },
  { id: 1436, name: 'REDEEM_RESOLVE_AGE_LIFESPAN', desc: '年龄区间 + 境界寿元 ±10% 波动' },
  { id: 1437, name: 'REDEEM_ROLL_SKILLS', desc: '灵根阶梯属性掷点 + 避开哨兵 50' },
  { id: 1438, name: 'REDEEM_GENERATE_VARIANCE', desc: '属性方差生成（-50..50）' },
  { id: 1439, name: 'MAIL_ATTACHMENT_ENCODE', desc: '邮件附件列表 → JSON 字符串（kotlinx 对齐）' },

  // ── 秘境交互会话域（SecretRealmService.startSession/
  //    chooseOption/endSession/processYearlySpawn——nativeExecute +
  //    tryExecuteNative 交互域转发，Kotlin 回退保留） ──
  { id: 1440, name: 'SECRET_REALM_START', desc: '出发探索（校验+会话写入+初始妖兽事件）' },
  { id: 1441, name: 'SECRET_REALM_CHOOSE', desc: '选择选项（结算+战斗+下一事件+会话结束）' },
  { id: 1442, name: 'SECRET_REALM_END', desc: '结束探索（背包结算入仓+秘境清场）' },
  // 注：年变现世（processYearlySpawn）已在 runYearSettlement 原生下沉
  //（processAncientSecretRealmSpawn）——独立 ActionId 冗余，不注册（死导出纪律）。

  // ── 生产排程交互事务（手动排班 C++ 真相先行——
  //    校验链/材料消耗/槽位 WORKING 写入 + duration 重算合并；Kotlin 后置持久化）──
  { id: 1443, name: 'PRODUCTION_START', desc: '手动排班事务（配方校验+材料检查消耗+槽位WORKING）' },
  { id: 1444, name: 'PRODUCTION_RESET', desc: '手动重置事务（槽位回IDLE全清空）' },

  // ── 建筑放置/迁移/升级/拆除事务（BuildingFacade AUTHORITATIVE 转发——
  //    batch-06 下沉；校验链/灵石/引导计数 C++ 真相先行，失败零写入
  //    Kotlin 回退；宗门过滤与占用集合组装归 Kotlin，§2.36 同偏差）──
  { id: 1450, name: 'BUILDING_PLACE', desc: '建筑放置事务（等级/环界门楼/限建/占位/灵石校验+建筑写入）' },
  { id: 1451, name: 'BUILDING_MOVE', desc: '建筑迁移事务（存在性/环界门楼校验+坐标改写）' },
  { id: 1452, name: 'BUILDING_UPGRADE', desc: '建筑升级事务（等级/差价/canFit校验+原地变换）' },
  { id: 1453, name: 'BUILDING_REMOVE', desc: '建筑批量拆除事务（存在性/幽灵防御+灵石返还）' },
  { id: 1454, name: 'BUILDING_UPGRADE_BATCH', desc: '建筑批量升级事务（整批等级/候选稳定序/可负担上限/增量canFit）' },
  // ── 道路放置/拆除事务（RoadFacade AUTHORITATIVE 转发——batch-07 下沉；
  //    校验链/灵石扣减/5 格邻域掩码重算 C++ 真相先行，失败零写入 Kotlin 回退）──
  { id: 1470, name: 'ROAD_PLACE', desc: '道路放置事务（占位校验+扣灵石+邻域掩码重算）' },
  { id: 1471, name: 'ROAD_REMOVE', desc: '道路拆除事务（存在性校验+邻域掩码重算）' },

  // ── 弟子管理 UI 操作事务（batch-08 第一子批：装备穿脱/功法学习卸下/
  //    任命卸任——disciple_tx.h 零 RNG 纯事务；失败信封回退 Kotlin 原路径）──
  { id: 1480, name: 'DISCIPLE_TX_EQUIP', desc: '装备穿戴事务（校验链+堆叠扣减/实例铸造+槽位列写）' },
  { id: 1481, name: 'DISCIPLE_TX_UNEQUIP', desc: '装备卸下事务（实例入袋+实例表移除+槽位列清）' },
  { id: 1482, name: 'DISCIPLE_TX_LEARN_MANUAL', desc: '功法学习事务（资格守卫+堆叠消耗+实例铸造+HP/MP增量）' },
  { id: 1483, name: 'DISCIPLE_TX_UNLEARN_MANUAL', desc: '功法卸下事务（实例入袋+manualIds/熟练度清理）' },
  { id: 1484, name: 'DISCIPLE_TX_ASSIGN_SLOT', desc: '任命事务（clearAllSlots+亲传/藏经阁槽覆写）' },
  { id: 1485, name: 'DISCIPLE_TX_UNASSIGN_SLOT', desc: '卸任事务（亲传/藏经阁单槽重置）' },

  // ── 外交/好感/附庸 UI 操作事务族（batch-09——diplomacy_tx.h；
  //    校验失败 failure 信封回退 Kotlin 原路径（零抽取零写入），roll 后
  //    结果 success 信封直返——双臂 SYSTEM 分区同源、抽取序逐位一致）──
  { id: 1500, name: 'DIPLOMACY_TX', desc: '外交事务（结盟请求/解除结盟——仅结盟消费 SYSTEM 1×nextDouble）' },
  { id: 1501, name: 'FAVOR_GIFT', desc: '赠礼事务（校验链+拒绝 roll SYSTEM 1×nextInt(100)+好感写入+扣费）' },
  { id: 1502, name: 'VASSAL_TX', desc: '附庸事务（附属请求/解除——仅请求消费 SYSTEM 1×nextDouble）' },

  // ── 库存出售/上架 UI 操作事务族（W2-a——inventory_tx.h；出售族
  //    AUTHORITATIVE 稳态写者归 C++，零 RNG 纯确定性变换，失败零写入
  //    Kotlin 回退原路径重执行校验链）──
  { id: 1520, name: 'INV_SELL_ITEM', desc: '单类出售事务（存在/锁定/数量守卫+售价入账+堆叠扣减）' },
  { id: 1521, name: 'INV_BULK_SELL', desc: '批量出售事务（逐条扣减零入账+末尾一次 Sell(bulk) 入账）' },
  { id: 1522, name: 'MERCHANT_SELL_ACQUISITION', desc: '商人收购事务（非法参数拒绝+仓库实售量+扣仓入账+收购项回写）' },
  { id: 1523, name: 'MERCHANT_LIST_ITEMS', desc: '玩家上架事务（装备/功法/丹药三段首命中登记，不扣仓库）' },
  { id: 1524, name: 'MERCHANT_REMOVE_LISTED', desc: '撤下上架项事务（playerListedItems 按 id 移除）' },
  { id: 1525, name: 'INV_CONSUME_MATERIAL', desc: '按名称+品阶消耗材料（未锁定按列表序扣减，快照语义）' },

  // ── 库存域收官：商人购买/充公（batch-11——inventory_tx.h；零 RNG 纯事务，
  //    模板缺失/容量不足 failure 信封回退 Kotlin 原路径；开袋族因
  //    Random.Default 模板抽取不可逐位复刻，按路线 B 未下沉并登记）──
  { id: 1530, name: 'INV_BUY_MERCHANT_ITEM', desc: '商人购买事务（判定序+按型槽位预算容量预测+先加后扣+商家库存扣减；模板缺失failure信封回退）' },
  { id: 1531, name: 'INV_CONFISCATE_BAG_ITEM', desc: '充公事务（幂等探测+三态物化+溢出抑制+仅Success移除袋条目）' },

  // ── 探索域 UI 操作事务族（batch-13——exploration_tx.h；世界关卡/侦察
  //    战斗执行 BATTLE 分区 + 伤亡写回（袋物化）入 C++，奖励生成/胜利
  //    事务/哀伤/gate/战报留 Kotlin；分舵驻守零 RNG 纯事务，失败零写入
  //    Kotlin 回退原路径重执行校验链）──
  { id: 1570, name: 'EXPLORE_TX_ATTACK_WORLD_LEVEL', desc: '世界关卡战斗事务（校验链+组装 pregens/基础值双分支+BATTLE 分区执行+伤亡写回，回传终态/幸存/阵亡）' },
  { id: 1571, name: 'EXPLORE_TX_SCOUT_SECT', desc: '宗门侦察事务（AI 守卫选取 alive∧7..9 取 8+PvP 组装满血 ATTACKER+BATTLE 分区执行+伤亡写回，回传守卫展示段）' },
  { id: 1572, name: 'EXPLORE_TX_ASSIGN_GARRISON', desc: '分舵驻守分配（存在/存活校验+同宗已驻静默跳过+旧occupant捕获+全槽清理+槽位字段写，零 RNG）' },
  { id: 1573, name: 'EXPLORE_TX_REMOVE_GARRISON', desc: '分舵驻守移除（occupant 捕获+槽位清空保留索引，零 RNG）' },

  // ── 弟子管理二：生命周期族（batch-14——disciple_lifecycle_tx.h；
  //    逐出/拜师/婚姻批准/释放思过/年俸开关五事务；逐出含 12 类槽位清理
  //    与实例销毁，袋物品经信封回传 Kotlin 物化；失败零写入 Kotlin 回退）──
  { id: 1590, name: 'DISCIPLE_LIFECYCLE_EXPEL', desc: '逐出事务（存在/存活/非血炼校验+12类槽位清理含住所+实例销毁+派生map收口+行删除；袋物品信封回传Kotlin物化）' },
  { id: 1591, name: 'DISCIPLE_LIFECYCLE_APPRENTICE', desc: '拜师事务（三相校验+masterIds落表；双侧日志草稿回写lifeEvents）' },
  { id: 1592, name: 'DISCIPLE_LIFECYCLE_MARRY_APPROVE', desc: '婚姻批准事务（已有道侣防御+partnerIds双向绑定+MARRIAGE事件直写；提议移除留Kotlin）' },
  { id: 1593, name: 'DISCIPLE_LIFECYCLE_RELEASE_REFLECTION', desc: '释放思过事务（statusData思过双键定向移除+状态回IDLE；静默no-op同义）' },
  { id: 1594, name: 'DISCIPLE_LIFECYCLE_SALARY_TOGGLE', desc: '境界年俸开关事务（yearlySalaryEnabled[realm]覆写，无校验）' },

  // ── 弟子管理三：任命/驻守/洗炼消耗族（batch-15——appointment_tx.h；
  //    长老单值槽与仓库驻守零 RNG 纯事务，洗炼三族含玉符消耗（C++ 承扣）
  //    与 SYSTEM 分区抽取，失败臂零写入零抽取，Kotlin 回退原路径）──
  { id: 1610, name: 'ELDER_APPOINT_TX', desc: '长老单值槽任命（存在/存活校验+全槽清理+槽位字段写+亲传列表清空，回传被顶替者）' },
  { id: 1611, name: 'ELDER_DISMISS_TX', desc: '长老单值槽卸任（槽位字段清空+亲传列表清空，回传被卸任者）' },
  { id: 1612, name: 'WAREHOUSE_GARRISON_TX', desc: '仓库驻守分配（存在/存活校验+旧occupant捕获+全槽清理+条目替换）' },
  { id: 1613, name: 'SPIRIT_ROOT_WASH_TX', desc: '洗炼灵根（先扣玉符后抽取：保底/双灵根判定+元素洗牌，SYSTEM 分区）' },
  { id: 1614, name: 'TRAIT_ADD_ROLL_TX', desc: '新增特质刷新（上限/候选预检+扣玉符+品阶抽取+pending 落盘，SYSTEM 分区）' },
  { id: 1615, name: 'TRAIT_ADD_CONFIRM_TX', desc: '新增特质确认（上限/合法性校验+追加+lifespan 同步+checkpoint+清 pending，零 RNG）' },
  { id: 1616, name: 'TRAIT_WASH_SLOT_TX', desc: '特质单槽洗炼（目标校验+排除集+扣玉符+保底/品阶抽取，SYSTEM 分区）' },

  // ── 招募/派遣/俘虏残余族（batch-16——recruit_tx.h；招募列表 UI 直调点
  //    入 C++：移除/老化净化零 RNG 纯事务，刷新复用 year_settlement 候选
  //    生成链（SYSTEM 分区与 Kotlin 臂逐位同源）。失败零写入，Kotlin 回退
  //    原路径重执行校验链。手动/一键/自动招募与俘虏物化已在
  //    recruit_settlement.h / 专用 JNI，不在本段）──
  { id: 1630, name: 'RECRUIT_REMOVE_TX', desc: '招募列表移除条目（按 id 过滤幂等，零 RNG）' },
  { id: 1631, name: 'RECRUIT_REFRESH_TX', desc: '年度招募列表刷新（差值门+宗门等级/长老魅力加成+广纳门徒+候选生成，SYSTEM 分区）' },
  { id: 1632, name: 'RECRUIT_AGE_TX', desc: '招募列表老化+净化（age+1/超寿元移除/损坏过滤/三级去重/跨表残留，零 RNG）' },

  // ── 生产 UI 面 + 灵田种植族（batch-17——production.h / spirit_field.h
  //    追加；生产槽任命/卸任/自动续炼开关/惰性建槽与灵田种植/移除全族
  //    零 RNG 纯事务，失败零写入，Kotlin 回退原路径重执行校验链；
  //    Room 持久化为后置残差（S7 同族，镜像为真源）。自动收获入口经
  //    写者审计判定**不迁**——读档时刻 native 基线尚未建立（见 §2.48））──
  { id: 1650, name: 'PROD_UI_ASSIGN_SLOT', desc: '生产槽弟子任命（槽位存在校验+全槽位清理+目标槽写+他槽清空，零 RNG）' },
  { id: 1651, name: 'PROD_UI_REMOVE_SLOT', desc: '生产槽弟子卸任（占用捕获+WORKING 剩余时长归一+槽位清空，零 RNG）' },
  { id: 1652, name: 'PROD_UI_TOGGLE_AUTO_RESTART', desc: '自动续炼开关翻转（槽位存在校验+镜像字段翻转，零 RNG）' },
  { id: 1653, name: 'PROD_UI_ADD_SLOT', desc: '生产槽惰性建槽/镜像槽维护（按 buildingId+slotIndex 覆写或追加，零 RNG）' },
  { id: 1654, name: 'SPIRIT_FIELD_PLANT_ONE', desc: '灵田单块播种（种子存在/未锁定/余量>0+空地匹配+同事务扣种，零 RNG）' },
  { id: 1655, name: 'SPIRIT_FIELD_PLANT_BATCH', desc: '灵田批量播种（余量约束上限+地块镜像序播种+按实际播种数扣种，零 RNG）' },
  { id: 1656, name: 'SPIRIT_FIELD_REMOVE_ONE', desc: '灵田单块移除（按实例首命中清空种植字段，零 RNG）' },
  { id: 1657, name: 'SPIRIT_FIELD_REMOVE_BATCH', desc: '灵田批量移除（按实例集合清空种植字段，零 RNG）' },

  // ── 月年边界编排族·引导计数面（batch-18a——boundary_tx.h；写者审计收窄批面：
  //    月年边界效果入口 advanceMonth/advanceYear、战后 HP/MP 写回、
  //    checkGameOverCondition 均为零调用者死代码或月变事务内步骤，登记不下沉；
  //    本段只下沉三处真正 Kotlin 独占的活路径计数写者，零 RNG 纯确定性变换，
  //    失败零写入 Kotlin 回退原路径）──
  { id: 1670, name: 'BOUNDARY_GUIDE_COUNTER_INCREMENT_TX', desc: '引导计数递增（键+增量，缺省 0 起算，零 RNG）' },
  { id: 1671, name: 'BOUNDARY_AUTO_ASSIGN_GUIDE_TX', desc: '自动分配策略+引导计数合并写（策略整包替换+三激活计数增量，零 RNG）' },
  { id: 1672, name: 'BOUNDARY_BUILDING_GUIDE_BACKFILL_TX', desc: '建筑建造计数回填（按显示名 max 语义幂等，零 RNG）' },

  // ── 政策开关事务（batch-18b——追加 government.h；政策置位/首月扣费/
  //    激活计数/修炼速率 checkpoint，零 RNG；生产类政策（丹道激励/锻造激励/
  //    灵药培育/灵泉灌溉）经回执标记由 Kotlin 臂触发 checkpointAllProduction
  //    ——CLAUDE.md 6.4/13.3 红线，生产槽位真源在 Kotlin 侧）──
  { id: 1680, name: 'GOV_POLICY_TOGGLE_TX', desc: '政策开关（可负担校验+首月扣费+置位+激活计数+修炼 checkpoint，零 RNG）' },
  { id: 1681, name: 'GOV_OPEN_RECRUITMENT_TOGGLE_TX', desc: '广纳门徒开关（固定费用+付费月戳+激活计数，零 RNG）' },
  { id: 1682, name: 'GOV_SPIRIT_MINE_BOOST_TOGGLE_TX', desc: '灵矿增产开关（免费+激活计数+灵矿结算月戳，零 RNG）' },

  // ── 玉符/宗门升级落账事务族（batch-19——jade_tx.h；运营商城/兑换/升级/
  //    邮件四族中「零 RNG 且物品模板已定」的**落账段**下沉 C++：宗门升级
  //    写回、宗门等级奖励领取落账（凭据类溢出抑制 0 转邮件）、玉符购买
  //    落账（扣减 + 写回，totalCount 由 Kotlin 侧显式重锚同步）。物品随机
  //    生成（EquipmentDatabase.generateRandom 等，C++ data 层仅有静态模板
  //    表无生成器）与广告/支付/网络平台效应留 Kotlin 残差；失败零写入，
  //    Kotlin 回退原路径重执行校验链）──
  { id: 1690, name: 'SECT_LEVEL_UPGRADE_TX', desc: '宗门升级写回（存在性/目标等级校验+玩家宗门 level/levelName 改写，零 RNG）' },
  { id: 1691, name: 'SECT_LEVEL_CLAIM_TX', desc: '宗门等级奖励领取落账（7 天冷却校验+材料/储物袋/灵石入账+领取记录 upsert，凭据类溢出抑制，零 RNG）' },
  { id: 1692, name: 'JADE_PURCHASE_MERCHANT_REFRESH_TX', desc: '玉符购买商人刷新落账（上限校验先于扣款+玉符扣减+刷新次数累加钳制，零 RNG）' },
  { id: 1693, name: 'JADE_PURCHASE_BREAKTHROUGH_BONUS_TX', desc: '玉符购买突破率加成落账（弟子存在/存活/上限校验先于扣款+玉符扣减+statusData 写回，零 RNG）' },

  // ── 秘境平台段读档恢复事务（batch-20a——secret_realm_platform_tx.h；
  //    秘境平台段写者审计收窄批面：start/choose/end 已于 S6 下沉
  //    （SECRET_REALM_START/CHOOSE/END），autoAssign 为纯只读选择器、
  //    pause/resume/renew 为运行时时钟平台残差（S5/S6 口径留 Kotlin），
  //    本段只下沉唯一未下沉写者 continueSecretRealmExploration 的会话域
  //    判定段（到期关闭/死局重置/成员净化），零 RNG 纯确定性变换，
  //    失败零写入 Kotlin 回退原路径）──
  { id: 1710, name: 'SECRET_REALM_CONTINUE_TX', desc: '读档恢复会话域判定（到期关闭 closeSecretRealmByExpiry 状态段/死局 endSession 重置/成员净化写回，零 RNG）' },

  // ── 巡逻 / 住所 / 矿场 / 年俸 UI 操作面事务（batch-12——patrol_tx.h；
  //    ui-read-surface §4.1「巡逻/探索」族写者下沉：GameEngineAtomicAssign
  //    六入口（住所 2 + 巡逻 4）+ GameEnginePatrolOps 四个活写者
  //    （updatePatrolConfig 补位覆写 / updateSpiritMineSlots 整表覆写 /
  //    validateAndFixSpiritMineData 矿场自愈 / updateYearlySalary 年俸）。
  //    **全链零 RNG**（签名级无 RngManager 入参）；事务外残差
  //    （assignmentGate release/confirmAssign、Room 生产槽清理、弟子状态
  //    同步）保留 Kotlin，经 releasedIds/confirmedIds 信封回执驱动；
  //    失败零写入 → Kotlin 回退臂重执行校验链）──
  { id: 1550, name: 'PATROL_ASSIGN_RESIDENCE', desc: '住所分配（释放原 occupant + 跨住所搬迁清旧槽 + name 写回，零 RNG）' },
  { id: 1551, name: 'PATROL_REMOVE_RESIDENCE', desc: '住所移除（空槽无操作，零 RNG）' },
  { id: 1552, name: 'PATROL_ASSIGN', desc: '巡逻分配（释放原 occupant 保留 buildingInstanceId + 清新弟子其它槽位 + 展示字段重建，零 RNG）' },
  { id: 1553, name: 'PATROL_REMOVE', desc: '巡逻移除（空槽无操作；index/buildingInstanceId 保留，零 RNG）' },
  { id: 1554, name: 'PATROL_SWAP', desc: '巡逻交换（同索引无操作；一方为空即移动；两侧 buildingInstanceId 各自保留，零 RNG）' },
  { id: 1555, name: 'PATROL_AUTO_ASSIGN', desc: '批量自动分配（前置校验重复槽/同弟子多槽 + 锁内全量预检 + releasedIds/confirmedIds 回执，零 RNG）' },
  { id: 1556, name: 'PATROL_UPDATE_CONFIG', desc: '巡视配置覆写（补足到 towerIndex 的 PatrolConfig 默认填位 + 就地覆写，零 RNG）' },
  { id: 1557, name: 'PATROL_UPDATE_SPIRIT_MINE_SLOTS', desc: '矿场槽位整表覆写，零 RNG' },
  { id: 1558, name: 'PATROL_FIX_SPIRIT_MINE', desc: '矿场槽位自愈（按灵矿场建筑重建 3 槽：孤儿引用清空 + index 重排 + buildingInstanceId 重锚 + sectId 对齐，零 RNG）' },
  { id: 1559, name: 'PATROL_UPDATE_YEARLY_SALARY', desc: '年俸覆写，零 RNG' },

  // ── 攻宗确定性写回事务（batch-20b——sect_attack_tx.h；ui-read-surface
  //    §4.1「aiSectDisciples 段」+ 战斗域 UI 触发面写者下沉。攻宗战斗执行
  //    覆盖面已在 C++（executeAiBattle / computeCanOccupy /
  //    nativeCheckAttackConditions / discipleToCombatant 实例语义组装），
  //    本段只承 UI 触发面仍 Kotlin 独占的**零 RNG** 写段。
  //    登记不下沉（RNG 红线/协议形状，见 handover §2.51b）：
  //    战利品生成族（Random.Default 非分区随机域）/ occupy·crush 奖励入账
  //    （同一原子事务含奖励段）/ recordSectBattleRecord（与 Kotlin 显示域
  //    battleLogs 同事务，battleLogs 不入 C++ 状态））──
  { id: 1711, name: 'SECT_ATTACK_REMOVE_DEAD_DEFENDERS_TX', desc: 'AI 阵亡守军清理（目标池过滤 + 目标宗门驻军槽清空保留索引，零 RNG）' },
  { id: 1712, name: 'SECT_ATTACK_GRANT_SOUL_POWERS_TX', desc: '胜方存活玩家弟子魂魄 +1（行序 + 存活性过滤，零 RNG）' },

  // ── 残余域补齐（batch-23——lock_beast_tx.h；ui-read-surface §4.1
  //    两处 **AUTHORITATIVE 稳态 Kotlin 直改写者**下沉（写者审计实测
  //    结论，见 handover §2.55）：
  //      · GameEngine.lockBeastView/unlockBeastView（GameEngine.kt:319/324，
  //        launchOnEngine 调用面 = 妖兽详情弹窗开/关事件）→ lockedBeastIds
  //        （NativeGameState 顶层段，@Transient——S-15 独立全量段；
  //        AUTHORITATIVE 月结「锁定妖兽不被 AI 攻击」判据的输入）；
  //      · 设置项域（SettingsDelegate :21/:25/:29/:33/:73/:83 +
  //        AutoAssignDelegate :18/:25/:83/:92/:101/:109/:118 +
  //        InventoryDelegate :157/:177）——17 个 gameData 标量/Int 集字段
  //        经**字段名 → 值通用补丁**单动作覆盖（未知字段 → 失败信封）。
  //    **全链零 RNG**（签名级无 RngManager 入参）；失败零写入 →
  //    Kotlin 回退臂重执行校验链；零 JNI 新导出（nativeExecute 通道）。
  //    证据：自动分配策略族（sectPolicies）走独立入口
  //    batchUpdateAutoAssignAndGuide（batch-18 已下沉 boundary_tx.h），
  //    属 policy 域而非 settings 域，本段不重复承接）──
  { id: 1730, name: 'BEAST_VIEW_LOCK_TX', desc: '妖兽视图锁定/解锁（Set 语义幂等 + 保序剔除 + lockedCount 回执，零 RNG）' },
  { id: 1731, name: 'SETTINGS_PATCH_TX', desc: '设置项字段补丁（17 字段通用：bool 开关 + Int 集，未知字段失败零写入，零 RNG）' },

  // ── 弟子管理残差 confirm 两入口（batch-24——appointment_tx.h 追加事务；
  //    ui-read-surface §4.1「弟子管理」残余最后一项。两入口为**纯数据写**：
  //    灵根替换（校验串合法性 → 覆盖写 → checkpoint）与特质单槽替换
  //    （三态判定 → 替换 + lifespan 同步 → checkpoint）——**零玉符、零 RNG**
  //    （与 batch-15 已下沉的 roll 族分离：roll 扣玉符+抽取在 1613/1615/1616，
  //    confirm 只做数据落地）。失败零写入 → Kotlin 回退臂重执行校验链
  //    （用户可见文案由 Kotlin 臂产出：弟子不存在/已死亡/该特质已不存在））──
  { id: 1732, name: 'SPIRIT_ROOT_WASH_CONFIRM_TX', desc: '洗炼灵根确认替换（元素串合法性 → 覆写 → checkpoint，零 RNG/零玉符）' },
  { id: 1733, name: 'TRAIT_WASH_CONFIRM_TX', desc: '特质单槽确认替换（三态判定 → 替换 + lifespan 同步 + checkpoint，零 RNG/零玉符）' },

  // ── 开袋抽签事务（ADR rng-determinism-remediation 阶段 1①——
  //    ui-read-surface §4.3 残余域「库存开袋」收口）──
  //    语义：C++ 只承担**抽签**（消费 EXPLORATION 分区产出确定性抽取描述符序列
  //    count + kind[i]），模板选择与入库事务留 Kotlin——原因是 13.3 红线要求
  //    物品发放必须经 InventorySystem.addXxx 统一入口（StackableItemStore 自动
  //    合并 + withTrackingSource 年度报告 + 溢出转邮件），C++ 侧无该原语；
  //    且表模板库在 Kotlin 注册表（EquipmentDatabase/ManualDatabase/
  //    ItemDatabase/HerbDatabase），C++ 不可复刻（路线 B 先例）。
  //    ⇒ 抽签序（RNG 消费序）归 C++ 真相源、产出可复现；Kotlin 据描述符物化入库。──
  { id: 1734, name: 'STORAGE_BAG_OPEN_TX', desc: '开袋抽签（EXPLORATION 分区产出 count + kind 描述符序列，模板物化留 Kotlin）' },
];


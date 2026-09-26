# TASKBOOK-G13 · 数值落地（M0 杠杆回填 · 突破补偿 · 回血参数 · 经济复测 · 星级乘区终值）

> **本文件是 G13 的派工真源**，取代 `docs/design/character-gacha-implementation.md` §G13（`:210-214`）的粗口径。
> **时点**：2026-09-26；依赖 **G00（白皮书）+ G09 + G10**（G10 已重录基线 ⇒ 本批任何数值改动都要**重新评估是否新增红**）。
> **侦察方式**：1 个只读子代理穷举十个杠杆的「配置键 → 值 → 落点 → 是否已生效」＋公式位置＋守卫覆盖；主线程抽核。
> **纪律**：引用写全路径；代码片段已过项目红线；**改数值 = 改行为 ⇒ 必须跑对拍相关门禁**。

---

## 0. 关键前提（本批与前几批不同：**真实增量很小**）

白皮书 §3 的十个杠杆里 **①–⑧ 已全部落配置且已生效**（三向/两向守卫在册），**⑨⑩ 是唯一未决项**；
因此本批的真实工作量 = **⑨⑩ 的终局处置 ＋ 星级乘区一致性收口 ＋ 经济复测 ＋ 文档数值修正**，
**不是**重做数值表。下表为实测现状（配置键 → 值 → 落点 → 生效）：

| 杠杆 | 配置键 | 值 | 落点（全路径） | 已生效？ |
|---|---|---|---|---|
| ① 碎片门槛 | `gachaPools[0].fragmentsPerStar` / `GameConfig.Gacha.FRAGMENTS_PER_STAR` / C++ `kFragmentsPerStar` | 100 ×3 | `scripts/data/gacha_config_sample.json:49`、`android/core/domain/src/main/java/com/xianxia/sect/core/GameConfig.kt:222`、`.../gamecore/include/gamecore/system/gacha_fragment.h` | ✅ 三向守卫 |
| ② 保底碎片量 | `pity.fragmentCount` / `PITY_FRAGMENT_COUNT` | 5 ×2 | `gacha_config_sample.json:46`、`GameConfig.kt:226` | ✅ |
| ③ 保底归属 | `pity.pickMode` | `"random"` | `gacha_config_sample.json:47`、`.../domain/gacha/GachaPoolConfig.kt`、`GachaOddsPanel.kt:142` | ✅ |
| ④ 类别概率 | `categories[].weightPct` | 11+10+26+26+27 = 100 | `gacha_config_sample.json:8-37` | ✅ 和守卫 |
| ⑤ 开局送碎片 | `gachaDefaults.startBonusFragments` | 0 | `gacha_config_sample.json:105` | ✅ 守卫钉死 =0 |
| ⑥ 初始灵石 | `gachaDefaults.startSpiritStones` / `START_SPIRIT_STONES` | 50000 ×2 | `gacha_config_sample.json:104`、`GameConfig.kt:237` | ✅ |
| ⑦ 星级战斗乘区 | `starBattlePctPerStar` / `STAR_BATTLE_PCT_PER_STAR` / C++ `kStarBattlePctPerStar` | 0.08 ×3 | `gacha_config_sample.json:107`、`GameConfig.kt:238`、`.../system/star_zone.h:34` | ✅ 三向守卫 |
| ⑧ 星级修炼乘区 | `starCultPctPerStar` / `STAR_CULT_PCT_PER_STAR` / C++ `kStarCultPctPerStar` | 0.05 ×3 | `gacha_config_sample.json:108`、`GameConfig.kt:239`、`star_zone.h:36` | ✅ |
| ⑨ **突破补偿** | `gachaDefaults.breakthroughCompBonus` / `BREAKTHROUGH_COMP_BONUS` | 0.02 ×2 | `gacha_config_sample.json:111`、`GameConfig.kt:241` | ❌ **零消费点**（仅守卫同值断言）⇒ **悬空键** |
| ⑩ **重伤回血** | `gachaDefaults.injuryHealPctPerPhase` / `INJURY_HEAL_PCT_PER_PHASE` | 0.2 ×2 | `gacha_config_sample.json:110`、`GameConfig.kt:240` | ⚠️ **零消费点**；口径已由既有机制等价实现（`GameConfig.Cultivation.PHASE_HP_MP_RECOVERY_RATE = 0.2`、C++ `disciple_stats.h:44 kPhaseHpMpRecoveryRate = 0.2`）⇒ **悬空键** |

---

## 1. 目标与验收判据

| 项 | 内容 |
|---|---|
| 目标 | 把白皮书 §3 十个杠杆**逐条钉成终局**（不再留悬空键）；星级乘区四处口径**加可判据守卫**；产出**经济复测表**；修掉白皮书与经济相关的**数值口径错** |
| 验收① | 白皮书 §3 杠杆表的「采纳/不采纳」列**十项全部有勾**，且每一项在任务书里能指向"已落配置/已删键/已实现"三者之一 |
| 验收② | ⑨⑩ 两个悬空键**各自二选一定死**（删键单源化 / 实现分叉），**不留第三种状态**；删键时同步删守卫行并跑绿 |
| 验收③ | 白皮书 `m0-economic-whitepaper.md:143-144` 的「采纳口径 B」改为**口径 A**（`1★` 基线），与经济表口径一致 |
| 验收④ | 经济复测表落到白皮书内（修订版，不新建文件），含 5 项：月收入曲线 / 寻访 sink 占比 / 五条已删 sink 的影响列 / 第二角色时刻 / 物品四阶池的材料注入期望 |
| 验收⑤ | 星级乘区四处（配置 / `GameConfig` / C++ 常量 / Kotlin 实现）**加「Kotlin 实现不得改写为字面量」的源码扫描守卫**；`StarZone.kt` 仍引用 `GameConfig` |
| 验收⑥ | 若本批**动了任何判定行为**（如 clamp 上限、回血分叉）：`ctest` **全绿**或红集 ⊆ B 类登记，且 `ctest -R "Determinism|SceneEquivalence|Breakthrough"` 逐条有据；否则报告须写明「本批零行为改动」 |
| 验收⑦ | 全量 JUnit ＋ detekt 六模块 0 ＋ lint 36 警告 0 error；`check-agent-instructions` 通过 |
| 验收⑧ | 双 changelog ＋ `report-G13.md` ＋ 单次提交 |
| 不做 | 不改 `gacha_tx.h` 抽卡算法；不重录金黄（G10 已做；本批若产生新红须先定性再决定）；不改版本号；不新增字段（除产品要求的） |

---

## 2. 任务 A：⑨ 突破补偿的终局处置

**事实**：产品/白皮书 §杠杆⑨ 的形态是「跨大境界突破成功 **+2pp**，上限 `c 0..0.95`」（`m0-economic-whitepaper.md:193`、`:114`）；
**用户已在 G09 拍板 P-6 = 不补**（`TASKBOOK-G09.md` §7.1、`ACCEPTANCE-G09.md` §4）：

> 因此 ① 白皮书 §3 杠杆表 ⑨ 勾 **「不采纳」**；② `gachaDefaults.breakthroughCompBonus` 与
> `GameConfig.Gacha.BREAKTHROUGH_COMP_BONUS` **删除**（含守卫同值断言行），并在白皮书 §3 备注「终局=不补，键已移除」。
> ⚠️ 若产品反悔要补，改动面 = ① `BreakthroughZones` 加第 5 项（`android/core/engine/.../domain/disciple/DiscipleStatCalculator.kt` 的 `BreakthroughZones`）
> ② `computeBreakthroughZones` 只对**跨大境界**分支注入 ③ 上限常量单源化 ④ C++ `BreakthroughChanceInput` 同名同参 ⑤ 双端守卫扩例。

**当前突破率实现面（供将来参考，本批不改）**：
`android/core/engine/src/main/java/com/xianxia/sect/core/engine/domain/disciple/DiscipleStatCalculator突破Ops5.kt`
（`base = baseZone × (1 + elderGuidance + selfBonus)`，`clamp(0, 1.0)`——**上限是 1.0，不是 0.95**）；
基础表 `GameConfig.kt:371-401`；C++ `.../system/breakthrough.h`、`disciple.h:87`。

---

## 3. 任务 B：⑩ 回血参数的终局处置（**二选一，推荐 ①**）

| 选项 | 做法 | 代价 |
|---|---|---|
| **①（推荐）认定重复键** | 删除 `gachaDefaults.injuryHealPctPerPhase` + `INJURY_HEAL_PCT_PER_PHASE` + 守卫行；回血**单源**留在 `GameConfig.Cultivation.PHASE_HP_MP_RECOVERY_RATE = 0.2`（G07 明写"未新增配置项"） | 一处删键 + 一行守卫；白皮书 §3 杠杆 ⑩ 勾「沿用既有机制」 |
| ② 认定重伤兜底档 | 在 `android/core/engine/.../service/HpMpRecoveryService.kt` 显式实现「`hp<max` 时按 `maxHp × 20%/旬`」并加守卫断言两常量关系 | 改行为 ⇒ 必跑 `-R Determinism`/`Diff*`；且与既有机制语义重叠 |

**判据**：无论选哪条，**测试里不得再出现"配置键存在但无人消费"**；白皮书 ⑩ 必须有勾。

---

## 4. 任务 C：星级乘区一致性收口

| 处 | 现状 | 本批动作 |
|---|---|---|
| 配置 `gacha_config_sample.json:107-108` | 0.08 / 0.05 | 不动（已三向） |
| `GameConfig.kt:238-239` | 0.08 / 0.05 | 不动 |
| C++ `star_zone.h:34,36` | 0.08 / 0.05 | 不动 |
| Kotlin 实现 `android/core/domain/src/main/java/com/xianxia/sect/core/model/StarZone.kt:29,33` | **引用 `GameConfig` 派生**（无字面量）⇒ 恒定一致 | 🔴 **缺口**：有人把它改成字面量 `0.08` 时**守卫不会红** ⇒ 加**源码扫描守卫**：断言 `StarZone.kt` 内**不含** `0.08`/`0.05` 字面量、且引用 `GameConfig.Gacha.STAR_*` |
| 环大小 `historyRingSize=50` | 三向已通 | 补一条：环截断处（`android/core/engine/.../domain/gacha/GachaService.kt` 的 `take(...)`）**引用常量而非字面量 50** 的源码扫描断言 |

---

## 5. 任务 D：经济复测（产出 = 白皮书内修订版经济表）

- 口径真源：`m0-economic-whitepaper.md:88-96`（删掉广纳门徒/血炼池/忠诚政策/偷盗四条 sink 后的源汇修订）＋ `:96` 闭环判定（寻访 sink 应吞掉自然月入 **60–100%**）。
- 风险登记：`docs/character-gacha-redesign-2026-09-23.md:571`「广纳门徒删除后经济表过期 ⇒ M0 白皮书显式核对」。
- **必含 5 项**：① 月收入曲线（灵石/月）② 寻访 sink 占比（月入 ÷ 5000 × 100%）③ 五条已删 sink 的逐项影响列 ④ 第二角色时刻（周/月）⑤ 四阶物品池的材料注入期望。
- 🔴 **前置**：先做任务 §6 的口径修正，否则整表按错乘区算。

---

## 6. 任务 E：文档数值口径修正

| 文件 | 行 | 现状 | 目标 |
|---|---|---|---|
| `docs/design/gacha-batches/m0-economic-whitepaper.md` | `:143-144` | 正文写「采纳建议：口径 B（star×8%/×5%）」 | 改为**口径 A**（`1★` 基线、5★ +32%/+20%），与 `star_zone.h` / `StarZone.kt` / G09 拍板一致 |
| 同上 | `:114-115` | 杠杆 ⑨⑩ 两列皆空 | 按 §2/§3 结论补勾 |
| 同上 | `:133-141` | §4.1 表格用口径 A、正文结论写 B（自相矛盾） | 统一为 A，正文与该表一致 |

---

## 7. 决策（D-1…D-5）

| # | 决策 | 依据 |
|---|---|---|
| **D-1** | ⑨ **不补** ⇒ 白皮书勾「不采纳」＋**删两个悬空常量** | 用户 G09 P-6 已拍板；留悬空键违反单源化 |
| **D-2** | ⑩ **认定重复键** ⇒ 删 `injuryHealPctPerPhase` 两处，单源留 `Cultivation.PHASE_HP_MP_RECOVERY_RATE` | G07 明写"未新增回血机制/未新增配置项"；语义重复 |
| **D-3** | 星级乘区加**实现面源码扫描守卫**（禁字面量） | §4 的缺口 |
| **D-4** | 经济复测**写进白皮书**（不新建文件） | 白皮书是 M0 单一真源；避免文档分裂 |
| **D-5** | 🔴 删除常量会触碰 `android/core/engine/src/test/java/com/xianxia/sect/core/nativebridge/CharacterTemplateGuardTest.kt`（三向断言所在）⇒ **与 G12 串行**（G12 也可能改它） | 共享编辑面；并行会互撞 |

---

## 8. 文件面与切片（全路径）

| 片 | 允许改 |
|---|---|
| **A-13a** 杠杆终局 | `scripts/data/gacha_config_sample.json`、`android/core/domain/src/main/java/com/xianxia/sect/core/GameConfig.kt`、`android/core/engine/src/test/java/com/xianxia/sect/core/nativebridge/CharacterTemplateGuardTest.kt` |
| **A-13b** 守卫加严 | `CharacterTemplateGuardTest.kt`（星级实现面源码扫描）、`android/core/engine/src/test/.../nativebridge/GachaConfigGuardTest.kt`（如需） |
| **D-13c** 白皮书 | `docs/design/gacha-batches/m0-economic-whitepaper.md`（口径修正 + 杠杆勾选 + 经济复测表） |
| **主线程** | `assets/data/game-data.json` 由生成器重跑（**禁手改产物**，见 `rules`：改中性源 → `node scripts/gen-game-data.mjs` → 记新 sha256 → `--check` 复验）、双 changelog、`report-G13.md`、门禁、提交 |

⚠️ 改 `gacha_config_sample.json` 会**改变 `game-data.json` 的 sha256**（G01 起一直稳定在 `035066cb…`）⇒
报告必须记**新 sha256** 并说明「改中性源导致，非漂移」。

---

## 9. 门禁清单

```powershell
# 0) 改中性源后必须重跑生成器并记录新 sha256
node scripts/gen-game-data.mjs ; node scripts/gen-game-data.mjs --check
# 1) 桌面（只有动了 C++ 常量或判定行为才必跑；判据 = 命令输出原文）
#    <repo>\android\app\src\main\cpp\gamecore\build\desktop-test
cmake --build . ; ctest
ctest -R "Determinism|SceneEquivalence|Breakthrough"
# 2) JNI（C++ 有实质改动才重建；mtime + 体积 + sha256 三件套）
pwsh -File scripts/build-desktop-jni.ps1
# 3) Kotlin 组合门（工作目录 android；判别力复跑必须 --rerun-tasks）
.\gradlew.bat compileReleaseKotlin testReleaseUnitTest --max-workers=1 --rerun-tasks --continue `
  "-Dgamecore.jni.path=<repo>\android\core\engine\build\desktop-jni\libgamecorejni.so" detekt lintRelease --console=plain
# 4) node 门
node scripts/gen-action-ids.mjs ; node scripts/check-jni-count.mjs ; node scripts/check-agent-instructions.mjs
```

---

## 10. 登记 / 待拍板

1. 🟠 **若产品要恢复 ⑨ 突破补偿**：按 §2 引用的五步改动面实施（含 clamp 上限 1.0 → 0.95），并复跑 `-R Breakthrough` 与全部 `Diff*`。
2. 🟠 经济表若显示寻访 sink 过高/过低 ⇒ 属**数值杠杆**（白皮书 §3 的 ①②③④ 任一），可按 D-5 同形改配置，**不动结构**。
3. 🟡 M0 白皮书其余章节是否还有与已拍板口径冲突的表述（本批只修 §3/§4.1 已知三处，其余顺带扫描并登记）。

---

## 11. 一句话给执行者

**G13 的增量只有四件：把白皮书 §3 十个杠杆勾完（①–⑧ 已生效、⑨ 按用户拍板「不补」并删悬空常量、⑩ 认定重复键并删）、
给星级乘区补「Kotlin 实现不得写字面量」的源码扫描守卫、把经济复测表写进白皮书（含 5 项）、把白皮书里口径 B 的表述改成已拍板的 A；
🔴 删常量会碰 `CharacterTemplateGuardTest.kt` —— 必须与 G12 串行；改中性源会改 `game-data.json` 的 sha256，报告要记新值。**

# 角色卡池重构 · 实施计划（G 批）

> 性质：**可执行实施计划**（产品方案权威见 [character-gacha-redesign-2026-09-23.md](character-gacha-redesign-2026-09-23.md) v1.4，下称「产品方案」；架构约束 = 产品方案 §15 + [architecture.md](architecture.md) + [CODE_WIKI.md](../CODE_WIKI.md)）。
> 日期：2026-09-23
> 结构对齐 CLAUDE.md「设计方案规则」+ 仓库 `docs/parallel-batches-w*` 分批协议。
> **总批次数：15（M0×1 案头 + M1×11 代码 + M2×3 代码）。M3 不在本计划。**

---

## Report

（交付时回填：建了什么 / 验证命令与结果 / Journey log ≤5 条）

---

## [S1] Problem

旧「弟子群体模拟」无角色定义层、无卡池；免费招募链与寿命/忠诚等机制交织，无法承载米哈游式具名角色收集体验。产品方案 v1.4 已拍板 Q1–Q31（碎片制混池、删招募/逐出/洗炼/改名、不可战死、开局周明+5 万、结果页 Q30/Q31 等），缺一份**可按批合入、可独立验收**的实施拆解。

**成功标准（总验收）**：

1. 全量 GTest 绿（基线 + 删除后 RNG 对拍重录）；`:core:engine` / 六模块 detekt / lintRelease 绿。
2. 旧档读档零报错；`recruitList` 迁移后恒空；proto 新字段 slot 隔离正确。
3. 寻访 → 碎片 → 解锁/升星 → `finalStats` 双端一致；概率守卫与公示页同源。
4. 招募/逐出/洗炼/改名/玩家侧 `markDead` 生产路径 grep 清零（UI 死链同步）。
5. `MirrorReadOnly`：抽卡无新增 Kotlin→C++ 反向写；ActionId 经 `gen-action-ids.mjs` 五件套同批提交。
6. 双 changelog + `CODE_WIKI.md` + 必要 architecture 段回写完成。

---

## [S2] Design

### S2.1 批次总览（回答「分几批」）

| 期 | 批 ID | 名称 | 类型 | 依赖 | 可并行组 |
|---|---|---|---|---|---|
| **M0** | **G00** | 数值白皮书与杠杆拍板 | 案头 | 产品方案 v1.4 | 可与 G01 并行起草，**数值表冻结前不锁死 G09 终值** |
| **M1** | **G01** | 脚手架：配置/协议/域骨架/ActionId 预留 | 代码 | — | 组 S |
| M1 | **G02** | 删除：寿命·年龄·忠诚·叛逃·偷盗·神魂 | 代码 | G01（字段协议已有则可并行） | 组 A |
| M1 | **G03** | 删除：生育·道侣·亲缘（parentId 全链） | 代码 | G01 | 组 A |
| M1 | **G04** | 删除：灵根/特质洗炼·资质·悟性 + 修炼乘区收口 | 代码 | G01 | 组 A |
| M1 | **G05** | 删除：招募链 + 广纳门徒/招贤/天书 + recruitList 迁移 | 代码 | G01 | 组 A |
| M1 | **G06** | 删除：逐出弟子 + 弟子改名系统 | 代码 | G01 | 组 A |
| M1 | **G07** | 玩家侧战死 → 重伤 + 旬结回血 | 代码 | G01；建议在 G02 后（死亡链与寿命删除相邻） | 组 A′（G02 后） |
| M1 | **G08** | 角色模板层 + templateId 实例化 + 开局周明/5 万 + 兑换码改道 | 代码 | G01；构造路径与 G05 招募删除交汇处需串行合入顺序协调 | 组 B |
| M1 | **G09** | 抽卡核心权威 `gacha_tx`（roll/保底/碎片/升星/解锁/入库） | 代码 | G01, G08（解锁依赖模板）；数值默认可先用方案表，G00 终值回填配置 | 组 C |
| M1 | **G10** | RNG 对拍基线重录 + 全量回归收口 + 死代码 grep | 代码/门禁 | **G02–G07 全部合入后**；G08/G09 建议同入基线窗口 | 组 D（串行） |
| M1 | **G11** | 最简寻访 UI：主界面 + 结果页(Q30/Q31) + 图鉴最小 + GachaDelegate | 代码 | G09 | 组 E |
| **M2** | **G12** | 体验完成：历史·公示·图鉴完整·流光/引导·重伤文案 | 代码 | G11 | 组 F |
| M2 | **G13** | 数值落地：突破补偿/回血参数/M0 杠杆回填 + 经济复测 | 代码/案头 | G00, G09, G10 | 组 F |
| M2 | **G14** | 文档与发布收口：双 changelog、CODE_WIKI、architecture、验收报告 | 文档 | G11–G13 | 组 G |

**总批次数 = 15。**

**建议合入顺序（关键路径）**：

```
G01 → ┬─ G02/G03/G04/G05/G06 ─┬─ G07 → G10 → G11 → G12 ─┬─ G14
       │                      │                           ├─ G13 →
       └─ G08 → G09 ──────────┘（G09 须在 G10 基线窗口前或同窗进入）
G00 ──────────────────────────────（尽早完成，回填 G09/G13 配置）
```

- **组 A 可并行**（不同删除域、不同主文件为主；共享 `models.h`/`Disciple.kt` 时按字段分区 commit，冲突以行为序合并）。
- **G10 串行**：RNG 序列因删除平移，**只允许重录一次**（招募+生育+其他删除合并进同一基线窗口）。
- 与 SR / 地图 S 系：**独立分支、对表串行**（产品方案原则 5）。

### S2.2 每批统一交付形态（对齐 w3 协议）

每批至少：

1. **分类表**：受影响写入点 → ①搬迁 / ②删除或只读 / ③命令进 C++、回执出 C++（仅删除批适用简化版：直接列删除面）。
2. **Commit 切分**：`feat|refactor|chore(gacha|disciple|…)` 中文说明；**共享五件套**（`gen-action-ids.mjs` / `action_ids.h` / `ActionIds.kt` / `execute_dispatch.cpp` / `test/CMakeLists.txt`）同批同 commit。
3. **测试**：新增/修改单测列出；删除批给「旧用例处置表」（删/改断言/保留）。
4. **门禁**：桌面 GTest + `:core:engine:testReleaseUnitTest --max-workers=1` + detekt（该批触及模块）；涉及 NDK 时 arm64。
5. **交付物**：`docs/design/gacha-batches/report-Gxx.md`（简短：做了什么/验证/未完成）。
6. **不中途停**：无封锁性问题不询问；遇产品歧义回产品方案 §12，不自行改拍板。

### S2.3 架构硬约束（每批红线，摘自产品方案 §15）

- 🔴 C++ AUTHORITATIVE：抽卡/碎片/升星/开局/重伤写入走 native 事务 → `applyDirtyFromNative`；**禁止** Kotlin `update` 稳态写抽卡结果；`MirrorReadOnlyGuardTest` 零命中。
- 🔴 新动作只经 `scripts/gen-action-ids.mjs`；**不新增 JNI 导出**；ActionId 只增不复用（含改名 1740 留洞）。
- 🔴 落位：`domain/gacha/GachaFacade` + `GachaDelegate` + `system/gacha_tx.h`；禁止塞进 `DiscipleFacade` 滚雪球。
- 🔴 数据结构：碎片/星 = `map<templateId,…>`（或平行数组+守卫）；保底 = `map<poolId,pity>`；**禁止 6×int、禁止全局单保底**。
- 🔴 物品掉落：`InventorySystem.addXxx` + `withTrackingSource("仙缘寻访")` + 来源名登记 + 发放类溢出邮件。
- 🔴 星级/资质删除后的修炼与战斗：命名 Zone，双端 `finalStats` 对拍；禁魔法数字。
- 🔴 UI 不直写 Store；不进 `GameSystem` 月/年回调；不新开反向通道。
- 🔴 测试必须 `--max-workers=1`；detekt baseline 只缩不增。
- 🔴 每批触达功能面则同步评估 changelog（最终 G14 保证双 changelog 必齐；M1 中途可只记技术 CHANGELOG 草稿，**G14 为硬门**）。

### S2.4 各批范围明细

#### G00 · M0 数值白皮书（案头）

**交付**：`docs/design/gacha-batches/m0-economic-whitepaper.md`

- 开局 50,000 耗尽后的收入曲线；第二角色时刻；0.71 碎片/抽复核。
- 杠杆逐项拍板建议：碎片门槛 / 保底量 / 保底自选 / 类别概率 / 开局送 99 碎片（默认不送）。
- 资质乘区删除后的修炼速度重校准表（单根 vs 双根）。
- 星级 +8%/+5% 终值（或维持 strawman 的书面确认）。
- 重伤旬结回血参数；突破补偿（寿元增益删除后）。
- 广纳门徒 sink 删除后的经济表修订。
- **输出配置键名清单** → G09/G13 直接消费。

**验收**：白皮书入 docs；杠杆表有「采纳/不采纳」列可勾选。

#### G01 · 脚手架

- `game-data.json`（及中立源+生成器，若静态走 codegen）：`gachaPools[]`（poolId=standard、类别权重、品阶表、保底、单价 5000）、`characterTemplates[]`（6 角色：id/姓名/灵根配置/特质预设/立绘键）。
- GameData/GameState：碎片 map、星 map、pity map、历史环缓冲 50 —— **协议字段先落**（`@ProtoNumber` 只增），GC_FROM 默认空。
- `domain/gacha/` 空 Facade + Impl + `GachaService` 骨架；`GachaDelegate` 空壳。
- ActionId 目录预分配 gacha 段（具体号段以 gen 脚本实跑为准，禁止手填过期值）。
- 色表 Q31 单源常量/中立 JSON + 单测断言六阶/五灵根色值。
- 守卫测试壳：池权重和、品阶和、模板 id 集合。

**验收**：编译绿；新字段空档读写往返；guard 壳在配置完整前允许 `assumeTrue` 跳过并注明 G09 启用。

#### G02 · 删寿命/年龄/忠诚/叛逃/偷盗/神魂

按产品方案 §5、§6.1–6.4 删除面表逐项；三端字段链 `models.h → DiscipleColumn → … → Disciple.kt → Room migration`；UI 摘行；政策忠诚类下架；偷盗道德降风味。

**验收**：字段 grep 无生产引用；旧测试改删绿；migration 测试过。

#### G03 · 删生育/道侣/亲缘

§6.5 全表：`child_birth.h` 整文件、配对、婚姻事务（ActionId 留洞）、亲缘 7 字段、哀悼、parentId 全仓清点、AI 宗 name_service 改接、RNG 平移登记（**G10 重录**）。

**验收**：`parentId`/`child_birth` 生产零引用；旧档亲缘 GC 清空幂等。

#### G04 · 删洗炼+资质+悟性

§6.6 / §6.6.1：`SPIRIT_ROOT_WASH_TX`、`appointment_tx` 洗炼分支、资质/悟性字段与 `(1+aptitude)` 乘区、属性丹目标、UI；**特质数据表只读保留**；职位特质若走随机重 roll 则删该路径。

**验收**：修炼公式无 aptitude；`CultivationSpeedZones` 字段与双端测试更新；洗炼 UI/grep 零。

#### G05 · 删招募链

§6.8 全表：RecruitDialog/RecruitService/年结刷新/广纳门徒/招贤/天书效果/autoRecruit*/recruitList 读档清空/`RecruitListCleanupRule` 恒空/`ai_sect_recruit` 自动招募钩子摘除/年报计数改道预留（G08 定「解锁入宗计入」）。

**入口**：招募按钮本批改为**打开占位「寻访未就绪」或直接指向空寻访路由**（避免死按钮）；完整寻访在 G11。

**验收**：生产无 recruit 事务；旧档 recruitList 空；RNG 平移登记 G10。

#### G06 · 删逐出+改名

§5.3、§6.9 改名表：`expelDisciple` 全链；弟子改名 UI/Delegate/`renameDisciple`/`DISCIPLE_OP_RENAME`/`renameDiscipleTx`；**宗门改名保留**；没收/执法保留。

**验收**：grep 清零；详情页无按钮；宗门改名回归绿。

#### G07 · 重伤

§6.9：玩家侧 `markDead` 调用点改重伤（HP=1、isAlive 不变）；旬结回血（若无现成则补，参数配置）；AI/妖兽可死不动；`hasReviveEffect` 玩家侧停读写；UI 重伤文案（最小：HP=1 派生）。

**验收**：宗门战/探索败北不出现玩家尸体行；GTest death_handler 玩家语义更新；回血单测。

#### G08 · 模板层与开局

§4.3/4.5/6.10：`DiscipleCreationSeed.templateId`；主工厂/Kotlin 臂/兑换码收敛单点；**兑换码不直造弟子**；6 模板特质/灵根/立绘；开局：新档实例化周明（碎片账本 100 等效）+ 灵石 50,000；禁改名已在 G06。

**验收**：新档名册仅 6 模板可能 + 存量旧档保留；周明限持；兑换码发角色=碎片入账。

#### G09 · 抽卡核心

§4.1–4.2 + §8 + §15：`gacha_tx` 单抽/十连语义（先 roll 后 pity++，满 10 发保底×5 再清零）；两级物品 roll；`InventorySystem` 入库；碎片入账 `addFragment` 单函数；跨星自动升星；解锁触发模板实例化（限持 1）；灵石扣费与不足拒绝；结果 DTO 供 UI。

**ActionId**：`GACHA_PULL_ONCE` / `GACHA_PULL_TEN` /（开局注入可并入新档导入路径而非 ActionId）。

**验收**：GTest 概率结构守卫 + 保底边界（count 8 十连两次保底等）+ 入库 guard + 镜像只读；**不**依赖 UI。

#### G10 · 对拍基线与回归收口

- 确认 G02–G07（及已合入的 G08/G09）全部就位。
- 重录月/年/旬 Diff 对拍基线与 fixture（生育/招募删除平移）。
- 全量 GTest + engine JUnit + detekt + lint；死代码 grep 清单归零表。
- 修复回归：**分 commit、绿一块挪一块**。

**验收**：产品方案 §11 M1 验收口径（除 UI 完成项）全绿。

#### G11 · 最简寻访 UI

§4.4 Q30/Q31 + §9：招募按钮 → 寻访主界面（池信息、一次/十次、x/10、灵石不足禁用）；结果页（2×5、流光色表、右下白字数量、单抽居中、点外关闭、双按钮连抽）；图鉴 6 格最小（进度+星级）；升星全屏层；`GachaDelegate` 接 Facade；DialogType + 系统栏 guard。

**素材**：头像精灵注册 `SpriteResRegistry` 双模块 WebP；禁 PNG。

**验收**：真机/模拟器抽卡→结果页→再抽→关闭；守卫不红。

#### G12 · 体验完成

历史 50 条、概率公示同源页、图鉴完整态、流光低端降级、引导「打开寻访」、重伤/逐出移除后文案扫尾、结果页连抽体验打磨。

**验收**：产品方案 M2 验收「抽卡→解锁→养成全链真机走通」。

#### G13 · 数值落地

G00 终值写入配置；突破补偿；回血参数；经济复测（广纳门徒删除后）；星级乘区终值双端；必要时调 G09 配置表 **不动结构**。

**验收**：白皮书勾选项已落配置；对拍仍绿（改配置不改 RNG 结构则跑相关 Diff）。

#### G14 · 文档收口

- `changelog_entries.json`（玩家向）+ 根 `CHANGELOG.md`（技术向）。
- `CODE_WIKI.md`：Facade 列表 +8、Delegate、ActionId 段、删除域。
- `docs/architecture.md`：若修炼 Zones/结算列表表述过期则改。
- 本实施文档 Report 回填；`report-G14-completion.md`。
- 死代码与文档引用一致性扫尾。

**验收**：双 changelog 有条目；CODE_WIKI 与代码一致；AGENTS 文档门禁（引用可解析）。

---

## [S3] Out of Scope

- M3：轮换池实体、UP、第 7 角色、元素克制、皮肤池、通胀治理、付费点、远程埋点实现、RemoteConfig 绑定、广告送抽。
- 图鉴收集奖励、具名改名回退、差额抽卡、语音/Live2D/抽卡动画。
- 修复 `manualMasteries` 双真相、`DiscipleCompact` 寿命列（登记债，不随本计划偿还——G14 可链到 §14.5）。
- 与 SR/地图批次的合流改造（仅对表协调，不并批）。
- 自动存档（产品已禁）；AI 宗抽卡化。

---

## Tasks

> 每条 = 可独立验收的最小工作项；`covers` 指向设计节。状态勾选在交付时更新。

- [ ] **T00**: 完成 M0 白皮书并输出配置键名清单 — acceptance: `m0-economic-whitepaper.md` 入库，杠杆表可勾选 (covers: S2.4 G00; 产品方案 §7/§11)
- [ ] **T01**: G01 脚手架合入 — acceptance: 配置+协议字段+域骨架+色表单测绿 (covers: S2.4 G01; §15.2/15.3)
- [ ] **T02**: G02 寿命忠诚等删除 — acceptance: 字段与 UI 零生产引用，migration 绿 (covers: §5 §6.1–6.4)
- [ ] **T03**: G03 生育亲缘删除 — acceptance: child_birth/parentId 清零，旧档亲缘清空 (covers: §6.5)
- [ ] **T04**: G04 洗炼资质悟性删除 — acceptance: 无 aptitude 乘区，洗炼 grep 零 (covers: §6.6 §6.6.1)
- [ ] **T05**: G05 招募链删除 + recruitList 迁移 — acceptance: 招募生产链零，旧档列表空 (covers: §6.8; depends: T01)
- [ ] **T06**: G06 逐出+改名删除 — acceptance: expel/rename 链零，宗门改名仍绿 (covers: §5.3 §6.9; depends: T01)
- [ ] **T07**: G07 重伤+回血 — acceptance: 玩家败北 HP=1 存活，旬回血单测绿 (covers: §6.9; depends: T02)
- [ ] **T08**: G08 模板+开局+兑换码改道 — acceptance: 新档周明+50000，无直造弟子旁路 (covers: §4.3 §4.5 §6.10; depends: T01)
- [ ] **T09**: G09 gacha_tx 核心 — acceptance: GTest 保底/权重/入库/升星绿，镜像只读 (covers: §4.1–4.2 §8 §15; depends: T08)
- [ ] **T10**: G10 基线重录+全量回归 — acceptance: Diff 基线重录完成，死代码 grep 表归零 (covers: §8.5 §11 M1; depends: T02 T03 T04 T05 T06 T07 T08 T09)
- [ ] **T11**: G11 最简寻访+结果页+图鉴 — acceptance: 真机十连流程通，Q30/Q31 视觉项核对表勾完 (covers: §4.4 §9; depends: T09)
- [ ] **T12**: G12 历史公示引导等 — acceptance: 产品方案 M2 验收口径满足 (covers: §4.4 §9 §11 M2; depends: T11)
- [ ] **T13**: G13 数值回填与经济复测 — acceptance: G00 采纳项进配置，相关测试绿 (covers: §7 §11; depends: T00 T10 T09)
- [ ] **T14**: G14 双 changelog+CODE_WIKI+验收报告 — acceptance: 文档门禁过，Report 回填 (covers: §8.15 §15.7; depends: T12 T13)

依赖无环；T02–T06 可并行；T07 依赖 T02；T10 为删除批汇合点。

---

## 兼容性分析

| 面 | 策略 |
|---|---|
| 存档 proto | 新字段只增 `@ProtoNumber`；旧档 map 空/保底默认 `standard`；GC_FROM 宽松；不动 SR migrator 链 |
| 旧档 recruitList | G05 读档整表清空幂等 |
| 旧档程序化弟子 | 保留；改名/逐出消失后行为仍合法；亦不可战死（G07） |
| 字段删除 | 三端同步 + bijection；禁 DROP COLUMN 列策略按 rules/database-migration |
| Room | 版本递增 + 迁移测试；slot_id 隔离 + resetForSlot |
| 云体积 | G01 后跑 CloudPayloadSizeBench |
| 回退 | 分支级回滚；G10 基线 tag；ActionId 留洞不复用 |

---

## 测试方案

| 类型 | 内容 |
|---|---|
| 单元 | 保底边界、权重和、品阶和、addFragment、升星阈值、开局注入、回血、禁改名/逐出 NotFound |
| 守卫 | InventoryAddPath（寻访源）、MirrorReadOnly、概率公示同源、Q31 色值、碎片 key⊆模板、pity key⊆pool、Slot/模板扩展类按 rules 9.5 |
| 对拍 | G10 重录 Diff*；`finalStats` 星级/无资质双端 |
| Migration | 亲缘清空、recruitList 清空、新字段默认值 |
| UI | DialogType/系统栏；结果页点击热区（框/钮不关闭） |
| 门禁 | 每批：`compileReleaseKotlin` + 相关模块 test `--max-workers=1` + detekt；G10/G14：全量 + lint + 双 changelog 检查 |
| 对抗审查要点 | 反向通道符号；共享五件套半提交；十连跨 pity 边界；满仓溢出邮件；限持双实例；兑换码直造弟子；宗门改名误删 |

---

## 风险评估与兜底

| 风险 | 等级 | 兜底 |
|---|---|---|
| 删除批共享文件冲突 | 高 | 按字段分区 commit；组 A 合并顺序固定 G02→G05→G06→G03→G04（或 rebase 时以「后合批改断言」为准）；冲突不改行为只并表 |
| RNG 基线重录两次 | 高 | **仅 G10 一次**；G07 若晚于 G10 则并入补录窗口同一 tag |
| 与 SR 并行写 GameData | 中 | 独立分支，合入前对表；新字段命名前缀/分区不撞 SR |
| 节奏数值过慢 | 中 | G00 杠杆；只动配置 |
| 流光低端机性能 | 中 | G12 降级路径 + SoftwareCanvas 测试 |
| 范围蔓延 | 低 | S3 红线；M3 不开 |

---

## 未来场景推演（≥6 个月）

- **+3–6 月**：轮换池 — 已有多 poolId/pity；只需池表行+立绘+页签。
- **+6–12 月**：第 7 角色 — 模板+map key，协议不动。
- **付费/广告抽** — 货币参数位 + AdService 统一入口，另立项。
- **iOS** — gacha_tx/gcore 直接复用；UI 重写对等页；色表中立数据。
- **回退** — 单批 revert；G10 tag 为删除行为分界。

---

## 技术债与偿还计划

| 债 | 触发 | 计划 |
|---|---|---|
| DiscipleCompact 寿命/年龄列 | 字段三端删除批或独立清理 | G14 登记，不阻塞 G00–G13 |
| manualMasteries 双真相 | 独立票 | 不进本计划 |
| 仓储/丹药旧 Rarity 色与 Q31 不一致 | 若全 UI 换色 | G12 部分或债票；**结果页+灵根徽章 G11 强制 Q31** |
| 37 肖像素材 | 资产清理 | M3 |
| **无债声明** | — | S2.4 范围内不留「后续优化」尾巴；M3 显式排除 |

---

## 盲区自查与完善建议

1. **G05 按钮占位** 若实现期嫌丑，允许 G05 只改路由到「施工中」Dialog，但 **G11 前不得删除按钮**——已在范围写明。  
2. **G07 与 G02 顺序**：若寿命删除牵动死亡测试夹具，允许 G07 并入 G02 commit 组，**不减少总批数统计中的工作项**，但 Report 须合并叙述（批次 ID 仍按 15 计则允许 G07 与 G02 同 PR 两 commit）。  
3. **解锁入宗是否计年报** 产品方案倾向「计入」，G08 须落一种并在 G05 预留的计数点接线——已写入 G05/G08。  
4. **十连 UI 一次事务 vs 十次单抽事务**：推荐 **一笔 `GACHA_PULL_TEN` 内循环十次单抽语义**（1 个 ActionId、1 次镜像回读）；若实现拆十次 native 调用，须保证 pity 中途态原子性（事务内不可被存档打断）。**此点建议 G09 开工时在 batch 内用一段注释钉死，不另开拍板。**  
5. **头像精灵是否已有**：G11 前素材盘点——若无头像切图，允许用立绘缩放占位并开美术债，**不阻塞 G11 逻辑验收**。  
6. **实施期发现与产品方案冲突**：停在封锁点，改产品方案 §12 再继续（不静默改行为）。

**实质结论已回写正文**：并行组、G10 单次基线、按钮占位、年报计数点均已进 S2.4。

---

## 批次速查（给执行者）

| 问 | 答 |
|---|---|
| 总共几批？ | **15**（G00–G14） |
| M1 几批？ | **11**（G01–G11） |
| 能并行吗？ | G02–G06 可并行；G10 必须串行汇合；G11 在 G09 后 |
| 先做什么？ | 分支 → **G01** + **G00** 并行 → 组 A 删除 |
| 何时算 M1 完？ | **G10 全绿 + G11 最简 UI 真机通**（产品方案 M1 验收） |
| 何时算全部完？ | **G14 文档与双 changelog** |

---

## 与内存重构方案的交叉影响（2026-09-23 对照）

> 对照对象：`.worktrees/memory-refactor/docs/memory-refactor-implementation-plan-2026-09-23.md`（下称「内存册」，D1–D7 / Phase 0–4，分支 `docs/memory-refactor-plan`）。  
> **结论：产品需求零冲突；存在中高程度的「同文件 / 同对拍窗口 / 同文档」施工冲突，必须按下方规则串窗口，不可双线无锁并行合入 main。**

### 1. 影响分级

| 级 | 项 | 内存册落点 | 角色批落点 | 影响 |
|---|---|---|---|---|
| **高** | 弟子列扩容与列结构 | D4 `column_dirty.h` 几何增长 + `DiscipleStore.reserve` | G02–G06 **删列**（三端字段链） | 同头文件/同存储：删列后的 schema 与 growth 测试必须在同一稳定结构上做，否则 `ColumnResizeGrowthTest`/bijection 与删除迁移互相返工 |
| **高** | 状态基线 / 导出 diff | D5 `dirty_tracker` 去双 DOM + P4.2 每旬非弟子导出减载 | G01 **新增**碎片/星/保底/历史字段；G09 写入；G10 **重录 Diff 基线** | 新字段必须进基线与导出包；D5/P4.2 改导出形状会再次动 `DiffAuthoritativeTickTest`——**与 G10 是同一类对拍风险** |
| **高** | 对拍基线窗口 | D5/P4.2 要求对拍全绿（自身变更导出） | G10 删除平移后**只允许重录一次** | 两套变更若各录一次基线 = 双倍夹具分叉；必须**合并进同一重录窗口或严格先后且后录者吃前者的 diff 脚本** |
| **中** | 存档/协议 | 内存册：**磁盘格式与 Proto 零变更、无 Room** | G01：**新增 `@ProtoNumber` + Room 扩展** | 非互斥，但是「协议冻结」假设不同：内存 Phase 若先于 G01 合入，D5 基线**尚不知道** gacha 字段；G01 合入后 D5 的块/字段基线**必须覆盖新字段**（缺字段=漏同步脏） |
| **中** | 业务进度 vs Trim | 全局约束 5：Trim 禁清 `cultivationCheckpoints` / `lastSettled*` / 生产完成月 | 碎片/星/保底/历史 = **进度语义** | 内存册**未列** gacha 字段 → CRITICAL trim 若误清游戏态会**清掉收集进度**；须把 gacha 字段写入「只释放资源不释放进度」白名单 |
| **中** | `game_core` import 路径 | D5 import 三峰消除、指针切换 | G05 `recruitList` 读档清空、开局注入挂新档 | 同文件不同函数，合并时注意 import 顺序与清空逻辑不互相踩 |
| **低** | 结果页头像 / 精灵 | D2 `TextureCache` 全路径上传 | G11 头像精灵注册与显示 | G11 若在 D2 后做，上传走 cache；若 D2 未合，允许走既有 Sprite 路径，**不阻塞** |
| **低** | GPU/GLES/场景缓存 | D1/D3/D6/D7 | 角色方案基本不碰渲染后端 | **可完全并行**（VulkanBackend、VMA、sectMapCache 与 G 批正交） |
| **低** | 文档与 changelog | P4.6 CODE_WIKI + architecture + 双 changelog | G14 同三项 | 收口期**必须合并写**，禁止两边各写一半版本段 |
| **无** | 产品语义（概率/碎片/删招募…） | 不涉及 | 产品方案 §12 | **内存重构不改变任何 G 批产品拍板** |

### 2. 推荐串行策略（可执行）

**原则：逻辑状态（gamecore 协议/列/对拍）串行；渲染资源（GPU/纹理/trim 桥）可并行。**

```
阶段 I（可并行）
  角色：G00 案头 + G01 脚手架（协议字段先落地，含进 dirty 导出清单）
  内存：Phase 0–1 止血（P1.1 位图 growth / P1.3 trim / P1.4 纹理过渡）
        —— 允许与 G01 并行，但 P1.1 若已改 column_dirty.h，G02 删列前 rebase 一次

阶段 II（逻辑串行 — 建议角色优先）
  角色：G02–G07 删除 → G08 模板 → G09 gacha_tx
  内存：Phase 2 GPU（D1）可与阶段 II 并行（不动 gamecore 业务字段）

阶段 III（单一基线窗口 — 强制汇合）
  角色：G10 RNG/对拍重录（此时已含删除平移 + gacha 新字段）
  内存：P4.1–P4.2（D5 基线换存储 + 每旬导出减载）必须排在 G10 之后，
        或与 G10 同窗口一次重录 Diff 夹具 —— 禁止 G10 之后、P4.2 之前再录第二次

阶段 IV（可并行收尾）
  角色：G11–G14 UI/数值/文档
  内存：P4.3–P4.6 GLES + 文档
  G14 与 P4.6 合并 CODE_WIKI/architecture/双 changelog（后合者以 main 为准 rebase）
```

**若内存必须先根治 D5**：则角色 **G01 新字段设计必须读内存册 D5 的「字段/块级基线」接口**，把碎片/星/保底/历史登记进块清单；G10 在 D5 之后再录。两方案二选一，**禁止交叉各录一半**。

### 3. 写入两侧的硬规则

| # | 规则 | 给谁 |
|---|---|---|
| R1 | Trim/预算 **永不**清除：碎片、星级、保底计数、寻访历史、开局已注入状态 | 内存册 D3/D6 实施时回写约束 5 白名单（**待改内存册**） |
| R2 | `column_dirty.h` / `dirty_tracker.*` / `models.h`(GameData) / `game_core.cpp` import / Diff 夹具 / `CODE_WIKI.md` / 双 changelog → **文件级锁：同一时间只进一个方案的 PR** | 双方 |
| R3 | Diff/对拍基线 **全局只保留一次权威重录窗口**（默认 = 角色 G10；若 D5 必须先动导出，则窗口改为 D5 完成日，G10 并入） | 双方 PM |
| R4 | 内存册「存档格式零变更」解读为**不改磁盘布局语义**；不反对 G01 **只增字段**——G01 合并后 D5 基线必须覆盖新字段（缺字段即 bug） | 内存 P4.1 |
| R5 | G11 头像上传：D2 已合则走 `TextureCache`；未合则 Sprite 既有路径，G14 文档注明后续可迁 cache | 角色 G11 |
| R6 | 两分支都从 main 切；合并顺序建议 **角色 G09/G10 → 内存 P4**（或反过来但执行 R3） | 分支管理 |

### 4. 对 15 批计划的增补（不改批数）

- **G01** 验收追加：新 gacha 字段列入 dirty/导出清单（为 D5 预埋）。  
- **G10** 验收追加：若内存 D5/P4.2 已合入，对拍夹具含新导出形状；未合入则 Report 注明「D5 合入后须触发 R3 补录窗口」。  
- **G14** 与内存 P4.6 **联席收口**（CODE_WIKI/architecture/changelog）。  
- **不新增第 16 批**；交叉工作挂在既有 T01/T10/T14。

### 5. 一句话

**内存重构不推翻角色方案，但会在 `column_dirty` / 基线导出 / Diff 重录 / CODE_WIKI 四处正面相撞**；按 §2 阶段图串「逻辑状态」、并行「GPU/Trim」，并执行 R1–R6，即可两边都按原计划完成，总批数仍为角色 15 + 内存 Phase 0–4，互不吞并。

# TASKBOOK-G11 验收报告（任务书自身的可派工性核验）

> **验收对象**：[`TASKBOOK-G11.md`](TASKBOOK-G11.md)（2026-09-26 撰写，随 `0fe23b8d7` 入库）。
> **验收时点**：2026-09-26（G11 已由并发会话实施并提交 `a2923bced`，回写 `b574d8189`）。
> **核验方法**：① 引用锚点**回到撰写时点的树**（`19405358a`，即任务书入库前一个代码提交）逐条断言
> —— 不能拿实施后的树比对，那会把「任务书写对了但代码已被改」误判成任务书错；
> ② 决策级结论逐条对照 G11 实际交付；③ 交叉核对 `report-G11.md` §4 的「上位失真 6 条」。
> **总判**：**通过**——任务书的决定性判断全部成立、G11 一次性交付无返工；
> 同时**实测出 7 条失真**（6 条已由实施会话自查并当场纠正 + 我补 2 条），逐条列在 §4/§5。

---

## 1. 结论速览

| 项 | 结果 |
|---|---|
| 派工有效性 | ✅ **成立**：G11 按任务书的切片（A-11a…e + c11-a/b）一次交付，无返工；`DialogFeatureRoutes` 占位体替换、`GachaDelegate` 接线、色表单一源、Compose 层流光**全部按任务书口径落地** |
| 锚点可靠性 | ✅ 34 个引用断言中 **28 精确命中**，4 处为**范围边界差 ≤2 行（指向的构件正确）**，2 处为**我核验脚本自己的路径/正则笔误**；**无一处指向错文件或错构件** |
| 决策命中率 | 12 条决策中 **10 条原样成立**；**2 条被执行会话修正**（D-5 少算 3 份 C++ 副本、D-6 引用的动画范式不存在且有 API 限制） |
| 🔴 红线冲突 | **1 条**：任务书验收⑤ 写了 `byId(tid)!!.avatarKey`，违反根 `AGENTS.md` §5 1.1「禁止 `!!`」；实施会话改为安全调用 + `unresolved` 分支（§5） |
| 最关键的命中 | **D-7（先验订阅发射）直接抓出真缺陷**：`GachaService` 两条臂原地改 `GameData` 字段 ⇒ 四个派生流不发射 ⇒ 结果页/图鉴永不刷新（G08/G09 遗留）。实施会话先写 `GachaSubscribeEmissionTest` 证伪、再改 `copy()` |
| 文档卫生 | ⚠️ 1 条：§5 文件面用**裸文件名**，复核者按名找不到（详见 §6） |

---

## 2. 锚点核验（回到撰写时点的树 `19405358a`）

**方法**：对任务书里每条 `file:line` 断言，用 `git show 19405358a:<path>` 取该行内容并按期望子串断言。

| 批次 | 断言数 | 精确命中 | 范围边界差 | 核验脚本自身笔误 | 错文件/错构件 |
|---|---|---|---|---|---|
| 第一批（定义面 + 色表地雷） | 17 | 13 | 1 | 3 | **0** |
| 第二批（G09 交付面 + 守卫 + 文档） | 17 | 15 | 2 | 0 | **0** |
| **合计** | **34** | **28** | **3** | **3** | **0** |

**代表性的精确命中（原文摘录）**：
- `DialogType.kt:31/32` → `/** 招募 */` / `data object Recruit : DialogType` ✅
- `DialogFeatureRoutes.kt:53/69/74` → `DialogType.Recruit -> renderRecruit(...)` / `@Suppress("UnusedParameter")`
  （注释原文还写着「G11 替换分支体时…」）/ `title = "寻访"` ✅
- `GameActionButtons.kt:103` → `text = "招募"` ✅（实施后已为 `"寻访"`）
- `ItemCard.kt:245` → `text = GameUtils.formatNumber(data.quantity)`（**角标确无 `×`**，故任务书要求自建角标）✅
- `GachaFacade.kt:97` → `data class Success(` ✅；`:114` → KDoc「小头像位读 `avatarKey`」✅；`:66` → 「必须在引擎线程上下文内调用」✅
- `GachaService.kt:134/160` → `val drawn = ArrayList<GachaHistoryEntry>(count)` / `(drawn.asReversed() + data.gachaHistory).take(…)` ✅
- `GachaPoolConfig.kt:57/94` → `data class GachaPoolSpec(` / `fun pool(poolId: String): GachaPoolSpec?` ✅
- 色表地雷四条全中：`Color.kt:40 RarityCommon`、`:110 GameColors.getRarityColor`、`ItemCard.kt:79/189/337`、`GameConfig.kt:244 RARITY_COLORS` ✅
- `ui-read-surface.md:54` → 「UI 消费面（结果页 / 历史页 / 图鉴）尚未接入，接入时按本节判据读取，不得另立镜像字段。」✅；`:94` → `| recruitListAggregates | recruitList |`（§3.2 登记格式样例）✅

**范围边界差 3 处（不影响可派工性）**：`Disciple.kt:299-306`（`:299` 是 `val countColor … when`，旧值写在 `:300-305`）、
`GameOverlayHost.kt:456-478`（`:456` 是该节的 KDoc 头）、`ButtonSizes.kt:6-11`（`:6` 是 `object ButtonSizes {`）。

**核验脚本自身笔误 3 处（非任务书问题）**：我按猜测路径找 `ResourcePreloader.kt` / `ViewModelArchitectureTest.kt` 用了错的目录前缀；
以及 `key(` 当正则导致断言失败（内容本身正确）。

---

## 3. 决策与切片的落地对照（任务书 §4/§5 → G11 实际交付）

| 决策 | 任务书口径 | G11 实际 | 判定 |
|---|---|---|---|
| **D-1** | 复用 `DialogType.Recruit`，只替换分支体；注册/守卫 4 文件零改 | `DialogType.kt` / `OverlayDialogRouter.kt` / `DialogTypeRenderCoverageTest.kt` / `GameRoute.kt` **均未改**（只改 KDoc） | ✅ 原样成立 |
| **D-2** | 结果层用窗口内最高 z 序层自管层外点击；**首片先做能力验证**，不满足则退到独立窗口 | 落地为 `UnifiedGameDialog` 的 overlay 层 + 分层 `BackHandler`，并**新增 `UnifiedGameDialogOverlayLayerTest`（5 例）把容器能力变成被测契约** | ✅ 超出预期（把"先验证"升级为"永久守卫"） |
| **D-3** | 升星层做窗口内本地状态，不写 `pendingNotification`、不新建总线 | `GachaStarUpModel` / `starJumps` 队列；未动 `GameNotification` | ✅ |
| **D-4** | 失败文案内联；`RecruitFailed` 只核实不删 | 报告 §8-2 把 `RecruitFailed` 生产者存废登记给 G10 | ✅ |
| **D-5** | 色表单一源：新建 `GachaColors` + `Disciple.countColor` 委托；「⇒ 10 处消费者自动跟随」 | ⚠️ **修正**：灵根数色另有 **3 份 C++ 字面量副本**（`exploration_tx.h:71-85`、`year_settlement.h:1022-1034`、`sect_defense_battle.h:88-101`）写进**持久化**槽位色 + 1 条 C++ 断言；直接消费点是 **11 处** | ⚠️ 任务书少算，实施会话补做并加守卫 |
| **D-6** | 流光走 Compose 层，照 `LizhanDialog.kt:466-479` / `RewardCardHost.kt:90-99` 既有范式（`Animatable` + `sweepGradient`）；不改 native、不更新渲染清单 | ⚠️ **修正**：那两段是**位移/淡入**，全仓无扫光；且本仓 Compose 的 `Brush.sweepGradient` **无角度入参** ⇒ 改用 `Animatable` 相位 + 逐帧平移 `linearGradient`（描边扫光）；**"不改 native/不更新清单"部分严格遵守**（实测 G11 提交未碰 `renderer-feature-checklist.md`） | ⚠️ 范式引用错，方向对 |
| **D-7** | 🔴 接线第一步先验订阅是否发射（G08/G09 遗留） | 🔴 **命中真缺陷**：`GameStateStoreImpl.emitStateFlows` 判据是 `!==`，而两臂原地改字段 ⇒ 引用不变 ⇒ 四流不发射；先证伪（`GachaSubscribeEmissionTest` 红）再改 `copy()` | ✅ **本任务书最高价值的判断** |
| **D-8** | 渲染模型纯函数化 | `GachaRenderModel` + `GachaRenderModelTest`（18 例，含判别力自证：改 `asReversed()` 即红） | ✅ |
| **D-9** | 不新增 `SpriteCategory`、大图类不加预载 | 均遵守；`PortraitResolverTest` 读点台账零漂移 | ✅ |
| **D-10** | 新 VM 只持 Facade + 继承 `BaseViewModel` | `GachaViewModel`；`ViewModelArchitectureTest` 绿 | ✅ |
| **D-11** | 埋点不上报，只登记 | 报告 §8-3 | ✅ |
| **D-12** | `ui-read-surface.md` 回写两处 | 报告 §2-⑧：§2.1 末句改写 + §3.2 增行 | ✅ |
| **切片** | A-11a…e + c11-a/b，≤10 文件/片 | 逐片落地；**范围外多出 3 个文件面**（C++ 色副对齐、`GachaService` 根因修复、依赖收窄），报告 §3 逐条给理由 | ✅ 有据越界 |

---

## 4. 实施会话自查出的 6 条上位失真（我逐条复核，**全部成立**）

`report-G11.md` §4 列了 6 条，我核对结论如下：

| # | 报告结论 | 我的复核 |
|---|---|---|
| 1 | D-6 引的两处"扫光范式"实为位移/淡入；`Brush.sweepGradient` 无角度入参 | ✅ 与我在 `19405358a` 上核对的一致：那两文件无 `sweepGradient`/相位逻辑；本仓无 `InfiniteTransition` 先例 |
| 2 | D-5 的"10 处消费者"漏了 3 份 C++ 副本 + 1 条断言；且槽位色是**持久化串** | ✅ 报告给了三份副本的行号与消费点（灵矿/巅峰/世界地图驻守槽）；并诚实登记"旧档已持久化的色串要等下次写入才刷新" |
| 3 | 验收⑤ 的 `!!` 违反根 §5 1.1；`byId` KDoc 明写"未知 id 返回 null，禁止兜底" | ✅ 成立（见 §5） |
| 4 | §2.3-1「加 `gachaFacade` 会打破 4 个测试构造点」成立，行号随批次漂移（按 `grep "GameVmDelegateServices("` 定位） | ✅ 与我核到的 `GameVmServices.kt:41 class GameVmDelegateServices` 一致 |
| 5 | §5 A-11b「`DialogFeatureRoutes.kt:53` 需改指向」——实际 `:53` 早已正确，只替换 `:68-92` | ✅ 我核到 `:53` = `DialogType.Recruit -> renderRecruit(...)`，**任务书这一句是多余的**（无害） |
| 6 | 产品 §4.4「满星显示 MAX」按字面会一格两个 MAX | ✅ 处置合理（进度行改显"碎片 x"） |

---

## 5. 🔴 任务书自身的红线冲突（我的失误，已由实施会话纠正）

任务书**验收⑤** 与 **§3.2 角色框** 写了：

```kotlin
CharacterTemplateDb.byId(tid)!!.avatarKey
```

**这违反根 `AGENTS.md` §5 编码规范 1.1「禁止 `!!` 操作符」**（除有编译时证明）。
实施会话按 `byId` 的 KDoc（未知 id 返回 null、禁止兜底到开局模板）改为**安全调用 + `unresolved` 显式分支**
（格子显"未知"且不猜图），与 `GachaPullLedger.poolError` 的"显式拒绝"口径一致。

**教训（已作为新纪律写入 G12/G13/G14 任务书）**：
🔴 **任务书里出现的任何代码片段/调用式，同样要过项目红线检查**（`!!`、`runBlocking`、`Random`、直写 Store、硬编码数值等），
不能因为"只是示意"就放过——实施会话若照抄就会直接踩红线。

---

## 6. 文档卫生问题（1 条）

§5 的文件面表用了**裸文件名**（如 `DialogType.kt`、`GachaService.kt`、`GameDialog.kt`）。
后果：复核者按名检索会扑空（我核验时先按猜测路径找了两次才定位到
`android/core/domain/src/main/java/com/xianxia/sect/core/domain/dialog/DialogType.kt`）。
`docs/AGENTS.md`「引用路径规范」第 1 条明确要求写**可解析路径**。

**已作为新纪律写入 G12/G13/G14 任务书**：§文件面表的每一行一律写**仓库根相对完整路径**。

---

## 7. 验收结论

1. ✅ **TASKBOOK-G11.md 通过验收**：作为派工真源，它让 G11 一次交付、零返工；
   其中 **D-7 是决定性的**——若不先验订阅发射，G11 会交付一个"服务端自测全绿、界面永不刷新"的假绿批次。
2. ⚠️ **7 条失真**（2 条决策级 D-5/D-6、1 条红线冲突、3 条无伤大雅的范围/冗余、1 条文档卫生）**全部有据**，
   且 **6 条由实施会话在批内自查并当场纠正**——这反过来说明本仓"任务书 §2 上位失真清单 + 实施期回查"的机制是有效的。
3. 📌 **任务书不再回改**（G11 已收官，回改无收益）；教训以「两条新纪律」的形式进入后续任务书。
4. 📌 **G11 移交面**（供 G10/G12 消费）：真实缺陷 A（订阅发射）已修并加守卫；
   pending-device 12 项；色板对齐债（旧 4 张物品色表）→ G12；`RecruitFailed` 存废 → G10 死码清零。

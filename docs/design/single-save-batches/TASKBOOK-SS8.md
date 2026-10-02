# TASKBOOK-SS8 · 登录门槛 + 隐私政策双入口

> **本文件是 SS8 的派工真源**（开工补卡）。上位方案 §2.2（Q5 全部功能要求登录）+ §2.8；拍板 B1（离线宽限）。
> 协议：[`DISPATCH-ledger.md`](DISPATCH-ledger.md) §4 + [`../gacha-batches/EXECUTION-PROTOCOL.md`](../gacha-batches/EXECUTION-PROTOCOL.md)。
> 入口判据：**SS2 已合入**（`a4c17acc2`，"无账号 ⇒ 不建库 + 引导登录"行为基座已落）；SS3–SS7 已并网。
> ⚠️ 派工册 §3 SS8 附带修正项（`rules/sdk-init-lifecycle.md:50` 已退役行）**已由 SS2 随批消费**（SS2 报告 §1-10：规范行删除+登出清单升五件套）——本批复核即可，不重复修。

---

## 1. 目标与验收判据

| 项 | 内容 |
|---|---|
| 业务目标 | **未登录不可进游戏**（Q5）：登录门槛收口进 `LoginFlowStateMachine` 显式状态；离线宽限按 **B1**（已登录过可离线继续、从未登录必须联网完成首次登录）；隐私政策双入口同步更新。 |
| 验收① | **强制登录门槛状态机化**：未登录（无 unionId）路径必须经登录页，不得静默进游戏——SS2 的 `enterGameAuto` 防御兜底升格为 `LoginFlowStateMachine` **显式状态**（如 `RequireLogin`），测试锁定该状态不可绕过；**禁新增"只置位不复位"的一次性布尔标记**（`rules/sdk-init-lifecycle.md` 原则 5） |
| 验收② | **B1 离线宽限**：①已登录过（本地有账号数据空间/unionId 缓存）+ 离线 ⇒ 可继续游戏（节拍/事件存档照常本地落盘，云上传由队列退避自然挂起）；②从未登录 + 无网 ⇒ 停留在登录/引导页（不能进游戏，因无账号键无法建数据空间——SS2 D-5 语义的自然延伸）；判定面 = `SessionManager` 既有会话/账号缓存，不新增持久化键 |
| 验收③ | **隐私政策双入口同步**：`PrivacyConsentScreen.kt`（应用内首启同意页）与 `docs/index.html`（外部政策页）内容/版本/链接一致；更新点 = 单存档改造相关条款（删档重置告知、账号数据空间隔离、云灾备语义）——文案给玩家看，通俗无术语 |
| 验收④ | **五守卫全绿（硬门）**：`LoginFlowStateMachineTest` / `SafeRunAfterSdkInitTest` / `ComplianceManagerSelfHealTest` / `SdkInitGuardTest` / `TapDBManagerInitGuardTest` |
| 验收⑤ | **与既有链无回归**：SS2 登出五件套（`FullLogout.kt`）、SS6 事件触发存档、SS7 云上传队列在"离线继续"场景下的行为有测试覆盖（离线时云上传挂起不报错、恢复在线后队列自然排空——既有退避语义复用） |
| **不做** | 不改账号隔离（SS2 已做）；不改云链路（SS7 已做）；不新增 SDK 初始化顺序变更（`SdkInitGuardTest` 锁定面不扩）；不碰 C++；不做游客模式（Q5 拍板=全部功能要求登录） |

---

## 2. 实测现状（2026-10-02 主树）

- `LoginFlowStateMachine.kt`（`app/login/`）+ `LoginFlowState.kt`：状态机承载 ColdStart→验证→EnterGame/LogoutRequested 链（主界面退役批落地）；`FullLogout.kt` 五件套（SS2）。
- SS2 行为基座：登录前零存储链实例化（Lazy 化）+ `enterGameAuto` 无 unionId 回登录页（防御兜底）+ `require*` fail-fast。
- `PrivacyConsentScreen.kt`（`app/ui/`）+ `docs/index.html` 双入口在册；`SessionManager` `KEY_PRIVACY_AGREED`/`KEY_PRIVACY_CHECKBOX_CONFIRMED`（SS2 分库后随账号空间 wipe 清除 = W5 强制重新同意语义已就位）。
- B1 现状：**离线宽限未显式实现**（当前行为由 SS2 基座隐式决定）——本批把它变成状态机显式语义 + 测试。

---

## 3. 决策

| # | 决策 | 依据 / 代价 |
|---|---|---|
| **D-1** | **门槛进状态机，不进 Activity 布尔** | 派工册明令禁一次性布尔；状态机是登录链唯一真源（主界面退役批先例） |
| **D-2** | **B1 判定 = "本地是否已有该账号数据空间/unionId 缓存"**，不引入新持久化键 | 已登录过的自然凭证 = SS2 的数据空间与 SessionManager 缓存；新增键=双真源 |
| **D-3** | **离线继续时云上传不特殊处理** | `UploadQueue` 既有退避/熔断天然吸收离线（SS7 语义复用）；特殊处理=新增状态面 |
| **D-4** | **隐私文案同步以"当前行为"为准**（删档告知/账号隔离/云灾备），不做营销性承诺 | 文档三同步纪律；`rules/data-analytics.md` 式双入口一致性 |
| **D-5** | **首次登录强制联网 = 现状语义的显式化**（无账号键无法建空间 ⇒ 必须联网登录成功） | SS2 D-5 的自然延伸，本批只补状态机显式化与测试 |

---

## 4. 文件面与切片（≤10 文件/片）

| 片 | 允许改 | 禁止改 | 自检项 |
|---|---|---|---|
| **SS8-a** 状态机门槛 | `LoginFlowState.kt`、`LoginFlowStateMachine.kt`、`LoginFlowStateMachineTest` | SDK 初始化顺序、`FullLogout` 五件套 | RequireLogin 状态不可绕过测试 |
| **SS8-b** B1 离线宽限 | 状态机判定面 + `SessionManager` 只读消费 + 离线场景测试 | 新持久化键、云队列语义 | 两分支（离线继续/引导联网）各有测试 |
| **SS8-c** 隐私双入口 | `PrivacyConsentScreen.kt`、`docs/index.html` | 同意存储语义（W5 清除面） | 双入口内容一致性核对表 |
| **主线程** | `report-SS8.md`、门禁复跑 | — | §5 清单 |

---

## 5. 门禁清单

```powershell
# 工作目录 = C:\Mnzm\XianxiaSectNative\android
.\gradlew.bat compileReleaseKotlin --console=plain
.\gradlew.bat :app:testReleaseUnitTest :feature:game:testReleaseUnitTest --max-workers=1 "-Dgamecore.jni.path=C:\Mnzm\XianxiaSectNative\android\core\engine\build\desktop-jni\libgamecorejni.so" --rerun-tasks --console=plain
.\gradlew.bat :app:detekt :feature:game:detekt --console=plain
.\gradlew.bat :app:testReleaseUnitTest --tests "*LoginFlowStateMachineTest*" --tests "*SafeRunAfterSdkInitTest*" --tests "*ComplianceManagerSelfHealTest*" --tests "*SdkInitGuardTest*" --tests "*TapDBManagerInitGuardTest*" --max-workers=1 --console=plain
node scripts/check-agent-instructions.mjs   # 改 rules/docs 后必跑
```

**真机 pending-device**：离线宽限两分支 + 首次登录强制联网，需真机飞行模式验证。

**提交前**：`git status` 只留本批改动；构建副产物 `git checkout --` 还原；一次性脚本清零；**文档面编辑一律在 worktree 内完成并随批 commit**。

---

## 6. 登记项

1. **跨批登记（SS10）**：玩家可见变更（强制登录+隐私政策更新）并入 4.2.00 唯一条目；隐私政策更新若需渠道侧配合（合规审核），登记给发布流程。
2. **风险（必须写进报告）**：B1"已登录过"判定的边界——账号数据空间存在但 unionId 缓存被清的极端态（wipe 后半态）；报告给出该态的行为定义（按未登录处理，数据空间等下次同账号登录重新激活）。
3. **风险**：门槛状态机化对既有启动路径（冷启动/杀进程重进/登出重启）的回归面——`LoginFlowStateMachineTest` 既有用例全绿为底线。

---

## 7. 一句话给执行者

**把"未登录不可进游戏"从防御兜底升格为状态机显式状态：RequireLogin 不可绕过 + B1 离线宽限两分支（已登录过离线继续/从未登录必须联网）+ 隐私政策双入口同步单存档条款——五守卫全绿，禁一次性布尔。**

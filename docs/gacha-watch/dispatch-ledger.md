# G 批实施看护台账（G10 → G12 → G13 → G14）

> **本文件是看护的「唯一权威状态文件」**。看护每轮读它；有实质变化才 `git commit -m "docs(gacha-watch): …"`。
> 运行手册（状态机 / 派发命令 / 核验口径 / 模板）在 [`WATCHDOG-PROMPT.md`](WATCHDOG-PROMPT.md) —— 本文件**只放状态**，不重复手册。
> 批任务书真源：`../design/gacha-batches/TASKBOOK-G10.md`、`TASKBOOK-G12.md`、`TASKBOOK-G13.md`、`TASKBOOK-G14.md`。
> 上位指针：`../design/gacha-batches/HANDOVER-m1-remaining-3.md`（§7 剩余批次指针 / §8.G G11 交接）。
> 实施器：ZCode CLI 无头（`node %LOCALAPPDATA%\Programs\ZCode\resources\glm\zcode.cjs --prompt …`），**默认模型**，**每批新会话**。

## 给实施会话的留言区（看护或用户可追加；实施会话开工前**必读**）

（最新在上）

- **2026-09-26 · 看护建立**：本台账建立时点的事实基线见下方《当前状态》。给所有实施会话的三条通用提醒：
  ① 开工前 `git status` 必须只剩 `docs/research/`×2；② 提交**明确文件名**，禁 `git add -A`；
  ③ **门禁必须同轮实跑并把命令原文数字写进报告**（G09 曾因「代码写完但新测试没跑」漏掉 5 条真缺陷）。

---

## 当前状态

（最新在上）

- **2026-09-26 20:10 G10 派发尝试失败 ⇒ 派发通道故障，记 blocked（待用户处置）**：在途=无（实施进程启动即退 `turn.failed`，零代码改动）；新提交=无；交付核验=不适用；动作=记 blocked（通道层，非任务书层）；备注=独立无头 CLI（`node …\resources\glm\zcode.cjs --prompt`）对最小 prompt 亦复现 `ClientRequestSigningV4Error: Client signing credential must contain one separator`（providerId=bigmodel-api，kind=invalid-config，fail closed；traceId `709c644f` / `6be132e4`）——与 G10 派发文本无关；今天 16:07–19:48 跑通的 G11 会话（sess_59503805）入口是 `zcode_protocol`（桌面 app 运行时）而非本命令 ⇒ **独立 CLI 通道从未被验证且当前不可用**；`~/.zcode/v2/credentials.json` 全为 `enc:v1:` 加密凭据块（含 2 个点 ⇒ 恰好触发「必须恰有一个分隔符」解析失败）、`v2/setting.json` bigmodel=oauth（mtime 19:48）、`~/.zcode/cli/config.json` 的 apiKey（49 字符恰 1 点，`{id}.{secret}` 合法）未被该路径采用。**处置建议交用户**：① 终端跑一次 `zcode login`（OAuth 浏览器授权，CLI `--help` 自述「Sign in with Z.AI OAuth for model access」）后重试派发；或 ② 用户明确指示改派发渠道（app 内派发 = G11 实际通道）。派发件已就绪未消费（`_dispatch-G10.md` 26,721 字节 = 纪律前言 + 任务书全文逐字 + G11 交付事实附录；`.gitignore` 已覆盖，重试时直接喂 CLI）。
- **2026-09-26 · 四份批任务书与看护件就绪（可开始派发）**：
  - 派发顺序 **G10 → G12 → G13 → G14**；任务书 = `../design/gacha-batches/TASKBOOK-{G10,G12,G13,G14}.md`（**均已入库**）；
  - 看护件 = 本目录 `WATCHDOG-PROMPT.md`（提示词真源）＋ `fire.ps1`（fire 文本装配器，**只装配不启动**）；
  - G11 任务书已验收 = `../design/gacha-batches/ACCEPTANCE-TASKBOOK-G11.md`（含其实施期自查出的 6 条失真 + 我补的 2 条）；
  - ✅ **版本号目标已拍板 = `4.01.16`**（用户 2026-09-26）⇒ `TASKBOOK-G14.md` §3.1 已写死三个文件的归一表，
    看护/实施会话**无需再问**；G14 已无用户阻塞项。
- **2026-09-26 · 看护建立（status: running）**：M1 已完成 **10/11**（G02/G03/G04/G05/G06/G07/G08/G09/**G11**，G00/G01 为案头与脚手架），
  **M1 剩余 = G10**；M2 三批（G12/G13/G14）未开工。HEAD 见 `git log`（G11 收官笔 `a2923bced` + 回写 `b574d8189`；看护建立笔即本文件首笔）。
  **派发顺序 = G10 → G12 → G13 → G14**（G14 依赖前三批）。
  🔴 **两处必须记住的事实**：
  ① **M1 的「G11 最简 UI 真机通」判据尚未满足**——G11 报告 §7 列了 **12 项 pending-device**（本机无可连设备/模拟器）。
     该判据**不阻塞**看护派发（看护无法提供设备），但 M1 总收官必须把它列为未完成项交用户接设备。
  ② **G10 是 RNG 金黄的唯一一次重录窗口**——G09/G11 完成时三条金黄红仍**逐条同名**且
     `DeterminismProbe actual=0xb4f3c6912207f597` 逐字符不变；**G10 之后该值允许变化**，但报告必须写明新值与依据。
- **2026-09-26 · G11 交付（accepted 待用户/看护核验）**：`a2923bced feat(gacha): G11 最简寻访 UI 批` + 回写 `b574d8189`；
  报告 `report-G11.md`（含 §5 守卫判别力 5 条自证、§6 首轮 7 条缺陷修复、§7 pending-device 12 项、§8 遗留 5 条）。
  范围外多出 3 个文件面（C++ 三份灵根数色副本对齐、`GachaService` 订阅发射根因修复、`DialogFeatureRoutes` 依赖收窄），报告 §3 已逐条给理由。

---

## 批次总表（看护据此决定动作）

| # | 批次 | 依赖 | 状态 | 派发时间 | 收官笔 | 报告 | 备注 |
|---|---|---|---|---|---|---|---|
| 1 | **G10** RNG 基线重录 + 全量回归收口 + 死代码 grep 清零 + 文档收口（M1 末批） | G02–G09/G11 已合入 | `blocked(派发通道)` | 2026-09-26 19:57（尝试） | — | — | 任务书已就绪；**唯一重录窗口**；无头 CLI 认证故障待用户处置（见当前状态 20:10 条） |
| 2 | **G12** 体验完成（历史 50 条 / 概率公示 / 图鉴完整 / 流光降级 / 引导 / 死文案清零） | G11 | `pending` | — | — | — | 任务书已就绪 |
| 3 | **G13** 数值落地（M0 杠杆回填 / 突破补偿 / 回血参数 / 经济复测 / 星级乘区终值） | G00、G09、G10 | `pending` | — | — | — | 任务书已就绪；**需产品在 M0 勾选表上拍板** |
| 4 | **G14** 文档与发布收口（双 changelog / CODE_WIKI / architecture / 验收报告 / 死文案清单） | G11–G13 | `pending` | — | — | — | 任务书已就绪 |
| 5 | **G11-真机** 设备验证（M1 完成判据） | G11、用户提供设备 | `blocked(需设备)` | — | — | — | **不阻塞前 4 批派发**；清单 = `report-G11.md` §7 的 D-1…D-12 |
| 6 | **M2 总收官** | G14 | `pending` | — | — | — | 见 WATCHDOG-PROMPT §6 |

**状态取值**：`pending`（未派）／`dispatched`（已派在途）／`accepted`（三要素通过）／`needs-fix`（门禁失败待修复轮）／`stalled`（超时无进展）／`blocked`（停派待用户）。

---

## 看护锁

| 项 | 值 |
|---|---|
| 看护启用时点 | 2026-09-26（本文件首笔） |
| 定时任务 | **看护不自行安装**；由用户在 WATCHDOG-PROMPT §3 的方案 A/B 中择一安装 |
| 锁时间戳 | `2026-09-26 20:10 · G10 派发尝试→通道故障记 blocked`（每轮 fire 有实质动作时更新；15 分钟内视为活跃） |
| 自动化 id | （用户安装后回填；keep-alive 记录） |

---

## 累计 pending-device（只登记，不阻塞）

| 来源 | 项数 | 位置 |
|---|---|---|
| G11 | 12（D-1…D-12） | `../design/gacha-batches/report-G11.md` §7 |
| G09 | 0（真机随 G11 一并验） | `../design/gacha-batches/report-G09.md` §六 |
| G16 / G08 | 各 1（素材视觉 / 周明立绘视觉） | `report-G16.md` §七、`report-G08.md` §七 |

---

## 每轮监控行格式（追加到《当前状态》最上方）

```
- **YYYY-MM-DD HH:mm <轮次结论>**：在途=<批次@PID/sessionId | 无>；新提交=<sha 或 无>；
  交付核验=<通过/未通过(缺哪一项)>；动作=<派发 Gxx / 只观察 / 记 stalled / 记 blocked>；备注=<一句话>。
```

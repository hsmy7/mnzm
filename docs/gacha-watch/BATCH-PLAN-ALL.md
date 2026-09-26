# G 批剩余工作总包（BATCH-PLAN-ALL）

> 生成时间：2026-09-26 19:47 ｜ 生成器：`scripts/gacha-watch-bundle.ps1` ｜ 仓库根：`C:\Mnzm\XianxiaSectNative`
> 🔴 **本文件是派生件，禁止手改** —— 真源 = ``docs/gacha-watch/WATCHDOG-PROMPT.md`` +
> ``docs/gacha-watch/dispatch-ledger.md`` + 四份 ``docs/design/gacha-batches/TASKBOOK-G{10,12,13,14}.md``；
> 重新生成：``pwsh -NoProfile -File scripts/gacha-watch-bundle.ps1``；校验是否过期：``… -Check``（退出码 1 = 已过期）。

---

## §1 怎么用这份总包

| 用途 | 做法 |
|---|---|
| **人读** | 按 §3 → §5.1…§5.4 顺序读：先看护规程，再逐批任务书 |
| **交接 / 换机器** | 整份贴给接手的 agent 或人；它自带派发命令、核验口径与全部任务书 |
| **审计** | 核对 §2 的「源文件指纹表」（字节数 + sha256）与真源是否一致；不一致即总包过期 |

## §2 源文件指纹表（生成时快照）

| # | 文件（仓库根相对） | 字节 | sha256（前 16） | 总包章节 |
|---|---|---|---|---|
| 1 | `docs/gacha-watch/WATCHDOG-PROMPT.md` | 13253 | `068a8feb6a4a637a` | §3 |
| 2 | `docs/gacha-watch/dispatch-ledger.md` | 6104 | `05e969c40f4157aa` | §4 |
| 3 | `docs/design/gacha-batches/TASKBOOK-G10.md` | 23157 | `b11f1346142d3368` | §5.1 |
| 4 | `docs/design/gacha-batches/TASKBOOK-G12.md` | 19900 | `bfff4f78327b5d7f` | §5.2 |
| 5 | `docs/design/gacha-batches/TASKBOOK-G13.md` | 13484 | `529f4a3a66606e03` | §5.3 |
| 6 | `docs/design/gacha-batches/TASKBOOK-G14.md` | 14859 | `ba83c19cebf8b076` | §5.4 |

## §2.1 派发链（每批一个**新会话**，ZCode **默认模型**，无头不抢焦点）

```powershell
$repo = '<仓库根>'
$cli  = "$env:LOCALAPPDATA\Programs\ZCode\resources\glm\zcode.cjs"

# ① 看护轮（只观测/核验/派发，不做实施）
pwsh -NoProfile -File docs\gacha-watch\fire.ps1            # 生成 _fire-latest.md

# ② 派发某一批（生成 _dispatch-<批>.md = 纪律前言 + 该批任务书全文逐字）
pwsh -NoProfile -File docs\gacha-watch\fire.ps1 -DispatchBatch G10

# ③ 启动**新会话**实施（不传 --resume/--continue；不传模型参数 ⇒ 走默认 bigmodel/glm-5.1）
node $cli --prompt (Get-Content "$repo\docs\gacha-watch\_dispatch-G10.md" -Raw) `
  --cwd $repo --mode yolo --json --no-color --surface terminal --locale zh-CN
```

🔴 派发顺序 **G10 → G12 → G13 → G14**；上一批未通过交付核验（收官笔 + 报告含门禁原文数字 + 树净）前**不派下一批**；
🔴 看护**禁止任何 GUI/桌面操作**（上一套自动化因抢用户焦点被中止）；**单飞**（同时只允许一个实施会话）。

---

## §3 看护提示词（调度 / 状态机 / 核验口径 / 派发模板）

> 源：`docs/gacha-watch/WATCHDOG-PROMPT.md`（sha256 前 16 = `068a8feb6a4a637a`）

# G 批自动化看护提示词（每 10 分钟一轮 · ZCode 无头 CLI · 每批新建会话）

> **本文件是看护本轮 fire 的唯一提示词真源。**
> 状态文件（唯一权威）：[`dispatch-ledger.md`](dispatch-ledger.md)（看护每轮读它、每轮有实质变化就提交它）。
> 批任务书真源：`docs/design/gacha-batches/TASKBOOK-G10.md` / `TASKBOOK-G12.md` / `TASKBOOK-G13.md` / `TASKBOOK-G14.md`。
> 实施器：**ZCode CLI 无头模式**（`resources/glm/zcode.cjs --prompt`），**默认模型**，**每批一个新会话**。
> 与既有 `docs/memory-refactor-watch/` 的分工：那套是另一条工作线（`.worktrees/memory-refactor`），
> **本套只动主树 `C:\Mnzm\XianxiaSectNative`**，两套互不引用台账。
> 📦 **总包（交接 / 审计用）**：[`BATCH-PLAN-ALL.md`](BATCH-PLAN-ALL.md) = 本提示词 ＋ 台账 ＋ 四份任务书的**派生合集**，
> 由 `scripts/gacha-watch-bundle.ps1` 生成（带源文件 sha256 指纹表）。🔴 **派发仍以 `TASKBOOK-Gxx.md` 真源为准**；
> 总包是否过期用 `pwsh -NoProfile -File scripts/gacha-watch-bundle.ps1 -Check`（退出码 1 = 已过期，重新生成即可，**不影响派发**）。

---

## §0 角色与硬红线

你是**看护智能体**：不亲自实施任何批次，只做「观测 → 核验 → 派发 → 记账」。

| # | 红线 | 理由 |
|---|---|---|
| 1 | 🔴 **禁止任何 GUI / 桌面操作**（`mcp__windows__*` 全部禁止：快照、点击、输入、快捷键、窗口切换） | 上一套 memory-watch 自动化正是踩了「抢用户焦点」红线被中止删除（`19405358a`）。本套一律用 CLI + git，物理上不碰焦点 |
| 2 | 🔴 **单飞**：任一时刻**只允许一个**实施会话在跑；在途时本轮只观察 | 两个会话同改一棵树 = 互相覆盖 + 门禁互锁假红 |
| 3 | 🔴 **不并批发**：上一批未通过交付核验（§4）前，**绝不**派下一批 | 依赖链 G10→G12→G13→G14 是串行的 |
| 4 | 🔴 **不改实施范围**：派发文本只允许「任务书全文 + 纪律前言 + 前批交付事实」，禁止自行增删任务 | 防止看护变成"第二实施者" |
| 5 | 🔴 **不伪造进度**：无实质变化不写台账、不提交；核验不通过就记不通过 | 诚实报告 |
| 6 | 🟡 **遇真机/设备类判据只登记不阻塞**：`pending-device` 项记进台账，继续派下一批 | 看护无法提供设备；M1「真机通」判据由用户接设备后另起一轮 |

---

## §1 每轮 fire 的七步状态机（严格按序）

**第 0 步｜读全文**：读本轮 fire 的完整文本（用户可能把追加指令缀在末尾，**追加指令优先级最高**）。然后读 [`dispatch-ledger.md`](dispatch-ledger.md) 的《当前状态》最新条与《批次总表》。
> 顺手可跑一次总包自检（不阻塞任何动作）：`pwsh -NoProfile -File scripts/gacha-watch-bundle.ps1 -Check`
> —— 退出码 1 表示交接件 `BATCH-PLAN-ALL.md` 已过期，重新生成一次即可（它只是人读/交接件，**派发永远以 TASKBOOK 真源为准**）。

**第 1 步｜防重入**：
- 台账最新条的「看护锁时间戳」在 **15 分钟**以内 ⇒ 看护活跃 ⇒ 本轮**只观察不动作**（记一行或直接退出）。
- 若本机存在**第二个**同类看护任务 ⇒ 立即在台账记异常，并**只保留一个**（不自行删除任务，交用户处置）。

**第 2 步｜探测在途**（三信号，缺一不可）：
1. **进程**：是否存在 `node` + `zcode.cjs` 的看护派发的实施进程（记 PID）；
2. **提交**：`git -C C:\Mnzm\XianxiaSectNative log --oneline -5` 是否出现本批 `feat(gacha): Gxx`；
3. **报告**：`docs/design/gacha-batches/report-Gxx.md` 是否存在且 mtime 晚于本批派发时间。
> 三者全无 ⇒ 视为**未开跑/空跑**（见第 4 步的卡死判定）。

**第 3 步｜交付核验**（在途且有新提交时执行，判据见 §4）。核验通过 → 台账记 `accepted`。

**第 4 步｜卡死判定**：
- 派发后 **60 分钟**内既无新提交也无报告更新 ⇒ 记 `stalled`，本轮**不重启**（避免双开），下一轮再观察；
- 连续 **2 轮** `stalled`（≥20 分钟无任何变化）且进程已不存在 ⇒ 记 `blocked`，**停止派发**，在台账写「给用户的处置建议」（如：进程被杀/需人工重派），本轮结束；
- 同一批次**门禁失败**且报告未提交 ⇒ 不派下一批，改为生成**同批修复会话**派发文本（§5 模板 B）。

**第 5 步｜决定动作**（四选一）：
| 条件 | 动作 |
|---|---|
| 有在途且未见交付 | 只观察（可记监控行） |
| 交付核验通过且还有未派批次 | §2 派发下一批（**新建会话**） |
| 交付核验通过且全部批次已派/已验 | §6 总收官 |
| 卡死/门禁失败 | §4/§5 相应处置 |

**第 6 步｜执行动作**（派发命令与文本组装见 §2、§5）。

**第 7 步｜记账**：把本轮结论追加到台账《当前状态》**最上方**（最新在上），然后
`git -C C:\Mnzm\XianxiaSectNative add docs/gacha-watch/dispatch-ledger.md && git commit -m "docs(gacha-watch): <一句话>"`。
无实质变化**不提交**。台账 Edit 报「modified since read」必须先重读再追加，追加锚点只能是当前最新条目。

---

## §2 派发命令（无头 CLI，不抢焦点，默认模型，新会话）

```powershell
$repo = 'C:\Mnzm\XianxiaSectNative'
$cli  = "$env:LOCALAPPDATA\Programs\ZCode\resources\glm\zcode.cjs"
$text = Get-Content "$repo\docs\gacha-watch\_dispatch-G10.md" -Raw   # §5 组装出的派发文本
node $cli --prompt $text --cwd $repo --mode yolo --json --no-color --surface terminal --locale zh-CN
```

**参数纪律（逐条不可改）**：
| 参数 | 作用 | 为什么必须这样 |
|---|---|---|
| `--prompt <全文>` | 单条提示词、**不开 TUI** | 无头、可无人值守 |
| `--cwd $repo` | 工作目录 = 主树 | 批次任务书都相对仓库根 |
| `--mode yolo` | 全权限（`--prompt` 的**默认值**，显式写出） | 允许改文件/跑门禁/提交，无需人工批准 |
| 🔴 **不传 `--resume` / `--continue`** | ⇒ 每次调用**新建会话** | 用户要求「每完成一批新开会话实施下一批」；带 resume 会串上下文 |
| 🔴 **不传模型参数、不执行 `/model`** | ⇒ 用**默认模型** | 用户要求「命令默认模型进行实施」；默认主模型 = `bigmodel/glm-5.1`（`~/.zcode/cli/config.json` 的 `model.main`，`lite = bigmodel/glm-4.7`） |
| `--surface terminal` | 终端呈现面 | 避免 `desktop` 面带来的窗口/焦点副作用 |
| `--json` | 机器可读输出 | 看护可解析 sessionId / 结果；**不做二次解析封装，只归档原文** |
| `--no-color` `--locale zh-CN` | 纯文本、中文 | 便于日志留存 |

**启动方式**：以**后台作业**方式起（不阻塞看护轮次），把 `node` 的 **PID** 与 CLI 输出的 `sessionId` 记进台账；
**绝不用 GUI**、**绝不切窗口**、**绝不注入按键**。派发后本轮即结束（不在同轮等待实施完成）。

**轮询节奏**：后续每轮 fire（`*/10`）只做第 2/3 步观测；实施会话可远超 10 分钟（G11 实测约 4 小时），
**10 分钟是看护频率，不是批次的时限**。

---

## §3 看护自身的调度（二选一，用户择一安装）

> ⚠️ 看护**不自行安装**定时任务；下列命令由用户执行（或由用户在 ZCode 侧栏「自动化」里创建）。

**方案 A（推荐：Windows 任务计划程序，与 ZCode 完全解耦）**
```powershell
# 每 10 分钟触发一次；把 <THIS_PROMPT_FILE> 的正文作为 fire 提示词交给看护智能体
schtasks /Create /TN "gacha-watch" /SC MINUTE /MO 10 /F `
  /TR "pwsh -NoProfile -File C:\Mnzm\XianxiaSectNative\docs\gacha-watch\fire.ps1"
# 查看 / 删除
schtasks /Query /TN "gacha-watch" /V /FO LIST
schtasks /Delete /TN "gacha-watch" /F
```
`fire.ps1` 的职责（薄脚本，不含业务逻辑）：把本文件全文 + 台账最新 30 行拼成 fire 文本，交给看护智能体入口。

**方案 B（ZCode 侧栏「自动化」）**：新建自动化、周期 `*/10`、提示词 = **本文件全文**。
🔴 若走方案 B，必须确认执行环境是**无头**的；**禁止**使用「GUI 六步派发」（那是上一套被删除的原因）。

---

## §4 交付核验口径（三要素 + 门禁抽验）

一批**只有同时满足**下列三项才算 `accepted`：

| # | 要素 | 判据（命令原文） |
|---|---|---|
| 1 | **收官笔** | `git log --oneline -20` 出现本批 `feat(gacha): Gxx …`，且该笔**含代码 + 测试 + 双 changelog + `report-Gxx.md`**（`git show --stat <sha>` 抽验文件名） |
| 2 | **报告** | `docs/design/gacha-batches/report-Gxx.md` 存在，含「门禁实测」节且**有命令原文数字**（不是「应该通过」） |
| 3 | **树净** | `git status --porcelain` 只允许 `docs/research/`×2（本就未跟踪）与看护自己的台账改动；构建副产物（`atlas-rgba-manifest.json`、`sprite-uid-map.json`）若为 ` M` ⇒ 记**未还原**（不判通过，交下一批或用户处理） |

**门禁抽验（看护只读复跑，不跑全量组合门）**：
- `node scripts/gen-action-ids.mjs` + `git diff --exit-code -- <两份产物>`（零漂移）；
- `node scripts/gen-game-data.mjs --check`；`node scripts/check-jni-count.mjs`；`node scripts/check-agent-instructions.mjs`；
- `git -C … show <本批 sha> --stat | tail -1`（规模对账）。
> 🔴 **G10 之前**：三条金黄红必须**逐条同名且 `DeterminismProbe actual=0xb4f3c6912207f597` 逐字符不变**；
> **G10 之后**：允许变化，但报告必须写明**新值**与「这是唯一一次重录窗口」的依据。
> 全量组合门（gradle）由**实施会话**跑并在报告里给数字；看护**不复跑**（避免与在途会话互锁）。

---

## §5 派发文本组装（两模板）

**模板 A｜新批次**
```
【Gxx <批名>】

■ 会话纪律：本会话**只实施本批**；完成后写 `report-Gxx.md` 并停止，不开始下一批；**不得自登记 accepted**（接受与否留用户/看护）；如实登记失败、未完成与 pending 项，禁止虚报；遇产品歧义按任务书口径执行并登记，不停下询问。

■ 施工面（唯一）：主树 `C:\Mnzm\XianxiaSectNative`（分支 `feat/gacha-m0-m1`）。开工前先 `git status` 确认树净；有他批在途改动就停手报告。提交一律明确文件名 `git add <file>`，禁止 `git add -A`。

■ 必读（按序）：① 本批任务书全文（下方插入）；② 根 `AGENTS.md` + 相关模块级 `AGENTS.md`；③ `docs/design/gacha-batches/EXECUTION-PROTOCOL.md`；④ 上一批 `report-Gxx.md` 的「交付事实/登记」节（下方附录）。

■ 本批任务书全文：
<<TASKBOOK-Gxx.md 全文>>

■ 前批交付事实（附录）：
<<从上批报告抽 5–8 条：终态契约/新增守卫/门禁基线数字/未做项>>

■ 门禁与交付：按任务书 §门禁 原样执行；交付 = 单次提交（代码+测试+双 changelog+报告）+ 树净。
```

**模板 B｜同批修复会话**（门禁失败或报告未提交时）
```
【Gxx 修复轮 N】

■ 背景：上一会话在第 <阶段> 失败/中断，证据：<报告缺失 | 门禁红原文抄录>。**不得重做已完成部分**，先 `git status` / `git log` 定位已落盘改动。
■ 任务：只修复失败面；**禁止扩大范围**；修完补齐 `report-Gxx.md` 的门禁节。
■ 其余纪律同模板 A。
```

**组装纪律**：派发文本 = 模板 + **任务书原文逐字**（不得摘要、不得改数字）；把完整文本先写入
`docs/gacha-watch/_dispatch-Gxx.md`（便于复核与复现），再用 §2 的命令喂给 CLI。

---

## §6 总收官（全部批次 accepted 后）

1. 台账顶部写 `status: completed` + 一句话总账（各批 sha、门禁终值、`pending-device` 累计）。
2. 复跑一次**只读**总门：node 四门 + `git log`/`status` 快照，结果写进台账。
3. **不删除**定时任务、**不删台账**（保留审计痕迹）；在台账写「可停用看护」的明确指令供用户执行。
4. 汇总 `pending-device` 清单（至少含：G11 真机 12 项 / 后续批新增项）交用户。

---

## §7 一页速查（看护每轮只读这一段也能干活）

```
轮次 = 读 fire 全文 → 读台账最新条
      → 锁在 15 分钟内？ 是 → 只观察，结束
      → 有在途？（进程 / 新提交 / 新报告）
           无 → 派下一批（§5 模板 A + §2 命令，后台起，记 PID+sessionId）
           有 → 交付核验（§4 三要素）
                通过 → 台账 accepted → 派下一批
                未通过且 <60min → 观察
                未通过且 ≥2 轮无变化 → blocked，停派，写处置建议
      → 台账追加一行 → 有实质变化才 commit（docs(gacha-watch): …）
```

**看护绝不做的四件事**：① 不碰 GUI/焦点；② 不自己实施批次；③ 不并发派发；④ 不替实施会话判 accepted。

---

## §4 看护台账（状态真源：批次总表 / 锁 / pending-device / 监控行）

> 源：`docs/gacha-watch/dispatch-ledger.md`（sha256 前 16 = `05e969c40f4157aa`）

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
| 1 | **G10** RNG 基线重录 + 全量回归收口 + 死代码 grep 清零 + 文档收口（M1 末批） | G02–G09/G11 已合入 | `pending` | — | — | — | 任务书已就绪；**唯一重录窗口** |
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
| 锁时间戳 | `2026-09-26 · 看护建立`（每轮 fire 有实质动作时更新；15 分钟内视为活跃） |
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

---

## §5.1 TASKBOOK-G10 · RNG 基线重录 + 全量回归收口 + 死代码清零 + 文档收口（M1 末批）

> 源：`docs/design/gacha-batches/TASKBOOK-G10.md`（sha256 前 16 = `b11f1346142d3368`）

# TASKBOOK-G10 · RNG 对拍基线重录 + 全量回归收口 + 死代码 grep 清零 + 文档收口

> **本文件是 G10 的派工真源**，取代 `HANDOVER-m1-remaining-3.md` §6.3 的转述（该节实测 **4 处计数错**，见 §2）
> 与 `docs/design/character-gacha-implementation.md` §G10（`:187-194`）的粗口径。
> **时点**：2026-09-26，M1 已完成 10/11（G11 收官 `a2923bced`）；本批 = **M1 末批**。
> **侦察方式**：1 个只读子代理穷举 9 份报告的登记节 + RNG 重录面 + 死代码面 + 文档面（逐条 file:line 实测）。
> **本批纪律（两条新增，因 G11 任务书的失误而定）**：① 引用一律写**仓库根相对完整路径**；
> ② 任务书内出现的任何代码片段必须已过项目红线（禁 `!!`／`runBlocking`／`Random`／直写 Store／硬编码数值）。

---

## 0. 位置、依赖与风险等级

| 项 | 内容 |
|---|---|
| 依赖 | G02–G09、G11 **全部已合入**（✅ 已满足）；G12/G13 依赖本批的基线窗口 |
| 出口判据 | ctest **全绿**（金黄已重录）＋ 全量 JUnit 绿 ＋ 死代码/死符号清零表归零 ＋ 文档改写清单归零 ＋ `report-G10.md` |
| 🔴 风险等级 | **本批是全项目唯一一次允许重录金黄/对拍基线的窗口**。任何「顺手改随机消费序」都会把这一窗口污染成两次（§7） |
| 不做 | 不做新功能、不动 `gacha_tx.h` 抽卡算法、不动 `seed + 12` 播种式、不动 `advancePhaseBaseline` 冻结基准、不动 `kProbeVersion`、不删列/不改 Room/不改 `@ProtoNumber`、不改版本号 |

---

## 1. 目标与验收判据

| 项 | 内容 |
|---|---|
| 目标 | ① 把 G02–G09 删除面造成的 RNG 平移**一次性**重录到位；② 全量回归跑绿；③ 把 9 批累积的死代码/死符号清零；④ 把活文档里"仍描述已删玩法"的段落改掉 |
| 验收① | 🔴 **金黄两处同步重录**：`determinism_probe.h:37` 的 `kGoldenDigest` **与** `android/app/src/androidTest/java/com/xianxia/sect/NativeFpDeterminismTest.kt:25` 的字符串常量**同批改成同一个新值**；改完 `ctest -R Determinism` 全绿（含 `DigestIsStableAcrossRepeatedRuns`） |
| 验收② | 三条红清零：`DiscipleFactory.GoldenSequenceSeed42`、`DiscipleFactory.GoldenSequenceSeed987654321Female`（字面量在 `test/disciple_factory_test.cpp:85-108` / `:122-145`）、`DeterminismProbeTest.DigestMatchesGoldenBaseline` ⇒ `ctest` **全绿** |
| 验收③ | `RngSourceGuardTest` 的**上限型登记值按实值下调**（假绿消除，见 §2-5）；`docs/rng-source-inventory.md` §2/§3 整行按 `grep` 重跑盘点 |
| 验收④ | 死代码/死符号清零表（§5）**逐条归零或改为"保留并给理由"**；新增/修改的守卫必须有判别力（退回旧态判红） |
| 验收⑤ | 文档改写清单（§6）逐条完成；`node scripts/check-agent-instructions.mjs` = ✓ 全部通过且**引用不精确计数不增长** |
| 验收⑥ | 全量 JUnit（`--rerun-tasks`）＋ detekt 六模块 0 ＋ lint 36 警告 0 error；`git status` 只留本批改动 |
| 验收⑦ | 双 changelog ＋ `report-G10.md` ＋ 单次提交（**允许拆多笔但必须都在本批内且报告写明**） |

---

## 2. 🔴 上位失真与实测修正（执行者必读）

| # | 上位记载 | 实测真值 | 后果 |
|---|---|---|---|
| 1 | `HANDOVER-3` §6.3「G02 保留项登记 **13** 条」 | 实为 **14 行**（`report-G02.md:69-84`，#1–#14） | 逐条处置会漏一条 |
| 2 | §6.3「G02 未完成/登记 **9** 条」 | 实为 **7 条顶层 bullet**（`report-G02.md:88-101`）＋ 11 行收口表（`:105-116`）＋ 2 条尾注 | 同上 |
| 3 | §6.3「G06 既有 ctest **4** 条」 | 实为 **3 条**（`report-G06.md:85`） | 计数对账错 |
| 4 | §6.3 **整表缺 G09 行** | `report-G09.md` §6.2 有 **9 条**登记需纳入 | 漏一整批 |
| 5 | 🔴 `RngSourceGuardTest` 被当作"计数守卫" | 它是**上限型**（`RngSourceGuardTest.kt:192 hits.size > limit`）⇒ G02–G11 只删不增时**恒假绿**；实测登记值与 `docs/rng-source-inventory.md` §2 **三处矛盾**（inventory 写 core/domain ②=5、core/engine ⑤=7、合计 21；守卫实值 core/domain ②=6 `:99`、⑤=19 `:102`、core/engine ②=14 `:105`、④=2 `:107`、⑤=5 `:110`、feature ②=1 `:125`、⑤=1 `:128`） | **死代码清零没有守卫背书**：必须同批把上限下调到实值 |
| 6 | 「`Diff*` 家族两侧同源 ⇒ 无需重录」（G03–G08 一路沿用） | **对两个新夹具不成立**：`DiffGachaPullTest`（`:492/:623` 黄金表字面量）与 `DiffGachaFragmentTest`（`:310/:387`）是**硬编码黄金值**，绑 `GACHA(12)` 新分区 + 固定种子 | 本批**不要动** `gacha_tx.h` / `seed + 12` 播种式，否则新增红并扩到 Kotlin 侧 |
| 7 | `report-G06.md:83`「`eraseDiscipleDerivedMaps` 生产消费方 = 0」 | **有生产调用**：`android/core/engine/src/main/java/com/xianxia/sect/core/engine/service/DiscipleLifecycleProcessor.kt:124`（`applyCombatInjury` 路径；定义在 `.../domain/disciple/DiscipleDerivedMapsCleanup.kt:14`） | 误删即破坏重伤链 |
| 8 | `report-G03.md:156`「`rootCount = 1;` 自赋值 2 处」 | **3 处**且行号漂移：`gamecore/include/gamecore/system/ai_sect_ops.h:164-165`、`.../disciple_stats.h:474-475`、`.../disciple_stats.h:523-524` | 漏清一处 |
| 9 | 待清符号 `clearAllDiscipleSlotsForRemoval` / `GameEngineLifecycleOps.initializeNewGameSuspend` / `FormulaService.calculateSuccessRateBonus` | **符号已不存在**（全仓 0 命中）⇒ 已由后续批解决，**不要再列** | 白跑 |
| 10 | `HANDOVER-3` §2.3「十八条坑」 | 现为 **26 条**；与本批强相关的 12 条：2 副产物／3 changelog 工具／5 读日志／9 手工复刻期望表／12 零漂移自证／13 detekt 口径／14 引用计数／16 死码（搬迁后旧位置同名成员）／17 退役集／18 类型不符／21+26 `.so` 判据 = mtime＋体积＋**sha256**／23 反例复跑必须 `--rerun-tasks` | 门禁漏项 |

---

## 3. 任务 A：RNG 基线重录（**本批唯一窗口**）

### 3.1 开工固证（先做，不可跳）
```powershell
git -C C:\Mnzm\XianxiaSectNative status --porcelain        # 树净（只允许 docs/research/ ×2）
git -C C:\Mnzm\XianxiaSectNative rev-parse HEAD
# 桌面 C++：<repo>\android\app\src\main\cpp\gamecore\build\desktop-test
ctest -N                                   # 用例总数（G11 基线 = 1437）
ctest -R Determinism --output-on-failure   # 抄下 actual= / golden= 原文
Get-ChildItem <repo>\android\core\engine\build\desktop-jni | Select Name,Length,LastWriteTime
(Get-FileHash <repo>\android\core\engine\build\desktop-jni\libgamecorejni.so -Algorithm SHA256).Hash
```

### 3.2 重录步骤（顺序固定）
1. 跑 `ctest` 取三条红的**断言原文**（`:85-108`、`:122-145` 的实际值、探针的 `actual`）。
2. **判定每条红是"预期的删除平移"还是"真缺陷"**：预期 ⇒ 重录；若出现**非三条**的红 ⇒ 先按 §6.1 的 A/B 判据定性，**A 类必修，B 类才登记**。
3. 重录金序列：把 `test/disciple_factory_test.cpp` 的期望常量改为实测值（**只改期望，不改被测逻辑**）。
4. 🔴 重录探针摘要：**同时**改两处 —— `gamecore/include/gamecore/determinism_probe.h:37` 的
   `kGoldenDigest` 与 `android/app/src/androidTest/java/com/xianxia/sect/NativeFpDeterminismTest.kt:25` 的字符串常量。
   **`determinism_probe.h` 的 `kProbeVersion` 不得改动**（改它 = 第二次基线重定）。
5. 🔴 `advancePhaseBaseline`（Kotlin 侧冻结基准）**不得改**：4 个 `Diff*` 夹具（`DiffPhaseSettlementTest.kt:68/370/552/573`、`DiffAuthoritativeTickTest.kt:481`、`DiffTimeTest.kt:56`、`DiffMonthSettlementFixture.kt:508`）由它驱动、**无录制值**；一改它们会红且**不是 B 类**，极易误判。
6. 复跑 `ctest`（**全绿**）+ `ctest -R Determinism`（两条都 Passed）。
7. 提交说明必须写明：**根因**（哪些批次的删除平移了序列）＋ 新 `kGoldenDigest` 值 ＋ 「唯一重录窗口」字样。

### 3.3 判据
- `ctest` 全绿；`DeterminismProbeTest.DigestMatchesGoldenBaseline` 与 `DigestIsStableAcrossRepeatedRuns` 均 Passed；
- `report-G10.md` 里记：旧值 → 新值、旧红集 → 新红集（空）、以及**「本批未改任何 RNG 消费次数/顺序」**的证据（`git diff --stat` 里 `disciple_factory.h` / `phase_settlement.h` / `month_settlement.h` / `year_settlement.h` 的 RNG 相关行**零改动**）。

---

## 4. 任务 B：全量回归收口

1. 桌面：`cmake --build .` ＋ `ctest`（全绿，见任务 A）。
2. 桌面 JNI 重建：`pwsh -File scripts/build-desktop-jni.ps1`（**工作目录 = 仓库根**；判据 = mtime ＋ 体积 ＋ **sha256** 三件套，见 §2-10）。
3. Kotlin 组合门：`compileReleaseKotlin testReleaseUnitTest --max-workers=1 --rerun-tasks --continue "-Dgamecore.jni.path=<.so 绝对路径>" detekt lintRelease`
   - 🔴 凡"退回旧态判红"的判别力实验，复跑必须带 `--rerun-tasks`（C++ 头不是 Gradle 任务输入，否则 UP-TO-DATE 假绿）。
4. 基线对照（G11 终树）：ctest **1437**；JUnit **7471/0/0/18**（app 1011 / domain 1571 / data 814 / engine 2936 / ui 151 / feature 988；692 XML）；detekt 六模块 0；lint 36 警告 0 error；ActionId 201/1872；JNI 86/86；Room v59；`game-data.json` sha256 `035066cb…94ef`；图集 41 精灵 / `layoutHash 6a122ed22d66bee5`。
5. 复跑 node 四门 + `catalog↔guard` 退役集（应仍 **24 == 24** 双向零差）。

---

## 5. 任务 C：死代码 / 死符号 grep 清零

### 5.1 ✅ 实测零调用 —— 可删（删前按名再 grep 一次，防 §2-7 式过期结论）
| 符号 | 定义位置 | 现状 |
|---|---|---|
| `NullSafeProtoBuf.relationIdToProto` / `relationIdFromProto` | `android/core/data/src/main/java/com/xianxia/sect/data/serialization/NullSafeProtoBuf.kt:376/378` | 仅自 KDoc（`:30/:34`）与自身测试引用 ⇒ **清零需同批改测试** |
| `NullableStringSerializer` / `NullableLongSerializer` | `.../data/serialization/Serializers.kt:14/28` | 全仓 0 引用 |
| `DiffMonthSettlementFixture.buildProductionProcessor` | `android/core/engine/src/test/java/com/xianxia/sect/core/nativebridge/DiffMonthSettlementFixture.kt:83` | 0 调用（`buildDiffService` 已不存在） |
| `BeastMaterialDatabase` 四个零消费者方法 | `android/core/domain/.../registry/BeastMaterialDatabase.kt:351/354/361/368` | 0 外部引用 |
| `DiscipleFacade.addDisciple` 全链 | `DiscipleFacade.kt:21` → `DiscipleFacadeImpl.kt:67` → `DiscipleService.kt:58` → `DiscipleLifecycleManager.kt:49`（另 `GameEngineDiscipleOps.kt:12`） | 唯一调用方是测试（`DiscipleServiceCrudTest.kt:103/115`）；守卫 `DiscipleCreationPathGuardTest.kt:109-110` 已把该口列为 `LEGACY_CRUD` ⇒ 删除需**同批改这两处测试** |
| `GameNotification.RecruitFailed` | `android/core/domain/src/main/java/com/xianxia/sect/core/state/GameNotification.kt:6` | **发布者 0**（消费分支 `android/feature/game/src/main/java/com/xianxia/sect/ui/game/components/GameOverlayHost.kt:466` 仍在）⇒ 删变体＋删分支，或改判保留（见 §7 D-3） |
| `MIN_AGE` | `android/core/domain/.../GameConfig.kt:126` | 仅 3 处测试引用 |
| `MailAttachment.extra` | `android/core/data/.../MailEntity.kt:21` | 仅测试引用（**删字段涉及 Room ⇒ 见 §7 D-4**） |
| `recruitListAggregates` | `android/feature/game/.../GameViewModel.kt:533` | 0 消费者 |
| `rootCount = 1;` 自赋值（**3 处**） | `.../system/ai_sect_ops.h:164-165`、`.../system/disciple_stats.h:474-475`、`:523-524` | 无效语句，直删 |
| 零调用色 helper | `android/core/ui/.../theme/Color.kt` 的 `getSpiritRootCountColor`、`SingleRoot…PentaRoot`、`XianxiaColorScheme.rarityColors`；转发壳 `WarehouseTab.kt:893`、`MerchantDialog.kt:679` 的 `getRarityColor` | G11 实测零调用方（G11 报告 §8-8） |

### 5.2 🔴 判为**不是**死码 —— 保留（防误删）
`eraseDiscipleDerivedMaps`（生产调用 `DiscipleLifecycleProcessor.kt:124`）、`recruitCountThisMonth`（多处生产读写 + C++ 契约）、
`kPermanentLife`（`gamecore/include/gamecore/system/pill_system.h:45/54/87/164`）、`notifyBreakthroughChanges`（`DiscipleBreakthroughHandler.kt:239`）、
`checkAndRepairMerchantAndRecruit`（`GameEngineLifecycleOps.kt:57/189`）。

### 5.3 🟠 需拍板后处置（不阻塞其余清零项）
- `prisonerSpiritRootFilter`：**字段仍活**（`GameData.kt:534`、`GameDataFieldPatch.kt:232`、`SectPolicyDomainState.kt:16/36/50`、`models.h:1251`、`lock_beast_tx.h:175/209/247`、`json_codec.cpp:1195/1286`），但 `AutoAssignDelegate.kt:88/93` 两个方法**零 UI 调用方** ⇒ **本批只删死 UI 入口**；字段删除需 Room 迁移 ⇒ 登记（§7 D-5）。
- `predefinedCodes`：`RedeemCodeManager.kt:87` 已是空 `mutableMapOf()`，仅 `:240` 由 `serverCode` 填充 ⇒ 编译期码表已空（运营口径，登记 G12）。

### 5.4 守卫同步（本批必做）
1. `RngSourceGuardTest`：把 §2-5 的**上限值下调到实测值**（只缩不增），并给每条登记加一句「实测值来源」注释。
2. `docs/rng-source-inventory.md`：§2（`:35-41`）与 §3 按 `grep -rn "RngPartition\."` **重跑盘点**；删掉死引用（`:99` `ChildBirthSystem:147`、`:143` `MerchantAndRecruitService:224`）；`:286` 的 `SYSTEM(3)`（35 处）整行按实值重写（其中 `LawEnforcement*`(9)、`ChildBirthSystem`、`PartnerSystem`、`RecruitService`、`GameEngineSpiritRootOps`、`TraitAddOps`、`TraitWashOps` **全部已删**）。
3. 每条死码删除都要有**按名 grep 零命中**的贴证（双向：删除模式归零 + 保留清单命中贴证）。

---

## 6. 任务 D：文档收口（活文档只描述当前状态）

### 6.1 需改写（逐文件逐行；行号为本轮实测）
| 文件 | 行 | 要改什么 |
|---|---|---|
| `docs/cpp-engine.md` | `:211`（血炼三件套）、`:212`（天赋/体质/词条 204）、`:621`（逐出/拜师/婚姻批准 —— G15 报告点名**优先**）、`:622`（仓库驻守/洗炼）、`:623`（招募列表）、`:561`/`:563`（执法堂/执法域） | 删条目或改注「已删」表述 ⇒ 只描述当前状态 |
| `docs/architecture.md` | `:97`（L3 月变树 反动/执法/叛逃）、`:99`（伴侣配对＋忠诚衰减）、`:102`（T1 年龄不变量/招募三件套/驻军报告）、`:109`/`:114`/`:117`、`:410`/`:414`（`DiscipleAgePositiveRule`/`AgeLifespanRule`） | 同步现况 |
| `docs/knowledge-base.md` | `:17`、`:201-215`（**整节「偷盗系统年上限」**，含 `:209/:211/:215`）、`:182`、`:194`（技能含 loyalty）、`:306`/`:310`、`:475`/`:476`（`ChildBirthSystem`/`renameDisciple`）、`:643`（跨宗道侣配对）、`:721`（偷盗损失经济行） | 同步现况 |
| `CODE_WIKI.md` | `:94`（逐出/拜师/婚姻批准 —— 点名**优先**）、`:93`/`:95`/`:96`、`:299`（`DiscipleDelegate` 招募/驱逐/道侣）、`:327`（lifespan/age）、`:549`（邮件/生育/道侣）、`:1018`（传功，待核）、`:306`（宗门/改名，待核） | 同步现况；Facade/Delegate 列表按现状（第 8 个 Facade 为 `GachaFacade`） |
| `docs/ui-read-surface.md` | `:124`（血炼、婚姻审批）、`:137`（手动/一键招募） | 同步现况 |

### 6.2 有意保留（**不回改**，按 `report-G04.md:154` 纪律）
`docs/cpp-engine.md:10-23/46-55/204-205/223-227/237-245/310-378/388-456/480-541/615-635`；
`CODE_WIKI.md:103-106`（「已退役，编号禁复用…以 catalog 为准」批量注记）；
`docs/ui-read-surface.md:9` 与 `:120-135/:149-165/:227/:274-334`；`docs/architecture.md:480-544`
（以上为 dated 取证段或退役注记）。

### 6.3 判据
`node scripts/check-agent-instructions.mjs` = ✓ 全部通过，且**规则③「引用路径不精确」计数不得高于改前**（改前 0 处）；
引用一律写仓库根相对完整路径（`docs/AGENTS.md` 四条规范）。

---

## 7. 决策（D-1…D-6）

| # | 决策 | 依据 |
|---|---|---|
| **D-1** | **重录只做"期望值"层**：只改测试期望常量 ＋ 探针两处 digest；**禁止**改被测逻辑、消费序、播种式 | 「唯一窗口」一旦掺入逻辑改动就变成两次重定（§0 风险） |
| **D-2** | **不动 `DiffGachaPullTest` / `DiffGachaFragmentTest` 的黄金表**（它们绑 `GACHA(12)` + 固定种子，G02–G11 未动抽卡算法 ⇒ 应继续绿）；**若出现新红 ⇒ 不进重录，先按 A/B 定性查因** | §2-6：这两张表是硬编码黄金值，不是同源对拍 |
| **D-3** | `GameNotification.RecruitFailed`：**发布者为 0 ⇒ 本批删除变体 + 删 `GameOverlayHost.kt:466` 分支**（若删分支触发渲染覆盖守卫，同批补齐） | 死事件清算是 G10 的职责；保留会造成"永远不可能发生的 UI 分支" |
| **D-4** | `MailAttachment.extra` / `prisonerSpiritRootFilter` **字段本体不动**（涉及 Room ⇒ Migration 成本与风险）⇒ 只删零调用 UI 入口，字段登记 | `rules/database-migration.md`：改 Entity 必须迁移；本批无迁移预算 |
| **D-5** | `MIN_AGE` 与零调用色 helper：**删**（纯 Kotlin 常量/函数，无协议面） | 无迁移风险 |
| **D-6** | 文档改写**只碰"活文档"**（`architecture`/`knowledge-base`/`cpp-engine`/`CODE_WIKI`/`ui-read-surface`），过程档案（`docs/report-*`、`docs/parallel-batches*`）不回改 | `docs/AGENTS.md` 目录性质表 |

---

## 8. 文件面与切片（≤10 文件/片；**一律写仓库根相对完整路径**）

| 片 | 允许改 | 自检项 |
|---|---|---|
| **T-10a** 探针与金序列重录 | `android/app/src/main/cpp/gamecore/include/gamecore/determinism_probe.h`、`android/app/src/main/cpp/gamecore/test/disciple_factory_test.cpp`、`android/app/src/androidTest/java/com/xianxia/sect/NativeFpDeterminismTest.kt` | 两处 digest 同值；`kProbeVersion` 未动；`ctest -R Determinism` 两条 Passed |
| **T-10b** 死码清零（C++） | `.../gamecore/include/gamecore/system/ai_sect_ops.h`、`.../system/disciple_stats.h` | `rootCount = 1;` 三处归零；`ctest` 不因删无效语句变红 |
| **A-10c** 死码清零（Kotlin 主源） | `android/core/data/.../serialization/NullSafeProtoBuf.kt`、`.../Serializers.kt`、`android/core/domain/.../GameConfig.kt`、`android/core/domain/.../state/GameNotification.kt`、`android/core/domain/.../registry/BeastMaterialDatabase.kt`、`android/core/ui/.../theme/Color.kt`、`android/feature/game/.../GameViewModel.kt`、`android/feature/game/.../components/GameOverlayHost.kt` | 每符号按名 grep 零命中；`GameOverlayHost` 删分支后 `DialogTypeRenderCoverageTest` 仍绿 |
| **A-10d** 死码清零（Facade 链） | `android/core/engine/.../domain/disciple/DiscipleFacade.kt`、`.../DiscipleFacadeImpl.kt`、`.../disciple/DiscipleService.kt`、`.../disciple/DiscipleLifecycleManager.kt`、`android/core/engine/.../GameEngineDiscipleOps.kt` | `DiscipleCreationPathGuardTest` 与 `DiscipleServiceCrudTest` 同批改；`LEGACY_CRUD` 白名单同步 |
| **c10-a** 测试面同步 | `android/core/data/src/test/.../NullSafeProtoBufTest.kt`、`android/core/engine/src/test/.../DiscipleServiceCrudTest.kt`、`android/core/engine/src/test/.../nativebridge/DiffMonthSettlementFixture.kt`、`android/core/engine/src/test/.../architecture/RngSourceGuardTest.kt` | 上限下调后**退回旧态必须判红**（判别力自证） |
| **D-10e** 文档收口 | `docs/cpp-engine.md`、`docs/architecture.md`、`docs/knowledge-base.md`、`CODE_WIKI.md`、`docs/ui-read-surface.md`、`docs/rng-source-inventory.md` | §6.1 清单归零；§6.2 保留清单未被动 |
| **主线程** | 双 changelog、`report-G10.md`、全门禁、单次提交（或本批内多笔且报告写明） | §9 |

---

## 9. 门禁清单（终树同轮重跑；判据 = 命令自己的输出原文）

```powershell
# 0) 固证（§3.1）+ 树净
# 1) 桌面
$env:PATH = "C:\Users\cp050\llvm-mingw\llvm-mingw-20260616-ucrt-x86_64\bin;C:\Users\cp050\llvm-mingw\llvm-mingw-20260616-ucrt-x86_64\x86_64-w64-mingw32\bin;$env:LOCALAPPDATA\Android\Sdk\cmake\3.22.1\bin;$env:PATH"
#   工作目录 = <repo>\android\app\src\main\cpp\gamecore\build\desktop-test
cmake --build . ; ctest                       # 目标：全绿（0 失败）
ctest -R Determinism                          # 两条都 Passed
ctest -R SceneEquivalence                     # 13/13
# 2) JNI 重建（工作目录 = 仓库根）
pwsh -File scripts/build-desktop-jni.ps1      # 判据：mtime + 体积 + sha256 三件套
# 3) Kotlin 组合门（工作目录 = android）
.\gradlew.bat compileReleaseKotlin testReleaseUnitTest --max-workers=1 --rerun-tasks --continue `
  "-Dgamecore.jni.path=<repo>\android\core\engine\build\desktop-jni\libgamecorejni.so" detekt lintRelease --console=plain
# 4) node 四门 + 退役集（工作目录 = 仓库根）
node scripts/gen-action-ids.mjs ; node scripts/gen-game-data.mjs --check
node scripts/check-jni-count.mjs ; node scripts/check-agent-instructions.mjs
```

**提交前**：`git status` 只留本批改动；构建副产物（`atlas-rgba-manifest.json`、`sprite-uid-map.json`）`git checkout --` 还原；
双 changelog 用**编辑工具**改（禁脚本重排整文件），改完 `node -e "JSON.parse(...)"` 校验。

---

## 10. 登记 / 待拍板

1. 🟠 `prisonerSpiritRootFilter` / `MailAttachment.extra` 字段删除（需 Room 迁移）—— 交后续清理批或产品拍板。
2. 🟠 `predefinedCodes` 置空后的运营下发通道（G12/商业化口径）。
3. 🟠 🔴 **测试基建债（G11 报告 §8-4 新发现）**：`android/core/engine/src/test/.../FakeGameStateStore.gameData` 是「每次属性访问新建一次性 `MutableStateFlow`」的**断线桩** ⇒ 用它测任何派生流刷新**必然假绿**；`FakeAtomicStateStore` 缺生产的 `!==` 提交守卫。**本批要求：至少给这两个替身加显式注释 + 在 `rules/testing.md` 登记**，收敛为生产语义替身可拆到精修批。
4. 🟠 旋转式流光（`Brush.sweepGradient` 的 `colorStops` 逐帧采样）与 `ItemCard`/`GachaRewardCell` 的共享"按 resId 画注册精灵"composable —— 精修批。
5. 🟠 旧档 `GarrisonSlot.discipleSpiritRootColor` 里持久化的旧色串要等该槽下次写入才刷新（显示串，不入 RNG 面）—— 观察项。

---

## 11. 一句话给执行者

**G10 只做四件事：把三条金黄红一次性重录到位（探针 digest **两处同改** ＋ 金序列期望值 ＋ **`kProbeVersion` 与 `advancePhaseBaseline` 绝不动**）、全量回归跑绿、
把 §5 的死代码按"按名 grep 零命中"清零并**同批把 `RngSourceGuardTest` 的上限下调到实值**（否则守卫一路假绿）、把 §6.1 的活文档段落改到只描述当前状态；
🔴 绝不动 `gacha_tx.h`、`seed + 12` 播种式与 `DiscipleFactory` 的随机消费序——那会把唯一的基线重录窗口污染成两次。**

---

## §5.2 TASKBOOK-G12 · 体验完成（M2-1）

> 源：`docs/design/gacha-batches/TASKBOOK-G12.md`（sha256 前 16 = `bfff4f78327b5d7f`）

# TASKBOOK-G12 · 体验完成（历史·公示·图鉴完整态·流光降级·引导·死文案清零·连抽打磨）

> **本文件是 G12 的派工真源**，取代 `docs/design/character-gacha-implementation.md` §G12（`:204-208`）的粗口径。
> **时点**：2026-09-26，G11 已收官（`a2923bced`），G10 为**前置**（本批在其后派发）。
> **侦察方式**：1 个只读子代理穷举（现状 file:line / 缺口 / 死文案 14 词 × 4 区计数），
> 关键结论由主线程回核（**其中 1 条被推翻，见 §2-1**）。
> **纪律**：引用一律写仓库根相对完整路径；任务书内代码片段必须已过项目红线（禁 `!!`/`Random`/直写 Store/硬编码数值）。

---

## 0. 位置与边界（**先看这条，避免重做 G11**）

| G11 已交付（**G12 不要重做**） | G12 的真实增量 |
|---|---|
| 寻访主界面（价签/保底 x10/余额/禁用态/四入口切换）、结果页 Q30 主体（2×5、单格居中、`×n`、点外关闭、叠下一轮、升星层时序）、**历史页骨架**、**概率公示页**、**图鉴最小态（6 格）**、Compose 层描边扫光 + `GpuTier.LOW` 静态降级、`GachaDelegate` 接线、Q31 色表单源（`GachaColors`） | ① 历史页**按抽粒度 + 保底标注 + x/10 同屏**；② 公示页**补"最高四阶"的池口径依据**；③ 图鉴**完整态**（升星预览/属性预览/立绘大图/来源）；④ **引导「打开寻访」**（G11 未碰 `GuideTask.kt`，实测零命中）；⑤ **死文案清零**（4 处真玩家可见）；⑥ 连抽打磨 4 项残余；⑦ 色板对齐债（旧 4 张物品色表 → Q31） |

---

## 1. 目标与验收判据

| 项 | 内容 |
|---|---|
| 目标 | 把「最简可用」抬到「体验完整」：历史能看出**按抽与保底**、公示能解释**为什么只到四阶**、图鉴能看出**养成收益**、新手引导能**把人带到寻访**、界面**不再出现已下线玩法的死文案** |
| 验收① | 历史页每行可按抽区分，**保底抽带标注**；页内可见 `x/threshold` 保底进度（与主界面同源，禁第二份计数） |
| 验收② | 公示页显示**每个类别的 `maxRarity` 依据**（最高四阶），数据全部来自 `GachaPoolConfig`（禁硬编码） |
| 验收③ | 图鉴完整态：6 格含**立绘（`portraitKey`，按需 decode）**＋星级＋下一星进度＋**升星收益预览**（战斗/修炼）；未解锁仍置灰 |
| 验收④ | 新手引导新增「打开寻访」步骤：判据走既有 `CumulativeCounter` 机制（**不新增 `GameData` 字段**），打开寻访即计数，步骤可完成且不卡死 |
| 验收⑤ | 死文案清零表（§7）**逐条归零或显式豁免**；清零范围与"不可清零区"边界见 §7 |
| 验收⑥ | 连抽打磨 4 项（§8）全绿，其中「保底更亮一档」按 §4.4 `:185` 落地 |
| 验收⑦ | 色板对齐债：`Color.kt` 四份物品色表 + `GameConfig.Rarity.CONFIGS` + `ItemCard.getQualityColor` 全部改为**引用 Q31 单源**（不再各写一套值）；仓储/奖励弹窗视觉随之统一 |
| 验收⑧ | 零新镜像字段 / 零反向通道 / UI 不直写 Store / 无新 tick；`ui-read-surface.md` 如新增读面须登记 |
| 验收⑨ | 全量 JUnit（`--rerun-tasks`）＋ detekt 六模块 0 ＋ lint 36 警告 0 error；`check-agent-instructions` 通过且**规则③「写法不精确」计数不增长** |
| 验收⑩ | 双 changelog ＋ `report-G12.md` ＋ 单次提交 |
| 不做 | 不做轮换池/UP（M3）；不做翻牌长动画；不做埋点上报（只登记）；**不动 G10 已定的 RNG 基线**（本批若改 `CharacterTemplateGuardTest` 的共享断言须与 G13 串行，见 §10 D-7） |

---

## 2. 🔴 上位记载 vs 实测（含**推翻**的一条）

| # | 记载 | 实测真值 | 后果 |
|---|---|---|---|
| 1 | 子代理初判：「主界面与结果页的按钮文案都该由『招募一次/十次』改成『寻访一次/十次』」 | ❌ **推翻**。产品方案两处口径**不同**：`:461`（寻访主界面行）= **「寻访一次/十次」**；`:187`（§4.4 结果页布局表）= **「左：招募一次 · 右：招募十次」**。实测现状：`GachaMainPanel.kt:319` = `"招募一次"`（**该改**）；`GachaResultLayer.kt:225` = `"招募一次"`（**按 `:187` 正确，不该改**） | 照子代理结论改结果页会**反而偏离产品规格** |
| 2 | G11 报告称「历史页已交付」 | ✅ 骨架已交付；但 `GachaRenderModel.kt:260` 的 `GachaHistoryRow` **只有 `monthLabel/displayName/quantity/colorHex`** ⇒ **无 `isPity`/无类别/无 `x/10`** | 历史页看不出保底（Q40 要求「保底附着标注」） |
| 3 | 产品 §4.4 `:185`「保底碎片：流光/背景可用**更亮一档**或附加『保底』角标」 | G11 只落了**角标**（`GachaRewardCell.kt:65-75`），**未见「亮一档」** | 差一半 |
| 4 | 「引导需要改判据防卡死」（G02 先例） | ✅ 但 G11 **完全没碰引导**：`GuideTaskRegistry.ALL_TASKS` 实测 23 条任务、id = **1–23 ＋ 25（无 24）**；`GuideCondition` 现 10 种条件**全部读状态**，**没有「打开某界面」型条件** | 需自行选路（§6） |
| 5 | 「死机制文案全库 grep 清零」 | ⚠️ 14 词 × 4 区实测合计 **244（Kotlin src/main）/ 51（C++）/ 0（assets/config）/ 101（changelog 历史条目）**；其中 **C++ 51 处全是注释/退役 desc**、Kotlin 区 240 处是**历史迁移注释与 ActionId 退役 desc（不可清零）** | 盲扫会误删迁移链与「ActionId 只增不复用」红线内容 |
| 6 | `architecture.md` 乘区表 | `:213` 写「`CultivationSpeedZones`（**4 乘区**：资源/社交/状态/临时）」⇒ 实为 **5**（多 `starBonus`）；`:215` 写「`BreakthroughZones`（长老指导/自身加成/**状态惩罚**）」⇒ 实为 `{baseZone, elderGuidance, selfBonus, adFlatBonus}` | 归 G14；本批只需知情 |

---

## 3. 任务 A：历史页「按抽 + 保底标注 + x/10 同屏」

- 数据面已齐：`GachaHistoryEntry.isPity`（`android/core/domain/src/main/java/com/xianxia/sect/core/model/GachaHistoryEntry.kt`）、
  环容量 `GameConfig.Gacha.HISTORY_RING_SIZE = 50`、保底计数 `GachaFacade.pityCounters`、阈值 `GachaPoolConfig.pool("standard")!!.pity.pullThreshold`。
- 改点：
  1. `android/feature/game/src/main/java/com/xianxia/sect/ui/game/GachaRenderModel.kt` 的 `GachaHistoryRow` **加** `isPity: Boolean`（可选加 `category`/`starLabel`）；`historyRows` 填充处同步；
  2. `android/feature/game/src/main/java/com/xianxia/sect/ui/game/dialogs/GachaHistoryPanel.kt` 的 `HistoryLine` 渲染保底标注（角标或更亮一档，与 §8-③ 同口径）；
  3. 页内加一行 `x/10` 保底进度（**读同一 `pityCounters` 流，禁自算**）。
- 判据：`android/feature/game/src/test/java/com/xianxia/sect/ui/game/GachaRenderModelTest.kt` 扩断言（含「历史下标 0 = 最新」与「保底行 `isPity=true`」两条）；禁用 `assumeTrue`。

---

## 4. 任务 B：公示页补「最高四阶」的池口径依据

- 现状：`GachaOddsPanel.kt` 已渲染类别权重/品阶权重/保底/价格，全部读 `GachaPoolReadModel`（`GachaRenderModel.kt:273`）。
- 缺口：`GachaPoolReadModel` **未带 `maxRarity`**；页面不解释「为什么最高只到四阶」。
- 改点：`GachaPoolReadModel` 加 `maxRarityPerKind`（或整体上限），`GachaOddsPanel` 品阶段补一行「池内最高四阶」；
  数据源 = `GachaPoolConfig.GachaCategorySpec.maxRarity`（配置侧已是 4；C++ 侧 `gacha_tx.h` 的 `clampedRarity` 强制截断）。
- 判据：`GachaConfigGuardTest.kt:112` 的「最高品阶 ≤ 4」断言保持绿；新增渲染/模型断言各一条。

---

## 5. 任务 C：图鉴完整态（相对 G11 最小态的 4 项增量）

| 增量 | 落点 | 备注 |
|---|---|---|
| ① **升星收益预览** | `GachaRenderModel.kt` 的 `GachaCodexCellModel` 加 `battleBonusText/cultivationBonusText`（**复用** `GachaStarUpModel` 已有的纯函数文案） | 纯函数化，便于单测 |
| ② **立绘大图** | `android/feature/game/src/main/java/com/xianxia/sect/ui/game/dialogs/GachaCodexPanel.kt` 的 cell 补 `portraitKey`（1024 档，**按需 decode**，禁加预载） | 与结果格 512 档 `avatarKey` 口径区分 |
| ③ **属性预览（`finalStats` 级）** | 🔴 **依赖产品拍板**：`report-G09.md` §六-1 明确「星级乘区是否进 `finalStats` 展示链」未定，且 `CachedPower` 指纹刻意不含星级 | 未拍板前**不做**，只登记（§13-1） |
| ④ **来源说明** | 静态文案「寻访所得」（模板表无来源字段） | 一句话 |

- 未解锁判据保持 G11 口径：`tid !in starMap`（**稀疏账本语义**：0 星不落键）。

---

## 6. 任务 D：引导「打开寻访」（两条路，**默认选 a**）

| 路 | 做法 | 代价 |
|---|---|---|
| **a（默认）** | 复用 `GuideCondition.CumulativeCounter`：新增 `GuideCounterKeys` 常量 + 在「打开寻访」处自增（写点建议 `GachaViewModel` 装载成功处或 `DialogFeatureRoutes.kt` 的 `renderRecruit` 入口）；任务条目 id **用 24（空号）或追加 26** | **零新增 `GameData` 字段**、零迁移；与既有 23 条任务同形 |
| b | 新增 `GuideCondition.DialogOpened(dialogType)` | 需新写点与新条件类型，超出「最简」；**除非**产品要求「必须证明是点开寻访而非计数器」 |

- id 选择：实测 id 集合 **1–23 ＋ 25，24 为空号**（历史 24 是血炼强化，随 G04 整拆）。
  ⇒ **D-2 决策：追加 id=26 而非复用 24**——旧档若残留 id 24 的进度，复用会让新步骤**开局即完成**（静默失效）。
- 判据：新步骤在真机/渲染测试里可被打开寻访推进到完成；`GuideTaskRegistry` 的既有断言（若有数量断言）同批更新；**引导不卡死**（打开寻访后步骤可完成、且不阻塞后续步骤解锁）。

---

## 7. 任务 E：死文案清零（**范围必须先划清**）

### 7.1 🔴 真·玩家可见、必清零（4 处，实测）
| # | 位置 | 现状 | 目标 |
|---|---|---|---|
| 1 | `android/feature/game/src/main/java/com/xianxia/sect/ui/game/dialogs/GachaMainPanel.kt:319-320` | `PULL_ONCE_TEXT = "招募一次"` / `PULL_TEN_TEXT = "招募十次"` | **「寻访一次 / 寻访十次」**（产品 `:461` 主界面口径） |
| 2 | `GameNotification.RecruitFailed` 消费分支 | `android/feature/game/src/main/java/com/xianxia/sect/ui/game/components/GameOverlayHost.kt:466` 的「招募失败」 | **归 G10 死码清零**（发布者已零；G11 已登记）⇒ 本批**不重复处置**，只在清零表里交叉引用 |
| 3 | `android/core/ui/src/main/java/com/xianxia/sect/ui/components/ElderBonusInfoButton.kt:322-327` 的 `recruitingElderInfo` | 文案描述「提升每年待招募弟子的刷新数量上限」——该效果**已随 G05 删除**，但 UI 仍引用（`TianshuHallDialog.kt:268-269`、`:513-523`） | 改为**当前真实效果**（纳徒长老在位只影响既有槽位/状态语义）或移除该说明项；**不得保留无效玩法说明** |
| 4 | `GachaResultLayer.kt:225-226` 的按钮文案 | `"招募一次"/"招募十次"` | ✅ **正确（产品 `:187`），不动**——列入「复核后确认不改」清单 |

### 7.2 区分类说明（**不可清零，误删即破坏红线**）
- **Kotlin `src/main` 其余 240 处**：`GameDatabaseMigrations*.kt`（历史迁移注释）、`ActionIds.kt`（「【已退役，编号禁复用】」desc）、`GameConfig.kt` 的旧常量、`BloodPoolBuildingCleanupRule.kt`/`RecruitListCleanupRule.kt`（读档修复提示）、`WorldMapGenerator.kt`（AI 宗门名）、宗门改名（**保留**功能）⇒ **一律不动**。
- **C++ 51 处**：全部是注释/退役 desc（非注释命中仅 `scene/float_text.h` 的「浮字**动画寿命**」= 假阳性）⇒ 零玩家可见文案。
- **`assets/config` / `assets/data` = 0 命中** ⇒ 无需处理。
- **`changelog_entries.json` 101 处**：全在**历史版本条目**内（描述当时下线的玩法）⇒ **只查当前版本条目，不追历史**。

### 7.3 判据
清零表格式：`关键词 | 区 | 清零前 | 清零后 | 处置（清零/豁免+理由）`；
**豁免必须写理由**（如「历史迁移注释，迁移链不可改写」）。

---

## 8. 任务 F：连抽打磨 4 项残余

| # | 项 | 现状 | 目标 |
|---|---|---|---|
| 1 | 叠轮时升星层游标 | 由 `key(resultToken)` 重建而"实测安全"，但**无守卫钉死这条假设** | 加渲染测试：连抽两轮后升星层只显示本轮条目 |
| 2 | Q39「点奖励框不关、不弹详情」 | 实现已有（`GachaResultLayer` 渲染层 `clickable` + 框内 `clickable`），**无测试** | 补 `GachaRecruitDialogTest` 一条 |
| 3 | 保底「更亮一档」 | 只有角标（§2-3） | `GachaRewardCell` 的填充/描边色按 `isPity` 提亮（与历史页保底标注同口径） |
| 4 | 数量角标可读性 | `×n` 白字位置已有，**无细描边/半透明底保证** | 按 §4.4「可加细描边/半透明底保证在彩色背景上可读」补一层 |

---

## 9. 任务 G：色板对齐债（Q31 单源化）

- 现状（G11 只保证寻访域 + 灵根徽章）：`android/core/ui/src/main/java/com/xianxia/sect/ui/theme/Color.kt` 的
  `RarityCommon…RarityHeaven`（`:40-52`）、`GameColors.getRarityColor`（`:110-118`）、同名顶层函数（`:164`）、
  归零后仅存的旧表消费点。
- 目标：把这些定义**改为引用 Q31 单源**（`GameConfig.Gacha.RARITY_COLORS` / `SPIRIT_ROOT_COUNT_COLORS`），
  消灭"同一品阶两套色值"；`UnifiedItemCard` 等消费点**不改行为**（只换色源）。
- ⚠️ 视觉影响面：仓储/奖励弹窗/详情页的品阶色会**统一到 Q31**（六阶粉红→金、五阶→红）——这是产品 `:216-218` 的**明确要求**；
  但会改动多处既有截图预期 ⇒ 实施时逐处登记。
- 判据：`GachaColorSingleSourceGuardTest` 扩为「全仓不得存在与 Q31 冲突的品阶色字面量」（源码扫描）；
  该守卫必须有**判别力自证**（退回旧值判红）。

---

## 10. 决策（D-1…D-7）

| # | 决策 | 依据 |
|---|---|---|
| **D-1** | 主界面按钮改「寻访一次/十次」；**结果页保持「招募一次/十次」** | §2-1：产品 `:461` 与 `:187` 两处口径不同，按各自上下文执行 |
| **D-2** | 引导任务 **id = 26**（不复用空号 24） | 复用会让旧档残留 id 24 的进度把新步骤"开局即完成" |
| **D-3** | 引导判据走 `CumulativeCounter`（**不新增 `GameData` 字段**） | 零迁移、零协议面；与 `AUTO_MINE_ACTIVATED` 同形 |
| **D-4** | 图鉴「属性预览」**未拍板前不做**，只登记 | `report-G09.md` §六-1 未定；`CachedPower` 指纹刻意不含星级（改了会平移既有指纹） |
| **D-5** | 死文案清零**只做 4 处真玩家可见**；历史迁移注释/退役 desc/历史 changelog **显式豁免并写理由** | §7.2：误删破坏迁移链与 ActionId 红线 |
| **D-6** | 色板对齐**改定义点、不改消费点行为**；视觉变化逐处登记 | 产品 `:216-218` 要求单一源；减小回归面 |
| **D-7** | 🔴 **`CharacterTemplateGuardTest.kt` 是 G12/G13/G14 的共享编辑面** ⇒ 本批若需触碰，先确认 G13 未在途 | 三批并行会互撞（G13 也要改三向常量断言） |

---

## 11. 文件面与切片（≤10 文件/片；全路径）

| 片 | 允许改 | 自检项 |
|---|---|---|
| **A-12a** 历史页 | `android/feature/game/src/main/java/com/xianxia/sect/ui/game/GachaRenderModel.kt`、`.../ui/game/dialogs/GachaHistoryPanel.kt` | `isPity` 进模型；`x/10` 读同一流 |
| **A-12b** 公示页 | `.../ui/game/dialogs/GachaOddsPanel.kt`、`.../ui/game/GachaRenderModel.kt`（`GachaPoolReadModel`） | `maxRarity` 依据来自配置 |
| **A-12c** 图鉴完整态 | `.../ui/game/dialogs/GachaCodexPanel.kt`、`.../ui/game/GachaRenderModel.kt` | 立绘按需 decode；未解锁判据不变 |
| **A-12d** 引导 | `android/core/domain/src/main/java/com/xianxia/sect/core/model/guide/GuideTask.kt`、`android/core/domain/.../guide/GuideCounterKeys*.kt`（若存在）、打开寻访写点（`.../ui/game/GachaViewModel.kt` 或 `.../components/dialog/DialogFeatureRoutes.kt`） | id=26；零新 `GameData` 字段；不卡死 |
| **A-12e** 死文案 | `.../ui/game/dialogs/GachaMainPanel.kt`、`android/core/ui/src/main/java/com/xianxia/sect/ui/components/ElderBonusInfoButton.kt` | 4 处清零表 |
| **A-12f** 连抽打磨 | `.../ui/game/dialogs/GachaResultLayer.kt`、`.../ui/game/dialogs/GachaRewardCell.kt` | 4 项；保底提亮 |
| **A-12g** 色板对齐 | `android/core/ui/src/main/java/com/xianxia/sect/ui/theme/Color.kt`、（必要时）`.../ui/components/ItemCard.kt` | 定义点改引用；消费点行为不变 |
| **c12-a** 测试 | `android/feature/game/src/test/.../ui/game/GachaRenderModelTest.kt`、`.../ui/game/dialogs/GachaRecruitDialogTest.kt`、`android/core/engine/src/test/.../nativebridge/GachaColorSingleSourceGuardTest.kt`、引导相关既有测试 | 判别力自证；禁 `assumeTrue` |
| **主线程** | 双 changelog、`report-G12.md`、门禁、提交 | §12 |

---

## 12. 门禁清单（终树同轮重跑；判据 = 命令输出原文）

```powershell
# C++（本批预计只碰 C++ 显示串/注释；若碰了必跑）
# 工作目录 <repo>\android\app\src\main\cpp\gamecore\build\desktop-test
cmake --build . ; ctest               # G10 之后应为全绿；本批不允许新增红
ctest -R SceneEquivalence
# JNI（C++ 有实质改动才重建；判据 = mtime + 体积 + sha256）
pwsh -File scripts/build-desktop-jni.ps1
# Kotlin 组合门（工作目录 android）
.\gradlew.bat compileReleaseKotlin testReleaseUnitTest --max-workers=1 --rerun-tasks --continue `
  "-Dgamecore.jni.path=<repo>\android\core\engine\build\desktop-jni\libgamecorejni.so" detekt lintRelease --console=plain
# node 四门（工作目录 = 仓库根）
node scripts/gen-action-ids.mjs ; node scripts/gen-game-data.mjs --check
node scripts/check-jni-count.mjs ; node scripts/check-agent-instructions.mjs
```

**真机**：本批与 `report-G11.md` §7 的 D-1…D-12 **同一台设备同一轮**验证（M1 完成判据 + M2 验收"抽卡→解锁→养成全链真机走通"合并执行）；
未做项登记 `report-G12.md` 的 pending-device 节。

---

## 13. 登记 / 待拍板

1. 🔴 **产品拍板**：星级乘区**是否进 `finalStats` 展示链**（决定图鉴「属性预览」做不做；`report-G09.md` §六-1）。
2. 🟠 引导「打开寻访」的**触发时机**（进主界面即算 / 必须点过一次抽卡才算）——默认：**打开寻访主界面即算**。
3. 🟠 埋点事件名 `gacha_pull` / `gacha_unlock` 仍只登记不上报（商业化批处理）。
4. 🟠 旋转式流光（`Brush.sweepGradient` 的 `colorStops` 逐帧采样或升级 Compose）——精修批。
5. 🟠 `ItemCard`/`GachaRewardCell` 的共享"按 resId 画注册精灵"composable —— 精修批。

---

## 14. 一句话给执行者

**G12 = G11 的"完整态 delta"，不是重做**：历史页补「按抽 + 保底标注 + x/10」、公示页补「最高四阶」依据、图鉴补立绘与升星收益预览（属性预览待产品拍板）、
引导新增「打开寻访」步骤（id=26、走 `CumulativeCounter`、零新字段）、**死文案只清 4 处真玩家可见**（历史迁移注释与退役 desc 一律豁免并写理由）、
连抽补 4 项残余（含保底「更亮一档」）、色板把旧四张物品色表改成引用 Q31 单源；
🔴 主界面按钮改「寻访一次/十次」而**结果页保持「招募一次/十次」**（产品两处口径不同，别统一）。

---

## §5.3 TASKBOOK-G13 · 数值落地（M2-2）

> 源：`docs/design/gacha-batches/TASKBOOK-G13.md`（sha256 前 16 = `529f4a3a66606e03`）

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

---

## §5.4 TASKBOOK-G14 · 文档与发布收口（M2-3）

> 源：`docs/design/gacha-batches/TASKBOOK-G14.md`（sha256 前 16 = `ba83c19cebf8b076`）

# TASKBOOK-G14 · 文档与发布收口（双 changelog · CODE_WIKI · architecture · 验收报告 · 死代码死文案终稿）

> **本文件是 G14 的派工真源**，取代 `docs/design/character-gacha-implementation.md` §G14（`:216-224`）的粗口径。
> **时点**：2026-09-26；**依赖 G11–G13 全部合入**（本批是 M2 末批，也是整个 G 批的**文档硬门**）。
> **侦察方式**：1 个只读子代理穷举文档现状（逐行 file:line）＋ 主线程回核（版本号三方不一致、Facade/Delegate 计数均**已实测确认**）。
> **纪律**：引用写全路径（`docs/AGENTS.md` 四条规范）；**新增引用不得顶高规则③的"写法不精确"计数**（`HANDOVER-3` 坑 14）；
> 本批**禁止擅自更新版本号**（`rules/version-release.md:8`：由用户判断和指令）。

---

## 0. 与 G10 的边界（**先看，避免重复劳动**）

| 批次 | 负责的文档改动 |
|---|---|
| **G10**（已派） | **「仍描述已删玩法」的表述**：`docs/cpp-engine.md` / `docs/architecture.md` / `docs/knowledge-base.md` / `CODE_WIKI.md` / `docs/ui-read-surface.md` 的删除面改写 ＋ `docs/rng-source-inventory.md` §2/§3 重跑盘点 |
| **G14**（本批） | **「结构与计数」的表述**：版本号三方归一、双 changelog 终稿、`CODE_WIKI.md` 的 Facade/Delegate/ActionId **计数与列表**、`architecture.md` 的**乘区表字段数与扩展点钩子**、`report-G14-completion.md`、死代码/死文案清零表**终稿** |

> 边界判据：**"某个玩法已删 → 段落该删/改" 归 G10；"设施数量/版本/列表口径不对 → 该数字该改" 归 G14。**

---

## 1. 目标与验收判据

| 项 | 内容 |
|---|---|
| 目标 | 让「文档 = 代码现状」在**结构、计数、版本、列表**四个维度成立，并给出可审计的批次收官报告 |
| 验收① | 🔴 **版本号三方归一为 `4.01.16`**（✅ 用户 2026-09-26 已拍板，§3.1）：`version.properties`（现 `4.01.14`/`4114` → `4.01.16`/`4116`）、`CHANGELOG.md` 段头（现已是 `4.01.16`，不动）、`android/app/src/main/assets/changelog_entries.json`（现重复 4 条 `4.01.14` → 合并为唯一 `4.01.16` 条）三者一致 |
| 验收② | 双 changelog **终稿齐备且同版本**：玩家向条目符合「无专业术语/不泄露数值/只粗略描述」，技术向条目覆盖 G12/G13；**禁止按日期拆多个同版本条目** |
| 验收③ | `CODE_WIKI.md` 六处计数/列表修正（§4）逐条完成：Facade **7 → 12**、Delegate **9 → 27**、ActionId **166/1712 → 201/1872**、退役 **21 → 24**、目录树补 `gacha/` 等、`DiscipleDelegate` 的已删玩法说明 |
| 验收④ | `docs/architecture.md` 五处修正（§5）逐条完成：`CultivationSpeedZones` **4 → 5 乘区**、`BreakthroughZones` 字段名对齐代码、补「星级进战力」一行、扩展点补寻访钩子、结算列表声明「寻访不属于第五层结算」 |
| 验收⑤ | `docs/report-G14-completion.md` 落盘，按 §6 的七节结构（含门禁实证表 + 关键实施事实 + 诚实残余 + pending-device） |
| 验收⑥ | 死代码 + **死文案** grep 清零表**终稿**（含 G12 的 14 词 × 4 区计数：清零前后对照；豁免项写理由） |
| 验收⑦ | `node scripts/check-agent-instructions.mjs` = ✓ 全部通过，且**规则③「引用路径不精确」计数不高于改前（改前 0 处）** |
| 验收⑧ | 提交前过 `rules/pr-review-checklist.md` 与 `rules/version-release.md`；单次提交（或本批内多笔且报告写明） |
| 不做 | 不改代码逻辑（纯文档批）；不改版本号（除非用户明确指令）；不回改过程档案（`docs/report-*`、`docs/parallel-batches*`）；不动 `docs/design/gacha-batches/` 的历史报告 |

---

## 2. 🔴 实测硬伤（本批必须解决，逐条已核）

| # | 硬伤 | 实测证据 | 性质 |
|---|---|---|---|
| 1 | **版本号三方不一致** | `version.properties`：`versionName=4.01.14` / `versionCode=4114`；`CHANGELOG.md:1` = `## [4.01.16] - 2026-09-22`；`changelog_entries.json` 首条 `"version":"4.01.14"` | 🔴 发布口径断裂 |
| 2 | **`changelog_entries.json` 同版本条目重复 4 条** | `"version":"4.01.14"` 在该文件出现 **4 次** | 🔴 违反 `rules/version-release.md:53`「同日同版本一律并入同一条目」 |
| 3 | `CODE_WIKI.md` 计数全面过期 | `:228`/`:236` 写「**7 个**领域 Facade 接口」⇒ 实存 **12**（漏 Cultivation/Economy/Exploration/**Gacha**/Road）；`:295` 写「**9 个** Delegate」⇒ `feature/game/.../ui/game/delegate/` 实存 **27 个 `.kt`**；`:102-106` 旁注写「198 动作 / maxId 1861 / 退役 21」⇒ 实为 **201 / 1872 / 24**；`:75-76` 写「166 动作 / maxId 1712 / 31 handler」 | 🔴 `docs/AGENTS.md` 文档同步义务 |
| 4 | `architecture.md` 乘区表与代码不符 | `:213`「`CultivationSpeedZones`（**4 乘区**：资源/社交/状态/临时）」⇒ 实为 **5**（多 `starBonus`）；`:215`「`BreakthroughZones`（长老指导/自身加成/**状态惩罚**）」⇒ 代码字段为 `{baseZone, elderGuidance, selfBonus, adFlatBonus}` | 🔴 同上 |
| 5 | `CODE_WIKI.md:299` `DiscipleDelegate.kt` 说明仍写「弟子管理（**招募/驱逐**/装备/**道侣**）」 | 三者均已删除（G05/G06/G03） | 归 G10 边界内（"已删玩法"），本批只需**交叉确认** G10 已改 |

---

## 3. 任务 A：双 changelog 与版本号归一

### 3.1 ✅ 目标版本号（**用户 2026-09-26 已拍板 = `4.01.16`**）

三方归一目标 = **`4.01.16`**（对齐 `CHANGELOG.md` 顶部段头）：

| 文件 | 改前 | 改后 |
|---|---|---|
| `CHANGELOG.md:1` | `## [4.01.16] - 2026-09-22` | **不动**（G12/G13 的小节并入该段内） |
| `version.properties` | `versionName=4.01.14` / `versionCode=4114` | `versionName=4.01.16` / `versionCode=4116` |
| `android/app/src/main/assets/changelog_entries.json` | 首条 `"version":"4.01.14"`（**重复 4 条**） | 合并为**唯一条** `"version":"4.01.16"`，`date` 保持首次发布日 `2026-09-23` |

🔴 判据：三者版本号**完全相同**且 `versionName` 形如 `X.XX.XX`（禁 `4.0.16`）；
`rules/version-release.md:8` 的「禁止擅自更新版本号」已由本次用户拍板满足 ⇒ 本批按上表执行即可。

### 3.2 条目合并（不依赖 §3.1）
1. `android/app/src/main/assets/changelog_entries.json`：把 **4 条同版本 `4.01.14` 条目合并为 1 条**，
   `changes` 数组按时间顺序保留全部内容（**只增不减**），`date` 取首次发布日（`2026-09-23`）不改；
   追加 G12/G13 的**玩家向**条目（通俗、无数值细节）。
2. `CHANGELOG.md`：在**当前版本段内**追加 G12/G13 小节（技术向，可写实现细节）；若 §3.1 定为 (a)/(c)，同步段头版本号。
3. 改完校验：`node -e "JSON.parse(require('fs').readFileSync('android/app/src/main/assets/changelog_entries.json','utf8'))"`；
   **用编辑工具改，禁脚本重排整文件**（`HANDOVER-3` 坑 3：G04 曾把 2,474 行全文件重写导致审查不可读）。
4. 判据：同版本条目**唯一**；`version.properties` / `CHANGELOG.md` 段头 / `changelog_entries.json` 版本号**三者相同**且 `versionName` 形如 `X.XX.XX`。

---

## 4. 任务 B：`CODE_WIKI.md` 六处

| # | 行 | 现状 | 改成 |
|---|---|---|---|
| 1 | `:228`、`:236` | 「**7 个**领域 Facade 接口」 | **12 个**，并列全 12 个域（补 `Cultivation`/`Economy`/`Exploration`/`Gacha`/`Road`） |
| 2 | `:236-243` | 七行 Facade 清单 | 补 5 行；`GachaFacade` 的说明写「寻访域：抽卡/保底/碎片/升星/入库（C++ AUTHORITATIVE，`gacha_tx.h`）」 |
| 3 | `:249-258` | 目录树缺 `gacha/`、`cultivation/`、`economy/`、`road/` | 补四行 |
| 4 | `:294-307` | 「**9 个** Delegate」 | 按实存 **27** 个 `.kt` 重写（至少补齐 `GachaDelegate` 与其余遗漏项；若数量断言不可维护，改为「按域分组列出」并注明"以 `ui/game/delegate/` 实际文件为准"） |
| 5 | `:75-76`、`:102-106` | ActionId 计数 `166/1712`、`198/1861`、退役 `21` | **201 / maxId 1872 / 退役 24**（口径见 `HANDOVER-m1-remaining-3.md` §2.2 的 ActionId 行）；台账表补 `1870–1872` 抽卡段 |
| 6 | `:81-100` 台账表 | 缺抽卡段 | 补 `1870 GACHA_FRAGMENT_GRANT_TX` / `1871 GACHA_PULL_ONCE` / `1872 GACHA_PULL_TEN` |

**判据**：每个数字都能用一条命令复现（`node scripts/gen-action-ids.mjs` 的输出、`ls` 计数）——报告里贴命令与输出。

---

## 5. 任务 C：`docs/architecture.md` 五处

| # | 行 | 改成 |
|---|---|---|
| 1 | `:213` | `CultivationSpeedZones` **5 乘区**（资源/社交/状态/临时/**星级 `starBonus`**），注明口径 A（`1★` 基线 ×1.00）与单源 `GameConfig.Gacha.STAR_CULT_PCT_PER_STAR` |
| 2 | `:215` | `BreakthroughZones` 字段名与代码一致（`baseZone`/`elderGuidance`/`selfBonus`/`adFlatBonus`）；若 G13 已改 clamp 上限则同步 |
| 3 | 乘区表 | 补一行「**星级进战力**」：`SectCombatPower`（Kotlin）与 `sect_power`（C++）同乘区、`star_zone.h` 单源、纳入对拍 |
| 4 | `:309-333` 扩展点段 | 补寻访域钩子：轮换池/UP（`poolId` 级 pity、池开关）、埋点 `gacha_pull`/`gacha_unlock`、付费抽入口位（依据 `docs/character-gacha-redesign-2026-09-23.md:721-732`） |
| 5 | `:188-199` GameSystem 段 | 声明「**寻访不属于第五层结算**，不注册月/年回调；保底不进年变 T1/T2」（依据同文件 §15.1/§15.4） |

**判据**：表内每个 `Zones` 名与代码 `grep "data class .*Zones"` 的**字段数**一致；报告贴 grep 输出。

---

## 6. 任务 D：`docs/report-G14-completion.md`（新建，七节结构）

参照 `docs/report-MR4-completion-2026-09-23.md` 的结构：

| 节 | 内容 |
|---|---|
| 头部 | `> 批次 · 施工面（主树/分支）· 日期 · 性质（文档批）· 验收状态（**不得自登记 accepted**）· 方案指针 · 范围纪律` |
| 一 | **任务逐条交付**：本任务书 §1 的八条判据逐条 `✅` + 落点 + 证据 |
| 二 | **门禁实证**（表格：门禁 ‖ 口径 ‖ 结果），含 `check-agent-instructions` 五条规则原文 |
| 三 | **关键实施事实**（供总收官引用）：版本归一结论、CODE_WIKI/architecture 逐处改动、死文案清零表终稿 |
| 四 | **诚实残余与过程记录**（表：项 ‖ 状态）——含"未经用户/看护验收""未做项" |
| 五 | **pending-device 清单**（汇总 M1 的 G11 12 项 + M2 新增项） |
| 六 | **收官笔材料清单**（checkbox） |
| 七 | **提交前建议复跑**（代码块） |

---

## 7. 任务 E：死代码 + 死文案清零表终稿

- 素材来源：`TASKBOOK-G10.md` §5（死代码清零）＋ `TASKBOOK-G12.md` §7（死文案 14 词 × 4 区）＋ 各批报告的登记项。
- 格式：`类别 | 符号/关键词 | 定义位置 | 清零前 | 清零后 | 处置（清零 / 豁免+理由）`。
- 🔴 必须写清**豁免理由**（历史迁移注释、`ActionIds.kt` 退役 desc、历史 changelog 条目、`GameDatabaseMigrations*`）。
- 判据：清零表内**没有"待定"**；每条要么归零、要么有书面豁免。

---

## 8. 决策（D-1…D-5）

| # | 决策 | 依据 |
|---|---|---|
| **D-1** | ✅ **版本号目标 = `4.01.16`**（用户 2026-09-26 拍板）⇒ 三个文件按 §3.1 表归一 | `rules/version-release.md:8` 的"由用户判断指令"已满足 |
| **D-2** | `changelog_entries.json` 的 4 条同版本**合并为 1 条**，内容只增不减 | 同上 `:53` |
| **D-3** | `CODE_WIKI.md` 的 Delegate 计数改为**按域分组 + 以实际文件为准**的表述（避免"27"这类易腐计数） | 计数型表述注定漂移 |
| **D-4** | 与 G10 的边界按 §0 判据执行；G10 已改的段本批**不重复改**，只在报告里交叉确认 | 避免双改冲突 |
| **D-5** | 本批**不新建**"总验收报告"以外的新文档；`report-G14-completion.md` 是唯一新增文件 | 文档收敛 |

---

## 9. 文件面与切片（全路径）

| 片 | 允许改 |
|---|---|
| **D-14a** changelog 与版本 | `CHANGELOG.md`、`android/app/src/main/assets/changelog_entries.json`、`version.properties`（**仅当用户已拍板**） |
| **D-14b** CODE_WIKI | `CODE_WIKI.md` |
| **D-14c** architecture | `docs/architecture.md` |
| **D-14d** 收官报告 | `docs/report-G14-completion.md`（新建）、`docs/design/character-gacha-implementation.md` 的 Report 回填（若该文件有 Report 节） |
| **主线程** | 死代码/死文案清零表终稿（并入 `report-G14-completion.md`）、文档门禁、提交 |

---

## 10. 门禁清单

```powershell
# 文档门禁（本批唯一硬门；判据 = 五条规则全 ✓ 且规则③"写法不精确"计数不增长）
node scripts/check-agent-instructions.mjs
# JSON 校验（changelog_entries.json 改后必跑）
node -e "JSON.parse(require('fs').readFileSync('android/app/src/main/assets/changelog_entries.json','utf8'));console.log('JSON OK')"
# 计数复现（CODE_WIKI 里每个数字都要有命令支撑）
node scripts/gen-action-ids.mjs
Get-ChildItem android/feature/game/src/main/java/com/xianxia/sect/ui/game/delegate -Filter *.kt | Measure-Object
Get-ChildItem android/core/engine/src/main/java/com/xianxia/sect/core/engine/domain -Recurse -Filter *Facade.kt | Where-Object { $_.Name -notmatch 'Impl' } | Measure-Object
```

**提交前**：`git status` 只留本批改动；`docs/research/`×2 不入库；报告里**不得**写「accepted」（留用户/看护）。

---

## 11. 登记 / 待拍板

1. ✅ **版本号目标已拍板 = `4.01.16`**（用户 2026-09-26）——本批**已无用户阻塞项**。
2. 🟠 M1 的「G11 真机通」与 M2 的「全链真机走通」**同一台设备同一轮**（见 `report-G11.md` §7 的 D-1…D-12）。
3. 🟠 若 `docs/design/character-gacha-implementation.md` 的批次表需要标注「15 批全部完成」，由本批在 Report 节回填（但不改历史批次描述）。

---

## 12. 一句话给执行者

**G14 是文档硬门，只做四件事：把版本号三方归一为 `4.01.16` 并把双 changelog 合并成"同版本唯一条目"（玩家向/技术向各一）、
把 `CODE_WIKI.md` 的 Facade 7→12 / Delegate 9→27 / ActionId 166·1712·198·1861·退役21 → 201·1872·退役24 六处数字改成现状、
把 `architecture.md` 的乘区表（4→5 乘区、`BreakthroughZones` 字段名、补星级进战力行、补寻访扩展钩子、声明寻访不属第五层结算）改准、
产出 `report-G14-completion.md` 并把死代码/死文案清零表收成终稿；
🔴 版本号不许自选、引用必须写全路径（否则规则③计数被顶高而门禁仍显示"通过"）。**

<!-- 总包结束：共 6 份源文件；重新生成见文件头。 -->

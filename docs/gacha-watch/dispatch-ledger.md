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

- **2026-09-27 03:5x 用户拍板「任其落错，事后手术」**：G14 在当前分支 `refactor/remove-2x-speed` 上交付与验收照常（核验按文件面区分 footprint）；**G14 accepted 后看护执行分支手术**——①`cherry-pick` G14 收官笔（及其报告/变更）至 `feat/gacha-m0-m1` ②把 `refactor/remove-2x-speed` 重置回 G14 落笔前的原点（含清理误落的两笔看护台账登记，其中台账内容已快进在 feat 线无损失）③手术前置条件 = **用户确认他线会话已空闲**（重置他线分支需他线不在途），届时看护会先问再动。当前树 = `refactor/remove-2x-speed`（含 03:33 登记 `175bf2ff1` + 本笔 `0639ee586` 两笔看护登记 + 他线 5 代码文件未提交改动）。
- **2026-09-27 03:4x 🔴 分支被他线切换：主树现处 `refactor/remove-2x-speed`（G14 收官笔将落错分支）——停一切 git 分支操作，待用户裁决**：实测他线会话（remove-2x-speed 线）已把主树从 `feat/gacha-m0-m1` 切到其分支（其未提交的 5 个存档面代码文件随之在树）。**看护已做无损处置**：误落在该分支的台账笔 `175bf2ff1` 已 `git branch -f feat/gacha-m0-m1 175bf2ff1` 快进回 feat 线（分支指针操作、不动工作树、不打扰他线会话；现两分支同含此笔）。**未决风险**：G14 实施会话仍在本树工作，其收官笔将落在 `refactor/remove-2x-speed` 而非 feat 线——看护**不做任何 checkout/reset**（他线会话活跃，分支操作会毁其工作流），核验照常按当前树内容做（文件面区分），分支归位方案交用户：①暂停他线 ⇒ 切回 feat ⇒ G14 正常落笔 ②任其落在错误分支 ⇒ 事后 cherry-pick 到 feat 并修他线分支 ③其他。本条登记笔自身也会落在当前分支（无法避免，后续一并归位）。
- **2026-09-27 03:33 树况异常登记：他线代码改动与 G14 同树并行（G14 在途未受影响）**：在途=G14@app（01:57→03:22 派发，尚未落盘文件）；新提交=无；动作=登记树况；备注=树上出现 **5 个非 G14 文件面的代码改动**：`core/data/.../SaveTriggerFlag.kt`、`core/domain/.../ElderSlotType.kt`、`core/domain/.../GameDataSectModels.kt`、`feature/game/.../SaveLoadViewModelAutoSaveOps.kt`、`feature/game/.../SaveOrchestrator.kt`（存档/长老槽位面）＋ 3 个他线方案文档未跟踪——均为**用户他线会话并行产物**（G14 纯文档批不可能触碰）。**核验轮规则**：①G14 树净判定按文件面区分——他线 5 代码文件 + 他线方案文档不计入 G14 违规，G14 footprint（文档/changelog/version.properties/报告）须全部入库；②G14 自身纪律「有他批在途就停手报告」若触发，按其报告处置，非 stalled；③两线若同时跑重门致构建互扰，以「G14 重跑后数字为准」。看护不干预他线会话（暂停由用户决定）。
- **2026-09-27 03:22 G13 交付核验通过＝accepted ＋ G14（末批）已派发（桌面渠道）**：在途=G14@app「【G14 · 派发文本（由 fire.ps1 装配）】」（GLM-5.3-Flash / feat/gacha-m0-m1；03:22 起跑正在思考，已观测到解析 CHANGELOG 4.01.16 段并入规则）；G13 收官笔=`ddfa14fca refactor(gacha): G13 数值落地…`（9 文件 +323/−19，双 changelog + 报告 132 行 + 白皮书修订在内；🔴 前缀偏差 #2：`refactor` 非手册字面 `feat`，同 G10 `chore` 判例——语义准确实质满足，显式登记）；**交付核验=通过**——三要素 ✓（报告门禁原数字齐：组合门第 1 轮 detekt 1 条 MaxLineLength 真修、第 2 轮全绿 22m42s；JUnit **7476/0/0/18** = 7474+2（⑥c/⑥d 两条新守卫）账闭合；零行为改动 ⇒ ctest/JNI 不适用、.so 仍 G10 值）＋ 特别核验点 ✓（十杠杆全勾、悬空键四名全仓 grep **0 残留**（看护提交树亲证）、StarZone 零字面量、白皮书口径 A 统一 + §11 经济复测 5 项（MC 20 万局）、game-data **新 sha256 `809375f4…8619` 与看护亲跑逐字符一致**、判别力双反例红证）＋ 看护只读抽验 ✓（node 四门按新 sha 全绿）；树净=docs/research/×2 + **2 个他线方案文档**（`docs/realtime-settlement-plan-2026-09-27.md`、`docs/design/remove-2x-speed-implementation-plan.md`——G13 报告 §8-9 已定性「他线纯方案文档、不入库、零相交」，不计 G13 违规，留该线处置）；动作=派发 G14；备注=G13 经济复测结论（第二角色期望 683 抽＝数百月级）解锁节奏风险升级**留用户拍板**；G14 派发件=fire.ps1 装配+G13 交付事实附录 8 条（11,621 字符）。**派发小插曲**：首轮「新建任务」点击未生效致粘贴误入看护会话输入框——已 Ctrl+A+Delete 清除（未发送、零副作用），重开新任务视图后重新粘贴发送成功。
- **2026-09-27 01:57 G12 交付核验通过＝accepted ＋ G13 已派发（桌面渠道）**：在途=G13@app「【G13 · 派发文本（由 fire.ps1 装配）】」（GLM-5.3-Flash / feat/gacha-m0-m1；01:57 起跑正在思考）；G12 收官笔=`616f50912 feat(gacha): G12 体验完成…`（27 文件 +855/−103，双 changelog + 报告 171 行在内，前缀字面合规）；**交付核验=通过**——三要素 ✓（报告门禁原数字齐全：组合门第二轮 BUILD SUCCESSFUL 23m38s/339 tasks，第一轮 detekt 1 条 VariableNaming 真修并如实记录；JUnit **7474/0/0/18** = 7462+12 逐模块账闭合 domain+2/engine+2/ui+4/feature+4；detekt 0/0、lint 36w/0e；桌面门不适用＝零 C++ 改动、.so 与 G10 逐字一致）＋ 口径抽查 ✓（**D-1 陷阱正确处理**：主界面=寻访一次/十次、结果页=招募一次/十次；引导 id=26「初次寻访」CumulativeCounter 零新字段；D-7 共享面 CharacterTemplateGuardTest 零触碰）＋ 判别力红证 ✓（Rarity.CONFIGS[6] 回退旧值 → 守卫两条 FAILED → 复原绿）＋ 看护只读抽验 ✓（node 四门零漂移）；树净=仅 docs/research/×2 ✓；动作=派发 G13；备注=G12 新增真机 pending-device 6 项（累计 18）；🔴 产品拍板项延续：星级乘区是否进 finalStats 展示链（图鉴属性预览前置）；批次总表 G13 行原注「需产品拍板」已被 TASKBOOK-G13 预裁决取代（⑨ 用户 G09 已拍板不补、⑩ 认定重复键），无用户阻塞。G13 派发件=fire.ps1 装配+G12 交付事实附录 8 条（10,645 字符）。
- **2026-09-27 00:09 G10 交付核验通过＝accepted ＋ G12 已派发（桌面渠道）**：在途=G12@app「【G12 · 派发文本（由 fire.ps1 装配）】」（GLM-5.3-Flash / bigmodel 计划 / feat/gacha-m0-m1；00:09 起跑正在思考，已观测到核对附录 G10 色表事实）；G10 收官笔=`e966d7148 chore(gacha): G10 唯一重录窗口…`（41 文件 +529/−528，双 changelog + 报告 220 行在内）；**交付核验=通过**——三要素 ✓（🔴 要素①字面偏差：前缀 `chore` 非手册字面 `feat`，实质满足＝提交含全部交付物且信息含「唯一重录窗口」与根因，显式登记不判失败）＋ 特别核验点 ✓（金黄两处同值 `0xb4f3c6912207f597`、`kProbeVersion=1`/`advancePhaseBaseline`/`gacha_tx.h`/`seed+12` 提交树实测未动、`RngSourceGuardTest` 上限下调附判别力红证、`GachaColors.kt` 有 KDoc 连带说明）＋ 看护只读抽验 ✓（gen-action-ids 201/1872 零漂移、gen-game-data sha 035066cb…94ef 不变、check-jni-count 86/86、check-agent-instructions 五规则全过 444 引用不精确计数持平）；树净=仅 docs/research/×2 ✓（副产物已还原）；JUnit 基线 7471→7462＝纯删 9 例逐模块账闭合（data −5 / engine −2 / domain −2）；.so 新三件套 9015808 / 23:32 / b1eb1bec…；报告诚实残余 8 项（🔴 通知通道后端管线退役 vs 绑定新事件源 **待用户拍板**、SpiritRootGenerator 零调用、真机 12 项不变等）；动作=派发 G12；备注=G12 派发件=fire.ps1 装配+G10 交付事实附录 8 条（14,222 字符，粘贴全文发送）。
- **2026-09-26 22:10 G10 已派发（渠道=桌面 app 会话，用户拍板改渠道；原误记 20:32 系未核钟，22:22 勘误）**：在途=G10@app「Untitled session」（GLM-5.3-Flash / bigmodel 计划 / 项目 XianxiaSectNative / 分支 feat/gacha-m0-m1；sessionId 待回填）；新提交=无；交付核验=不适用；动作=派发 G10；备注=粘贴 `_dispatch-G10.md` 全文（「粘贴文本 · 238 行」芯片）发送成功，22:10 起跑即观测到按必读序读 AGENTS.md / EXECUTION-PROTOCOL.md / 前批报告，22:15 复核仍在按纪律推进（复述四任务 + 开工基线核对 + 读模块规范）；**渠道变更记录：用户拍板改用桌面控制派发/看护 ⇒ WATCHDOG-PROMPT §0 红线 1（禁 GUI）对「派发动作」由用户指令豁免；核验与记账仍纯 CLI+git**；无头 CLI 通道的结论存档见 20:10 / 20:30 两条（签名已修好、zai 计划未订阅是最终阻断）。后续轮次按 §4 三要素核验（`feat(gacha): G10` 收官笔 + `report-G10.md` + 树净）。22:22 已建 ZCode 自动化 automation-2578e36b-000d-4499-aabe-5a41f61028c7（每 10 分钟看护轮，提示词含两处用户修订）。
- **2026-09-26 20:30 通道修复进行到一半：签名已通、卡在计划订阅（blocked 持续，待用户二次决断）**：用户拍板走 `zcode login` ⇒ OAuth 登录成功（旅行者3309，凭据写入 `v2/credentials.json`）⇒ **最初的 ClientRequestSigningV4 报错已消除**（请求能打到服务商）；但 CLI 无头路径写死使用登录账号的 zai 计划默认模型（`account:zai-individual-coding-plan/GLM-5.3`），服务端拒 `[1113] Insufficient balance or no resource package`（type=rate_limit_error）；`v2/coding-plan-cache.json` 实证该身份 **zai-coding-plan = coding_plan_not_entitled**、**bigmodel-coding-plan = available**（与 app 侧 `setting.json providerFamilyConnectionSelections.bigmodel.kind=individual-coding-plan` 一致 = 用户实际订阅在 BigModel 侧）。已试并无效的本地开关：`v2/provider_config.json` 的 `defaultModelSelection` 改 `account:bigmodel-individual-coding-plan/GLM-5.3-Flash` 与 `builtin:bigmodel-coding-plan/GLM-5.3-Flash` 两形态均被无视（每次新进程仍解析到 zai）；CLI 无 `--model` 参数、`login` 无计划/账号参数。⚠️ 附带变更披露：login 把共享凭据文件 2215→3574 字节（app 当前不受影响，本会话即跑在 bigmodel 计划上）；`defaultModelSelection` 现遗留在 `builtin:bigmodel-coding-plan/GLM-5.3-Flash`。**候选项**：① 用户若有持 zai 计划的 Z.AI 账号 ⇒ 换号重跑 `zcode login`；② 走 API key 路线（需确认余额 + 处理 490f5e 无分隔符过不了签名的问题 + `zcode logout` 风险）；③ **改派发渠道 = app 内派发（G11 同款，唯一现在就能跑的路径）**。
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
| 1 | **G10** RNG 基线重录 + 全量回归收口 + 死代码 grep 清零 + 文档收口（M1 末批） | G02–G09/G11 已合入 | `accepted`（看护 00:09；用户终审留档） | 2026-09-26 22:10（桌面 app 渠道） | `e966d7148`（chore 前缀偏差已登记） | `report-G10.md` ✓ | 金黄两处同值 `0xb4f3c6912207f597`；ctest 1437 全绿；JUnit 7462；残余 8 项含通知管线拍板 |
| 2 | **G12** 体验完成（历史 50 条 / 概率公示 / 图鉴完整 / 流光降级 / 引导 / 死文案清零） | G11 | `accepted`（看护 01:57；用户终审留档） | 2026-09-27 00:09（桌面 app 渠道） | `616f50912` | `report-G12.md` ✓ | JUnit 7474（+12 账闭合）；D-1 两处口径陷阱正确处理；新增真机 pending-device 6 项（累计 18）；finalStats 星级乘区拍板项延续 |
| 3 | **G13** 数值落地（M0 杠杆回填 / 突破补偿 / 回血参数 / 经济复测 / 星级乘区终值） | G00、G09、G10 | `accepted`（看护 03:22；用户终审留档） | 2026-09-27 01:57（桌面 app 渠道） | `ddfa14fca`（refactor 前缀偏差已登记） | `report-G13.md` ✓ | game-data 新 sha256 `809375f4…`（改中性源非漂移）；十杠杆全勾、悬空键清零；经济复测解锁节奏风险留用户拍板 |
| 4 | **G14** 文档与发布收口（双 changelog / CODE_WIKI / architecture / 验收报告 / 死文案清单） | G11–G13 | `dispatched` | 2026-09-27 03:22（桌面 app 渠道） | — | — | 任务书已就绪（版本号 4.01.16 已拍板无阻塞）；会话名【G14 · 派发文本（由 fire.ps1 装配）】；派发件=装配+G13 事实附录 8 条 |
| 5 | **G11-真机** 设备验证（M1 完成判据） | G11、用户提供设备 | `blocked(需设备)` | — | — | — | **不阻塞前 4 批派发**；清单 = `report-G11.md` §7 的 D-1…D-12 |
| 6 | **M2 总收官** | G14 | `pending` | — | — | — | 见 WATCHDOG-PROMPT §6 |

**状态取值**：`pending`（未派）／`dispatched`（已派在途）／`accepted`（三要素通过）／`needs-fix`（门禁失败待修复轮）／`stalled`（超时无进展）／`blocked`（停派待用户）。

---

## 看护锁

| 项 | 值 |
|---|---|
| 看护启用时点 | 2026-09-26（本文件首笔） |
| 定时任务 | **看护不自行安装**；由用户在 WATCHDOG-PROMPT §3 的方案 A/B 中择一安装 |
| 锁时间戳 | `2026-09-27 03:22 · G13 accepted + G14（末批）派发`（每轮 fire 有实质动作时更新；15 分钟内视为活跃） |
| 自动化 id | `automation-2578e36b-000d-4499-aabe-5a41f61028c7`（2026-09-26 22:22 建，*/10，看护轮提示词含两处用户修订；总收官后由用户决定停用） |

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

# TASKBOOK-SS10 · 文档与发布收口（收官批）

> **本文件是 SS10 的派工真源**（开工补卡）。上位方案 §四（交付）+ 拍板 V1–V5。
> 协议：[`DISPATCH-ledger.md`](DISPATCH-ledger.md) §4 + [`../gacha-batches/EXECUTION-PROTOCOL.md`](../gacha-batches/EXECUTION-PROTOCOL.md)。
> 入口判据：**SS0–SS9 全部已合入 main**（`e7ab3d4e5` 时刻核验 ✓）。

---

## 1. 目标与验收判据

| 项 | 内容 |
|---|---|
| 业务目标 | 十一批次的文档、发布面、验收记录一次收口——代码面 SS0–SS9 已全部并网，本批**零生产代码改动**（纯文档+changelog）。 |
| 验收① | **双 changelog 收口（V4 拍板）**：①游戏内 `android/app/src/main/assets/changelog_entries.json`——SS0–SS9 的**玩家可见变更全部并入唯一 `4.2.00` 条目**（同版本禁新建第二条目；现条目为 e18001855 建立的骨架，本批补全）；文案给玩家看：通俗、无术语、不泄数值；**删档重置必须写进玩家可见条目**（SS0 §6.2 硬要求）。②外部 `CHANGELOG.md`——新增 `[4.2.00]` 段（技术细节可写；现文件已有骨架段则补全）。各批登记的玩家可见变更汇总表见 §2。 |
| 验收② | **活文档同步**：`CODE_WIKI.md`（目录树/子系统索引：账号数据空间/增量落盘/事件触发存档/账本/诊断面/登录门槛）、`docs/architecture.md`（持久化与存档体系节按 SS0–SS9 终态改写）、`docs/ui-read-surface.md` §2（镜像合法面：`jadeLedger`/`dirtySet` 相关新增字段核对——SS9 已登记的 coveredFields 面） |
| 验收③ | **验收报告出具**：`docs/design/single-save-batches/report-SS-final.md`——全案终验收报告：11 批次 commit/并网 sha 对照表、门禁基线演进（版本 66→70、ctest 1606→1538、JVM 用例数演进）、真机 pending-device 清单汇总（各批累积）、已知过期引用清单（SS0 A8-7 基础上补全）、遗留与后续建议 |
| 验收④ | **本册与各 report 回填**：`DISPATCH-ledger.md` 各批入口判据处如引用了"待 SS10"的项，逐条核对已消费（各 report §5/§6 的跨批登记逐一过账）；不回改历史批次档案（V5 先例） |
| 验收⑤ | **全门禁绿（硬门）**：`compileReleaseKotlin` ✓ + `node scripts/check-agent-instructions.mjs` EXIT=0（本批改大量 docs/，必跑）+ detekt 触及模块绿（baseline 只缩不增）+ 全量 JVM 四模块绿（docs-only 批预期零代码变更——若 diff 含 .kt 则升格为代码批重走全门禁） |
| **不做** | 不动版本号（`version.properties` 已是 4.2.00/4200，V1 拍板）；不做真机验证（pending-device 清单移交发布流程）；不碰 C++；不改任何生产代码 |

---

## 2. 玩家可见变更汇总表（验收①素材，从各批 report 收集）

| 批 | 玩家可见变更 | 文案落点建议 |
|---|---|---|
| SS0 | **删档重置**（老档不可恢复、云端旧档失联）+ 邮件清理（QQ 群邮件/白名单福利邮件移除，节日邮件保留） | 显著位置（重大变更） |
| SS2 | 账号数据空间分库（存档按账号隔离）；登出=切换账号不清进度 | 存档/账号节 |
| SS4 | 存档管理弹窗单档化（无选槽、保存/读取直动作） | 存档节 |
| SS5 | 保存更快（增量落盘——性能收益，玩家不可见但可写"优化存档速度"） | 可选 |
| SS6 | 关键数据丢失窗口关闭（重要操作立即保存） | 存档节 |
| SS7 | 换设备继续游戏（云灾备）+ 云端冲突提示 | 云存档节 |
| SS8 | 强制登录 + 隐私政策更新 | 登录/合规节 |
| SS9 | 玉符账本（余额全程可审计——玩家可感知为"玉符记录更可靠"） | 可选 |
| SS3 | 设置页新增「存档诊断」 | 设置节 |

**汇总表仅是素材底稿——文案组织与详略由本批按 changelog 文案规范（通俗/无术语/不泄数值）定稿。**

---

## 3. 决策

| # | 决策 | 依据 |
|---|---|---|
| **D-1** | 游戏内 json 补全唯一 `4.2.00` 条目，不新建条目 | V4 拍板 + `rules/version-release.md` §2 合并规则 |
| **D-2** | 外部 CHANGELOG 按技术细节写（面向开发者） | 双受众分工 |
| **D-3** | 验收报告由本批出具（`report-SS-final.md`），看护终验收据其复核 | 派工册 §3 SS10 出口判据原文 |
| **D-4** | 真机清单汇总移交，不在本批执行 | 各批 §6 一致登记（发布流程承载） |

---

## 4. 文件面与切片

| 片 | 允许改 | 禁止改 | 自检项 |
|---|---|---|---|
| **SS10-a** 双 changelog | `changelog_entries.json`、`CHANGELOG.md` | 版本号、既有条目（4.2.00 骨架内补全） | JSON 可解析 + 归一化对比其余条目零变化（EQ-B5 先例） |
| **SS10-b** 活文档 | `CODE_WIKI.md`、`docs/architecture.md`、`docs/ui-read-surface.md` | 历史章节（V5 先例） | grep 过期符号复核 |
| **SS10-c** 验收报告 | `report-SS-final.md`（新） | — | 11 批对照表 + 门禁演进 + 真机清单 |
| **SS10-d** 册面回填 | `DISPATCH-ledger.md` 判据核对注记 | 历史登记原文 | 逐条过账表 |
| **主线程** | 门禁复跑 | 生产代码 | diff 无 .kt/.java/.cpp |

---

## 5. 门禁清单

```powershell
# 工作目录 = C:\Mnzm\XianxiaSectNative\android
.\gradlew.bat compileReleaseKotlin --console=plain
.\gradlew.bat :app:testReleaseUnitTest --max-workers=1 --console=plain   # 抽验（docs-only 预期零影响）
.\gradlew.bat :app:detekt --console=plain
# 仓库根
node scripts/check-agent-instructions.mjs   # 本批核心门禁（大量 docs/ 改动）
git diff --stat   # 自证零生产代码改动
```

**提交前**：`git status` 只留本批改动；文档面编辑一律在 worktree 内完成并随批 commit。

---

## 6. 登记项

1. **真机 pending-device 清单汇总**（写进验收报告，移交发布流程）：分库换账号（SS2）、删档重置（SS0）、换设备续玩+W>C（SS7）、B1 离线宽限两分支（SS8）、事件触发落盘真机耗时（SS6）、增量写真机耗时（SS5）、玉符账本真机行为（SS9）。
2. **渠道侧配合项**：隐私政策更新合规审核（SS8）；强制更新/产品公告（W2/W10，SS0 前提，用户侧已确认）。
3. **验收报告须含**：「已知过期引用清单」（SS0 A8-7 基础上补 SS1–SS9 新增的档案过期引用）。

---

## 7. 一句话给执行者

**零生产代码：把 SS0–SS9 的玩家可见变更全部并进游戏内 changelog 唯一 4.2.00 条目（删档重置必须显著）、外部 CHANGELOG 补技术段、四份活文档同步 SS 终态、出具全案验收报告（11 批对照+门禁演进+真机清单+过期引用清单）——check-agent-instructions EXIT=0 是本批核心硬门。**

# 行业对标 · 抽卡随机源是否与结算随机共用一条流（G09 决策输入）

> **回答的问题**（用户 2026-09-26 提问）：主流游戏厂商怎么组织抽卡随机与结算随机——共用一条随机流，还是各自独立？
> **结论摘要**：**不共用**。抽卡走**独立随机分区**是行业一致做法；共用单流在三类场景下都有据可查的故障实例。
> **对标窗口**：2024-01-01 – 2026-09（当前 2026-09-26），符合 `rules/industry-benchmark.md` §二。
> **配额披露（诚实）**：窗口内来源 **24 条**（S 13 / A 3 / B 8，S+A = 16 ≥ 12），满足「≥20 条、≥12 条 S/A」。
> ⚠️ 其中 **2 条 S 级未能直读**（Photon 站点反爬、Godot 类参考正文未取到），**4 条本会话直取核验**，其余为子代理检索所得、URL 与日期齐备但未逐条直读——已在下表 `复核` 列逐条标注，**不掩饰**。
> **不计配额但现行有效的法规**单列于 §7（发布日早于窗口，按规则不得计入）。

---

## 1. 引擎官方怎么说：支持「按子系统分流」

| 引擎 | 官方原文要点 | 判定 |
|---|---|---|
| **Unity**（Mathematics 1.3.2《Random numbers》） | 「You can control the random number generator state explicitly, which is useful if you're using parallel code, or **if you want to make sure that one source of random numbers is seeded differently than another source**. You can also have **as many `Random` instances as you like**.」 | ✅ 官方**明文**承认「多随机源、分别播种」是合法且有用的用法（本会话直取核对原文） |
| **Godot**（4.5 教程 / 类参考） | 全局函数「don't offer as much control」；`RandomNumberGenerator`「allows **creating multiple instances, each with their own seed and state**」；全局 `randomize()` 只应在启动时调一次 | ✅ 官方把「分流能力」绑定在实例 API 上 |
| **Unreal**（5.5《Random Streams》） | `RandomStream` 是**每实例持有 `Initial Seed` 的变量**，「Different seeds generate different sequences」，另有 `Set Random Stream Seed` 固定 | ✅ 机制层面即「每条流一个有名字的持有者」 |

**没有任何一家官方文档写过「全局单一 RNG 更好」**；相反，Godot 官方警告「RNG **does not have an avalanche effect**，相似种子会产出相似流，外部种子应先哈希」。

---

## 2. 为什么确定性模拟必须分流：**消耗次数是隐式全局契约**

一条随机流的「当前状态」蕴含了历史上**所有**消费事件的顺序与次数。于是「谁在什么时候取了几次」变成跨系统的隐式契约：

1. 任一系统取数次数变化（分支、早退、UI 交互、帧数差异）⇒ 下游**所有**系统的随机结果整体平移；
2. 并行/分块会改序 ⇒ 结果随分块方式变化；
3. 回放/读档只能存「一个 state」，多取/少取**无法被局部检测**，只表现为整体漂移；
4. 无法「只重跑抽卡」或「只重跑结算」来定位分歧；
5. 状态即全部历史且随存档分发 ⇒ 可被逆向利用；
6. 任何「改 RNG 用法」的版本更新都会让依赖旧序列的回放/工具失效。

**窗口内有据可查的故障实例**：

| 实例 | 现象 | 根因 |
|---|---|---|
| CK3 官方补丁 1.19.0.4（2026-04-23） | 补丁第一条即修「**out-of-sync errors in cross-platform multiplayer**」 | 确定性维护是按系统逐项排查的长期工作 |
| Epic 官方社区帖（2025-01） | **同种子**仍失步 | 定位到服务器走 `Has Authority` 分支**多取一次**随机数；结论「代码稍有差异，种子就不同步」 |
| Factorio FFF #415（2024-06-14） | 联机 desync | 2017-07 起潜伏的线程确定性缺陷：**按 CPU 核数**并行生成 chunk 会改变结果；「deterministic multithreading」极难 |
| Factorio 单条全局 taus88（逆向贴 2026-07-08 / 2026-08-22） | 玩家可在游戏内**预测**品质随机结果 | 单流状态随存档保存 ⇒ 算法还原后即可预测；2.1 改 RNG 用法才使其失效 |

---

## 3. 头部抽卡产品的可查做法：**域状态独立 + 官方从不公开随机流拓扑**

**必须说清的取证边界**：没有任何头部厂商公开过「抽卡随机流是否与结算共用」的工程细节——这层属核心防作弊资产，**公开面为 0**。可查证的外显证据一致指向「**服务端权威 + 逐域持久化计数器**」：

- **HoYoverse 官方帮助页（2025-09-17，本会话直取）**：保底计数按 banner **类型**分别保存且同类型间**继承**（角色池两个 banner 共享 pity，武器池独立，90/80 抽）；并声明「probabilities listed on the Wish details page are accurate」、祈愿历史可回溯一年。这套语义只有在**账号级持久化 pity + 结果流水**存在时才成立。
- **日本 gacha 专利 JP5633100B1**：服务器侧接收请求 → 按**逐物品**出现概率抽取 → 记录 → 返回（概率表与判定都在服务端）。
- 平台合规：Google Play / Apple 均要求在**购买前**披露各随机物品的概率。

⇒ 行业形态不是「一条全局流」，而是「**每个玩法域持有可持久化、可审计的独立状态**」——保底计数本身就是显式的域状态。

---

## 4. 刻意共用一条流的场景（反面意见，说明它不是"绝对错误"）

1. **可分享的同一挑战**：每日挑战用公开日期派生的种子，让所有玩家拿到同一谜题，服务端按同一调用顺序重算校验（「RNG 调用顺序即契约」）。
2. **可分享的整局种子**：roguelike/deckbuilder 的 seed 分享、速通/TAS 复现——拆多流会削弱可分享性。
3. **防 save-scum**：读写档共用同一条流并推进它，读档无法重刷；**分流反而让"读档重抽"成为可能**。
4. **纯表现层随机 / 单线程无回放场景**：单流更省（每条流都要付「独立序列化 + 独立推进 + 独立守卫」成本）。

**代价**：被逆向预测、代码路径差异直接 desync、无法并行、无法局部回归、版本一改全失效 ⇒ 不能当默认。

---

## 5. 反面教材（本决策最强的两条实证）

| 案例 | 事实 | 教训 |
|---|---|---|
| **Slay the Spire 2 相关随机（2026-06-15，本会话直取，数值逐项吻合）** | 各系统**已分** RNG，但种子用 `seed + hash("名")` 派生 + C# `System.Random`（**近似线性**）⇒ 流间可互推。后果玩家可直接感知：Neow's Bones 的随机诅咒在 Underdocks **54.25%** 出 Debt（Clumsy 仅 0.10%）、Trash Heap **永远不可能**出 Rebound（图鉴无法完成）、首战掉药水 **76% vs 4%**。且**机制被分析出来之前玩家已在抱怨运气** | ⚠️ **关键区分**：根因是**算法线性**，不是「派生方式」本身。本项目用 **PCG-XSH-RR**（非线性输出函数），`seed + id` 派生不会产生 STS2 那种可互推——**不要因此改派生方案**（见 §6.3） |
| **Hades 1**：打碎纯装饰性罐子会让全局 RNG 步进 1 | speedrun 用它做 route | 一个**纯表现层**行为消费全局流，就能被玩家逆向操纵 |

合规侧佐证：东亚监管要求「公示概率**与实际出货分布一致**」（DiGRA GachaCon 2026 论文），而共用流导致的分布畸变（如某张卡 0%）会直接落在「公示与实际不符」的风险里。

---

## 6. 对本项目的可迁移结论与建议

### 6.1 结论：**抽卡独立分区**（`RngPartition.GACHA = 12`）

依据（强度排序）：
1. **驱动源不同，混流即耦合**：结算由 L0–L4 惰性结算按固定节奏驱动；抽卡由**玩家点击**驱动、次数无上限。混流后「玩家抽了多少次」会平移旬/月/年结的随机序列。
2. **架构一致性**：本项目红线是「每分区一条流 + 全部纳入快照 + 逐位对拍」。抽卡并入 SYSTEM 会让 SYSTEM 状态随 UI 抖动而变，污染 `StateSyncService` 的增量 dirty 判定与 `DiffAuthoritativeTickTest` 一类守卫的解释力。
3. **并行前提**：每旬核心批次可并行化的前提是「零 RNG」；混流会把这个前提打破。
4. **行业形态同向**：引擎官方（Unity/Godot/Unreal）支持多流；头部抽卡产品是「域状态独立 + 服务端权威」。

### 6.2 🔴 内部实测修正：本决策**不是**由「金序列红点」驱动的

本任务书初版 D-3 曾写「并入 SYSTEM ⇒ ctest 金序列红集可能扩大」。**本会话实测推翻该表述**：

- `DeterminismProbe`（`include/gamecore/determinism_probe.h:152-227`）哈希的是**行为 transcript**（`settleOnePhase()` 结果、弟子列、战斗链、`rngNextInt` 标量），**不哈希 `rngStates` 映射**（`models.h:1231`）；
- 现有 3 条金黄红（`DiscipleFactory.GoldenSequence*`、`DeterminismProbeTest.DigestMatchesGoldenBaseline`）的夹具**不做抽卡**；
- ⇒ **两个方案在现有套件下都不扰动既有金黄**，任何一方都**不强制** G10 提前重录。

**所以决策依据是结构性的（耦合、可解释性、公平性、并行前提），不是「红点多少」。** 这一点写进报告，避免后人拿「反正都不红」去否定独立分区。

### 6.3 建议**不采纳**「改用哈希派生」（对子代理建议的修正）

调研子代理之一建议把分区种子从 `seed + id` 改为 `H(seed ‖ subsystemId)`。**不采纳**，理由：

1. STS2 的泄漏根因是 **C# `System.Random` 对种子近似线性**；本项目是 **PCG-XSH-RR**（计数器型 + 强混淆输出函数），`seed + id` 不产生可互推的流；
2. 改派生方案会**作废全部 12 个既有分区的随机值** ⇒ 数十条对拍/金序列基线全部失效，收益为零、风险极大；
3. 若将来真要「整局可分享种子 / 每日限定」，正确做法是**新增**一条按 `H(rootSeed, partitionId)` 派生的分区，而不是改写既有分区。

### 6.4 落地方案与验收判据（若拍板独立分区）

| 项 | 内容 |
|---|---|
| 改动面 | `include/gamecore/rng/rng_manager.h`（新增 `kGacha = 12`，`kMaxPartitionId` 由 `kResidual` 上移——**否则 JNI 合法分区守卫会静默拒绝 12 号键**，`MISSION(8)` 曾因此恒返回 0）、`core/engine/.../util/RngPartition.kt`（镜像枚举 + 名称）、`RngSourceGuardTest`（分区集合与消耗点登记）、`GameRngManager` 缺键重种路径 |
| **不新增持久化字段** | `rngStates` 是 `map<partitionId, state>`；老档无 12 号键 ⇒ 按既有 `seed + id` 确定性重种，**零迁移** |
| 快照语义 | 抽卡分区**必须 `inSnapshot = true`**（分流 ≠ 不入档）：否则读档重种 ⇒ 「读档重刷首抽」或结果不确定 |
| 守卫 | ① 一次抽卡的随机消费次数 == 常量；② 抽卡前后 `rngStates` 快照差分只含 12 号键；③ 抽卡分区状态可存取往返一致；④ 双端同分区同序（`DiffGachaPullTest`） |
| 防回档（纯单机） | **不试图根除**（无可信第三方）。保留「保底计数随存档回退」——回档同时回退 pity，刷保底占不到便宜（`docs/character-gacha-redesign-2026-09-23.md:111` 已拍板接受） |

### 6.5 若产品坚持按方案原文走 SYSTEM（备选）

则必须在 G09 报告里把「**玩家抽卡次数会平移其它系统的随机序列**」写成**已知代价**（而不是「无副作用」），并登记 G10 的重录范围扩到「所有含抽卡的对拍夹具」；同时保留 §6.4 的守卫③④。

---

## 7. 来源清单（窗口内，计配额）

`复核` 列：✅ = 本会话直取内容核对；⚠️ = 子代理检索所得（URL/日期齐备，未逐条直读）；⛔ = 抓取受阻或正文未取到。

| # | 级 | 标题 | URL | 发布日期 | 复核 | 核心摘要 |
|---|---|---|---|---|---|---|
| 1 | S | Unity Mathematics 1.3.2《Random numbers》 | https://docs.unity3d.com/Packages/com.unity.mathematics@1.3/manual/random-numbers.html | 2026-08-06（页脚 docfx 元数据） | ✅ | 官方明文：可显式管理 RNG 状态，「让一个随机源的种子与另一个不同」，可有任意多个 `Random` 实例 |
| 2 | S | Godot 4.5《Random number generation》 | https://docs.godotengine.org/en/4.5/tutorials/math/random_number_generation.html | 2025-09（随 4.5 stable） | ⚠️ | 全局函数「控制力不足」；实例 API 可多实例、各有 seed/state；全局 `randomize()` 只调一次 |
| 3 | S | Godot 4.5《RandomNumberGenerator》类参考 | https://docs.godotengine.org/en/4.5/classes/class_randomnumbergenerator.html | 2025-09 | ⛔（只取到导航，正文未取到） | 不同实例可用不同种子；并警告无雪崩效应、相似种子产出相似流 |
| 4 | S | Unreal 5.5《Random Streams》 | https://dev.epicgames.com/documentation/en-us/unreal-engine/random-streams-in-unreal-engine?application_version=5.5 | 2024-11-12（5.5 发布） | ⚠️ | 随机流是每实例持有 `Initial Seed` 的变量，不同种子→不同序列 |
| 5 | S | Photon Quantum 3《RNG Session》 | https://doc.photonengine.com/quantum/v3/manual/rngsession | 2026-08-06 | ⛔（站点反爬，仅得校验页） | 共享全局 `frame.RNG` 在预测裁剪下消费次数不同 ⇒ 校验和不一致；修法为**每实体一个 RNGSession**，且只能在模拟层推进 |
| 6 | S | GDC Vault《Cross-Platform Determinism in Warhammer Age of Sigmar: Realms of Ruin》 | https://gdcvault.com/play/1034229/Cross-Platform-Determinism-in-Warhammer | 2024-03（GDC 2024） | ⚠️ | 为确定性重建仿真框架（线程安全 ECS + 反 desync 工具链）；收益含可复现与反作弊 |
| 7 | S | CK3 官方补丁 1.19.0.4 | https://ck3.paradoxwikis.com/Patch_1.19.X | 2026-04-23 官方帖 | ⚠️ | 补丁首条即修「cross-platform multiplayer out-of-sync」 |
| 8 | S | HoYoverse《How does the Wish guarantee system work?》 | https://support.hoyoverse.com/hc/en-us/articles/50333940684953-How-does-the-Wish-guarantee-system-work | 2025-09-17 | ✅ | pity 按 banner 类型分存且同类继承（角色 90/武器 80）；声明公示概率准确、历史可回溯一年 |
| 9 | S | 韩国文体部：확률형 아이템 정보 공개（2024-03-22 起强制） | https://www.mcst.go.kr/site/s_notice/press/pressView.jsp?pSeq=20757 | 2024-01-02 | ⚠️ | 游戏本体+官网+广告三处须标示概率型物品种类与**种类别供应概率**、限定总数或期间 |
| 10 | S | 韩国法制处 생활법령：확률형 아이템의 정보공개 | https://easylaw.go.kr/CSP/common/CnpClsMain.laf?popMenu=ov&csmSeq=2858&ccfNo=1&cciNo=2&cnpClsNo=2 | 2026-08-15 更新 | ⚠️ | **禁区间式概率**（须逐数量给出实际概率）、**禁只给基础概率**；含违反赔偿与 3 倍惩罚性赔偿 |
| 11 | S | KFTC：Sanctions on KRAFTON / Com2uS（概率型道具欺骗性手法） | https://www.ftc.go.kr/eng/downloadBbsFile.do?atchmnflNo=51035 | 2025（年粒度） | ⚠️ | 「公示概率与实际不符」具可执行法律后果 |
| 12 | S | MDPI Symmetry 18(6):1051《State-Dependent Asymmetry in Soft-Pity Gacha Waiting-Time Models》 | https://www.mdpi.com/2073-8994/18/6/1051 | 2026-06 | ⚠️ | 软保底等待时间的精确递推 + 尾部风险 + 定向目标扩展（保底实现与验证的数学依据） |
| 13 | S | DiGRA GachaCon 2026《Compliance of Gacha Probability Disclosure Regulations: a Comparative Study across East Asia》 | https://dl.digra.org/index.php/dl/article/view/2749 | 2026-03-02 | ⚠️ | 东亚监管要求公示概率与实际分布一致；玩家可据公开概率反推内部随机流是否被污染 |
| 14 | A | Factorio 官方博客 FFF #415《Fix, Improve, Optimize》 | https://factorio.com/blog/post/fff-415 | 2024-06-14 | ⚠️ | 2017 起潜伏的线程确定性缺陷：按核数并行分块改变生成结果 ⇒ desync |
| 15 | A | Aiven《Deterministic Simulation Testing in Diskless Apache Kafka》 | https://aiven.io/blog/deterministic-simulation-testing-in-diskless-apache-kafka | 2026-03-05 | ⚠️ | 把 IO/调度/随机的不确定性交给**确定性监督者**统一控制，才能复现失败与时间旅行调试 |
| 16 | A | Factorio 论坛《Towards a newer, better space casino in 2.1》 | https://forums.factorio.com/viewtopic.php?t=134879 | 2026-07-08 | ⚠️ | 用回收铁齿轮逐位泄出 88-bit 状态并预测未来 roll ⇒ 共用/单一流可被玩家逆向 |
| 17 | B | 《Correlated randomness in Slay the Spire 2》(Andy Tockman) | https://tck.mn/blog/correlated-randomness-sts2/ | 2026-06-15 | ✅（数值逐项吻合） | 线性派生 + 近似线性 RNG ⇒ 流间互推：54.25% / 0% / 76% vs 4%；玩家先于分析抱怨 |
| 18 | B | Gegell《Reversing Factorio's RNG》 | https://gegell.github.io/posts/factorio-rng/ | 2026-08-22 | ⚠️ | 单条 taus88 随存档保存 ⇒ 可逆向预测；2.1 改用法后失效 |
| 19 | B | Epic 开发者社区《Help with replicating Random Streams》 | https://forums.unrealengine.com/t/help-with-replicating-random-streams/2261404 | 2025-01-05 | ⚠️ | 同种子仍 desync，根因是服务器分支多取一次随机数 |
| 20 | B | DEV《One Seed, Same Puzzle for Everyone: Seeded RNG for a Fair Daily Challenge》 | https://dev.to/gowtham_r_2002/one-seed-same-puzzle-for-everyone-seeded-rng-for-a-fair-daily-challenge-1h7d | 2026-06-09 | ⚠️ | 每日挑战刻意用公开单种子 + 服务端按同一调用顺序重算；「RNG 调用顺序即契约」 |
| 21 | B | 《Design Notes for a Deterministic C++ Simulation Framework》 | https://dev.to/mendolatech/design-notes-for-a-deterministic-c-simulation-framework-56fo | 2026-08-13 | ⚠️ | 「多系统共享的全局生成器很脆弱——在无关特性里新增一次随机调用会平移全局序列」 |
| 22 | B | bugnet.io《How to Debug Random Seed Related Bugs in Games》 | https://bugnet.io/blog/how-to-debug-random-seed-related-bugs | 2026-04-06 | ⚠️ | 一个粒子特效多消费一次随机数即可改变敌人出生点、掉落与 AI；附状态校验和与首个分歧帧定位 |
| 23 | B | bugnet.io《Fix: Godot RNG randomize() Not Called Deterministic》 | https://bugnet.io/blog/fix-godot-rng-randomize-not-called-deterministic | 2026-04-04 | ⚠️ | 引擎默认单条全局 RNG 是规模化踩坑源：存档存种子、多线程各持一条流 |
| 24 | B | gamespark《Arknights: Endfield 主创 GDC 访谈：抽卡机制将调整》 | https://www.gamespark.jp/article/2026/04/07/164874.html | 2026-04-07 | ⚠️ | 头部厂商在抽卡体验/公平性上仍处迭代期 |

**配额构成**：合计 **24** 条；S **13** / A **3** / B **8** ⇒ S+A = **16**（≥12 ✅）、总数 ≥20 ✅。

---

## 8. 不计配额（按 `rules/industry-benchmark.md` §二/§三 如实剔除）

**发布日早于窗口（现行仍有效，但不计入配额）**
- 中国文化部 文市发〔2016〕32号《关于规范网络游戏运营加强事中事后监管工作的通知》——2016-12-01 发布 / 2017-05-01 施行：须公示全部道具名称/性能/数量/**抽取概率**且真实有效，抽取记录留存 ≥90 日，须提供直接购买等价道具的途径。https://zwgk.mct.gov.cn/zfxxgkml/scgl/202012/t20201206_918193.html
- 国家新闻出版署《网络游戏管理办法（草案征求意见稿）》——2023-12-22（**征求意见稿，未按原样落地**）：拟要求随机抽取对抽取次数与概率合理设置、不得诱导过度消费。http://zw.china.com.cn/2023-12/22/content_116897953.shtml
- 日本 JOGA《ランダム型アイテム提供方式における表示および運営ガイドライン》——行业自律，要求个别出现概率可查（**原始文档日期未能二次确认，不可用**）。https://japanonlinegame.org/wp-content/uploads/2017/06/JOGA120815-1.pdf

**无明确发布日期（不可用）**
- Google Play 开发者政策（随机虚拟物品须在购买前披露概率）https://support.google.com/googleplay/android-developer/answer/17190352 、Apple App Store 审核指南 loot box 概率披露要求（经 Fenwick 解读）https://www.fenwick.com/insights/publications/apple-now-requires-disclosure-of-loot-box-odds
- Playwright《Visual comparisons》https://playwright.dev/docs/test-snapshots 、Lost Pixel baseline 流程（golden 重录须显式 Approve / Reject）——作为**方法学佐证**引用，不计配额

**未能核验（明确不引用）**
- 比利时 Gaming Commission 与荷兰 Kansspelautoriteit 在窗口内的官方原文（抓取失败，仅得二手材料）
- `github.com` 在本机不可达 ⇒ godotengine/godot#119322、defold/defold#6170、MonoGame/MonoGame#3772、rwmt/Multiplayer（RimWorld 联机 desync 文档）的发布日期无法核验，**不计配额**；故 Godot/RimWorld 部分结论强度低于 Unity/Unreal/CK3/Factorio

**历史经典（窗口外，不计配额）**
- Will Wilson（FoundationDB）《Testing Distributed Systems w/ Deterministic Simulation》(2014) —— 确定性仿真测试源头
- Forgotten Arbiter《Correlated Randomness》（Slay the Spire 1 同类问题）https://forgottenarbiter.github.io/Correlated-Randomness/
- Google Testing Blog《Just Say No to More End-to-End Tests》(2015-04) —— golden/记录-回放思想源头
- 日本 gacha 专利 JP5633100B1「ガチャ実行処理」（服务端概率抽取流程）

---

## 9. 取证缺口（诚实声明）

1. **厂商署名的一手复盘缺失**：「因新增一次随机消费导致回归测试/回放大面积失效」的**厂商署名**材料未取得；目前最贴近的是 B 级的 dev.to/bugnet 两篇与 Epic 社区帖。
2. **Photon 与 Godot 类参考未能直读**（反爬 / 正文未取到）⇒ 这两条 S 级的引用内容以子代理检索为准，**实施若依赖其原文需在有网络权限的环境复核**。
3. **头部抽卡产品的随机流拓扑公开面为 0**：本报告只能证明「行业形态是域状态独立 + 服务端权威」，**不能**证明「某产品内部把抽卡与结算分成两条流」——这点必须如实标注。

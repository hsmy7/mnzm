# 批次 B13 — R3.8（原生浮层与文本通道 Tier1：浮字池 + 事件驱动 spawn + 场景回归集）

> 来源：`docs/native-engine-refactor-plan-2026-09-17.md` §3 R3 表 **R3.8 行** + R3 验收行
> （"验收：G3/G4；截图回归测试……扩展为场景回归集"）。
> 台账批次总表：`B13 = R3.8`（`docs/parallel-batches-w5/dispatch-ledger.md`）。
> **前置 = B12 已验收**：`batch-R3C.md`（R3.5 = `21345d413`/`e42088a3c`、守卫 = `d6d7b3b8b`、
> 文档 = `66f94d081`）与方案 §7.2 的 **B10/B11/B12 登记块**。
> 本批是 **R3 渲染阶段的收官批**：交付后 R3.1–R3.8 全部落地（R3.7 不存在于方案，编号跳位属方案原文）。
> 开工前先读仓库 `CLAUDE.md`、方案 §1/§2/§3.R3/§3.1 治理规则/§5 风险登记册/§7.2（B10–B12 段）。
> 直接消费既有成果：`scene/scene_draw.h`（绘制核心）、`scene/scene_uv_tables.h`（B10 UV codegen 先例）、
> `drawFrame(…)` 每帧标量通道、`SceneUpdateChannel`（脏更新判定器）、
> `RecorderRenderer`（B11 建立的顶点流记录测试替身）、R0.3 遥测计数器口径。

## 任务

1. **R3.8-① Tier1 文本资产管线（预烘焙，规避动态字体系统）**：
   - 数字 `0-9`、拉丁字母、有限固定词条（"会心/格挡/闪避/连击/暴击/+/-"等**有限游戏术语集**，
     词表须在批次内显式冻结并列清单）预烘焙为 sprite 资产；
   - 复用现有图集管线（build-atlas.mjs / KTX / ASTC），**沿 B10 `scene_uv_tables.h` 的 codegen 先例**：
     生成器纳入 codegen hash 门、生成头入库（桌面 GTest 与 NDK 构建无需先跑 codegen）、
     非法输入自抓；Kotlin 侧镜像守卫逐位对照（沿 `SceneUvTablesMirrorGuardTest` 先例，toRawBits）；
   - **Tier2（动态字形缓存/任意字符串）明确不在本批**——方案原文推迟，需求出现再立项；
2. **R3.8-② 浮字对象池 + 动画核心（全 C++）**：
   - 固定容量浮字池（~256 实例，常量显式命名）：实例 = 世界空间锚点 + 词条/数字资产索引
     + 颜色/样式档 + 出生时刻 + 动画参数；**池满覆盖最旧**（循环缓冲语义，守卫锁定）；
   - 动画（上浮 / 淡出 / 暴击弹跳等 Tier1 动画集）**全部 C++ 时间驱动，零每帧 JNI**：
     每帧绘制时由 C++ 依据当前时间自行推进；时间源设计自定（drawFrame 追加标量 / 既有字段复用
     / 独立轻量端口），但必须满足验收门 3 的 G3 字节预算与 JNI 纪律；
   - `shutdownRenderer` 后池**纪元复位**（沿 B10 场景/B12 farViewGroundQuad 复位先例）；
   - 池满/溢出遥测接既有计数器口径（R0.3 三计数器族），不新增静默丢弃；
3. **R3.8-③ spawn 通道（事件驱动，低频）**：
   - 新 JNI 端口 `sceneSpawnFloatingText`（或同族命名）：战斗事件发生时单次跨线，
     **逐个登记豁免理由**（沿 R0.2 / B06 / B10 八端口 / B11 三端口 / B12 一端口先例），
     给出端口总数前后对照；
   - 既有 JNI 签名零变更（若 `drawFrame` 须追加时间标量：允许尾部追加，但属 ABI 变更——
     须单独登记豁免 + Kotlin/C++ 双端同步 + 全部既有等价守卫更新并逐条复跑）；
4. **R3.8-④ 渲染集成 + 场景回归集（截图回归的桌面确定性形态）**：
   - 浮字批走既有 push-constant 投影（相机变换复用 `updateCameraGlobals` 单实现）；
     层序 = 浮字在最上层（叠加层之上）；**空池 = 0 draw call**（不得引入空批提交）；
   - Vulkan/GLES 同构：判定/推进/提交落基类（沿 B11/B12 构造性同构先例），零 GLES 专属分支；
   - **场景回归集（R3 验收行"截图回归"的落地）**：建立桌面确定性像素回归——
     在既有顶点流记录器（`RecorderRenderer`）之上加**确定性软光栅化**（测试代码内实现，
     顶点流 → 像素缓冲 → golden 基准入库），覆盖：六要素场景 + 浮字场景（spawn 后多帧动画采样：
     出生/上浮中/淡出/池满覆盖）× 相机三档位；`-ffp-contract=off` 已保证位一致（B11 前置缺陷 B）；
     golden 基线变更须显式重生并说明原因；
   - **Canvas 兜底零改动**（红线）：浮字状态不经 `RenderFrame` 契约（Canvas 消费面不动），
     Canvas 兜底下浮字通道不生效——该降级语义须在 KDoc/文档显式登记；
   - **消费面接入不在本批**（范围纪律）：既有 Compose 浮字（天劫界面 `DamageNumberState` 族）
     迁移到本通道列为后续事项，完成报告显式声明"零生产消费面接入"及其理由（通道交付批）。

## 红线（违者验收打回）

- **G3 不退化**：稳态每帧 JNI 传输字节（含新时间标量后重测）仍 **< 200B**；
  浮字活跃期**每帧 JNI 次数 = 0**（动画自驱动，行为级守卫锁定：spawn 后连续 N 帧 0 跨线）；
- **G4 不退化**：放置模式每帧 JNI 次数 **< 10**、vkCmdDraw **< 15**（含浮字批占用，
  给出 B11/B12 基线 → 本批前后对照；空池零开销）；
- **零每帧 JNI 的生成纪律**：spawn 只能事件驱动（低频），禁止帧循环轮询/逐帧同步浮字状态；
- **Canvas 兜底零改动**：`SoftwareCanvasBackend`/`SoftwareCanvasBackendOverlays` 不得出现在改动面，
  既有 Canvas 测试族全绿且未改语义；
- **§3.1 治理规则**：新视觉元素禁止建在 Compose（本批通道本身即 native 实现）；
  Tier2 动态字形系统不得以任何形式提前引入；
- **协议 JSON 面 / 存档格式 / 既有 JNI 签名零变更**（`drawFrame` 尾部追加标量须按任务 3 的
  豁免流程单独登记）；新增 `external fun` 逐个登记豁免理由 + 端口总数前后对照；
- **每子项独立 commit**（①资产管线 / ②池+动画核心 / ③spawn 通道 / ④渲染集成+回归集，
  允许按实际耦合合并为 ≥3 笔，但合并须在完成报告说明理由）；提交信息沿用仓库惯例
  （`feat(renderer): 重构方案 R3.8/B13 …`）；
- 提交前 `./gradlew.bat compileReleaseKotlin lintRelease` 必须 BUILD SUCCESSFUL。

## 验收门（自检全绿才算完成；看护会亲自复跑，且**不采信自述**）

1. **桌面全量 GTest 全绿**：当前 desktop-test 基线 **1494**（B12 后）。本批含 C++ 改动 ⇒
   先 `pwsh -File scripts/build-desktop-jni.ps1` 重建对拍桥，再在
   `android/app/src/main/cpp/gamecore/build/desktop-test` 执行 `cmake . && cmake --build . && ctest`；
   新增守卫同步登记**新基线数字**。
   - **环境注意（B11/B12 验收实测）**：`ctest` 须把 llvm-mingw `bin` 置于 PATH
     （`C:\Users\<user>\llvm-mingw\llvm-mingw-*\bin`），否则测试二进制因缺
     `libc++.dll`/`libunwind.dll` 全部 `0xc0000135` 假失败；
2. **全量 JUnit 实跑非 UP-TO-DATE**：
   `cd android && ./gradlew.bat testReleaseUnitTest --max-workers=1 --rerun-tasks "-Dgamecore.jni.path=C:/Mnzm/XianxiaSectNative/android/core/engine/build/desktop-jni/libgamecorejni.so" detekt compileReleaseKotlin lintRelease`
   （约 20–28 分钟；**须设 `JAVA_HOME=C:/Users/<user>/.jdks/jdk-21.0.12.1+1`**，
   否则 `JAVA_HOME is not set` 直接失败）。判绿必须 XML 实证：六模块 totals/0 失败/**Diff\* 类 0 skip**。
   已知抖动 `GameEngineCoreLifecycleInterleavingTest` 按 B03/B10/B11/B12 前例处置
   （单点失败 + Diff 全过 → 重跑，**勿改测试**）；
3. **G3/G4 前后对照数字**（守卫实跑打印为准，非文档声明）：稳态每帧 JNI 字节、
   浮字活跃期每帧 JNI = 0 的行为级证明、放置模式每帧 JNI 次数与 vkCmdDraw 计数
   （B11/B12 基线 → 本批对照表）；未达标即诚实登记残余与归因，不得粉饰；
4. **场景回归集可复跑**：软光栅 golden 基线入库（含浮字动画多帧采样），一条命令复跑全绿；
   golden 生成脚本 + 变更重生流程写明；真机截图回归与 Adreno 黑名单补全
   **登记残余**（延续 B11/B12 残余③，不在本批粉饰为已达成）；
5. **池语义守卫**：池满覆盖最旧、纪元复位、spawn 参数防御（非有限坐标/非法索引不崩溃不残留）；
6. **文档三件套**：方案 §7.2 追加 **B13 行**（Tier1 资产清单与词表冻结、池/动画设计、
   spawn 端口豁免、G3/G4 对照、场景回归集形态与残余）；`CHANGELOG.md` 4.01.15 段内追加；
   `docs/cpp-engine.md`（仓库根 `docs/` 下）进展行同步；
   另按惯例同步 `android/docs/renderer-feature-checklist.md`（新绘制形态登记双端状态 +
   Canvas 兜底降级语义）。

## 完成报告格式（最终消息必须含）

- 提交号列表（逐子项）；
- GTest 数字（含新基线）/ JUnit 六模块各模块数与实跑证明（XML 时间戳 + Diff 0 skip）/ detekt 结论——贴关键输出行；
- **G3/G4 前后对照表**（守卫实跑打印值）与达成判定；
- Tier1 词表冻结清单 + 资产生成管线说明（codegen hash 门证据）；
- 浮字池设计要点（容量/覆盖语义/时间源选型/纪元复位）与 spawn 端口豁免登记 + 端口总数前后对照；
- 场景回归集：软光栅实现形态、golden 覆盖矩阵、复跑命令、真机残余登记；
- 灰度开关（如有）名称与默认值、回退路径说明；
- 改动文件清单；"零生产消费面接入"声明；与方案 R3.8 验收口径的逐条对照。

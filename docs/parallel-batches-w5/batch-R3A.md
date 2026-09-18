# 批次 B10 — R3.1 + R3.2（C++ SceneStore + JNI 面重构：drawAllTiles 退役）

> 来源：`docs/native-engine-refactor-plan-2026-09-17.md` §3 R3 表（R3.1、R3.2 行）。
> **本批是 R3 渲染阶段的结构性开端，行为等价性风险最高——严格按红线执行。**
> 开工前先读仓库 `CLAUDE.md`、方案 §1/§2/§3.R3/§3.1 治理规则/§5 风险登记册/§7.2。

## 任务

1. **R3.1 新建 C++ `SceneStore`**（native-renderer 内新模块）：持有 terrain
   （与 gamecore 单一来源，初始化时导入一次）、road mask、建筑集、作物集、
   崖壁布局（现 Kotlin 预计算的稳定 FloatArray，下沉后 IslandCliffBridge 变薄）、云实例；
2. **R3.2 JNI 面重构**：废弃 `drawAllTiles(17 参数全量数组)` →
   `sceneSetTerrain(v)`（一次性）+ `sceneUpdateBuildings(diff)` + `sceneUpdateCrops(progress)` +
   `sceneUpdateRoads(diff)` + `drawFrame(camera, overlayFlags)`；
   UV 常量表由 build-atlas.mjs 同源生成进 C++（现仅生成 TextureAtlas.h），
   Kotlin 不再每帧传 SpriteAtlasDef 数组。

## 红线（R3 行为等价性风险最高，违者验收打回）

- **新旧 drawAllTiles 路径共存一个版本周期**（灰度开关，方案 §5 风险缓解原文）——
  旧行为不得删除，仅默认切新路径（或旗标控制），可即时回退；
- **三后端一致性**：Vulkan/GLES/Canvas 兜底三后端的场景回归测试扩展为验收门
  （方案 §3 R3 验收行原文），新 SceneStore 路径与旧路径像素/语义等价；
- **Canvas 兜底路径不动**（R3.6 原文——Kotlin 侧数据流保留，显式标注兜底专属技术债）；
- 每子项独立 commit（R3.1 一个、R3.2 一个），提交信息沿用仓库惯例；
- §3.1 治理规则生效：任何新视觉元素禁止建在 Compose；
- Kotlin 渲染层改动允许（薄驱动化），但协议 JSON 面/存档格式零变更；
- 提交前 `./gradlew.bat compileReleaseKotlin lintRelease` 必须 BUILD SUCCESSFUL。

## 验收门（自检全绿才算完成；看护会亲自复跑）

1. 桌面全量 GTest **全绿**（当前 desktop-test 配置基线 **1456**（B09 后）；
   本批含 C++ 改动：先 `pwsh scripts/build-desktop-jni.ps1` 重建对拍桥 +
   `cmake --build` 重建 GTest 二进制；新增守卫同步更新基线数字并登记）；
2. 全量 JUnit **实跑非 UP-TO-DATE**（`--rerun-tasks` + `-Dgamecore.jni.path`）；
   已知抖动 `GameEngineCoreLifecycleInterleavingTest` 按前例处置；
3. `./gradlew.bat detekt` 绿；
4. **场景等价性证据**：新旧路径场景回归对照（地形/道路/建筑/作物/崖壁/云 全要素 +
   相机多档位），截图回归或语义等价守卫任一，需可复跑；
5. **JNI 面纪律**：新增 external fun 逐个登记豁免理由（沿 R0.2/B06 先例），
   `drawAllTiles` 标记 deprecated 但保留；
6. 文档三件套：方案 §7.2 追加 B10 行（SceneStore 结构/JNI 面清单/灰度开关位置）；
   `CHANGELOG.md` 4.01.15 段内追加；`docs/cpp-engine.md` 口径同步。

## 完成报告格式（最终消息必须含）

- 提交号列表（逐子项）；
- GTest 数字 / JUnit 各模块数与实跑证明 / detekt 结论（贴关键输出行）；
- 场景回归对照方式与结果（三后端覆盖情况）；
- 新增 JNI 端口清单与豁免登记；
- 灰度开关名称与默认值、回退路径说明；
- 改动文件清单；与方案 R3.1/R3.2 验收口径的逐条对照。

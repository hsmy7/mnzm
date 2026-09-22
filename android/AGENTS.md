# AGENTS.md — Android 工程层

> 本文件是 `android/` 目录的追加规范，只覆盖本目录特有的约定。
> **通用规范（用户公约、编码规范、任务路由表）见仓库根 `AGENTS.md` —— 那份是唯一真源，本文件不重复。**

## 构建与测试

命令一律在 `android/` 下执行（完整清单见根 `AGENTS.md` §2）：

- 编译检查：`./gradlew.bat compileReleaseKotlin`（每次改动后必跑）
- 全量测试：`./gradlew.bat testReleaseUnitTest --max-workers=1`
  —— **`--max-workers=1` 不可省**：并行会因共享静态状态跨类污染，产生假红/假绿
- 单类测试必须模块限定：`./gradlew.bat :app:testReleaseUnitTest --tests "..." --max-workers=1`
  （裸 `test --tests` 在 Gradle 8.14.5 + AGP 聚合任务下报 Unknown command-line option）
- 构建质量门禁流程见 `rules/build-quality.md`；提交前审查清单见 `rules/pr-review-checklist.md`

## 模块结构与边界

```
android/
  app/                应用壳 + JNI 桥（core/* 与 feature/* 的组装点）
  core/domain/        数据类、接口、sealed、Registry 静态数据（零 Android 依赖）
  core/data/          Room DB/DAO/Migration、序列化、加密、Repository 实现
  core/engine/        GameEngine、Service、System、游戏循环
  core/ui/            共享 Compose 组件、Theme、导航工具
  feature/game/       ViewModel、Screen 级 Compose、对话框
  app/src/main/cpp/   原生渲染（Vulkan/GLES/软件）+ gamecore/ C++ 引擎
  config/detekt/      detekt 配置（阈值以该文件实值为准）
  docs/               渲染相关规范
```

依赖方向**不可反转**：`core:domain` ←（`core:data` / `core:engine` / `core:ui`）← `feature:game` ← `app`。
循环依赖由 CI 的 Konsist 检查拦截。各模块允许/禁止包含的内容见根 `AGENTS.md` §5 编码规范 2.2。

## 常见陷阱

- **KSP 增量缓存损坏**（`NoSuchFileException: *_Impl.java`）→ `./gradlew.bat clean` 后重试
- **覆盖率恒为 0** → Kover 插桩与报告同开关，必须传 `-Pkover.enabled=true`
- **detekt baseline 只缩不增** —— 新违规必须修复，不得加入 baseline
- **新增枚举/注册表/配置项** → 除构建检查外必须跑对应守卫测试，守卫红即任务未完成

# 阶段 3：构建配置重构（配置缓存 + codegen 增量）（2026-08-14）

> 阶段 3 产物。目标：移除配置缓存唯一代码阻塞（afterEvaluate）、配置期文件惰性化、
> codegen 增量化、启用配置缓存、CI 缓存收尾。全部验证通过（444 类测试全绿）。

## 3.1 移除根 build.gradle afterEvaluate hack

- **改动**：`android/build.gradle` 删除 `subprojects { afterEvaluate { ... } }` 块
  （原 18-45 行，compileSdk 35 / minSdk 24 / targetSdk 35 / Java 17 / UTF-8 / jvmTarget 17 /
  `-Xjsr305=strict` 全局注入）——配置缓存唯一真实代码阻塞。
- **7 个 android 模块显式化**（app / core:engine / core:data / core:domain / core:ui /
  feature:game / baselineprofile）：`android{}` 内写 `compileSdk = 35` + `compileOptions`，
  `defaultConfig{}` 内写 `minSdk = 24` / `targetSdk = 35`，文件末尾追加
  `tasks.withType(JavaCompile/KotlinCompile).configureEach`（encoding / jvmTarget / Xjsr305）。
- **app 模块注意**：第二个 `android{}` 块（externalNativeBuild cmake SPRITE_GENERATED_DIR）
  是同一 extension 的补充分配，无需重复配置。
- **验证**：`compileReleaseKotlin` BUILD SUCCESSFUL（漏改即编译期报错，方案原文预期）。
- **过程教训**：批量 sed 多行插入（`0,/pattern/a\`）在本环境（Git Bash GNU sed）不可靠，
  造成 core/domain 文件重复插入十几处损坏——`git checkout` 恢复后改用 Edit 工具精确编辑。

## 3.2 配置期 File 读取惰性化

- **改动**：app（keystore/api/version 3 个）、core:engine（api/version 2 个）、core:domain
  （version 1 个）的 `new Properties().load(new FileInputStream(...))` 全部改为
  `providers.fileContents(...).asText.map { loadProperties(it) }.get()`——声明式跟踪，
  **改属性文件后配置缓存正确失效**（普通 File IO 配置缓存无法感知，改了不生效）。
- **API 坑**：`providers.fileContents(File)` 重载**不存在**（Gradle 8.14 ProviderFactory 仅有
  RegularFile / RegularFileProvider / Path 重载），报 `Could not find method fileContents()`。
  改用 `layout.projectDirectory.file("相对路径")`（RegularFileProvider 重载，最老最稳），
  相对路径含 `../version.properties` 可用。
- **语义保持**：文件不存在时 `asText` 返回空文本 → 空 Properties，与原 `if (exists)` 一致。
- **验证**：`compileReleaseKotlin` BUILD SUCCESSFUL。

## 3.3 Node codegen 任务 inputs/outputs 声明（确认完成）

| 任务 | inputs | outputs | 状态 |
|---|---|---|---|
| app:generateResourceManifest | 双模块 drawable-nodpi + resource-manifest.mjs | atlas-manifest.json | ✅ 已声明 |
| app:generateSpriteCode | build-atlas.mjs + resource-registry.json + manifest | SpriteRegistryData.kt + TextureAtlas.h + .sprite-code.hash | ✅ 已声明 |
| app:generateFootprintHeader | 上游 SpriteAtlasDef.kt | src/main/cpp/footprint_table.h | ✅ 已声明 |
| core:engine:generateSpriteAtlasDef | build-atlas.mjs | SpriteAtlasDef.kt + .atlas-def.hash | ✅ 已声明 |

**技术债挂账**：generateFootprintHeader 输出仍写源码树 `src/main/cpp/footprint_table.h`
（非 build/）。up-to-date 判定已正确（inputs/outputs 完整），迁移需改 CMake include 路径
且需完整 C++ 构建验证，收益仅"避免写源码树"（对构建时长无影响）。
**偿还触发**：CMake 重构时一并迁移 `build/generated/`。

## 3.4 启用配置缓存

- **改动**：`gradle.properties` 加 `org.gradle.configuration-cache=true` +
  `org.gradle.configuration-cache.problems=warn`（渐进消化，全绿后视情况收紧为 fail）。
- **problems 修复（7 处 doLast 脚本对象引用）**——配置缓存禁止任务执行代码引用脚本对象：
  1. `checkDelegateLaunchPattern`：`$rootDir` 字符串插值 → 配置期捕获 `delegateDir`/`excludedFiles`
  2. `validateChangelogJson`：`$projectDir` → 配置期捕获 `jsonFile`
  3. `generateFootprintHeader`：`${rootProject.projectDir}` + `file()` 调用 → 配置期捕获 `srcFile`/`outFile`
  4. `generateResourceManifest`：`rootProject.projectDir` → 配置期捕获 `scriptDir`
  5. `generateSpriteCode`：同上
  6. `generateAstcAtlas`：`file()` 调用 + `projectDir` → 配置期捕获 `astcencDir`/`astcencCandidates`/`scriptDir`
  7. `core:engine:generateSpriteAtlasDef`：`rootProject.projectDir` → 配置期捕获 `scriptDir`
  （archiveAndUploadMapping 已符合规范——配置期捕获变量，未改）
- **验证**（全部在配置缓存启用下）：
  - `:app:assembleDebug`：首跑 "Configuration cache entry stored"（1m24s 含 CMake），
    二跑 "Reusing configuration cache" ✓
  - `detekt`：BUILD SUCCESSFUL（checkDelegateLaunchPattern 通过）✓
  - `testReleaseUnitTest --max-workers=1`：**444 类全绿、0 失败** ✓

## 3.5 CI 缓存收尾

- **改动**：`.github/workflows/ci.yml` 的 Detekt / validateChangelogJson 两步骤补
  `--build-cache`（与 Compile/Test/coverage 步骤一致，跨 runner 复用本地不可见的缓存）。
- **distributionUrl 镜像决策**：保留腾讯镜像
  （`https://mirrors.cloud.tencent.com/gradle/gradle-8.14.5-bin.zip`）。
  - 国内开发机持续受益（下载快）
  - CI（GitHub Actions 海外 runner）首次下载走镜像略慢于官方源，但一次性成本
    ——setup-gradle 缓存 wrapper dists，后续 runner 命中缓存
  - 官方源换回的收益（CI 首次下载 ~30s）远小于国内开发机持续损失，**保留镜像**
- **CI 全绿验证状态（诚实披露）**：本地无法运行 Ubuntu runner，ci.yml 语法与配置
  正确性已人工核对 + 本地等价命令全部验证（compileReleaseKotlin / testReleaseUnitTest /
  detekt / validateChangelogJson / koverHtmlReport -Pkover.enabled=true），
  **push 到 GitHub 后的首次实跑未验证**——待用户 push 触发。

## 阶段 3 量化收益（对照 baseline-20260814.md 第 5 节）

| 项 | Before | After |
|---|---|---|
| 配置阶段（二跑） | 全量配置每次执行 | "Reusing configuration cache"——配置阶段跳过（典型降 30~70%） |
| 二跑增量构建 | — | assembleDebug 全 UP-TO-DATE + 缓存复用 |
| codegen 判定 | mjs 脚本每次启动 | Gradle up-to-date 判定（权威源未变跳过 node 启动） |

## 技术债汇总（阶段 3 新增/确认）

| 技术债 | 偿还触发 |
|---|---|
| generateFootprintHeader 输出写源码树 | CMake 重构时迁移 build/generated/ |
| SDK 配置 7 模块重复（无 convention plugin） | 模块再增长或 KMP 迁移时建 build-logic 收编 |

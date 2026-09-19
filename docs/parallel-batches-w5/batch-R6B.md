# 批次 B16 — R6.2（数值外置：C++ 头文件 DB → 数据文件加载）

> 来源：`docs/native-engine-refactor-plan-2026-09-17.md` §3 R6 表 **R6.2 行**
> （"C++ 头文件 DB（herb/equipment/recipe/trait 等）改数据文件加载
> （`nativeSetGameConfig` 通道扩展）。改数值不再触发逻辑重编译"）。
> 台账批次总表：`B16 = R6.2`（`docs/parallel-batches-w5/dispatch-ledger.md`）。
> **前置 = B15 已验收**（R6.1 图集离线化，`batch-R6A.md`）。
> 开工前先读仓库 `CLAUDE.md`、方案 §3.R6/§5 风险登记册/§7.2（B10–B15 段）、
> `gamecore/include/gamecore/core/game_config.h`（**既有注入先例**）、
> `gamecore/include/gamecore/data/`（待外置的 7 个头文件 DB）、
> `GameConfigProvider`/`game_config.json`（Kotlin 配置单源先例）、
> `GameCoreBridge.cpp::nativeSetGameConfig`（既有 JNI 注入通道）。

## 任务

1. **消费面枚举**：逐个盘点 `data/` 头文件 DB（beast_config / beast_material_db /
   equipment_db / herb_db / manual_db / recipe_db / trait_db）的**行数、字段、
   C++ 消费点**（编译期常量表的每处引用），列出清单后动手；
2. **数据文件化**：DB 内容改由**数据文件**承载（assets 下 JSON/二进制，格式自定但须
   与 Kotlin 侧同源可校验）；构建期生成或人工维护二选一并说明理由；
   **沿 `game_config.json` 单源先例**：数值真相源在 Kotlin/资产侧，C++ 启动时注入解析；
3. **注入通道扩展**：沿 `nativeSetGameConfig` 既有先例扩展注入通道（单端口批量注入优先，
   禁止逐条目多次跨线）；注入时点 = 引擎初始化期（单线程契约）；注入失败时行为明确
   （编译期静态兜底值或显式失败，禁止静默空表）；
4. **桌面/测试接线**：桌面 GTest 与对拍桥必须注入**同一份数据**（与生产值一致），
   保证 Diff 对拍与新增守卫在桌面可复跑；
5. **头文件 DB 退场形态**：改数据文件后，头文件 DB 可 ①删除 ②转为"默认值兜底 + 生成物"
   ——二选一并说明理由；若保留须与数据文件**同源生成**（codegen hash 门先例），
   禁止双真相源漂移；
6. **改数值不再触发逻辑重编译的实证**：修改一个数值（如某丹药数值）后，仅数据文件变更、
   零 C++ 逻辑重编译的演示/说明（验收门 5）。

## 红线（违者验收打回）

- **数值逐位等价**：数据文件加载后的 C++ DB 与改前头文件 DB **逐行逐字段相等**
  （等价性守卫锁定；这是对拍不变的前提）；
- **既有分区/对拍/存档零扰动**：`Diff*` 测试零改动（或逐条说明原因）；
  存档 schema/协议 JSON 面零变更；
- **JNI 纪律**：沿 `nativeSetGameConfig` 先例扩展通道，新增端口逐个登记豁免 +
  端口总数前后对照；**零每帧/每事务跨线**（注入仅初始化期一次）；
- **注入前/失败语义明确**：兜底值与数据文件默认值一致（game_config.h 注释先例），
  静默空 DB 视为缺陷；
- **协议 JSON 面 / 存档格式 / 既有 JNI 签名零变更**；
- **每子项独立 commit**（消费面清单一笔 / 数据文件+注入+接线一笔 / 守卫+文档一笔，
  或按耦合合并 ≥2 笔但说明理由）；提交信息沿用仓库惯例
  （`feat(engine): 重构方案 R6.2/B16 …`）；
- 提交前 `./gradlew.bat compileReleaseKotlin lintRelease` 必须 BUILD SUCCESSFUL。

## 验收门（自检全绿才算完成；看护会亲自复跑，且**不采信自述**）

1. **桌面全量 GTest 全绿**：当前 desktop-test 基线 **1545**（B14 后实测；B15 零 C++
   回归持平）。本批含 C++ 改动 ⇒ 先 `pwsh -File scripts/build-desktop-jni.ps1` 重建
   对拍桥，再 `cmake . && cmake --build . && ctest`（desktop-test 目录）；
   新增守卫同步登记**新基线数字**。
   - **环境注意**：`ctest` 须把 llvm-mingw `bin` **及** `x86_64-w64-mingw32/bin`（UCRT）
     置于 PATH；
2. **全量 JUnit 实跑非 UP-TO-DATE**：
   `cd android && ./gradlew.bat testReleaseUnitTest --max-workers=1 --rerun-tasks "-Dgamecore.jni.path=C:/Mnzm/XianxiaSectNative/android/core/engine/build/desktop-jni/libgamecorejni.so" detekt compileReleaseKotlin lintRelease`
   （约 30–55 分钟；**须设 `JAVA_HOME=C:/Users/cp050/.jdks/jdk-21.0.12.1+1`**）。
   判绿必须 XML 实证：六模块 totals/0 失败/**Diff\* 类 0 skip**；
   已知抖动按前例处置；**已知环境锁**（mergeReleaseResources）按台账经验教训处置；
3. **数值等价守卫**：数据文件加载后 DB 与改前头文件 DB 逐行逐字段相等的守卫实跑输出
   （行数 + 抽样/全量字段断言）；
4. **注入纪律守卫**：注入仅初始化期一次（行为级守卫：稳态零跨线）；
   注入失败路径语义明确（兜底值断言）；
5. **"改数值不重编译"实证**：修改某数值后仅资产变更、C++ 逻辑零重编译的复现说明
   （构建输出或等价证据）；
6. **文档三件套**：方案 §7.2 追加 **B16 行**（DB 清单、数据文件格式、注入通道扩展、
   退场形态选型、等价守卫）；`CHANGELOG.md` 4.01.15 段内追加；
   `docs/cpp-engine.md` 进展行同步。

## 完成报告格式（最终消息必须含）

- 提交号列表（逐子项）；
- GTest 数字（含新基线）/ JUnit 六模块各模块数与实跑证明（XML 时间戳 + Diff 0 skip）/ detekt 结论——贴关键输出行；
- **DB 消费面枚举清单**（行数/字段/消费点）；
- **数值等价守卫实跑输出**（逐行逐字段相等证明）；
- 注入通道扩展说明（端口豁免登记 + 端口总数前后对照）与注入失败语义；
- 数据文件格式选型理由 + "改数值不重编译"实证；
- 改动文件清单；与方案 R6.2 验收口径的逐条对照。

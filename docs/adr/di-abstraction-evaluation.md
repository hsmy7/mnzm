# ADR: DI 抽象选型评估（G5 闭环）

> 状态：✅ 已评估 | 日期：2026-08 | 关联：docs/platform-abilities.md G5

## 背景

`docs/platform-abilities.md` G5 登记待办：DI 无抽象（Hilt Android 独占），Koin/手写 DI
评估，迁移前置条件之一。

## 现状事实（2026-08 实测）

- Hilt 2.56：@Singleton/@Inject 构造注入 + 少量 @Provides 模块
  （StorageModule/AudioModule/EngineCrashReporterModule/CrashReporterModule）
- Hilt 使用面：app 层 DI 模块 + core 层 @Inject 注解（javax.inject，KMP 友好）
- 本次债务根治新增的 4 个接口绑定（KeyValueStore/AudioPlayerFacade/CrashReporter/
  ComplianceCallbackHost）全部为"接口 + 实现"形态，**与 DI 框架解耦**——手写/替换
  框架时仅需改 @Module 绑定处

## 评估结论

**决策：维持 Hilt，Koin 列为 iOS 迁移候选项；不引入手写 DI。**

理由：

1. **Koin 与 Hilt 功能对等**——两者均支持 KMP（Koin 原生 KMP，Hilt 仅 Android）。
   Koin 的运行时依赖解析 vs Hilt 的编译期校验：Hilt 的编译期安全网（缺依赖即编译
   失败）在本项目 40+ 单例的规模下价值明确，Koin 的运行时失败模式是回归。
2. **手写 DI 被否决**——40+ 单例 + 分层构造顺序手写管理 = 隐性债务源，违反
   CLAUDE.md 0.2 可维护性铁律。
3. **当前无迁移收益**——iOS 立项前切换 DI 只有成本（全量 @Inject 注解迁移 +
   作用域语义核对）无收益。

## 迁移启动判据

**iOS 迁移立项**时：

1. 评估 Koin 当前版本的 KMP 成熟度与 Compose Multiplatform 集成（当时再调研，
   结论可能变化）
2. 迁移路径：@Inject 构造注解 Koin 兼容 → 逐模块替换 @Singleton 作用域 →
   @Provides 模块改为 Koin module → 删除 Hilt 插件
3. 保留门面边界（本次根治已把所有平台接口做成纯接口），DI 替换不影响引擎/UI 契约

## 后果

- 正向：DI 框架替换风险被接口化隔离（本次根治的附带收益）
- 负向：iOS 立项时需一次性迁移成本（一次性，可控）

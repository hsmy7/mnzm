@file:Suppress("unused")

/**
 * i18n 国际化准备标记文件（预留）
 *
 * 当前状态：项目使用 100% 硬编码中文字符串（零 @StringRes / stringResource() 调用）。
 * 本文件为未来国际化预留的架构设计文档。
 *
 * == 启动 i18n 时的工作清单 ==
 *
 * 1. 创建 values-en/strings.xml 和 values-zh-rCN/strings.xml
 * 2. 提取以下文件中的硬编码字符串到 string resources：
 *    - SaveSelectScreen.kt（存档选择界面）
 *    - PrivacyConsentScreen.kt（隐私政策界面）
 *    - MainActivity.kt（主界面）
 *    - GameActivity.kt（游戏界面）
 *    - SettingsTab.kt（设置界面）
 *    - 仓库/背包 UI 文件（10+ 个 Composable）
 *    - 世界地图/外交 UI 文件（5+ 个 Composable）
 *    - 战斗系统 UI（BattleOverlay, HeavenlyTrialScreen）
 *    - DomainLog / 错误消息（面向用户的异常提示）
 *    - GameConfig（游戏内文本如境界名称、物品类型名称）
 *    - BattleDescriptionGenerator（战斗日志描述）
 *
 * 3. 替换模式：
 *    "硬编码中文" → stringResource(id = R.strings.xxx)
 *    Composable 内: LocalContext.current.getString(R.string.xxx)
 *    非 UI 代码: context.getString(R.string.xxx)
 *
 * 4. GameConfig 中文化方案：
 *    - 方案 A（推荐）：JSON 配置外部化（Phase 1 已完成 game_config.json 基础设施）
 *      为每个语言创建 assets/config/game_config_zh.json / game_config_en.json
 *    - 方案 B：将名称字段（如境界名、BuffType.displayName）从 GameConfig 迁移到 strings.xml
 *
 * 5. 注意事项：
 *    - GameConfig 中的枚举 displayName（BuffType, SkillType, HealType）被战斗日志直接引用
 *    - 顶部枚举（DamageType, SkillType 等）的 displayName 返回中文字面量
 *    - BattleDescriptionGenerator 中的动词列表全部硬编码中文
 *    - 以上需统一为 locale-aware 的字符串获取方式
 *
 * == 预估工作量 ==
 * - 字符串提取：~500+ 条字符串，~15 个 UI 文件 + 3 个核心文件
 * - GameConfig 名称国际化：~100 个名称字段
 * - 战斗日志国际化：BattleDescriptionGenerator ~200 行中文描述
 * - 总计：约 3-5 人天
 */
package com.xianxia.sect.core.i18n

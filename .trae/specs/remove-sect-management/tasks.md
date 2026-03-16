# Tasks

- [x] Task 1: 移除 SectMainScreen.kt 中的宗门管理按钮和对话框
  - [x] SubTask 1.1: 从 QuickActionPanel 中移除"宗门管理"按钮
  - [x] SubTask 1.2: 从 SectMainScreenDialogs 中移除 SectManagementDialog 的状态订阅和显示逻辑
  - [x] SubTask 1.3: 移除 SectManagementDialog 组件定义
  - [x] SubTask 1.4: 更新注释，移除"宗门管理"描述

- [x] Task 2: 移除 MainGameScreen.kt 中的宗门管理按钮和对话框
  - [x] SubTask 2.1: 从快捷操作板块中移除"宗门管理"按钮
  - [x] SubTask 2.2: 从对话框显示逻辑中移除 SectManagementDialog 的状态订阅和显示逻辑
  - [x] SubTask 2.3: 移除 SectManagementDialog 组件定义
  - [x] SubTask 2.4: 移除未使用的 QuickActionGrid 函数（死代码）
  - [x] SubTask 2.5: 保留 ElderSlotWithDisciples、ElderDiscipleSelectionDialog、DirectDiscipleSelectionDialog 组件（其他界面仍在使用）

- [x] Task 3: 移除 GameViewModel.kt 中的宗门管理状态和方法
  - [x] SubTask 3.1: 移除 _showSectManagementDialog 私有状态
  - [x] SubTask 3.2: 移除 showSectManagementDialog 公开状态
  - [x] SubTask 3.3: 移除 openSectManagementDialog() 方法
  - [x] SubTask 3.4: 移除 closeSectManagementDialog() 方法

- [x] Task 4: 验证修改
  - [x] SubTask 4.1: 确认快捷操作板块布局正确
  - [x] SubTask 4.2: 确认其他建筑界面的长老和弟子槽位功能正常
  - [x] SubTask 4.3: 确认所有宗门管理相关代码已被移除

# Task Dependencies
- Task 2 和 Task 3 可以并行执行
- Task 4 依赖 Task 1、Task 2、Task 3 完成

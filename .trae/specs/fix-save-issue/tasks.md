# Tasks

- [x] Task 1: 修复 Gson 配置
  - [x] SubTask 1.1: 在 SaveManager.kt 中修改 Gson 配置，添加 excludeFieldsWithModifiers
  - [x] SubTask 1.2: 在 ModelConverters.kt 中修改 Gson 配置，添加 excludeFieldsWithModifiers
  - [x] SubTask 1.3: 验证修改后的 Gson 配置能正确排除 @Transient 字段

- [x] Task 2: 验证修复效果
  - [x] SubTask 2.1: 编译项目确保无语法错误
  - [x] SubTask 2.2: 确认存档功能正常工作

# Task Dependencies
- Task 2 依赖 Task 1 完成

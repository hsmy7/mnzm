# Tasks

- [x] Task 1: 在GameEngine中添加重置弟子状态的核心逻辑
  - [x] SubTask 1.1: 添加resetAllDisciplesStatus函数，处理探索队伍解散
  - [x] SubTask 1.2: 处理战斗队伍解散
  - [x] SubTask 1.3: 处理工作槽位清空（BuildingSlot、AlchemySlot、SpiritMineSlot、LibrarySlot）
  - [x] SubTask 1.4: 处理职务槽位清空（ElderSlots）
  - [x] SubTask 1.5: 确保思过崖弟子（REFLECTING状态）不受影响

- [x] Task 2: 在GameViewModel中添加调用重置函数的方法
  - [x] SubTask 2.1: 添加resetAllDisciplesStatus函数，调用GameEngine的对应方法

- [x] Task 3: 在设置界面添加重置弟子状态按钮和确认对话框
  - [x] SubTask 3.1: 在SettingsTab中添加"重置弟子状态"按钮
  - [x] SubTask 3.2: 添加确认对话框，显示提示信息
  - [x] SubTask 3.3: 确认后调用ViewModel的重置方法

# Task Dependencies
- Task 2 依赖 Task 1
- Task 3 依赖 Task 2

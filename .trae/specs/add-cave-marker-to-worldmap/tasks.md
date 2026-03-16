# Tasks

- [x] Task 1: 在 WorldMapScreen 中添加洞府标记组件
  - [x] 1.1: 创建 CaveMarker Composable 函数，显示洞府标记
  - [x] 1.2: 洞府标记根据状态显示不同颜色（可用/探索中/已探索）
  - [x] 1.3: 在 WorldMapScreen 中遍历显示洞府标记

- [x] Task 2: 修改 WorldMapScreen 接口以接收洞府数据
  - [x] 2.1: 添加 caves 参数到 WorldMapScreen 函数
  - [x] 2.2: 添加 onCaveClick 回调参数

- [x] Task 3: 修改 WorldMapDialog 传入洞府数据
  - [x] 3.1: 从 gameData.cultivatorCaves 获取洞府列表
  - [x] 3.2: 过滤掉已消失(EXPIRED)的洞府
  - [x] 3.3: 传递洞府数据和点击回调到 WorldMapScreen

- [x] Task 4: 添加洞府详情对话框
  - [x] 4.1: 创建 CaveDetailDialog Composable 函数
  - [x] 4.2: 显示洞府名称、境界、剩余时间等信息
  - [x] 4.3: 根据洞府状态显示不同操作按钮

- [x] Task 5: 添加洞府交互状态管理
  - [x] 5.1: 在 WorldMapDialog 中添加选中洞府状态
  - [x] 5.2: 点击洞府时显示详情对话框

# Task Dependencies
- Task 2 依赖于 Task 1
- Task 3 依赖于 Task 2
- Task 5 依赖于 Task 4

# Tasks

- [x] Task 1: 仓库界面改为5列显示
  - [x] SubTask 1.1: 将 WarehouseTab 的 GridCells.Fixed(4) 改为 GridCells.Fixed(5)

- [x] Task 2: 装备槽位大小与道具卡片一致
  - [x] SubTask 2.1: 将 EquipmentSlot 的固定大小 60.dp 改为 aspectRatio(1f)
  - [x] SubTask 2.2: 调整槽位布局以适应新的尺寸

- [x] Task 3: 功法槽位大小与道具卡片一致
  - [x] SubTask 3.1: 将 ManualSlot 的固定大小 60.dp 改为 aspectRatio(1f)
  - [x] SubTask 3.2: 调整槽位布局以适应新的尺寸

- [x] Task 4: 重构 SeedSelectionDialog 使用选择丹药界面的卡片样式
  - [x] SubTask 4.1: 将 LazyColumn 改为 LazyVerticalGrid（4列）
  - [x] SubTask 4.2: 创建 SeedSelectionCard 组件，使用正方形卡片样式（aspectRatio(1f)）
  - [x] SubTask 4.3: 添加稀有度边框颜色
  - [x] SubTask 4.4: 卡片内边距改为 8.dp
  - [x] SubTask 4.5: 添加查看按钮（右上角，offset(x = (-2).dp, y = 2.dp)）
  - [x] SubTask 4.6: 添加种子详情对话框

- [x] Task 5: 统一装备选择界面卡片样式
  - [x] SubTask 5.1: 卡片内边距改为 8.dp
  - [x] SubTask 5.2: 查看按钮样式改为 RoundedCornerShape(4.dp)、字体大小 8.sp、padding(horizontal = 6.dp, vertical = 2.dp)
  - [x] SubTask 5.3: 查看按钮添加 offset(x = (-2).dp, y = 2.dp)

- [x] Task 6: 统一功法选择界面卡片样式
  - [x] SubTask 6.1: 卡片内边距改为 8.dp
  - [x] SubTask 6.2: 查看按钮样式改为 RoundedCornerShape(4.dp)、字体大小 8.sp、padding(horizontal = 6.dp, vertical = 2.dp)
  - [x] SubTask 6.3: 查看按钮添加 offset(x = (-2).dp, y = 2.dp)

- [x] Task 7: 移除所有详情对话框的确认按钮
  - [x] SubTask 7.1: 移除 PillDetailDialog 的确认按钮
  - [x] SubTask 7.2: 移除 EquipmentSelectionDialog 中装备详情对话框的确认按钮
  - [x] SubTask 7.3: 移除 ManualSelectionDialog 中功法详情对话框的确认按钮

- [x] Task 8: 验证功能完整性
  - [x] SubTask 8.1: 测试仓库界面5列显示
  - [x] SubTask 8.2: 测试装备槽位大小与道具卡片一致
  - [x] SubTask 8.3: 测试功法槽位大小与道具卡片一致
  - [x] SubTask 8.4: 测试种子选择对话框的选中功能
  - [x] SubTask 8.5: 测试种子详情查看功能
  - [x] SubTask 8.6: 测试装备选择对话框的卡片样式
  - [x] SubTask 8.7: 测试功法选择对话框的卡片样式
  - [x] SubTask 8.8: 测试所有详情对话框无确认按钮

# Task Dependencies
- Task 1、Task 2、Task 3 可以并行执行
- Task 5 和 Task 6 可以并行执行
- Task 7 可以与 Task 4、Task 5、Task 6 并行执行
- Task 8 依赖所有其他任务

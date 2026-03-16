# Checklist

- [x] 用户在游戏内可以正常读取其他存档
- [x] 读档后游戏循环正常运行
- [x] 读档失败时显示错误提示
- [x] 读档操作不会导致游戏崩溃或数据异常
- [x] stopGameLoop 改为 suspend 函数，避免竞态条件
- [x] loadGameFromSlot 方法同步修改，保持行为一致

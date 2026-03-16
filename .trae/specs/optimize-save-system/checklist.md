# Checklist

- [x] SaveManager 使用文件存储替代 SharedPreferences
- [x] 存档数据使用 GZIP 压缩
- [x] 存档操作为异步执行，不阻塞主线程
- [x] 存档前自动清理过期战斗日志（保留最近 100 条）
- [x] 存档前自动清理过期游戏事件（保留最近 50 条）
- [x] 旧版 SharedPreferences 存档可自动迁移到新格式
- [x] 存档失败时显示错误信息
- [x] 存档损坏时显示恢复提示
- [x] 存档完整性校验正常工作
- [x] GameViewModel 正确调用新的 SaveManager
- [x] 大数据量存档（500+ 弟子）测试通过
- [x] 旧存档迁移测试通过
- [x] 存档压缩率达到 50% 以上

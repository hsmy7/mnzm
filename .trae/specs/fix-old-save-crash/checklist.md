# Checklist

- [x] `EnumFallbackTypeAdapterFactory` 类已创建并实现枚举容错逻辑
- [x] `GsonConfig.createGson()` 已注册枚举容错工厂
- [x] `GsonConfig.createGsonForRoom()` 已注册枚举容错工厂
- [x] `ModelConverters` 中所有枚举转换器已更新为容错逻辑
- [x] `SaveManager.loadFromFile()` 已添加 `IllegalArgumentException` 捕获
- [x] `SaveManager` 错误日志包含详细的异常信息
- [x] `GameEngine.loadData()` 正确处理缺失的 `alchemySlots` 字段
- [x] 项目编译无错误
- [x] 旧存档加载测试通过

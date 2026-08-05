# ============================================================
# TapTap 排行榜 SDK（tap-leaderboard-androidx）混淆规则
# 回调与数据模型走 Gson 反射序列化，须 keep 防止 R8 混淆后
# 回调分派失败或响应解析为空（参照 tap-login 同族处理）。
# ============================================================
-keep class com.taptap.sdk.leaderboard.** { *; }
-dontwarn com.taptap.sdk.leaderboard.**

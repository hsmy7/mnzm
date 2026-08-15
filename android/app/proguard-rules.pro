# TapTap SDK
-keep class com.taptap.** { *; }
-keep interface com.taptap.** { *; }
-dontwarn com.taptap.**

# Dirichlet Ad SDK（com.tapsdk.tapad，与 TapTap 不同包名）
# 以下规则与 AAR 自带 proguard.txt 保持一致
-keepattributes *Annotation*
-keepattributes Signature
-keep class com.tapsdk.tapad.** { *; }
-keep interface com.tapsdk.tapad.** { *; }
-keep enum com.tapsdk.tapad.** { *; }
-keeppackagenames com.tapsdk.tapad.**
-dontwarn com.tapsdk.tapad.**

# 穿山甲广告 SDK（Pangle / CSJ，Maven com.pangle.cn:ads-sdk-pro）
# keep 核心公开包；可选组件（注解类/keva 存储/sdkmonitor 监控/embed_dr OAID）
# 缺失时 SDK 功能降级不崩溃，按官方文档 dontwarn 豁免
-keep class com.bytedance.sdk.openadsdk.** { *; }
-keep class com.pangle.** { *; }
-keep class com.bytedance.embedapplog.** { *; }
-keep class com.bytedance.embed_dr.** { *; }
-dontwarn com.bytedance.sdk.openadsdk.**
-dontwarn com.pangle.**
-dontwarn com.bytedance.embedapplog.**
-dontwarn com.bytedance.embed_dr.**
-dontwarn com.bytedance.component.sdk.annotation.**
-dontwarn com.bytedance.framwork.core.sdkmonitor.**
-dontwarn com.bytedance.keva.**
-dontwarn android.app.Activity$TranslucentConversionListener
-dontwarn android.os.SystemProperties

# 优量汇广告 SDK（GDT / 腾讯广告，Maven com.qq.e.union:union）
-keep class com.qq.e.** { *; }
-dontwarn com.qq.e.**

# 爱奇艺广告 SDK（iQiyi，AAR iadsdk-release-2.3.102.110，经 Dirichlet 聚合接入）
# 以下规则与 AAR 自带 proguard.txt 保持一致
-keep class com.mcto.sspsdk.** { *; }
-dontwarn com.mcto.sspsdk.**
-dontwarn com.mcto.unionsdk.**
-dontwarn com.mcto.cupid.**

# 百度百青藤广告 SDK（Baidu / mobads，Maven com.baidu:mobads:9.45.0）
# 以下规则与 AAR 自带 proguard.txt 保持一致（BD adapter 内 proguard.txt 含 ignorewarnings + 完整 keep；
# SDK 主体以 DEX 形式位于 assets/bdxadsdk.jar 运行时动态加载，必须 keep）
-keep class com.baidu.mobads.** { *; }
-dontwarn com.baidu.mobads.**
-keep class com.style.widget.** { *; }
-keep class com.component.** { *; }
-keep class com.baidu.ad.magic.flute.** { *; }
-keep class com.baidu.mobstat.forbes.** { *; }

# TapTap SDK annotations
-keep class com.taptap.sdk.servicemanager.annotation.** { *; }
-keep class com.taptap.sdk.startup.annotation.** { *; }

# Hilt
# dagger.hilt.**/javax.inject.** 整包保留：Hilt 编译期生成组件/工厂，运行时经反射与注解解析
# 完成注入，官方建议 keep（Hilt 文档）；删除后 Hilt 注入会失败，必须保留
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ComponentSupplier { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
-keepclassmembers class * {
    @javax.inject.Inject <init>(...);
}
-keepclassmembers class * {
    @dagger.hilt.android.lifecycle.HiltViewModel <init>(...);
}

# Kotlin
# kotlin.Metadata 保留：Kotlin 反射（kotlin-reflect）与序列化读取类元数据需要，官方规则
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings {
    <fields>;
}
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}

# kotlinx.serialization - CRITICAL for ManualDatabase JSON fallback and all save/load serialization
# 注：2026-08-15 项 F 收窄——com.xianxia.sect.core.model/data.model 全成员保留已删除，
# 序列化访问由下方 com.xianxia.sect.** 针对性规则（$$serializer/Companion/serializer()/@Serializable 字段）完整覆盖。
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# 整包保留：库自带 consumer rules 理论上可删，但 ManualDatabase JSON fallback 全链路依赖序列化器，
# 删除后未经 R8 验证，保守保留（待最终 assembleRelease 验证）
-keep class kotlinx.serialization.** { *; }
# 全局 Companion 必须保留：kotlinx.serialization 经伴生对象反射获取序列化器实例，
# 需覆盖所有 @Serializable 类（含第三方库类），不能仅限 com.xianxia.sect.**（kotlinx.serialization 官方要求）
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.xianxia.sect.**$$serializer { *; }
-keepclassmembers class com.xianxia.sect.** {
    *** Companion;
}
-keepclasseswithmembers class com.xianxia.sect.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}

# Coroutines
# 整包保留：库自带 consumer rules 理论上可删，但全局保留覆盖协程反射/恢复路径（挂起点类、Dispatchers 内部），
# 删除后未经 R8 验证，保守保留（待最终 assembleRelease 验证）
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keep class kotlinx.coroutines.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers class * {
    @androidx.room.Query <methods>;
    @androidx.room.Insert <methods>;
    @androidx.room.Update <methods>;
    @androidx.room.Delete <methods>;
    @androidx.room.RawQuery <methods>;
    @androidx.room.Transaction <methods>;
}
-keep class * extends androidx.room.Dao
-dontwarn androidx.room.paging.**

# Google Protobuf - generated message classes used by ManualDatabase and save system
-keep class com.google.protobuf.GeneratedMessageLite { *; }
-dontwarn com.google.protobuf.**
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
    <methods>;
}
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }



# MMKV - high-performance key-value storage (uses JNI)
-keep class com.tencent.mmkv.** { *; }
-dontwarn com.tencent.mmkv.**

# Zstd JNI - compression library (uses JNI native methods)
-keep class com.github.luben.zstd.** { *; }
-dontwarn com.github.luben.zstd.**

# LZ4 Java - compression library (uses JNI native methods)
-keep class net.jpountz.** { *; }
-dontwarn net.jpountz.**
-keep class org.lz4.** { *; }
-dontwarn org.lz4.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.OkHttpClient { *; }
-keep class okhttp3.Request$Builder { *; }
-keep interface okhttp3.Interceptor { *; }

# AndroidX Security Crypto
# 整包保留：EncryptedSharedPreferences/EncryptedFile 经反射访问实现类（master key 派生链），
# 官方 consumer rules 已含，删除后未经 R8 验证，保守保留（待最终 assembleRelease 验证）
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# AndroidX — precise rules (replaces broad androidx.** wildcard)
# Compose runtime: only keep runtime-accessed annotations; library's own proguard.txt handles internals
-keep @interface androidx.compose.runtime.Composable
-keep @interface androidx.compose.runtime.Stable
-keep @interface androidx.compose.runtime.Immutable
# lifecycle 整包保留：ViewModel/Flow 反射恢复路径（SavedStateHandle 等），官方 consumer rules 已含，
# 删除后未经 R8 验证，保守保留（待最终 assembleRelease 验证）
-keep class androidx.lifecycle.** { *; }
# room 整包保留：生成 DAO 实现反射调用，官方 consumer rules 已含，删除后未经 R8 验证，
# 保守保留（待最终 assembleRelease 验证）；上方 112-124 行另有 Room 注解级精确规则
-keep class androidx.room.** { *; }
-dontwarn androidx.**

# Compose optimization rules
-dontwarn androidx.compose.**
-keepclassmembers class androidx.compose.runtime.** {
    *** Companion;
}

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep custom views
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Keep Parcelable
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Keep Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Keep enum classes used in game logic
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Remove verbose/debug/info logs in release; keep warn/error for production diagnostics
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Compose
-keepclassmembers class * extends androidx.compose.runtime.Composer
-dontwarn androidx.compose.**

# StateFlow
-keepclassmembers class * extends kotlinx.coroutines.flow.StateFlow {
    ** getValue();
}

# === 华为 HarmonyOS AOT 兼容规则 ===

# Compose runtime stability annotations
-keep @interface androidx.compose.runtime.Stable
-keep @interface androidx.compose.runtime.Immutable
# 注解类全保留：HarmonyOS AOT（方舟编译器）依赖 @Stable/@Immutable 注解做稳定性推断，
# 全保留保证 AOT 编译产物稳定（HarmonyOS AOT 兼容）；删除后需在 HarmonyOS 真机验证，保守保留
-keepclasseswithmembers @androidx.compose.runtime.Stable class * { *; }
-keepclasseswithmembers @androidx.compose.runtime.Immutable class * { *; }

# Hilt generated components
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
-keep class * extends dagger.hilt.android.internal.managers.ActivityComponentManager$ActivityContextWrapper { *; }

# Manual proficiency — accessed via reflection
-keep class com.xianxia.sect.core.model.ManualProficiencyData { *; }

# Room TypeConverters
-keep class * extends androidx.room.TypeConverter { *; }

# Compose runtime internal reflection
-keep class kotlin.coroutines.Continuation { *; }
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ReLinker native methods
-keep class com.getkeepsafe.relinker.** { *; }

# Bugly - 崩溃收集 SDK
# Bugly 整包保留：崩溃上报 SDK 经反射/动态注册收集崩溃信息，官方文档要求 keep，必须保留
-dontwarn com.tencent.bugly.**
-keep public class com.tencent.bugly.**{*;}
# android.support 兼容保留：部分广告 SDK（如 Baidu mobads）运行时引用旧 support 库类，
# 删除后 SDK 初始化可能崩溃，保守保留（待最终 assembleRelease 验证）
-keep class android.support.**{*;}

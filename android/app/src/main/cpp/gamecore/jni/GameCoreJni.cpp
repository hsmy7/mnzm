// ============================================================
// GameCoreJni - Desktop JNI bridge for diff testing (no Android deps)
//
// Purpose: loaded by JVM (JUnit) to cross-language diff-test against the
// real Kotlin engine. Unlike the Android GameCoreBridge.cpp, this does not
// depend on android/log.h and only exposes the minimal diff surface
// (batch 0: DeterministicRng/RngManager; later batches add
// execute/advance/snapshot channels).
//
// Kotlin side (test): core/engine/src/test/.../DiffRngBridge.kt
//
// Build: see scripts/build-desktop-jni.ps1 / CI
//   clang++ -shared -fPIC -std=c++20 -I../.. GameCoreJni.cpp ... -o libgamecorejni.so
// ============================================================
#include <jni.h>

#include <string>

#include "gamecore/game_core.h"
#include "gamecore/rng/pcg_xsh_rr.h"
#include "gamecore/rng/rng_manager.h"

namespace {

using gamecore::rng::DeterministicRng;
using gamecore::rng::RngManager;
using gamecore::rng::RngPartition;

/// Global RNG instance for diff testing (single-threaded tests only;
/// 1:1 with Kotlin DiffRngBridge)
DeterministicRng* g_rng = nullptr;
RngManager* g_rngManager = nullptr;

/// Global GameCore instance for state snapshot diff testing
gamecore::GameCore* g_core = nullptr;
gamecore::FixedClock g_coreClock;
gamecore::ConsoleLogger g_coreLogger;   // 对拍调试期输出异常到 stderr

/// jbyteArray → std::string
std::string jbytesToString(JNIEnv* env, jbyteArray array) {
    if (!array) return {};
    const jsize len = env->GetArrayLength(array);
    if (len <= 0) return {};
    jbyte* bytes = env->GetByteArrayElements(array, nullptr);
    std::string out(reinterpret_cast<const char*>(bytes), static_cast<size_t>(len));
    env->ReleaseByteArrayElements(array, bytes, JNI_ABORT);
    return out;
}

/// std::string → jbyteArray
jbyteArray stringToJbytes(JNIEnv* env, const std::string& s) {
    jbyteArray out = env->NewByteArray(static_cast<jsize>(s.size()));
    if (!out) return nullptr;
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(s.size()),
                            reinterpret_cast<const jbyte*>(s.data()));
    return out;
}

}  // namespace

// ============================================================
// DeterministicRng diff channel
// ============================================================

extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeFromSeed(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong seed) {
    delete g_rng;
    g_rng = new DeterministicRng(DeterministicRng::fromSeed(static_cast<int64_t>(seed)));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeNextInt(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    return g_rng ? g_rng->nextInt() : 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeNextIntBound(
    JNIEnv* /*env*/, jobject /*thiz*/, jint bound) {
    return g_rng ? g_rng->nextInt(static_cast<int32_t>(bound)) : 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeNextLongBound(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong bound) {
    return g_rng ? g_rng->nextLong(static_cast<int64_t>(bound)) : 0L;
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeNextDouble(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    return g_rng ? g_rng->nextDouble() : 0.0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeSnapshot(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    return g_rng ? g_rng->snapshot() : 0L;
}

// ============================================================
// RngManager (partition) diff channel
// ============================================================

extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeManagerInit(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong seed) {
    delete g_rngManager;
    g_rngManager = new RngManager();
    g_rngManager->initSystemSeed(static_cast<int64_t>(seed));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeManagerNextInt(
    JNIEnv* /*env*/, jobject /*thiz*/, jint partitionId, jint bound) {
    if (!g_rngManager) return 0;
    return g_rngManager->getRng(static_cast<RngPartition>(partitionId))
        .nextInt(static_cast<int32_t>(bound));
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeManagerSnapshot(
    JNIEnv* /*env*/, jobject /*thiz*/, jint partitionId) {
    if (!g_rngManager) return 0L;
    return g_rngManager->getRng(static_cast<RngPartition>(partitionId)).snapshot();
}

// ============================================================
// GameCore state snapshot channel (batch 1: JSON full snapshot)
// ============================================================

extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeCoreInit(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    if (!g_core) {
        g_core = new gamecore::GameCore(&g_coreClock, &g_coreLogger);
        gamecore::GameCoreConfig config;
        config.seedInitialized = true;
        config.systemSeed = 42;
        g_core->initialize(config);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeCoreImportState(
    JNIEnv* env, jobject /*thiz*/, jbyteArray stateJson) {
    if (!g_core) return JNI_FALSE;
    return g_core->importStateJson(jbytesToString(env, stateJson)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeCoreExportState(
    JNIEnv* env, jobject /*thiz*/) {
    if (!g_core) return stringToJbytes(env, "{}");
    return stringToJbytes(env, g_core->exportStateJson());
}

// ── 时间推进通道（批次 3：对拍用）──────────────────────────────

extern "C" JNIEXPORT jint JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeCoreAdvancePhases(
    JNIEnv* /*env*/, jobject /*thiz*/, jint phaseCount) {
    if (!g_core) return 0;
    return static_cast<jint>(g_core->advancePhases(static_cast<int>(phaseCount)).phasesAdvanced);
}

// ============================================================
// Cleanup on unload
// ============================================================

extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeDestroy(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    delete g_rng;
    g_rng = nullptr;
    delete g_rngManager;
    g_rngManager = nullptr;
    if (g_core) {
        g_core->shutdown();
        delete g_core;
        g_core = nullptr;
    }
}

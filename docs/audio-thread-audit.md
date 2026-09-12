# 音频线程审计报告（Audio Thread Audit）

> 对标 Godot 音频架构（AudioServer 独立混音线程 + chunk 混音 + `get_time_to_next_mix()` 时延 API）。
> 审计范围：`core/engine/.../audio/AudioEngine.kt`（229 行）+ `AudioPreloader.kt`（48 行）+ 全部调用点。
> 更新日期：2026-08-13。

---

## 一、线程模型事实

### 1.1 调用点全清单（实测 grep）

| 调用 | 位置 | 所在线程 |
|------|------|---------|
| `init()` | MainActivity.kt:296 | 主线程 |
| `init()` | ResourcePreloader.kt:120（coroutineScope 内，非 async） | 调用方协程上下文（ViewModel 主线程） |
| `preloadAll()` / `preloadBGM()` | ResourcePreloader.kt:153 `preloadAudio()` ← `withContext(Dispatchers.Default)` | **后台线程（Dispatchers.Default）** |
| `playBGM()` / `stopBGM()` | MainActivity.kt:301 / 365 / 402（设置开关） | 主线程 |
| `playSound("click")` | MainActivity.kt:344/377、GameActivity.kt:212（CompositionLocal） | 主线程 |
| `pauseBGM()` / `resumeBGM()` | MainActivity.kt:623/633、GameActivity.kt:558/593（onPause/onResume） | 主线程 |
| `onSettingsChanged()` | GameViewModel.kt:988 | 主线程 |
| **`release()`** | **全库无调用** | — |

### 1.2 混音/播放线程

- SoundPool：Android 系统内置 **mixer 线程**（独立于 App 线程），`load()` 异步解码、`play()` 非阻塞。
- MediaPlayer：Android 系统内部播放线程，`start()/pause()` 非阻塞。
- **结论：混音由平台承担**——与 Godot AudioServer 独立混音线程的目标一致，本项目无需自建混音线程（Godot 需自建是因为它是跨平台引擎）。

### 1.3 历史上下文

- 2026-07 音频断续问题曾将游戏线程优先级调整为 URGENT_AUDIO 缓解（OEM 调度挤压背景），详见 memory `audio-thread-priority-research`。
- BGM+SFX 集成于双 Activity（MainActivity/GameActivity）各自维护 onPause/onResume 生命周期，BGM 跨 Activity 不中断（无 release 调用是有意为之）。

## 二、审计发现

| # | 级别 | 发现 | 证据 | 处置 |
|---|------|------|------|------|
| A1 | 中 | **AudioEngine 破坏 :core:engine 零 Android 依赖自我声明**——`core/engine` 直接 `import android.media.SoundPool/MediaPlayer/Context`，iOS 迁移无法复用 | AudioEngine.kt:3-6 | 登记接口抽象待办（`AudioPlayerFacade`，参照 AdService 模式，见 platform-abilities.md G1） |
| A2 | 中 | **`release()` 全库无调用**——引擎音频资源仅靠进程死亡释放；MediaPlayer 长期持有，Activity 重建不回收（现状 BGM 跨 Activity 有意保持，但资源生命周期无统一出口，未来"退出游戏回主菜单"等场景需要） | 全库 grep 无 `audioEngine.release` | 待产品需要退出流程时接线；暂不改（避免引入无需求功能） |
| A3 | 低 | `MediaPlayer.create(context, resId)` 在**主线程同步解码**（playBGM 首次调用）——BGM 文件较大时首次播放可能卡帧 | AudioEngine.kt:164 | 真机验证 bgm_main 大小与耗时；若 >阈值再改异步预创建 |
| A4 | 低 | `preloadSound` 不注册 OnLoadCompleteListener，`play()` 早于异步 load 完成时该次播放静默丢失（SoundPool 内部不排队） | AudioEngine.kt:104-116 | 现状影响极小（点击音效丢失无感）；登记备查 |
| A5 | 低 | `init()` 双入口（MainActivity:296 + ResourcePreloader:120）依赖幂等守卫，Future 变更需保持 | AudioEngine.kt:60 `if (initialized) return` | 已由幂等守卫保护，文档化即可 |

## 三、结论

- **现状线程模型健康**：播放路径全部主线程串行 + 预加载后台线程（SoundPool 线程安全）+ 混音由系统承担，无并发缺陷。
- **无独立混音线程不是缺陷**：Godot 自建混音线程是跨平台引擎的职责，Android 上 SoundPool 已内置等价能力。
- **两处真实待办**：A1（iOS 接口抽象，登记 platform-abilities.md G1）、A2（release 接线，待产品场景）。
- 本审计不触发代码修改（"确认性修复清单"为空），发现项全部登记在案。

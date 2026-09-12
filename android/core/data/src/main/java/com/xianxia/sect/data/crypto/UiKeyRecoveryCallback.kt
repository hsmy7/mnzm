package com.xianxia.sect.data.crypto

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class UiKeyRecoveryCallback(
    private val activityProvider: () -> Activity?
) : KeyRecoveryCallback {

    companion object {
        private const val TAG = "UiKeyRecoveryCallback"
        private const val DIALOG_TIMEOUT_SECONDS = 120L
    }

    override fun onKeyRecoveryRequired(reason: String): KeyRecoveryDecision {
        val activity = activityProvider() ?: run {
            Log.w(TAG, "No activity available for key recovery UI, cancelling")
            return KeyRecoveryDecision.CANCEL
        }

        val decisionRef = AtomicReference<KeyRecoveryDecision>(KeyRecoveryDecision.CANCEL)
        val latch = CountDownLatch(1)

        Handler(Looper.getMainLooper()).post {
            showKeyRecoveryDialog(
                activity = activity,
                reason = reason,
                decisionRef = decisionRef,
                latch = latch
            )
        }

        return try {
            latch.await(DIALOG_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            decisionRef.get()
        } catch (e: InterruptedException) {
            Log.w(TAG, "Key recovery dialog interrupted", e)
            KeyRecoveryDecision.CANCEL
        }
    }

    /**
     * 在主线程展示密钥恢复对话框并写入决策。
     * 调用方通过 [latch] 阻塞等待决策结果；Activity 已销毁或对话框
     * 展示异常时统一回退为 CANCEL 并释放 latch。
     */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    private fun showKeyRecoveryDialog(
        activity: Activity,
        reason: String,
        decisionRef: AtomicReference<KeyRecoveryDecision>,
        latch: CountDownLatch
    ) {
        try {
            if (activity.isFinishing || activity.isDestroyed) {
                Log.w(TAG, "Activity is finishing/destroyed, cancelling key recovery")
                decisionRef.set(KeyRecoveryDecision.CANCEL)
                latch.countDown()
                return
            }

            AlertDialog.Builder(activity)
                .setTitle("存档密钥异常")
                .setMessage("$reason\n\n如果选择生成新密钥，旧存档将永久无法恢复。")
                .setPositiveButton("导入恢复令牌") { _, _ ->
                    decisionRef.set(KeyRecoveryDecision.IMPORT_TOKEN)
                    latch.countDown()
                }
                .setNegativeButton("取消") { _, _ ->
                    decisionRef.set(KeyRecoveryDecision.CANCEL)
                    latch.countDown()
                }
                .setNeutralButton("生成新密钥（旧存档将丢失）") { _, _ ->
                    AlertDialog.Builder(activity)
                        .setTitle("确认生成新密钥？")
                        .setMessage("此操作不可撤销，所有旧存档将永久无法恢复。")
                        .setPositiveButton("确认") { _, _ ->
                            decisionRef.set(KeyRecoveryDecision.GENERATE_NEW_KEY)
                            latch.countDown()
                        }
                        .setNegativeButton("取消") { _, _ ->
                            decisionRef.set(KeyRecoveryDecision.CANCEL)
                            latch.countDown()
                        }
                        .setOnCancelListener {
                            decisionRef.set(KeyRecoveryDecision.CANCEL)
                            latch.countDown()
                        }
                        .show()
                }
                .setOnCancelListener {
                    decisionRef.set(KeyRecoveryDecision.CANCEL)
                    latch.countDown()
                }
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show key recovery dialog", e)
            decisionRef.set(KeyRecoveryDecision.CANCEL)
            latch.countDown()
        }
    }
}

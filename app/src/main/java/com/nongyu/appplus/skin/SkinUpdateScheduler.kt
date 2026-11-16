package com.nongyu.appplus.skin

import android.content.Context
import android.util.Log
import android.view.Choreographer
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 皮肤包下载器的**首帧后触发点**（票 #53 交付 2）。
 *
 * ## 为什么是 postFrameCallback（ADR-0006 硬约束）
 * `Choreographer.postFrameCallback` 在下一帧绘制时回调，天然晚于 `setContent` 的
 * 首次布局 + 绘制 ⇒ **首帧已经上屏**，此后发网络不可能阻塞冷启动。
 * 与 `NetStartupProbe` / `SessionStartupVerifier` 同一形状（本仓既有范式）。
 *
 * ## 为什么还要切 Dispatchers.IO
 * `postFrameCallback` 跑在**主线程**；`SkinStore` 要读指针与写盘、OkHttp 要发请求，
 * 都是阻塞 IO。必须切到 IO 线程，否则首帧后仍会卡 UI。切线程后再把
 * `NetHolder` / Keystore 之类惰性装配的代价从主线程移走（net 包守卫 2）。
 *
 * ## 幂等
 * 同一个进程内只触发一次（`volatile` 标记），避免 Activity 重建时重复下载。
 */
object SkinUpdateScheduler {

    private const val TAG = "NongyuSkinDownload"

    @Volatile
    private var requested = false

    /**
     * 安排一次「首帧后」的皮肤更新。
     *
     * 必须在 `setContent` **之后**调用（与 `NetStartupProbe.schedule` 同位置），
     * 且**不得**在 Composable / 启动路径的同步段里调用。
     */
    fun schedule(owner: LifecycleOwner, context: Context, appVersionCode: Int) {
        if (requested) return
        requested = true

        Choreographer.getInstance().postFrameCallback {
            // 已到首帧之后：切到 IO 线程做网络与磁盘，绝不占主线程。
            owner.lifecycleScope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    runOnce(context.applicationContext, appVersionCode)
                }
                Log.i(TAG, "皮肤更新结束：$outcome")
            }
        }
    }

    /**
     * 真正执行一次（单独成函数便于测试直接调，绕开 Choreographer）。
     *
     * **永不抛异常**：任何失败都只记日志并返回失败结论。
     */
    fun runOnce(context: Context, appVersionCode: Int): SkinPackDownloader.Outcome {
        val app = context.applicationContext
        return try {
            val downloader = SkinPackDownloader(
                store = SkinStore(baseDir = app.filesDir, log = { Log.i(TAG, it) }),
                fetcher = SkinCatalogFetcher(),
                appVersionCode = appVersionCode,
                tempRoot = File(app.cacheDir, "skin-download").apply { mkdirs() },
            )
            downloader.run()
        } catch (t: Throwable) {
            Log.w(TAG, "皮肤更新流程异常，已忽略：${t.message}")
            SkinPackDownloader.Outcome.Failed("调度异常：${t.message}")
        }
    }

    /** 仅供测试：复位幂等标记。 */
    internal fun resetForTest() {
        requested = false
    }
}

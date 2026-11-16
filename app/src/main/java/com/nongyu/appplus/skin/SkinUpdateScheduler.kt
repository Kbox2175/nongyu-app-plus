package com.nongyu.appplus.skin

import android.content.Context
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 皮肤包下载器的**触发点**。
 *
 * ## 现在是手动触发（维护者 2026-11-16 决定）
 *
 * 原设计中它在每次冷启动的**首帧之后**自动拉一次目录（票 #53 交付 2）。
 * 维护者要求「改为手动、不自动」，因此：
 *
 * - **自动触发已移除**：`MainActivity` 不再调用本类，App 冷启动不会发任何皮肤网络请求；
 * - **手动入口保留**：[requestManualUpdate] 供将来的「设置页 / 检查更新按钮」调用，
 *   [runOnce] 供测试与调试直接执行一次。
 *
 * ## 无论自动还是手动，两条硬约束不变（ADR-0006）
 *
 * 1. **绝不阻塞首帧**：只允许在首帧之后调用。手动入口由 UI 事件触发，天然满足；
 *    若将来恢复自动触发，必须重新引入 `Choreographer.postFrameCallback`。
 * 2. **失败静默**：任何失败只记日志，不动当前生效的包。
 *
 * ## 为什么还切 Dispatchers.IO
 * `SkinStore` 要读指针与写盘、OkHttp 要发请求，都是阻塞 IO。
 * [requestManualUpdate] 从主线程发起时会切到 IO 线程执行，避免卡 UI。
 */
object SkinUpdateScheduler {

    private const val TAG = "NongyuSkinDownload"

    /**
     * 手动请求一次皮肤更新（用户显式操作触发）。**不会自动调用**。
     *
     * 由主线程（如按钮点击）调用即可；内部切到 `Dispatchers.IO` 执行，
     * 完成后回主线程只打一条日志。**永不抛异常**。
     *
     * @return 可直接丢弃的 Job 语义由调用方持有；失败不影响当前皮肤。
     */
    fun requestManualUpdate(owner: LifecycleOwner, context: Context, appVersionCode: Int) {
        val app = context.applicationContext
        owner.lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runOnce(app, appVersionCode)
            }
            Log.i(TAG, "手动皮肤更新结束：$outcome")
        }
    }

    /**
     * 真正执行一次（同步阻塞，**必须在非主线程调用**）。
     *
     * 单独成函数便于测试直接调，也便于将来在 WorkManager / 后台任务里复用。
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
}

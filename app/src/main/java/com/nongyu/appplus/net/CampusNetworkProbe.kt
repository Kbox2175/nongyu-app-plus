package com.nongyu.appplus.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import android.view.Choreographer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.nongyu.appplus.net.core.CampusNetworkState
import com.nongyu.appplus.net.model.NetJson
import com.nongyu.appplus.net.model.PresenceReportRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 探测「是否在校内」，并把结果上报给后端（`POST /school/presence`）。
 *
 * ## 判定方式：探测可达性（维护者拍板 B）
 * 对**教务直连域名** `https://jiaowu.sicau.edu.cn/` 发一次轻量 HEAD 请求：
 * - 能拿到任何 HTTP 响应（含 3xx/4xx）→ **校内**（域名可达即说明在校园网内）
 * - 连接超时 / DNS 失败 / 无路由 → **校外**
 * - 其它异常 → **保持 Unknown**（不冒充结论）
 *
 * ### 为什么不用 Wi-Fi SSID 或网络类型
 * - 读 SSID 在 Android 10+ 需要定位权限，用户会反感；
 * - 只看网络类型会把「在家连 Wi-Fi」误判成校内，错得离谱。
 *
 * ## ⚠️ 四条边界（与 [NetStartupProbe] 一致的范式）
 * 1. **首帧之后才探**：`Choreographer.postFrameCallback` 等首帧再切 `Dispatchers.IO`。
 *    满足 `docs/adr/0006`「冷启动只读本地、绝不碰网络」。
 * 2. **任何异常都吞掉**，只记日志 —— 判定失败不能影响 App 可用性。
 * 3. **探测失败不冒充结论**：留 `Unknown`，于是拦截器不挂头、后端用默认值。
 *    这是维护者拍板的兜底口径。
 * 4. **网络切换要重探**：用 `ConnectivityManager.registerDefaultNetworkCallback`，
 *    覆盖「从校外走进校内 / Wi-Fi 切换」这类场景。
 *
 * ## 为什么单独建一个裸 OkHttpClient
 * 探测打的是**学校域名**，不是农屿后端。不能复用车屿的 client：
 * 那会挂 `AppSignInterceptor`（给 `jiaowu.sicau.edu.cn` 带 NY1 签名头）、
 * `AuthInterceptor`（带农屿 Bearer）。与 `login/data/cas/` 同一隔离原则。
 */
object CampusNetworkProbe {

    private const val TAG = "NongyuCampusNet"

    /**
     * 探测目标：教务直连域名。
     *
     * 校内可达、校外不可达。这是判断「能不能直连校园网」最直接的信号，
     * 与后端 `school.Targets[TargetJiaowu]` 的直连形态是同一个域名。
     */
    private const val PROBE_URL = "https://jiaowu.sicau.edu.cn/"

    /** 单次探测超时。校内通常几十毫秒，5 秒足够且不会让用户等。 */
    private const val PROBE_TIMEOUT_SECONDS = 5L

    /** 上报超时。上报失败不影响判定本身。 */
    private const val REPORT_TIMEOUT_MILLIS = 5_000L

    @Volatile
    private var alreadyScheduled = false

    @Volatile
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** 测试用：复位。 */
    internal fun resetForTest() {
        alreadyScheduled = false
        networkCallback = null
    }

    /**
     * 在**首帧之后**探测一次，并注册网络变化监听。
     *
     * @param owner 绑定协程生命周期（传 Activity）
     * @param context 应用上下文
     */
    fun schedule(owner: LifecycleOwner, context: Context) {
        if (alreadyScheduled) return
        alreadyScheduled = true

        Choreographer.getInstance().postFrameCallback {
            val appContext = context.applicationContext
            owner.lifecycleScope.launch {
                owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    probeAndReport(appContext)
                }
            }
            registerNetworkCallback(appContext)
        }
    }

    /**
     * 注册默认网络变化监听，网络切换时重新探测。
     *
     * 用 `registerDefaultNetworkCallback`（API 24+，本工程 minSdk 26 满足）。
     * 注册失败只记日志 —— 不能因为监听听不上就把 App 弄崩。
     */
    private fun registerNetworkCallback(context: Context) {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // 网络切换后重探：覆盖「走进校园 / 关掉 Wi-Fi」等场景。
                    Log.i(TAG, "网络变化，重新探测校内/校外")
                    probeAsync()
                }
            }
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: RuntimeException) {
            // SecurityException / IllegalArgumentException 都只记日志
            Log.w(TAG, "网络监听注册失败（不影响单次探测）", e)
        }
    }

    /** 网络变化时在后台重探一次（不绑定生命周期，尽力而为）。 */
    private fun probeAsync() {
        Thread {
            try {
                val presence = detectPresence()
                applyPresence(presence)
            } catch (e: Throwable) {
                Log.w(TAG, "网络切换后重探失败", e)
            }
        }.start()
    }

    /**
     * 探测 + 更新状态 + 上报。**不抛任何异常。**
     *
     * 单独成函数便于测试直接调（绕开 Choreographer）。
     */
    internal suspend fun probeAndReport(context: Context) {
        try {
            val presence = withContext(Dispatchers.IO) { detectPresence() }
            applyPresence(presence)
            report(context, presence)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "校内/校外探测异常，保持未知", e)
        }
    }

    /** 把探测结果写进全局状态。 */
    private fun applyPresence(presence: CampusNetworkState.Presence) {
        CampusNetworkState.update(presence)
        Log.i(TAG, "校内/校外判定：$presence")
    }

    /**
     * 真正做探测。**不抛异常**，失败返回 [CampusNetworkState.Presence.Unknown]。
     *
     * 判定口径：
     * - 拿到**任何** HTTP 响应 → `Direct`（域名可达 = 在校园网内）
     * - `IOException`（超时 / DNS / 连不上） → `Vpn`（不可达 = 校外）
     * - 其它异常 → `Unknown`（不冒充结论）
     */
    internal fun detectPresence(): CampusNetworkState.Presence {
        val client = OkHttpClient.Builder()
            .connectTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            // 刻意不挂任何拦截器：不打农屿签名头、不带 Bearer
            .build()
        return try {
            // HEAD 而非 GET：只要确认可达，不下载页面
            val request = Request.Builder().url(PROBE_URL).head().build()
            client.newCall(request).execute().use { response ->
                // 任何 HTTP 状态码都算可达（401/403/302 都说明域名打得通）
                Log.i(TAG, "教务域名可达，HTTP ${response.code} → 校内")
                CampusNetworkState.Presence.Direct
            }
        } catch (e: IOException) {
            // 超时 / DNS 失败 / 连接被拒 —— 校外
            Log.i(TAG, "教务域名不可达（${e.javaClass.simpleName}）→ 校外")
            CampusNetworkState.Presence.Vpn
        } catch (e: RuntimeException) {
            Log.w(TAG, "探测出现非 IO 异常，保持未知", e)
            CampusNetworkState.Presence.Unknown
        }
    }

    /**
     * 上报判定结果到 `POST /school/presence`。
     *
     * 只上报 `direct` / `vpn`；[CampusNetworkState.Presence.Unknown] **不上报**
     * （与「不发头」同一口径）。上报失败只记日志。
     */
    private suspend fun report(
        context: Context,
        presence: CampusNetworkState.Presence,
    ) {
        val mode = when (presence) {
            CampusNetworkState.Presence.Direct -> CampusNetworkState.HEADER_VALUE_DIRECT
            CampusNetworkState.Presence.Vpn -> CampusNetworkState.HEADER_VALUE_VPN
            CampusNetworkState.Presence.Unknown -> return
        }
        try {
            val body = NetJson.instance.encodeToString(
                PresenceReportRequest.serializer(),
                PresenceReportRequest(mode = mode),
            ).toRequestBody("application/json".toMediaType())
            val outcome = withTimeoutOrNull(REPORT_TIMEOUT_MILLIS) {
                NetHolder.api(context).reportPresence(body)
            }
            Log.i(TAG, "presence 上报结果：$outcome")
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "presence 上报失败（不影响判定）", e)
        }
    }
}

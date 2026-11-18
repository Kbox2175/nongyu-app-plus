package com.nongyu.appplus.webvpn.logic

import com.nongyu.appplus.net.core.ApiErrorCode
import com.nongyu.appplus.webvpn.model.WebVpnTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 错误码 → 弹窗触发源。核心断言：**只有两个网关票码会触发弹窗**，
 * 尤其 `cache_unavailable`(503) 与 App 会话失效(401 `session_expired`) 不能误弹。
 */
class WebVpnErrorClassifierTest {

    @Test
    fun `没交过票 409 触发 TICKET_MISSING`() {
        assertEquals(
            WebVpnTrigger.TICKET_MISSING,
            WebVpnErrorClassifier.classify(ApiErrorCode.WEBVPN_TICKET_MISSING, 409),
        )
    }

    @Test
    fun `票失效 401 触发 TICKET_EXPIRED`() {
        assertEquals(
            WebVpnTrigger.TICKET_EXPIRED,
            WebVpnErrorClassifier.classify(ApiErrorCode.WEBVPN_TICKET_EXPIRED, 401),
        )
    }

    @Test
    fun `状态码对不上时不误弹`() {
        assertNull(WebVpnErrorClassifier.classify(ApiErrorCode.WEBVPN_TICKET_MISSING, 401))
        assertNull(WebVpnErrorClassifier.classify(ApiErrorCode.WEBVPN_TICKET_EXPIRED, 409))
    }

    @Test
    fun `功能未启用 503 不弹窗_可重试而不是骚扰用户`() {
        assertNull(WebVpnErrorClassifier.classify(ApiErrorCode.CACHE_UNAVAILABLE, 503))
    }

    @Test
    fun `App 会话失效不是网关票问题_不弹窗`() {
        assertNull(WebVpnErrorClassifier.classify(ApiErrorCode.SESSION_EXPIRED, 401))
        assertNull(WebVpnErrorClassifier.classify(ApiErrorCode.UNAUTHORIZED, 401))
    }

    @Test
    fun `未知码与 null 一律不弹`() {
        assertNull(WebVpnErrorClassifier.classify(null, 409))
        assertNull(WebVpnErrorClassifier.classify("some_new_backend_code", 401))
        assertNull(WebVpnErrorClassifier.classify("", 409))
    }

    @Test
    fun `isTicketErrorCode 只认两个码`() {
        assertTrue(WebVpnErrorClassifier.isTicketErrorCode(ApiErrorCode.WEBVPN_TICKET_MISSING))
        assertTrue(WebVpnErrorClassifier.isTicketErrorCode(ApiErrorCode.WEBVPN_TICKET_EXPIRED))
        assertFalse(WebVpnErrorClassifier.isTicketErrorCode(ApiErrorCode.CACHE_UNAVAILABLE))
        assertFalse(WebVpnErrorClassifier.isTicketErrorCode(null))
    }
}

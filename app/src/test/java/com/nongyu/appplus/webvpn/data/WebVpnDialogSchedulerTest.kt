package com.nongyu.appplus.webvpn.data

import com.nongyu.appplus.net.core.ApiErrorCode
import com.nongyu.appplus.webvpn.model.WebVpnConsent
import com.nongyu.appplus.webvpn.model.WebVpnGateDecision
import com.nongyu.appplus.webvpn.model.WebVpnTrigger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 全局调度接缝的行为测试：登录后生效 / 其他调度后生效 / 不同意抑制 / 去重 / nonce。
 */
class WebVpnDialogSchedulerTest {

    @Before
    fun setUp() = WebVpnDialogScheduler.reset()

    @After
    fun tearDown() = WebVpnDialogScheduler.reset()

    @Test
    fun `登录后投递 SIGNED_IN_产生请求且只弹一次`() {
        assertEquals(
            WebVpnGateDecision.SHOW,
            WebVpnDialogScheduler.request(WebVpnTrigger.SIGNED_IN),
        )
        val first = WebVpnDialogScheduler.currentRequest.value
        assertEquals(WebVpnTrigger.SIGNED_IN, first?.trigger)
        assertTrue(WebVpnDialogScheduler.isActive())

        // 已有请求时再投递：去重（不叠层），也不会换掉当前请求
        assertEquals(
            WebVpnGateDecision.SKIP_ALREADY_ACTIVE,
            WebVpnDialogScheduler.request(WebVpnTrigger.MANUAL),
        )
        assertEquals(first, WebVpnDialogScheduler.currentRequest.value)

        WebVpnDialogScheduler.complete()
        assertEquals(
            WebVpnGateDecision.SKIP_ALREADY_SHOWN,
            WebVpnDialogScheduler.request(WebVpnTrigger.SIGNED_IN),
        )
    }

    @Test
    fun `不同意写入偏好并关闭_之后自动调度被抑制_手动入口仍可弹`() {
        WebVpnDialogScheduler.request(WebVpnTrigger.SIGNED_IN)
        WebVpnDialogScheduler.decline()

        assertNull(WebVpnDialogScheduler.currentRequest.value)
        assertEquals(WebVpnConsent.DECLINED, WebVpnConsentStore.read())

        assertEquals(
            WebVpnGateDecision.SKIP_DECLINED,
            WebVpnDialogScheduler.request(WebVpnTrigger.TICKET_EXPIRED),
        )
        assertNull(WebVpnDialogScheduler.currentRequest.value)

        assertEquals(
            WebVpnGateDecision.SHOW,
            WebVpnDialogScheduler.request(WebVpnTrigger.MANUAL),
        )
        assertEquals(WebVpnTrigger.MANUAL, WebVpnDialogScheduler.currentRequest.value?.trigger)
    }

    @Test
    fun `dismiss 不改变同意态_同样触发源可以再弹`() {
        WebVpnDialogScheduler.request(WebVpnTrigger.TICKET_MISSING)
        WebVpnDialogScheduler.dismiss()

        assertNull(WebVpnDialogScheduler.currentRequest.value)
        assertEquals(WebVpnConsent.UNDECIDED, WebVpnConsentStore.read())
        assertEquals(
            WebVpnGateDecision.SHOW,
            WebVpnDialogScheduler.request(WebVpnTrigger.TICKET_MISSING),
        )
    }

    @Test
    fun `同一触发源连续两次调度_nonce 必须不同`() {
        WebVpnDialogScheduler.request(WebVpnTrigger.TICKET_MISSING)
        val a = WebVpnDialogScheduler.currentRequest.value
        WebVpnDialogScheduler.dismiss()
        WebVpnDialogScheduler.request(WebVpnTrigger.TICKET_MISSING)
        val b = WebVpnDialogScheduler.currentRequest.value
        assertNotEquals(a, b)
        assertNotEquals(a?.nonce, b?.nonce)
    }

    @Test
    fun `后端错误码经 Dispatcher 变成请求`() {
        assertEquals(
            WebVpnTrigger.TICKET_MISSING,
            WebVpnErrorDispatcher.dispatch(ApiErrorCode.WEBVPN_TICKET_MISSING, 409),
        )
        assertEquals(
            WebVpnTrigger.TICKET_MISSING,
            WebVpnDialogScheduler.currentRequest.value?.trigger,
        )
    }

    @Test
    fun `无关错误码不产生请求`() {
        assertNull(WebVpnErrorDispatcher.dispatch(ApiErrorCode.CACHE_UNAVAILABLE, 503))
        assertNull(WebVpnDialogScheduler.currentRequest.value)
    }
}

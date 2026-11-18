package com.nongyu.appplus.webvpn.logic

import com.nongyu.appplus.webvpn.model.WebVpnConsent
import com.nongyu.appplus.webvpn.model.WebVpnGateDecision
import com.nongyu.appplus.webvpn.model.WebVpnTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 弹窗闸门判定（`docs/specs/WebVPN登录弹窗.md` §3）。
 *
 * 覆盖「不同意登录」这条需求的**行为语义**：拒绝后自动调度不再骚扰，
 * 但手动入口永远可用；登录后每进程只弹一次；不叠层。
 */
class WebVpnGateTest {

    @Test
    fun `首次登录成功后自动弹出`() {
        val d = WebVpnGate.decide(
            trigger = WebVpnTrigger.SIGNED_IN,
            consent = WebVpnConsent.UNDECIDED,
            signedInShown = false,
            active = false,
        )
        assertEquals(WebVpnGateDecision.SHOW, d)
        assertTrue(WebVpnGate.shouldShow(d))
    }

    @Test
    fun `登录后的自动调度每进程只弹一次`() {
        val d = WebVpnGate.decide(
            trigger = WebVpnTrigger.SIGNED_IN,
            consent = WebVpnConsent.UNDECIDED,
            signedInShown = true,
            active = false,
        )
        assertEquals(WebVpnGateDecision.SKIP_ALREADY_SHOWN, d)
    }

    @Test
    fun `不同意后自动调度全部被抑制`() {
        for (trigger in listOf(
            WebVpnTrigger.SIGNED_IN,
            WebVpnTrigger.TICKET_MISSING,
            WebVpnTrigger.TICKET_EXPIRED,
        )) {
            assertEquals(
                "trigger=$trigger 应被抑制",
                WebVpnGateDecision.SKIP_DECLINED,
                WebVpnGate.decide(trigger, WebVpnConsent.DECLINED, false, false),
            )
        }
    }

    @Test
    fun `不同意后手动入口仍然可弹`() {
        val d = WebVpnGate.decide(
            trigger = WebVpnTrigger.MANUAL,
            consent = WebVpnConsent.DECLINED,
            signedInShown = true,
            active = false,
        )
        assertEquals(WebVpnGateDecision.SHOW, d)
    }

    @Test
    fun `已有弹窗时不叠第二层_即使手动调度`() {
        val d = WebVpnGate.decide(
            trigger = WebVpnTrigger.MANUAL,
            consent = WebVpnConsent.UNDECIDED,
            signedInShown = false,
            active = true,
        )
        assertEquals(WebVpnGateDecision.SKIP_ALREADY_ACTIVE, d)
    }

    @Test
    fun `票缺失与票过期在同意态下正常弹出`() {
        for (trigger in listOf(
            WebVpnTrigger.TICKET_MISSING,
            WebVpnTrigger.TICKET_EXPIRED,
        )) {
            assertEquals(
                WebVpnGateDecision.SHOW,
                WebVpnGate.decide(trigger, WebVpnConsent.AGREED, true, false),
            )
        }
    }

    @Test
    fun `MANUAL 不是自动调度`() {
        assertTrue(WebVpnTrigger.MANUAL.automatic.not())
        assertTrue(WebVpnTrigger.SIGNED_IN.automatic)
        assertTrue(WebVpnTrigger.TICKET_MISSING.automatic)
        assertTrue(WebVpnTrigger.TICKET_EXPIRED.automatic)
    }
}

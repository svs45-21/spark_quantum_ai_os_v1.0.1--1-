package com.spark.companion

import com.spark.companion.brain.ActionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionPolicyTest {
    @Test fun callsAndTextsAlwaysConfirm() {
        assertTrue(ActionPolicy.alwaysConfirm("make_call"))
        assertTrue(ActionPolicy.alwaysConfirm("send_sms"))
        assertFalse(ActionPolicy.alwaysConfirm("set_alarm"))
    }

    @Test fun riskyButtonsNeedConfirm() {
        for (label in listOf("Send", "Pay ₹500", "Delete chat", "Place order", "Allow", "Confirm payment")) {
            assertTrue(label, ActionPolicy.screenActionNeedsConfirm(label, "com.example"))
        }
    }

    @Test fun ordinaryButtonsDoNot() {
        for (label in listOf("Chats", "Search", "Settings", "Mom", "Back", "Open")) {
            assertFalse(label, ActionPolicy.screenActionNeedsConfirm(label, "com.whatsapp"))
        }
    }

    @Test fun moneyAppsAlwaysConfirm() {
        assertTrue(ActionPolicy.screenActionNeedsConfirm("Home", "com.phonepe.app"))
        assertTrue(ActionPolicy.screenActionNeedsConfirm("Home", "net.one97.paytm"))
        assertTrue(ActionPolicy.screenActionNeedsConfirm("Home", "com.sbi.bank.yono"))
        assertTrue(ActionPolicy.screenActionNeedsConfirm("Home", "com.google.android.apps.nbu.paisa.user"))
    }

    @Test fun submittingAlwaysConfirms() {
        assertTrue(ActionPolicy.screenActionNeedsConfirm("Message", "com.whatsapp", submits = true))
    }

    @Test fun yesNo() {
        assertEquals(true, ActionPolicy.parseYesNo("Yes please"))
        assertEquals(true, ActionPolicy.parseYesNo("go ahead"))
        assertEquals(true, ActionPolicy.parseYesNo("haan kar do"))
        assertEquals(false, ActionPolicy.parseYesNo("No"))
        assertEquals(false, ActionPolicy.parseYesNo("don't send it"))
        assertEquals(false, ActionPolicy.parseYesNo("no, yes"))
        assertNull(ActionPolicy.parseYesNo("what was that"))
        assertNull(ActionPolicy.parseYesNo(null))
    }
}

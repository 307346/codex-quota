package com.lcs.codexquota.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * The reset response headers are epoch SECONDS. Parsing them as milliseconds put
 * the schedule in 1970 and made the alarm re-fire in a loop, so this is pinned.
 */
class CodexClientTest {

    @Test
    fun `reset header is epoch seconds`() {
        assertEquals(Instant.ofEpochSecond(1794548610L), CodexClient.parseResetHeader("1794548610"))
    }

    @Test
    fun `reset header decodes to the expected wall clock`() {
        val instant = CodexClient.parseResetHeader("1794548610")!!
        val local = instant.atZone(ZoneId.of("Asia/Shanghai"))
        assertEquals(2026, local.year)
        assertEquals(11, local.monthValue)
        assertEquals(13, local.dayOfMonth)
        assertEquals("13:43", "%02d:%02d".format(local.hour, local.minute))
    }

    @Test
    fun `unusable headers yield null`() {
        assertNull(CodexClient.parseResetHeader(null))
        assertNull(CodexClient.parseResetHeader(""))
        assertNull(CodexClient.parseResetHeader("not-a-number"))
        assertNull(CodexClient.parseResetHeader("0"))
        assertNull(CodexClient.parseResetHeader("-5"))
    }
}

package com.lcs.codexquota.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Guards against the class of bug that shipped in the first build: reading the
 * server payload with the cache field names (reset/used/win) instead of the real
 * ones (reset_at/used_percent/limit_window_seconds).
 */
class UsageParserTest {

    // Trimmed from a real Plus account response.
    private val payload = """
        {
          "user_id": "user-REDACTED",
          "email": "user@example.com",
          "plan_type": "plus",
          "rate_limit": {
            "allowed": true,
            "limit_reached": false,
            "primary_window": {
              "used_percent": 0,
              "limit_window_seconds": 18000,
              "reset_after_seconds": 16410,
              "reset_at": 1794548610
            },
            "secondary_window": {
              "used_percent": 56,
              "limit_window_seconds": 604800,
              "reset_after_seconds": 295209,
              "reset_at": 1794827409
            }
          },
          "credits": {
            "has_credits": false,
            "unlimited": false,
            "balance": "0"
          },
          "rate_limit_reset_credits": { "available_count": 3 }
        }
    """.trimIndent()

    @Test
    fun `parses server payload fields`() {
        val snap = UsageParser.parse(payload)

        assertEquals("plus", snap.planType)
        assertEquals("user@example.com", snap.email)
        assertEquals(0.0, snap.primary.usedPercent, 0.001)
        assertEquals(18000, snap.primary.windowSeconds)
        assertEquals(Instant.ofEpochSecond(1794548610L), snap.primary.resetAt)
        assertEquals(3, snap.resetCredits)
        assertTrue(!snap.limitReached)
    }

    @Test
    fun `parses weekly window`() {
        val snap = UsageParser.parse(payload)
        val weekly = snap.secondary
        assertNotNull(weekly)
        assertEquals(56.0, weekly!!.usedPercent, 0.001)
        assertEquals(604800, weekly.windowSeconds)
        assertEquals(Instant.ofEpochSecond(1794827409L), weekly.resetAt)
    }

    @Test
    fun `cache round trip keeps reset instant`() {
        val snap = UsageParser.parse(payload)
        val restored = UsageSnapshot.fromJson(snap.toJson())
        assertNotNull(restored)
        assertEquals(snap.primary.resetAt, restored!!.primary.resetAt)
        assertEquals(snap.secondary?.resetAt, restored.secondary?.resetAt)
        assertEquals(snap.primary.usedPercent, restored.primary.usedPercent, 0.001)
        assertEquals(snap.secondary!!.usedPercent, restored.secondary!!.usedPercent, 0.001)
    }

    @Test
    fun `missing secondary window is null`() {
        val minimal = """
            {"plan_type":"free","rate_limit":{"primary_window":{
              "used_percent":5,"limit_window_seconds":18000,"reset_at":1794548610}}}
        """.trimIndent()
        val snap = UsageParser.parse(minimal)
        assertNull(snap.secondary)
    }
}

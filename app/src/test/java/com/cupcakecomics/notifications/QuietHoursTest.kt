package com.cupcakecomics.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietHoursTest {
    @Test
    fun quietHours_sameDayWindow() {
        assertTrue(CupcakeNotifications.isInQuietHours(true, 9, 17, 10))
        assertFalse(CupcakeNotifications.isInQuietHours(true, 9, 17, 8))
        assertFalse(CupcakeNotifications.isInQuietHours(true, 9, 17, 17))
    }

    @Test
    fun quietHours_crossesMidnight() {
        assertTrue(CupcakeNotifications.isInQuietHours(true, 22, 8, 23))
        assertTrue(CupcakeNotifications.isInQuietHours(true, 22, 8, 2))
        assertFalse(CupcakeNotifications.isInQuietHours(true, 22, 8, 12))
        assertFalse(CupcakeNotifications.isInQuietHours(true, 22, 8, 8))
    }

    @Test
    fun quietHours_disabled() {
        assertFalse(CupcakeNotifications.isInQuietHours(false, 22, 8, 23))
        assertFalse(CupcakeNotifications.isInQuietHours(true, 10, 10, 10))
    }
}

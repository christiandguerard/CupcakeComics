package com.cupcakecomics.kapowarr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KapowarrTest {

    @Test
    fun urlNormalization_lanHost_defaultsToHttp5656() {
        assertEquals("http://192.168.1.50:5656", KapowarrUrls.normalize("192.168.1.50"))
        assertEquals("http://10.0.0.5:5656", KapowarrUrls.normalize("10.0.0.5"))
        assertEquals("http://localhost:5656", KapowarrUrls.normalize("localhost"))
    }

    @Test
    fun urlNormalization_publicDomain_defaultsToHttps() {
        assertEquals("https://kapowarr.example.com", KapowarrUrls.normalize("kapowarr.example.com"))
    }

    @Test
    fun urlNormalization_explicitPort_preservesPort() {
        assertEquals("http://192.168.1.50:8080", KapowarrUrls.normalize("192.168.1.50:8080"))
        assertEquals("https://kapowarr.example.com:8443", KapowarrUrls.normalize("https://kapowarr.example.com:8443"))
    }

    @Test
    fun isPrivateHost_detectsPrivateRanges() {
        assertTrue(KapowarrUrls.isPrivateHost("192.168.1.1"))
        assertTrue(KapowarrUrls.isPrivateHost("10.1.2.3"))
        assertTrue(KapowarrUrls.isPrivateHost("172.16.0.1"))
        assertTrue(KapowarrUrls.isPrivateHost("127.0.0.1"))
        assertFalse(KapowarrUrls.isPrivateHost("8.8.8.8"))
        assertFalse(KapowarrUrls.isPrivateHost("example.com"))
    }

    @Test
    fun resolveMediaUrl_handlesRelativeAndAbsolute() {
        assertEquals(
            "http://192.168.1.50:5656/cover.jpg",
            KapowarrUrls.resolveMediaUrl("http://192.168.1.50:5656", "/cover.jpg"),
        )
        assertEquals(
            "https://cdn.example.com/cover.jpg",
            KapowarrUrls.resolveMediaUrl("http://192.168.1.50:5656", "https://cdn.example.com/cover.jpg"),
        )
        assertNull(KapowarrUrls.resolveMediaUrl("http://192.168.1.50:5656", ""))
    }
}

package com.remaply.navigation.ui

import com.remaply.navigation.ui.internal.MapRequestHeaders
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MapAuthenticationTest {
    @After
    fun tearDown() = MapRequestHeaders.clear()

    @Test
    fun `headers are added only for the given hosts`() {
        MapRequestHeaders.add(MapAuthentication(setOf("API.example.com"), mapOf("X-Api-Key" to "k")))

        val tile = MapRequestHeaders.withHeaders(Request.Builder().url("https://api.example.com/v1/tiles/remaply/1/2/3.mvt").build())
        assertEquals("k", tile.header("X-Api-Key"))

        val other = MapRequestHeaders.withHeaders(Request.Builder().url("https://tiles.openfreemap.org/planet").build())
        assertNull(other.header("X-Api-Key"))

        val lookalike = MapRequestHeaders.withHeaders(Request.Builder().url("https://api.example.com.evil.test/x").build())
        assertNull(lookalike.header("X-Api-Key"))
    }

    @Test
    fun `equal authentications are equal, and toString hides the headers`() {
        val a = MapAuthentication(setOf("a.test"), mapOf("X-Api-Key" to "secret"))
        assertEquals(a, MapAuthentication(setOf("A.TEST"), mapOf("X-Api-Key" to "secret")))
        assertEquals(false, a.toString().contains("secret"))
    }
}

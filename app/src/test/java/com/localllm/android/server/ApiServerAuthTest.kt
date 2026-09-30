package com.localllm.android.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pinned after the adversarial review: stats must only count authenticated
 * traffic, comparison must be constant-time, and empty keys must never match.
 */
class ApiServerAuthTest {

    private val key = "sk-local-testkey123"

    private fun headers(vararg pairs: Pair<String, String>) = mapOf(*pairs)

    @Test
    fun `bearer header authorizes`() {
        assertTrue(OllamaApiServer.checkAuthorization(headers("authorization" to "Bearer $key"), key))
    }

    @Test
    fun `lowercase bearer prefix authorizes`() {
        assertTrue(OllamaApiServer.checkAuthorization(headers("authorization" to "bearer $key"), key))
    }

    @Test
    fun `x-api-key authorizes`() {
        assertTrue(OllamaApiServer.checkAuthorization(headers("x-api-key" to key), key))
    }

    @Test
    fun `wrong key is rejected`() {
        assertFalse(OllamaApiServer.checkAuthorization(headers("authorization" to "Bearer wrong"), key))
        assertFalse(OllamaApiServer.checkAuthorization(headers("x-api-key" to "wrong"), key))
    }

    @Test
    fun `missing headers are rejected`() {
        assertFalse(OllamaApiServer.checkAuthorization(emptyMap(), key))
    }

    @Test
    fun `empty expected key never matches`() {
        assertFalse(OllamaApiServer.checkAuthorization(headers("authorization" to "Bearer $key"), ""))
        assertFalse(OllamaApiServer.checkAuthorization(headers("authorization" to "Bearer "), ""))
    }

    @Test
    fun `empty presented token never matches`() {
        assertFalse(OllamaApiServer.checkAuthorization(headers("authorization" to "Bearer "), key))
        assertFalse(OllamaApiServer.constantTimeEquals("", key))
        assertFalse(OllamaApiServer.constantTimeEquals(key, ""))
    }

    @Test
    fun `constant time equals matches identical strings`() {
        assertTrue(OllamaApiServer.constantTimeEquals(key, key))
        assertFalse(OllamaApiServer.constantTimeEquals(key, "$key!"))
    }
}

package com.mmbesar.kancard.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAddressTest {

    @Test
    fun plainHostGetsHttpsAndEndpoint() {
        val parsed = ServerAddress.parse("kanboard.lan")!!
        assertEquals("https://kanboard.lan/jsonrpc.php", parsed.endpoint)
        assertFalse(parsed.isCleartext)
    }

    @Test
    fun httpIsAccepted() {
        val parsed = ServerAddress.parse("http://10.0.0.5:8080")!!
        assertEquals("http://10.0.0.5:8080/jsonrpc.php", parsed.endpoint)
        assertTrue(parsed.isCleartext)
        assertEquals("10.0.0.5", parsed.host)
        assertEquals(8080, parsed.port)
    }

    @Test
    fun subfolderIsKept() {
        val parsed = ServerAddress.parse("https://example.com/kanboard/")!!
        assertEquals("https://example.com/kanboard/jsonrpc.php", parsed.endpoint)
    }

    @Test
    fun fullEndpointIsNotDoubled() {
        val parsed = ServerAddress.parse("https://example.com/jsonrpc.php")!!
        assertEquals("https://example.com/jsonrpc.php", parsed.endpoint)
    }

    @Test
    fun credentialsQueryAndFragmentAreDropped() {
        val parsed = ServerAddress.parse("https://user:secret@example.com/?a=b#frag")!!
        assertEquals("https://example.com/jsonrpc.php", parsed.endpoint)
    }

    @Test
    fun defaultPortsAreFilledIn() {
        assertEquals(443, ServerAddress.parse("https://example.com")!!.port)
        assertEquals(80, ServerAddress.parse("http://example.com")!!.port)
    }

    @Test
    fun otherSchemesAndGarbageAreRejected() {
        assertNull(ServerAddress.parse("ftp://example.com"))
        assertNull(ServerAddress.parse(""))
        assertNull(ServerAddress.parse("   "))
    }
}

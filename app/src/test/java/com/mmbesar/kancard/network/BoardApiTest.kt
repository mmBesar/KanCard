package com.mmbesar.kancard.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

/**
 * Proves the main promise of KanCard against real local servers:
 * plain http, self-signed https (pinned), and refusal of everything else.
 * Uses only local fixtures - never a real Kanboard server.
 */
class BoardApiTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val projectsReply =
        """{"jsonrpc":"2.0","id":1,"result":[{"id":"1","name":"Home","is_active":"1"}]}"""

    /** A self-signed certificate for 127.0.0.1, and a server that uses it. */
    private fun startSelfSignedServer(): HeldCertificate {
        val held = HeldCertificate.Builder()
            .commonName("kanboard.lan")
            .addSubjectAlternativeName(InetAddress.getByName("localhost").canonicalHostName)
            .build()
        val certs = HandshakeCertificates.Builder().heldCertificate(held).build()
        server.useHttps(certs.sslSocketFactory(), false)
        server.enqueue(MockResponse().setBody(projectsReply))
        server.start()
        return held
    }

    private fun fingerprintOf(held: HeldCertificate) = CertFingerprint.sha256(held.certificate)

    @Test
    fun plainHttpWorksWhenAllowed() = runBlocking {
        server.enqueue(MockResponse().setBody(projectsReply))
        server.start()
        val api = BoardApi(
            HttpClients.create(pinnedSha256 = null, allowCleartext = true),
            server.url("/jsonrpc.php").toString(),
            "alice",
            "secret",
        )
        val projects = api.getMyProjects()
        assertEquals("Home", projects.single().name)

        // The login is sent as HTTP Basic auth: alice:secret
        val request = server.takeRequest()
        assertEquals("Basic YWxpY2U6c2VjcmV0", request.getHeader("Authorization"))
        assertTrue(request.body.readUtf8().contains("getMyProjects"))
    }

    @Test
    fun plainHttpIsRefusedWhenNotAllowed() {
        server.enqueue(MockResponse().setBody(projectsReply))
        server.start()
        val api = BoardApi(
            HttpClients.create(pinnedSha256 = null, allowCleartext = false),
            server.url("/jsonrpc.php").toString(),
            "alice",
            "secret",
        )
        // A client that was not set up for http:// must never fall back to it.
        val e = assertThrows(ApiException::class.java) { runBlocking { api.getMyProjects() } }
        assertEquals(ApiException.Kind.NETWORK, e.kind)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun selfSignedIsRejectedWithoutThePin() {
        startSelfSignedServer()
        val api = BoardApi(
            HttpClients.create(pinnedSha256 = null, allowCleartext = false),
            server.url("/jsonrpc.php").toString(),
            "alice",
            "secret",
        )
        val e = assertThrows(ApiException::class.java) { runBlocking { api.getMyProjects() } }
        assertEquals(ApiException.Kind.CERTIFICATE, e.kind)
    }

    @Test
    fun selfSignedWorksWithTheRightPin() = runBlocking {
        val held = startSelfSignedServer()
        val api = BoardApi(
            HttpClients.create(pinnedSha256 = fingerprintOf(held), allowCleartext = false),
            server.url("/jsonrpc.php").toString(),
            "alice",
            "secret",
        )
        assertEquals("Home", api.getMyProjects().single().name)
    }

    @Test
    fun aDifferentCertificateIsRejectedEvenIfPinned() {
        startSelfSignedServer()
        // The pin belongs to some OTHER certificate, e.g. an impostor took over the address.
        val other = HeldCertificate.Builder().commonName("other").build()
        val api = BoardApi(
            HttpClients.create(pinnedSha256 = fingerprintOf(other), allowCleartext = false),
            server.url("/jsonrpc.php").toString(),
            "alice",
            "secret",
        )
        val e = assertThrows(ApiException::class.java) { runBlocking { api.getMyProjects() } }
        assertEquals(ApiException.Kind.CERTIFICATE, e.kind)
    }

    @Test
    fun pinAcceptsAnyFingerprintFormatting() {
        val held = HeldCertificate.Builder().commonName("x").build()
        val fp = fingerprintOf(held)
        val pin = PinnedTrustManager(fp.lowercase().replace(":", " "))
        pin.checkServerTrusted(arrayOf(held.certificate), "RSA") // must not throw
    }

    @Test
    fun certFetcherReadsTheServerCertificate() = runBlocking {
        val held = startSelfSignedServer()
        val info = CertFetcher.fetch("localhost", server.port)
        assertEquals(fingerprintOf(held), info.sha256)
        assertTrue(info.selfSigned)
    }

    @Test
    fun wrongTokenGivesUnauthorized() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"no"}"""))
        server.start()
        val api = BoardApi(
            HttpClients.create(null, allowCleartext = true),
            server.url("/jsonrpc.php").toString(),
            "alice",
            "bad",
        )
        val e = assertThrows(ApiException::class.java) { runBlocking { api.getMe() } }
        assertEquals(ApiException.Kind.UNAUTHORIZED, e.kind)
    }

    @Test
    fun falseResultMeansRejectedForActions() {
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":false}"""))
        server.start()
        val api = BoardApi(
            HttpClients.create(null, allowCleartext = true),
            server.url("/jsonrpc.php").toString(),
            "alice",
            "secret",
        )
        val e = assertThrows(ApiException::class.java) { runBlocking { api.closeTask(5) } }
        assertEquals(ApiException.Kind.REJECTED, e.kind)
    }

    @Test
    fun falseResultMeansEmptyForLists() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":false}"""))
        server.start()
        val api = BoardApi(
            HttpClients.create(null, allowCleartext = true),
            server.url("/jsonrpc.php").toString(),
            "alice",
            "secret",
        )
        assertTrue(api.getAllTasks(1, open = true).isEmpty())
    }

    @Test
    fun htmlAnswerMeansBadResponse() {
        server.enqueue(MockResponse().setBody("<html>Welcome to nginx</html>"))
        server.start()
        val api = BoardApi(
            HttpClients.create(null, allowCleartext = true),
            server.url("/jsonrpc.php").toString(),
            "alice",
            "secret",
        )
        val e = assertThrows(ApiException::class.java) { runBlocking { api.getMe() } }
        assertEquals(ApiException.Kind.BAD_RESPONSE, e.kind)
    }

    @Test
    fun applicationLoginUsesTheApplicationWideCalls() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":"1.2.40"}"""))
        server.enqueue(MockResponse().setBody(projectsReply))
        server.start()
        val api = BoardApi(
            HttpClients.create(null, allowCleartext = true),
            server.url("/jsonrpc.php").toString(),
            "jsonrpc",
            "app-token",
        )
        api.getMe()
        assertEquals(1, api.getMyProjects().size)
        // Application credentials have no user: no request is needed to know that.
        assertEquals(0L, api.myUserId())
        val first = server.takeRequest().body.readUtf8()
        val second = server.takeRequest().body.readUtf8()
        assertTrue(first.contains("getVersion"))
        assertTrue(second.contains("getAllProjects"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun subtasksAndCommentsAcceptListOrMapAnswers() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":[{"id":"1","title":"A","status":"0"}]}"""))
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":{"7":{"id":"7","comment":"hi","username":"mo","date_creation":"5"}}}"""))
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":false}"""))
        server.start()
        val api = BoardApi(
            HttpClients.create(null, allowCleartext = true),
            server.url("/jsonrpc.php").toString(),
            "alice",
            "secret",
        )
        assertEquals("A", api.getSubtasks(3).single().title)
        assertEquals("hi", api.getComments(3).single().comment)
        assertTrue(api.getComments(4).isEmpty())
    }
}

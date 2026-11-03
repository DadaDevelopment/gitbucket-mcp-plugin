package com.newsrx.mcpplugin.oauth

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpsConfigurator, HttpsServer}
import java.io.IOException
import java.net.{InetAddress, InetSocketAddress, ServerSocket, Socket, URL}
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.{HttpsURLConnection, KeyManagerFactory, SSLContext, SSLSocketFactory, TrustManagerFactory}
import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
 * The client metadata fetch, over a real connection.
 *
 * `ClientMetadataSpec` establishes the policy: which addresses are forbidden,
 * which client ids are malformed, what a redirect does. It cannot establish
 * where the request actually went, because it never makes one.
 *
 * That gap was not academic. The fence used to check an address and then hand
 * a *host name* to the URL connection, which resolved it again independently;
 * a name answering with a public address for the check and a loopback address
 * for the connection was fetched from loopback with the loopback escape off.
 * The second check that was supposed to catch this read the URL's host rather
 * than the peer, and ran after the response had arrived — after the request
 * had been sent, which is the entire injury.
 *
 * So these cases run the production `fetch` against sockets, and the witness
 * is what the listeners saw rather than what the fetch returned.
 *
 * The TLS material is a throwaway self-signed key pair in `src/test/resources`,
 * trusted only by the socket factory this suite installs. Nothing outside this
 * suite trusts it and it is never packaged with the plugin.
 */
@RunWith(classOf[JUnitRunner])
class PinnedFetchSpec extends AnyFunSuite with Matchers with BeforeAndAfterAll {

  private val mapper = new ObjectMapper()
  private val Host = "client.example.test"
  private val loopback = InetAddress.getByName("127.0.0.1")
  // Documentation range, no listener anywhere: if a request is attempted here
  // it fails, and if one is attempted elsewhere a recorder sees it.
  private val elsewhere = InetAddress.getByName("203.0.113.10")

  private var server: HttpsServer = _
  private var port: Int = 0
  private val served = new AtomicInteger(0)
  private var previousFactory: SSLSocketFactory = _

  private def keystore(): KeyStore = {
    val ks = KeyStore.getInstance("PKCS12")
    val in = getClass.getResourceAsStream("/cimd-test-server.p12")
    require(in != null, "the test keystore is missing from the test classpath")
    try ks.load(in, "changeit".toCharArray) finally in.close()
    ks
  }

  private def serverContext(): SSLContext = {
    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    kmf.init(keystore(), "changeit".toCharArray)
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(kmf.getKeyManagers, null, null)
    ctx
  }

  private def trustingContext(): SSLContext = {
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    tmf.init(keystore())
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(null, tmf.getTrustManagers, null)
    ctx
  }

  private def body(clientId: String) =
    s"""{"client_id":"$clientId","client_name":"Pinned","redirect_uris":["https://app.example.com/cb"]}"""

  override def beforeAll(): Unit = {
    // A proxy would send the request wherever it liked, so destination control
    // could not be observed at all. Production refuses proxies for the same
    // reason; here it also keeps the sandbox's proxy out of the measurement.
    System.clearProperty("https.proxyHost")
    System.clearProperty("http.proxyHost")

    server = HttpsServer.create(new InetSocketAddress(loopback, 0), 0)
    server.setHttpsConfigurator(new HttpsConfigurator(serverContext()))
    server.createContext("/client.json", new HttpHandler {
      def handle(ex: HttpExchange): Unit = {
        served.incrementAndGet()
        val bytes = body(clientId).getBytes("UTF-8")
        ex.getResponseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(200, bytes.length)
        ex.getResponseBody.write(bytes)
        ex.close()
      }
    })
    server.createContext("/redirect.json", new HttpHandler {
      def handle(ex: HttpExchange): Unit = {
        served.incrementAndGet()
        ex.getResponseHeaders.add("Location", "https://somewhere.example.com/other.json")
        ex.sendResponseHeaders(302, -1)
        ex.close()
      }
    })
    server.createContext("/endless.json", new HttpHandler {
      def handle(ex: HttpExchange): Unit = {
        served.incrementAndGet()
        val pad = "x" * (300 * 1024)
        val bytes = s"""{"client_id":"$clientId","redirect_uris":[],"pad":"$pad"}""".getBytes("UTF-8")
        ex.getResponseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(200, bytes.length)
        ex.getResponseBody.write(bytes)
        ex.close()
      }
    })
    server.start()
    port = server.getAddress.getPort

    previousFactory = HttpsURLConnection.getDefaultSSLSocketFactory
    HttpsURLConnection.setDefaultSSLSocketFactory(trustingContext().getSocketFactory)
  }

  override def afterAll(): Unit = {
    if (server != null) server.stop(0)
    if (previousFactory != null) HttpsURLConnection.setDefaultSSLSocketFactory(previousFactory)
  }

  private def clientId = s"https://$Host:$port/client.json"
  private def resolvesTo(addr: InetAddress): ClientMetadata.Resolver = _ => Array(addr)

  // ── the lawful case, end to end over TLS ──────────────────────────────────

  test("a permitted destination is fetched, with TLS verified against the host name") {
    // The socket goes to an address; the certificate is still checked against
    // the name. If pinning had replaced the name with the address, this would
    // fail certificate verification instead of succeeding.
    served.set(0)
    val result = ClientMetadata.fetch(mapper, clientId,
      resolve = resolvesTo(loopback), allowLoopback = true)
    result.map(_.clientName) shouldBe Right("Pinned")
    withClue("the server should have been asked exactly once: ")(served.get() shouldBe 1)
  }

  test("the fetch presents the host name to TLS, not the pinned address") {
    // Proved by elimination: the fixture's certificate is issued for the name,
    // and the default factory installed here trusts only that certificate.
    // A handshake carrying the literal address would not match it.
    val cert = keystore().getCertificate("cimd").asInstanceOf[java.security.cert.X509Certificate]
    cert.getSubjectX500Principal.getName should include(Host)
    ClientMetadata.fetch(mapper, clientId,
      resolve = resolvesTo(loopback), allowLoopback = true).isRight shouldBe true
  }

  // ── the divergence that used to get through ───────────────────────────────

  test("a request goes to the checked address, not to one DNS supplies later") {
    // The policy resolver answers with a public address. The server is on
    // loopback. Before the connection was pinned, the fetch succeeded and the
    // loopback server served the document with the escape switched off.
    served.set(0)
    val result = ClientMetadata.fetch(mapper, clientId,
      resolve = resolvesTo(elsewhere), allowLoopback = false)
    result.isLeft shouldBe true
    withClue("the loopback server must not have been contacted: ")(served.get() shouldBe 0)
  }

  test("the forbidden listener receives nothing even when it is the real host") {
    // The same shape, with a recorder that accepts raw TCP, so the witness is
    // a connection rather than a completed HTTP exchange: a pin that leaked
    // would show up here even if TLS then failed.
    val recorder = new ServerSocket(0, 50, loopback)
    val accepted = new AtomicInteger(0)
    val t = new Thread(() => {
      try while (!recorder.isClosed) {
        val s: Socket = recorder.accept()
        accepted.incrementAndGet()
        s.close()
      } catch { case _: Exception => () }
    })
    t.setDaemon(true)
    t.start()
    try {
      val target = s"https://$Host:${recorder.getLocalPort}/client.json"
      val result = ClientMetadata.fetch(mapper, target,
        resolve = resolvesTo(elsewhere), allowLoopback = false)
      result.isLeft shouldBe true
      Thread.sleep(200)
      withClue("no TCP connection may reach the loopback recorder: ")(accepted.get() shouldBe 0)
    } finally recorder.close()
  }

  test("a loopback destination is still refused when the escape is off") {
    served.set(0)
    val result = ClientMetadata.fetch(mapper, clientId,
      resolve = resolvesTo(loopback), allowLoopback = false)
    result shouldBe Left(ClientMetadataError.Rejected(
      s"$Host resolves to a non-public address"))
    withClue("refused before any request: ")(served.get() shouldBe 0)
  }

  // ── the pin refuses to be redirected by the JDK ───────────────────────────

  test("plain http is refused unless the address is loopback") {
    // The escape exists for a developer's own machine. Carrying it to any
    // other address would be an unauthenticated fetch to an arbitrary host.
    val thrown = the[IOException] thrownBy PinnedConnection.fetch(
      new URL(s"http://$Host:$port/client.json"), elsewhere, 2000, 2000, 1024)
    thrown.getMessage should include("loopback")
  }

  test("a scheme other than http or https is refused") {
    an[IOException] should be thrownBy PinnedConnection.fetch(
      new URL(s"ftp://$Host:$port/client.json"), loopback, 2000, 2000, 1024)
  }

  test("the transport sends no request when the connection cannot be made") {
    // 203.0.113.0/24 is documentation space with nothing listening; the point
    // is that the attempt goes there and not to the server on loopback.
    served.set(0)
    an[Exception] should be thrownBy PinnedConnection.fetch(
      new URL(s"https://$Host:$port/client.json"), elsewhere, 1500, 1500, 1024)
    served.get() shouldBe 0
  }

  // ── the other guarantees still hold on the real path ──────────────────────

  test("a redirect is refused over a real connection, not followed") {
    served.set(0)
    val result = ClientMetadata.fetch(mapper, s"https://$Host:$port/redirect.json",
      resolve = resolvesTo(loopback), allowLoopback = true)
    result shouldBe Left(ClientMetadataError.Rejected("client metadata must not redirect"))
    withClue("the redirecting document was fetched exactly once: ")(served.get() shouldBe 1)
  }

  test("an oversized document is capped over a real connection") {
    val result = ClientMetadata.fetch(mapper, s"https://$Host:$port/endless.json",
      resolve = resolvesTo(loopback), allowLoopback = true)
    // Truncated at the cap, so it no longer parses.
    result.isLeft shouldBe true
  }

  test("a destination with nothing listening is unreachable, not a client") {
    val result = ClientMetadata.fetch(mapper, s"https://$Host:$port/missing.json",
      resolve = resolvesTo(loopback), allowLoopback = true)
    result.isLeft shouldBe true
  }

  // ── the address classification the divergence also exposed ────────────────

  test("IPv6 unique-local addresses are forbidden targets") {
    // fc00::/7. `isSiteLocalAddress` covers only the deprecated fec0::/10, so
    // every one of these used to read as an ordinary public address.
    Seq("fd00::1", "fc00::1", "fd12:3456:789a:1::1", "fdff:ffff::1").foreach { ip =>
      withClue(s"$ip: ")(
        ClientMetadata.isForbiddenAddress(InetAddress.getByName(ip)) shouldBe true)
    }
  }

  test("an IPv4 address wrapped in IPv6 notation is still that address") {
    Seq("::ffff:127.0.0.1", "::ffff:10.0.0.1", "::ffff:169.254.169.254", "::ffff:100.64.0.1")
      .foreach { ip =>
        withClue(s"$ip: ")(
          ClientMetadata.isForbiddenAddress(InetAddress.getByName(ip)) shouldBe true)
      }
  }

  test("public IPv6 addresses are still permitted") {
    Seq("2606:2800:220:1:248:1893:25c8:1946", "2001:4860:4860::8888").foreach { ip =>
      withClue(s"$ip: ")(
        ClientMetadata.isForbiddenAddress(InetAddress.getByName(ip)) shouldBe false)
    }
  }

  // ── message framing ───────────────────────────────────────────────────────
  //
  // A JSON document that parses is not evidence that the HTTP message carrying
  // it finished. Two framing failures used to end as a complete-looking
  // response: a body that stopped short of its `Content-Length`, and a chunk
  // size that did not parse, which was read as the terminating zero chunk. In
  // both cases a truncated prefix became a valid client registration.
  //
  // These drive the production `fetch` against a fixture that writes an exact
  // sequence of bytes, so the wire is the thing under test. Each malformed
  // case has its lawful twin beside it: the point is that the refusal comes
  // from the framing and not from anything the fixtures have in common.

  /** Serve one scripted response on loopback, then close. */
  private def onTheWire[A](wire: String => String)(use: String => A): A = {
    val listener = new ServerSocket(0, 1, loopback)
    val clientId = s"http://localhost:${listener.getLocalPort}/client.json"
    val t = new Thread(() => {
      try {
        val s = listener.accept()
        try {
          val in = s.getInputStream
          var last = ""
          var c = in.read()
          while (c >= 0 && !last.endsWith("\r\n\r\n")) {
            last = (last + c.toChar).takeRight(4)
            if (!last.endsWith("\r\n\r\n")) c = in.read()
          }
          s.getOutputStream.write(wire(clientId).getBytes("UTF-8"))
          s.getOutputStream.flush()
        } finally s.close()
      } catch { case _: Exception => () } finally listener.close()
    })
    t.setDaemon(true)
    t.start()
    try use(clientId) finally { t.join(3000); listener.close() }
  }

  // A plain-http client id is refused by `validateClientId` unless the
  // loopback escape is on, and that refusal happens before anything is
  // fetched. Without this the malformed cases below would all "refuse"
  // without a byte reaching the wire — which is how the first version of
  // these controls passed while proving nothing. The lawful twins are what
  // exposed it, which is the reason they are here.
  private def fetchFromWire(wire: String => String) = {
    val previous = System.getProperty(ClientMetadata.AllowLoopbackProperty)
    System.setProperty(ClientMetadata.AllowLoopbackProperty, "true")
    try onTheWire(wire) { id =>
      ClientMetadata.fetch(mapper, id, allowLoopback = true,
        resolve = resolvesTo(loopback))
    } finally {
      if (previous == null) System.clearProperty(ClientMetadata.AllowLoopbackProperty)
      else System.setProperty(ClientMetadata.AllowLoopbackProperty, previous)
    }
  }

  /**
   * A refusal that came from reading the message, not from validation.
   *
   * Asserting `isLeft` alone cannot tell "the framing was rejected" from
   * "the request was never made", and those are the two outcomes these cases
   * exist to separate.
   */
  private def refusedByFraming(r: Either[ClientMetadataError, OAuthClient]): Unit =
    r match {
      case Left(ClientMetadataError.Unreachable(_)) => ()
      case other => fail(s"expected a transport-level refusal, got: $other")
    }

  private def document(id: String) =
    s"""{"client_id":"$id","client_name":"Wire","redirect_uris":["https://app.example.com/cb"]}"""

  test("a complete Content-Length body is accepted") {
    val r = fetchFromWire { id =>
      val b = document(id)
      s"HTTP/1.1 200 OK\r\nContent-Length: ${b.getBytes("UTF-8").length}\r\nConnection: close\r\n\r\n$b"
    }
    withClue(s"$r: ")(r.isRight shouldBe true)
  }

  test("a body that stops short of its Content-Length is refused") {
    // The JSON is complete and would parse. The message did not finish.
    val r = fetchFromWire { id =>
      val b = document(id)
      s"HTTP/1.1 200 OK\r\nContent-Length: ${b.getBytes("UTF-8").length + 100}\r\nConnection: close\r\n\r\n$b"
    }
    refusedByFraming(r)
  }

  test("a correctly chunked body is accepted") {
    val r = fetchFromWire { id =>
      val b = document(id)
      val n = b.getBytes("UTF-8").length.toHexString
      s"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n$n\r\n$b\r\n0\r\n\r\n"
    }
    withClue(s"$r: ")(r.isRight shouldBe true)
  }

  test("a chunk size that does not parse is refused, not read as the last chunk") {
    val r = fetchFromWire { id =>
      val b = document(id)
      val n = b.getBytes("UTF-8").length.toHexString
      s"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n$n\r\n$b\r\nNOTHEX\r\n"
    }
    refusedByFraming(r)
  }

  test("a chunked body that ends without its terminating chunk is refused") {
    val r = fetchFromWire { id =>
      val b = document(id)
      val n = b.getBytes("UTF-8").length.toHexString
      s"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n$n\r\n$b\r\n"
    }
    refusedByFraming(r)
  }

  test("a response that is not HTTP at all is refused") {
    val r = fetchFromWire(_ => "this is not a response\r\n")
    refusedByFraming(r)
  }

  // ── transport bounds ──────────────────────────────────────────────────────
  //
  // Two bounds were lost when message parsing moved to a maintained stack, and
  // neither loss was visible from a response the server had already finished
  // sending. The cap still threw, but closing a half-read entity makes the
  // client consume the rest of the framed body first, so a peer that keeps
  // supplying a tail held the fetch open past the cap. And the connection
  // manager's default message constraints are unlimited, so a single 1 MiB
  // header was accepted.

  /**
   * A server that overruns the cap and then keeps the body coming until it is
   * released.
   *
   * The open tail is the whole point: a large response the server has already
   * completed cannot detect the drain, because there is nothing left to drain.
   */
  private class Overrunning(capBytes: Int) {
    private val listener = new ServerSocket(0, 1, loopback)
    private val release = new java.util.concurrent.CountDownLatch(1)
    private val sent = new AtomicInteger(0)
    val returned = new AtomicInteger(0)
    val clientId = s"http://localhost:${listener.getLocalPort}/client.json"

    private val t = new Thread(() => {
      try {
        val s = listener.accept()
        try {
          val in = s.getInputStream
          var last = ""
          var c = in.read()
          while (c >= 0 && !last.endsWith("\r\n\r\n")) {
            last = (last + c.toChar).takeRight(4)
            if (!last.endsWith("\r\n\r\n")) c = in.read()
          }
          val out = s.getOutputStream
          // A huge declared length, so the body is well framed and the client
          // has every reason to keep reading.
          val declared = capBytes.toLong * 64
          out.write(s"HTTP/1.1 200 OK\r\nContent-Length: $declared\r\n\r\n".getBytes("UTF-8"))
          out.flush()
          val block = Array.fill[Byte](8192)('x'.toByte)
          // Past the cap first, then keep going until released or the peer
          // goes away.
          while (release.getCount > 0 && sent.get() < declared) {
            out.write(block); out.flush()
            sent.addAndGet(block.length)
            if (sent.get() > capBytes * 8) release.await(50, java.util.concurrent.TimeUnit.MILLISECONDS)
          }
        } finally s.close()
      } catch { case _: Exception => () } finally listener.close()
    })
    t.setDaemon(true); t.start()

    def bytesSent: Int = sent.get()
    def stop(): Unit = { release.countDown(); t.join(3000); listener.close() }
  }

  test("a body past the cap ends the fetch instead of draining the tail") {
    val previous = System.getProperty(ClientMetadata.AllowLoopbackProperty)
    System.setProperty(ClientMetadata.AllowLoopbackProperty, "true")
    val server = new Overrunning(64 * 1024)
    try {
      val done = new java.util.concurrent.CountDownLatch(1)
      @volatile var result: Either[ClientMetadataError, OAuthClient] = null
      val caller = new Thread(() => {
        result = ClientMetadata.fetch(mapper, server.clientId, allowLoopback = true,
          resolve = resolvesTo(loopback))
        done.countDown()
      })
      caller.setDaemon(true); caller.start()

      // The fetch has to finish on its own, while the peer is still willing to
      // send. If it only finishes once the fixture stops, the cap is not a
      // bound on anything.
      val finishedOnItsOwn = done.await(20, java.util.concurrent.TimeUnit.SECONDS)
      withClue(s"still pending after ${server.bytesSent} bytes: ")(finishedOnItsOwn shouldBe true)
      withClue(s"$result: ")(result.isLeft shouldBe true)
      caller.join(3000)
    } finally {
      server.stop()
      if (previous == null) System.clearProperty(ClientMetadata.AllowLoopbackProperty)
      else System.setProperty(ClientMetadata.AllowLoopbackProperty, previous)
    }
  }

  test("a single oversized header is refused") {
    val r = fetchFromWire { id =>
      val b = document(id)
      val huge = "x" * (1024 * 1024)
      s"HTTP/1.1 200 OK\r\nX-Big: $huge\r\nContent-Length: ${b.getBytes("UTF-8").length}\r\nConnection: close\r\n\r\n$b"
    }
    refusedByFraming(r)
  }

  test("too many headers are refused") {
    val r = fetchFromWire { id =>
      val b = document(id)
      val many = (1 to 500).map(i => s"X-Pad-$i: v\r\n").mkString
      s"HTTP/1.1 200 OK\r\n${many}Content-Length: ${b.getBytes("UTF-8").length}\r\nConnection: close\r\n\r\n$b"
    }
    refusedByFraming(r)
  }

  test("an ordinary number of ordinary headers is accepted") {
    // The lawful twin: the bounds have to admit a normal response, or they
    // would be refusing for the wrong reason above.
    val r = fetchFromWire { id =>
      val b = document(id)
      val ordinary = (1 to 20).map(i => s"X-Pad-$i: value-$i\r\n").mkString
      s"HTTP/1.1 200 OK\r\n${ordinary}Content-Type: application/json\r\nContent-Length: ${b.getBytes("UTF-8").length}\r\nConnection: close\r\n\r\n$b"
    }
    withClue(s"$r: ")(r.isRight shouldBe true)
  }
}

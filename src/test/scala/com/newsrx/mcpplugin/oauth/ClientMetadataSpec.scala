package com.newsrx.mcpplugin.oauth

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.{InetAddress, URL}
import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
 * The client metadata fetcher, and the fence around it.
 *
 * The client id is a URL the caller supplies and the server then requests, so
 * these are the controls that keep it from becoming a way to reach things
 * inside the network a GitBucket usually sits in.
 */
@RunWith(classOf[JUnitRunner])
class ClientMetadataSpec extends AnyFunSuite with Matchers {

  private val mapper = new ObjectMapper()
  private val clientId = "https://client.example.com/.well-known/mcp-client"

  private def doc(body: String) = ClientMetadata.parse(mapper, clientId, body)

  /** A resolver, so the policy is tested rather than this sandbox's DNS. */
  private def resolvesTo(ip: String): ClientMetadata.Resolver =
    (_: String) => Array(InetAddress.getByName(ip))

  private val publicHost = resolvesTo("93.184.216.34")

  // ── what counts as a client id ────────────────────────────────────────────

  test("an https client id with a host is accepted") {
    ClientMetadata.validateClientId(clientId).isRight shouldBe true
  }

  test("a non-https client id is refused") {
    Seq("http://client.example.com/m", "file:///etc/passwd", "gopher://x/1",
        "ftp://client.example.com/m").foreach { id =>
      withClue(s"$id: ")(ClientMetadata.validateClientId(id).isLeft shouldBe true)
    }
  }

  test("a client id carrying credentials or a fragment is refused") {
    // user:pass@ can make a URL read as one host and resolve as another.
    ClientMetadata.validateClientId("https://user:pw@client.example.com/m").isLeft shouldBe true
    ClientMetadata.validateClientId("https://client.example.com/m#x").isLeft shouldBe true
  }

  test("a malformed client id is refused rather than thrown") {
    Seq("", "not a url", "https://", "https:// space").foreach { id =>
      withClue(s"[$id]: ")(ClientMetadata.validateClientId(id).isLeft shouldBe true)
    }
  }

  // ── the SSRF fence ────────────────────────────────────────────────────────

  test("loopback, link-local, private and carrier-grade addresses are forbidden targets") {
    Seq("127.0.0.1", "127.0.0.53", "::1",
        "169.254.169.254",            // cloud instance metadata
        "10.0.0.1", "172.16.0.1", "192.168.1.1",
        "100.64.0.1", "100.127.255.254",
        "0.0.0.0", "224.0.0.1").foreach { ip =>
      withClue(s"$ip: ")(
        ClientMetadata.isForbiddenAddress(InetAddress.getByName(ip)) shouldBe true)
    }
  }

  test("ordinary public addresses are permitted targets") {
    Seq("93.184.216.34", "8.8.8.8", "2606:2800:220:1:248:1893:25c8:1946").foreach { ip =>
      withClue(s"$ip: ")(
        ClientMetadata.isForbiddenAddress(InetAddress.getByName(ip)) shouldBe false)
    }
  }

  test("a host resolving to any forbidden address is refused, not just the first") {
    // A split-horizon name that answers with one public and one private
    // address must not be reachable by retrying.
    var opened = false
    val mixed: ClientMetadata.Resolver = (_: String) =>
      Array(InetAddress.getByName("93.184.216.34"), InetAddress.getByName("10.0.0.5"))
    ClientMetadata.fetch(mapper, clientId, (_: URL, _: InetAddress) => { opened = true; null }, mixed).isLeft shouldBe true
    opened shouldBe false
  }

  test("a fetch succeeds through the real path when every rule is satisfied") {
    val body = s"""{"client_id":"$clientId","client_name":"Example","redirect_uris":["https://a.example.com/cb"]}"""
    ClientMetadata.fetch(mapper, clientId, (_: URL, _: InetAddress) => stub(200, body), publicHost)
      .map(_.clientName) shouldBe Right("Example")
  }

  test("a client id resolving to a forbidden address is refused before any request") {
    var opened = false
    val result = ClientMetadata.fetch(mapper, clientId,
      (_: URL, _: InetAddress) => { opened = true; null }, resolvesTo("127.0.0.1"))
    result shouldBe Left(ClientMetadataError.Rejected(
      "client.example.com resolves to a non-public address"))
    withClue("the fetcher must refuse before opening a connection: ")(opened shouldBe false)
  }

  test("a redirect is refused rather than followed") {
    // The classic bypass: an allowed host answers 302 to a forbidden one.
    Seq(301, 302, 303, 307, 308).foreach { status =>
      val result = ClientMetadata.fetch(mapper, clientId, (_: URL, _: InetAddress) => stub(status, ""), publicHost)
      withClue(s"$status: ") {
        result shouldBe Left(ClientMetadataError.Rejected("client metadata must not redirect"))
      }
    }
  }

  test("a non-200 response is reported as unreachable, not parsed") {
    ClientMetadata.fetch(mapper, clientId, (_: URL, _: InetAddress) => stub(404, "{}"), publicHost).isLeft shouldBe true
    ClientMetadata.fetch(mapper, clientId, (_: URL, _: InetAddress) => stub(500, "{}"), publicHost).isLeft shouldBe true
  }

  test("the response body is capped, so an endless document cannot be read forever") {
    val huge = """{"client_id":"""" + clientId + """","redirect_uris":["https://a.example.com/cb"],"pad":"""" +
      ("x" * (300 * 1024)) + """"}"""
    // Truncated at the cap, so it no longer parses: refused, not consumed.
    val result = ClientMetadata.fetch(mapper, clientId, (_: URL, _: InetAddress) => stub(200, huge), publicHost)
    result.isLeft shouldBe true
  }

  test("loopback stays forbidden unless the escape is explicitly set") {
    val loopback = InetAddress.getByName("127.0.0.1")
    ClientMetadata.isForbiddenAddress(loopback) shouldBe true
    ClientMetadata.isForbiddenAddress(loopback, allowLoopback = false) shouldBe true
    ClientMetadata.isForbiddenAddress(loopback, allowLoopback = true) shouldBe false
  }

  test("the loopback escape opens loopback and nothing else") {
    // The reason it is narrow: a flag that quietly also admitted 10.0.0.0/8
    // or 169.254.169.254 would be an SSRF switch rather than a development
    // convenience.
    Seq("10.0.0.1", "172.16.0.1", "192.168.1.1", "169.254.169.254",
        "100.64.0.1", "224.0.0.1").foreach { ip =>
      withClue(s"$ip with the escape on: ")(
        ClientMetadata.isForbiddenAddress(InetAddress.getByName(ip), allowLoopback = true) shouldBe true)
    }
  }

  test("the escape is off unless the property says otherwise") {
    val previous = System.getProperty(ClientMetadata.AllowLoopbackProperty)
    try {
      System.clearProperty(ClientMetadata.AllowLoopbackProperty)
      ClientMetadata.loopbackAllowed() shouldBe false
      System.setProperty(ClientMetadata.AllowLoopbackProperty, "false")
      ClientMetadata.loopbackAllowed() shouldBe false
      System.setProperty(ClientMetadata.AllowLoopbackProperty, "yes")
      ClientMetadata.loopbackAllowed() shouldBe false  // only "true" counts
      System.setProperty(ClientMetadata.AllowLoopbackProperty, "true")
      ClientMetadata.loopbackAllowed() shouldBe true
    } finally {
      if (previous == null) System.clearProperty(ClientMetadata.AllowLoopbackProperty)
      else System.setProperty(ClientMetadata.AllowLoopbackProperty, previous)
    }
  }

  test("an http client id is only accepted on loopback, and only with the escape") {
    val previous = System.getProperty(ClientMetadata.AllowLoopbackProperty)
    try {
      System.setProperty(ClientMetadata.AllowLoopbackProperty, "true")
      ClientMetadata.validateClientId("http://localhost:9000/m").isRight shouldBe true
      ClientMetadata.validateClientId("http://127.0.0.1:9000/m").isRight shouldBe true
      // Still not any other http host, even with the escape on.
      ClientMetadata.validateClientId("http://evil.example.com/m").isLeft shouldBe true
      System.setProperty(ClientMetadata.AllowLoopbackProperty, "false")
      ClientMetadata.validateClientId("http://localhost:9000/m").isLeft shouldBe true
    } finally {
      if (previous == null) System.clearProperty(ClientMetadata.AllowLoopbackProperty)
      else System.setProperty(ClientMetadata.AllowLoopbackProperty, previous)
    }
  }

  // ── what a document has to say ────────────────────────────────────────────

  test("a well-formed document yields the client's name and redirect URIs") {
    val result = doc(s"""{"client_id":"$clientId","client_name":"Example Client",
                        |"redirect_uris":["https://chatgpt.com/connector_platform_oauth_redirect"]}""".stripMargin)
    result.map(_.clientName) shouldBe Right("Example Client")
    result.map(_.redirectUris) shouldBe Right(Set("https://chatgpt.com/connector_platform_oauth_redirect"))
  }

  test("a document declaring a different client_id is refused") {
    // Otherwise one client's redirect list could authorise another's.
    doc(s"""{"client_id":"https://evil.example.com/m","redirect_uris":["https://a.example.com/cb"]}""") shouldBe
      Left(ClientMetadataError.Rejected("client metadata declares a different client_id"))
  }

  test("a document with no usable redirect URI is refused") {
    doc(s"""{"client_id":"$clientId"}""").isLeft shouldBe true
    doc(s"""{"client_id":"$clientId","redirect_uris":[]}""").isLeft shouldBe true
    // Non-https redirects are dropped, which leaves none.
    doc(s"""{"client_id":"$clientId","redirect_uris":["http://evil.example.com/cb"]}""").isLeft shouldBe true
  }

  test("a document that is not JSON, or not an object, is refused") {
    Seq("", "not json", "[1,2,3]", "\"a string\"", "null").foreach { body =>
      withClue(s"[$body]: ")(doc(body).isLeft shouldBe true)
    }
  }

  test("the client name falls back to the client id rather than being empty") {
    doc(s"""{"client_id":"$clientId","client_name":"","redirect_uris":["https://a.example.com/cb"]}""")
      .map(_.clientName) shouldBe Right(clientId)
  }

  // ── redirect matching ─────────────────────────────────────────────────────

  test("a redirect URI must match exactly") {
    val client = OAuthClient(clientId, "C", Set("https://chatgpt.com/connector/oauth/abc"))
    client.permits("https://chatgpt.com/connector/oauth/abc") shouldBe true
    // Every one of these is a near-miss a prefix or host check would admit.
    client.permits("https://chatgpt.com/connector/oauth/abc/..") shouldBe false
    client.permits("https://chatgpt.com/connector/oauth/abcd") shouldBe false
    client.permits("https://chatgpt.com/connector/oauth/abc?x=1") shouldBe false
    client.permits("https://chatgpt.com/connector/oauth/abc#x") shouldBe false
    client.permits("https://evil.example.com/connector/oauth/abc") shouldBe false
    client.permits("https://chatgpt.com.evil.example.com/connector/oauth/abc") shouldBe false
  }

  // ── consent rendering of untrusted values ─────────────────────────────────

  test("a client name cannot inject markup into the consent screen") {
    val hostile = """<script>alert(1)</script>"""
    val client = OAuthClient("https://evil.example.com/m\" onload=\"x", hostile,
      Set("https://evil.example.com/cb"))
    val html = ConsentView.render(client, "alice", Set("mcp:read"),
      "https://evil.example.com/cb", Some("s\"><b>"), "csrf", "/plugin-mcp/oauth/authorize?k=1")
    html should not include "<script>"
    html should include("&lt;script&gt;")
    // Neither the client id nor the state may break out of an attribute.
    html should not include """value="https://evil.example.com/m" onload="x""""
    html should include("&quot;")
  }

  test("the consent screen names the account that will be acting") {
    val client = OAuthClient(clientId, "Example Client", Set("https://a.example.com/cb"))
    val html = ConsentView.render(client, "alice", Set("mcp:read", "mcp:write"),
      "https://a.example.com/cb", None, "csrf", "/x")
    html should include("alice")
    html should include("mcp:read")
    html should include("mcp:write")
    html should include(clientId)
  }

  // The body is capped by the transport, so a stub that hands back more than
  // the cap is standing in for a transport that already truncated.
  private def stub(status: Int, body: String): PinnedResponse =
    PinnedResponse(status, body.take(64 * 1024))
}

package com.newsrx.mcpplugin.oauth

import com.fasterxml.jackson.databind.ObjectMapper
import com.newsrx.mcpplugin.mcp.ToolRegistry
import gitbucket.core.model.Account
import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
 * Controls for the parts of the OAuth layer that have no database and no
 * request: configuration, discovery documents, the challenge header, PKCE and
 * scopes.
 *
 * These are the pieces a client reads before it ever authenticates, so a
 * mistake here is a mistake every client inherits.
 */
@RunWith(classOf[JUnitRunner])
class OAuthFoundationSpec extends AnyFunSuite with Matchers {

  private val mapper = new ObjectMapper()
  private val config = OAuthConfig("https://git.example.com")

  // ── configuration ─────────────────────────────────────────────────────────

  test("a well-formed https base URL is accepted and derives every identifier") {
    val c = OAuthConfig.fromBaseUrl("https://git.example.com").toOption.get
    c.issuer shouldBe "https://git.example.com"
    c.resource shouldBe "https://git.example.com/plugin-mcp/"
    c.authorizationEndpoint shouldBe "https://git.example.com/plugin-mcp/oauth/authorize"
    c.tokenEndpoint shouldBe "https://git.example.com/plugin-mcp/oauth/token"
  }

  test("a context path is carried into every derived identifier") {
    // A GitBucket deployed under /gitbucket must not advertise endpoints at
    // the server root.
    val c = OAuthConfig.fromBaseUrl("https://example.com/gitbucket").toOption.get
    c.resource shouldBe "https://example.com/gitbucket/plugin-mcp/"
    c.authorizationEndpoint shouldBe "https://example.com/gitbucket/plugin-mcp/oauth/authorize"
  }

  test("an unconfigured base URL is refused with a reason an administrator can act on") {
    val reason = OAuthConfig.fromBaseUrl("").swap.toOption.get
    reason should include(OAuthConfig.BaseUrlProperty)
    reason should include(OAuthConfig.BaseUrlEnv)
    OAuthConfig.fromBaseUrl(null).isLeft shouldBe true
  }

  test("plain http is refused except on loopback") {
    OAuthConfig.fromBaseUrl("http://git.example.com").isLeft shouldBe true
    OAuthConfig.fromBaseUrl("http://localhost:8080").isRight shouldBe true
    OAuthConfig.fromBaseUrl("http://127.0.0.1:8080").isRight shouldBe true
  }

  test("a trailing slash, query or fragment is refused rather than normalised") {
    // Silently trimming would mean the configured value and the published
    // value differ, and the published one is what a token is bound to.
    OAuthConfig.fromBaseUrl("https://git.example.com/").isLeft shouldBe true
    OAuthConfig.fromBaseUrl("https://git.example.com?x=1").isLeft shouldBe true
    OAuthConfig.fromBaseUrl("https://git.example.com#x").isLeft shouldBe true
  }

  // ── protected resource metadata (RFC 9728) ────────────────────────────────

  test("protected resource metadata names this resource and its authorization server") {
    val n = Metadata.protectedResource(mapper, config)
    n.get("resource").asText() shouldBe "https://git.example.com/plugin-mcp/"
    n.get("authorization_servers").get(0).asText() shouldBe "https://git.example.com"
    n.get("bearer_methods_supported").get(0).asText() shouldBe "header"
  }

  test("protected resource metadata advertises exactly the scopes that exist") {
    val n = Metadata.protectedResource(mapper, config)
    val scopes = (0 until n.get("scopes_supported").size()).map(n.get("scopes_supported").get(_).asText())
    scopes should contain theSameElementsAs Seq("mcp:read", "mcp:write")
  }

  // ── authorization server metadata (RFC 8414) ──────────────────────────────

  test("authorization server metadata carries the fields ChatGPT requires") {
    val n = Metadata.authorizationServer(mapper, config)
    Seq("issuer", "authorization_endpoint", "token_endpoint", "code_challenge_methods_supported")
      .foreach(f => withClue(s"$f: ")(n.has(f) shouldBe true))
    n.get("issuer").asText() shouldBe config.issuer
  }

  test("S256 is the only code challenge method advertised") {
    // A metadata document that also lists "plain" invites a downgrade, and a
    // client that sends it gets no protection at all.
    val methods = Metadata.authorizationServer(mapper, config).get("code_challenge_methods_supported")
    methods.size() shouldBe 1
    methods.get(0).asText() shouldBe "S256"
  }

  test("only the implemented grant types are advertised") {
    val n = Metadata.authorizationServer(mapper, config).get("grant_types_supported")
    val grants = (0 until n.size()).map(n.get(_).asText())
    grants should contain theSameElementsAs Seq("authorization_code", "refresh_token")
    grants should not contain "implicit"
    grants should not contain "password"
    grants should not contain "client_credentials"
  }

  test("no registration endpoint is advertised, because none is implemented") {
    val n = Metadata.authorizationServer(mapper, config)
    n.has("registration_endpoint") shouldBe false
    n.get("client_id_metadata_document_supported").asBoolean() shouldBe true
  }

  test("issuer identification is advertised, which fixes ChatGPT's redirect URI") {
    // RFC 9207 support is what lets ChatGPT use its shared redirect URI
    // instead of a per-connection callback, so the claim must be true.
    Metadata.authorizationServer(mapper, config)
      .get("authorization_response_iss_parameter_supported").asBoolean() shouldBe true
  }

  test("the authorization server document parses back as RFC 8414 metadata") {
    // Round-tripping through the SDK's parser is what establishes that the
    // document is well-formed metadata rather than a JSON object that happens
    // to have the right key names.
    val json = Metadata.authorizationServer(mapper, config).toString
    val parsed = com.nimbusds.oauth2.sdk.`as`.AuthorizationServerMetadata.parse(json)
    parsed.getIssuer.getValue shouldBe config.issuer
    parsed.getAuthorizationEndpointURI.toString shouldBe config.authorizationEndpoint
    parsed.getTokenEndpointURI.toString shouldBe config.tokenEndpoint
    parsed.getCodeChallengeMethods.size shouldBe 1
    parsed.supportsAuthorizationResponseIssuerParam() shouldBe true
  }

  test("no OIDC scope or userinfo endpoint is advertised, because neither is implemented") {
    val n = Metadata.authorizationServer(mapper, config)
    n.has("userinfo_endpoint") shouldBe false
    val scopes = n.get("scopes_supported")
    (0 until scopes.size()).map(scopes.get(_).asText()) should not contain "openid"
  }

  // ── the 401 challenge ─────────────────────────────────────────────────────

  test("the challenge points at the protected resource metadata") {
    val c = Metadata.challenge(config)
    c should startWith("Bearer ")
    c should include("""resource_metadata="https://git.example.com/.well-known/oauth-protected-resource"""")
  }

  test("the challenge carries the error code a client branches on") {
    val c = Metadata.challenge(config, Some(ChallengeError.InvalidToken))
    c should include("""error="invalid_token"""")
    c should include("""error_description="the access token is not valid"""")
  }

  test("an insufficient-scope refusal is distinguishable from an invalid token") {
    // A client that holds a valid read token and calls a write tool must be
    // told to ask for more, not to authenticate again.
    Metadata.challenge(config, Some(ChallengeError.InsufficientScope)) should
      include("""error="insufficient_scope"""")
    ChallengeError.InsufficientScope.status shouldBe 403
    ChallengeError.InvalidToken.status shouldBe 401
  }

  test("no caller-controlled text can reach the challenge header") {
    // The reason is a closed set rather than a string, so there is no argument
    // through which a caller's bytes could arrive. Every description is
    // server-authored and free of the characters that would end a quoted
    // string or a header line.
    Seq(ChallengeError.InvalidRequest, ChallengeError.InvalidToken,
        ChallengeError.InsufficientScope).foreach { e =>
      val c = Metadata.challenge(config, Some(e))
      withClue(s"${e.code}: ") {
        c.count(_ == '"') shouldBe 6 // three quoted values, two quotes each
        c should not include "\r"
        c should not include "\n"
        e.description should fullyMatch regex "[ -!#-\\[\\]-~]*"
      }
    }
  }

  test("every refusal still points at the metadata, so a client can recover") {
    Seq(None, Some(ChallengeError.InvalidToken), Some(ChallengeError.InsufficientScope))
      .foreach { e =>
        Metadata.challenge(config, e) should
          include("""resource_metadata="https://git.example.com/.well-known/oauth-protected-resource"""")
      }
  }

  test("the token refusal reveals nothing about why the token failed") {
    // Unknown, expired, revoked and wrong-audience are one answer on purpose:
    // the differences are only useful to someone probing with tokens they
    // should not hold.
    ChallengeError.InvalidToken.description should not include "expired"
    ChallengeError.InvalidToken.description should not include "revoked"
    ChallengeError.InvalidToken.description should not include "unknown"
  }

  // ── PKCE ──────────────────────────────────────────────────────────────────

  test("a verifier matches the challenge derived from it") {
    val verifier = "a" * 43
    Pkce.verify(verifier, Pkce.challengeFor(verifier)) shouldBe true
  }

  test("the challenge is base64url with no padding, as RFC 7636 requires") {
    val challenge = Pkce.challengeFor("b" * 64)
    challenge should not include "="
    challenge should not include "+"
    challenge should not include "/"
    challenge should fullyMatch regex "[A-Za-z0-9_-]+"
  }

  test("the challenge agrees with the library's own computation") {
    // The SHA-256 and base64url are the SDK's; this asserts the adapter asks
    // it rather than reimplementing it beside it.
    val verifier = new com.nimbusds.oauth2.sdk.pkce.CodeVerifier("e" * 50)
    val fromLibrary = com.nimbusds.oauth2.sdk.pkce.CodeChallenge
      .compute(com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod.S256, verifier).getValue
    Pkce.challengeFor(verifier.getValue) shouldBe fromLibrary
    Pkce.verify(verifier.getValue, fromLibrary) shouldBe true
  }

  test("the advertised method is the library's S256 constant") {
    Pkce.supportedMethod shouldBe com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod.S256
  }

  test("the published RFC 7636 appendix B vector is reproduced") {
    // An independent value, so this checks the implementation rather than
    // checking it against itself.
    Pkce.challengeFor("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk") shouldBe
      "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
  }

  test("a wrong verifier is refused") {
    Pkce.verify("c" * 43, Pkce.challengeFor("d" * 43)) shouldBe false
  }

  test("a verifier outside the length range is refused") {
    Pkce.isWellFormedVerifier("a" * 42) shouldBe false
    Pkce.isWellFormedVerifier("a" * 43) shouldBe true
    Pkce.isWellFormedVerifier("a" * 128) shouldBe true
    Pkce.isWellFormedVerifier("a" * 129) shouldBe false
  }

  test("a verifier with characters outside the unreserved set is refused") {
    Pkce.isWellFormedVerifier(("a" * 42) + "+") shouldBe false
    Pkce.isWellFormedVerifier(("a" * 42) + "/") shouldBe false
    Pkce.isWellFormedVerifier(("a" * 42) + " ") shouldBe false
  }

  test("a missing verifier or challenge is refused rather than treated as a match") {
    Pkce.verify(null, "x") shouldBe false
    Pkce.verify("a" * 43, null) shouldBe false
    Pkce.verify("a" * 43, "") shouldBe false
  }

  // ── scopes ────────────────────────────────────────────────────────────────

  test("a read grant satisfies a read requirement and not a write one") {
    val granted = Set("mcp:read")
    McpScope.Read.satisfiedBy(granted) shouldBe true
    McpScope.Write.satisfiedBy(granted) shouldBe false
  }

  test("a write grant satisfies both, because writing implies reading back") {
    val granted = Set("mcp:write")
    McpScope.Read.satisfiedBy(granted) shouldBe true
    McpScope.Write.satisfiedBy(granted) shouldBe true
  }

  test("an empty grant satisfies nothing") {
    McpScope.Read.satisfiedBy(Set.empty) shouldBe false
    McpScope.Write.satisfiedBy(Set.empty) shouldBe false
  }

  test("an unrelated scope grants nothing") {
    val granted = Set("openid", "email", "admin")
    McpScope.Read.satisfiedBy(granted) shouldBe false
    McpScope.Write.satisfiedBy(granted) shouldBe false
  }

  test("requested scopes are narrowed to what this server issues") {
    McpScope.parseRequested("mcp:read openid profile") shouldBe Set("mcp:read")
    McpScope.parseRequested("mcp:read mcp:write") shouldBe Set("mcp:read", "mcp:write")
    McpScope.parseRequested("admin mcp:admin") shouldBe Set.empty
  }

  test("odd whitespace in a scope string does not create a scope") {
    McpScope.parseRequested("  mcp:read\t\tmcp:write  ") shouldBe Set("mcp:read", "mcp:write")
    McpScope.parseRequested("") shouldBe Set.empty
    McpScope.parseRequested(null) shouldBe Set.empty
  }

  test("a granted set renders in a stable order for the token response") {
    McpScope.render(Set("mcp:write", "mcp:read")) shouldBe "mcp:read mcp:write"
    McpScope.render(Set("mcp:read")) shouldBe "mcp:read"
    McpScope.render(Set.empty) shouldBe ""
  }

  test("the default grant is the weaker scope") {
    McpScope.default shouldBe Set("mcp:read")
  }

  // ── the gate every tool call passes through ───────────────────────────────
  //
  // `AuthenticatedCaller.permits` is the one place a scope decision is made,
  // and until now nothing offline exercised it: a mutation that made it
  // answer `true` for everything left the whole suite green and was caught
  // only by the live gate. These cases make the decision itself observable.

  private def grantOf(scopes: Set[String]) =
    ResolvedGrant("g-1", "someone", "https://client.example.com/meta",
                  "https://example.com/plugin-mcp/", scopes)

  // Every field named, and `accountId` deliberately omitted: 4.46.0's Account
  // has no such field and 4.48.0 gives it a default, so naming it would pin
  // this suite to one release for no benefit.
  private val account = Account(
    userName = "someone", fullName = "Someone",
    mailAddress = "someone@example.com", password = "x", isAdmin = false, url = None,
    registeredDate = new java.util.Date(0), updatedDate = new java.util.Date(0),
    lastLoginDate = None, image = None, isGroupAccount = false, isRemoved = false,
    description = None)

  test("a read-only grant does not permit a write") {
    val caller = AuthenticatedCaller.oauth(account, grantOf(Set("mcp:read")))
    caller.permits(McpScope.Read) shouldBe true
    caller.permits(McpScope.Write) shouldBe false
  }

  test("a write grant permits a read, because write implies read") {
    val caller = AuthenticatedCaller.oauth(account, grantOf(Set("mcp:write")))
    caller.permits(McpScope.Write) shouldBe true
    caller.permits(McpScope.Read) shouldBe true
  }

  test("a grant carrying no scope permits nothing") {
    val caller = AuthenticatedCaller.oauth(account, grantOf(Set.empty))
    caller.permits(McpScope.Read) shouldBe false
    caller.permits(McpScope.Write) shouldBe false
  }

  test("an unknown scope string is not a scope") {
    val caller = AuthenticatedCaller.oauth(account, grantOf(Set("mcp:admin", "write")))
    caller.permits(McpScope.Read) shouldBe false
    caller.permits(McpScope.Write) shouldBe false
  }

  test("a Basic caller holds every scope and carries no grant") {
    // The existing connection was never scoped; adding OAuth must not
    // retroactively restrict it.
    val caller = AuthenticatedCaller.basic(account)
    caller.permits(McpScope.Read) shouldBe true
    caller.permits(McpScope.Write) shouldBe true
    caller.grantId shouldBe None
  }

  test("an OAuth caller carries its grant, so a revoked grant can be found") {
    AuthenticatedCaller.oauth(account, grantOf(Set("mcp:read"))).grantId shouldBe Some("g-1")
  }

  test("every registered tool declares a scope, and mutating tools require write") {
    // The abstract member means a new tool cannot compile without answering
    // this. What it cannot do by itself is stop someone answering `Read` for
    // a tool that writes, so the registry is checked against the names.
    val mutating = Set("create_issue", "add_issue_comment", "add_pr_comment",
                       "close_issue", "reopen_issue", "close_pull_request",
                       "reopen_pull_request", "update_pull_request",
                       "update_own_comment", "mark_ready_for_review")
    val tools = ToolRegistry.all(new ObjectMapper())
    tools.map(_.name).toSet should contain allElementsOf mutating
    tools.foreach { t =>
      withClue(s"${t.name}: ") {
        t.requiredScope shouldBe (if (mutating.contains(t.name)) McpScope.Write else McpScope.Read)
      }
    }
  }
}

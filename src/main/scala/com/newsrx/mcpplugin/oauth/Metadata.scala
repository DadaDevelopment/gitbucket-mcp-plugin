package com.newsrx.mcpplugin.oauth

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.nimbusds.oauth2.sdk.{GrantType, ResponseMode, ResponseType, Scope => NimbusScope}
import com.nimbusds.oauth2.sdk.`as`.AuthorizationServerMetadata
import com.nimbusds.oauth2.sdk.auth.ClientAuthenticationMethod
import com.nimbusds.oauth2.sdk.id.Issuer
import java.net.URI
import scala.jdk.CollectionConverters._

/**
 * The discovery documents, and the challenge that points at them.
 *
 * These describe only what is implemented. A metadata document is a promise a
 * client acts on without asking again: advertising `plain` PKCE, a
 * registration endpoint or an OIDC scope that does not work produces a client
 * that fails later, further from the cause, with a worse error.
 *
 * Ownership, because it differs between the two documents. The authorization
 * server document is built and serialized by
 * `com.nimbusds.oauth2.sdk.as.AuthorizationServerMetadata`, so RFC 8414 field
 * names and shapes are the library's. The protected resource document is
 * written here by hand: RFC 9728 has no type in the oauth2-oidc-sdk version
 * GitBucket ships, which is a small document and a deliberate exception
 * rather than a preference for hand-rolled protocol code.
 */
object Metadata {

  /**
   * RFC 9728 protected resource metadata, served at
   * `/.well-known/oauth-protected-resource`.
   */
  def protectedResource(mapper: ObjectMapper, config: OAuthConfig): ObjectNode = {
    val n = mapper.createObjectNode()
    n.put("resource", config.resource)
    val servers = mapper.createArrayNode()
    servers.add(config.issuer)
    n.set[ObjectNode]("authorization_servers", servers)
    val scopes = mapper.createArrayNode()
    McpScope.all.foreach(s => scopes.add(s.name))
    n.set[ObjectNode]("scopes_supported", scopes)
    val methods = mapper.createArrayNode()
    methods.add("header")
    n.set[ObjectNode]("bearer_methods_supported", methods)
    n
  }

  /**
   * RFC 8414 authorization server metadata, served at
   * `/.well-known/oauth-authorization-server`.
   *
   * Built through Nimbus's `AuthorizationServerMetadata`, which owns the field
   * names and the JSON shape.
   */
  def authorizationServer(mapper: ObjectMapper, config: OAuthConfig): ObjectNode = {
    val m = new AuthorizationServerMetadata(new Issuer(config.issuer))
    m.setAuthorizationEndpointURI(URI.create(config.authorizationEndpoint))
    m.setTokenEndpointURI(URI.create(config.tokenEndpoint))
    m.setRevocationEndpointURI(URI.create(config.revocationEndpoint))

    m.setResponseTypes(List(ResponseType.CODE).asJava)
    m.setResponseModes(List(ResponseMode.QUERY).asJava)
    m.setGrantTypes(List(GrantType.AUTHORIZATION_CODE, GrantType.REFRESH_TOKEN).asJava)
    // S256 only. The absence of "plain" is the point, not an omission.
    m.setCodeChallengeMethods(List(Pkce.supportedMethod).asJava)
    m.setScopes(new NimbusScope(McpScope.all.map(_.name): _*))
    // Public clients proving possession with PKCE; no client secret exists to
    // present, so "none" is the honest value rather than a weaker default.
    m.setTokenEndpointAuthMethods(List(ClientAuthenticationMethod.NONE).asJava)
    m.setRevocationEndpointAuthMethods(List(ClientAuthenticationMethod.NONE).asJava)
    // RFC 9207: the authorization response carries `iss`, so a client can tell
    // which server answered and cannot be fed a response from another one.
    m.setSupportsAuthorizationResponseIssuerParam(true)

    val node = mapper.readTree(m.toJSONObject.toJSONString).asInstanceOf[ObjectNode]
    // SEP-3149. Not modelled by the SDK version GitBucket ships, so it is set
    // on the serialized document; the client_id is an HTTPS URL serving the
    // client's own metadata, and there is no registration step and therefore
    // no registration_endpoint.
    node.put("client_id_metadata_document_supported", true)
    node
  }

  /**
   * The `WWW-Authenticate` value for a 401 from the resource server
   * (RFC 9728 §5.1), which is how a client discovers where to authenticate
   * when it did not look first.
   *
   * The reason is a [[ChallengeError]] rather than a string, so nothing a
   * caller sent can reach a response header. Sanitising caller text would
   * also work and is worse: it is a blacklist, it has to stay correct as the
   * header grammar grows, and it still copies an attacker's words into a
   * response other software may log or parse.
   */
  def challenge(config: OAuthConfig, error: Option[ChallengeError] = None): String = {
    val metadataUrl = s"${config.baseUrl}/.well-known/oauth-protected-resource"
    val parts = Seq(
      Some(s"""resource_metadata="$metadataUrl""""),
      error.map(e => s"""error="${e.code}""""),
      error.map(e => s"""error_description="${e.description}"""")
    ).flatten
    s"Bearer ${parts.mkString(", ")}"
  }

  private def array(mapper: ObjectMapper, parent: ObjectNode, field: String, values: Seq[String]): Unit = {
    val arr = mapper.createArrayNode()
    values.foreach(arr.add)
    parent.set[ObjectNode](field, arr)
  }
}

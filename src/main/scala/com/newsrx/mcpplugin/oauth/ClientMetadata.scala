package com.newsrx.mcpplugin.oauth

import java.net.{InetAddress, URI, URL}
import java.nio.charset.StandardCharsets
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import scala.jdk.CollectionConverters._

/** A client described by its own metadata document (SEP-3149, CIMD). */
final case class OAuthClient(
  clientId: String,
  clientName: String,
  redirectUris: Set[String]
) {
  /** Exact match. A prefix or a host comparison is how redirect validation gets broken. */
  def permits(redirectUri: String): Boolean = redirectUris.contains(redirectUri)
}

sealed trait ClientMetadataError { def message: String }
object ClientMetadataError {
  final case class Rejected(message: String) extends ClientMetadataError
  final case class Unreachable(message: String) extends ClientMetadataError
  final case class Malformed(message: String) extends ClientMetadataError
}

/**
 * Fetching a client's metadata document.
 *
 * The client id *is* a URL the caller supplies, and the server then makes a
 * request to it. That is a server-side request forgery primitive unless it is
 * fenced, and the fence is the interesting part of this file: a GitBucket is
 * usually inside a network with things worth reaching.
 *
 * The rules, each because of a specific way this goes wrong:
 *
 *  - HTTPS only, so the document cannot be substituted on the wire;
 *  - every resolved address is checked against loopback, link-local, private
 *    and multicast ranges, so `https://internal.example/` and
 *    `https://169.254.169.254/` are refused rather than fetched;
 *  - redirects are not followed at all, so an allowed host cannot bounce the
 *    request to a refused one — the common bypass of a check done only on the
 *    first URL;
 *  - connect and read timeouts, and a hard cap on bytes read, so a slow or
 *    endless response occupies one request slot briefly instead of a thread
 *    forever.
 *
 * Checking the address and then connecting to a *name* closes nothing: the two
 * resolutions are independent, and only the second one decides where the
 * request goes. Verifying afterwards is worse than useless, because a response
 * exists only once the request has been sent. So the address is chosen here
 * and the socket is connected to exactly it; see `PinnedConnection`. The host
 * name is still what TLS asks for and what the certificate must match, so
 * nothing about authenticating the peer is given up.
 */
object ClientMetadata {

  /**
   * Permit client metadata documents on loopback addresses.
   *
   * Off. With it on, part of the fence below is gone: a client id pointing at
   * `127.0.0.1` will be fetched, and anything listening there can serve it.
   * It exists because a developer running an MCP client on the same machine
   * as GitBucket otherwise cannot complete an authorization at all, and
   * because the alternative — a test-only branch compiled into the
   * production path — is worse. Private, link-local, carrier-grade and
   * multicast ranges stay forbidden either way: this opens loopback only.
   */
  val AllowLoopbackProperty = "gitbucket.mcp.oauth.allowLoopbackClients"

  def loopbackAllowed(): Boolean =
    java.lang.Boolean.parseBoolean(System.getProperty(AllowLoopbackProperty, "false"))

  private val ConnectTimeoutMs = 5000
  private val ReadTimeoutMs = 5000
  private val MaxBytes = 64 * 1024

  /** Parse and validate a client id without fetching anything. */
  def validateClientId(clientId: String): Either[ClientMetadataError, URI] =
    try {
      val uri = new URI(clientId)
      if (uri.getScheme == null ||
          (uri.getScheme.toLowerCase != "https" &&
           !(loopbackAllowed() && uri.getScheme.toLowerCase == "http" && isLoopbackHost(uri.getHost))))
        Left(ClientMetadataError.Rejected("client_id must be an https URL"))
      else if (uri.getHost == null || uri.getHost.isEmpty)
        Left(ClientMetadataError.Rejected("client_id must name a host"))
      else if (uri.getFragment != null)
        Left(ClientMetadataError.Rejected("client_id must not carry a fragment"))
      else if (uri.getUserInfo != null)
        Left(ClientMetadataError.Rejected("client_id must not carry user information"))
      else Right(uri)
    } catch {
      case _: Exception => Left(ClientMetadataError.Rejected("client_id is not a valid URL"))
    }

  /** True when this address must never be the target of a server-side fetch. */
  def isForbiddenAddress(addr: InetAddress): Boolean =
    isForbiddenAddress(addr, allowLoopback = false)

  def isForbiddenAddress(addr: InetAddress, allowLoopback: Boolean): Boolean = {
    val bytes = addr.getAddress
    def octet(i: Int): Int = bytes(i) & 0xff

    // An IPv4 address written as an IPv6 one is the same destination, and
    // `isSiteLocalAddress` and friends do not look through the wrapper. Both
    // the mapped (::ffff:a.b.c.d) and compatible (::a.b.c.d) forms unwrap.
    val embeddedIPv4: Option[InetAddress] =
      if (bytes.length == 16 && (0 until 10).forall(bytes(_) == 0) &&
          ((octet(10) == 0xff && octet(11) == 0xff) || (octet(10) == 0 && octet(11) == 0)) &&
          !(0 until 12).forall(bytes(_) == 0))
        try Some(InetAddress.getByAddress(bytes.slice(12, 16))) catch { case _: Exception => None }
      else None

    embeddedIPv4.exists(isForbiddenAddress(_, allowLoopback)) ||
      (addr.isLoopbackAddress && !allowLoopback) || addr.isLinkLocalAddress ||
      addr.isSiteLocalAddress || addr.isAnyLocalAddress || addr.isMulticastAddress ||
      // 100.64.0.0/10, carrier-grade NAT, not covered by isSiteLocalAddress.
      (bytes.length == 4 && octet(0) == 100 && octet(1) >= 64 && octet(1) <= 127) ||
      // fc00::/7, IPv6 unique local. `isSiteLocalAddress` only covers the
      // deprecated fec0::/10, so without this a ULA address reads as public.
      (bytes.length == 16 && (octet(0) & 0xfe) == 0xfc)
  }

  /** How a host name becomes addresses; injectable so the policy is testable. */
  type Resolver = String => Array[InetAddress]

  val systemResolver: Resolver = InetAddress.getAllByName

  /**
   * Resolve `host` and return the one address the request may go to.
   *
   * Returning the address, rather than a verdict about the name, is the point:
   * the caller connects to this and to nothing else, so there is no second
   * resolution for an attacker to answer differently.
   */
  private[oauth] def permittedAddress(host: String, resolve: Resolver,
                                      allowLoopback: Boolean): Either[ClientMetadataError, InetAddress] =
    try {
      val addresses = resolve(host)
      if (addresses == null || addresses.isEmpty)
        Left(ClientMetadataError.Unreachable(s"cannot resolve $host"))
      // Every address, not the first: a host that resolves to one public and
      // one private address must not be reachable by retry.
      else if (addresses.exists(isForbiddenAddress(_, allowLoopback)))
        Left(ClientMetadataError.Rejected(s"$host resolves to a non-public address"))
      else Right(addresses.head)
    } catch {
      case _: Exception => Left(ClientMetadataError.Unreachable(s"cannot resolve $host"))
    }

  /**
   * How a validated address becomes a response.
   *
   * The address is a parameter rather than something the fetcher works out for
   * itself, so the destination cannot drift between the check and the request.
   * Injectable so a control can see which address the production path dialled.
   */
  type Fetcher = (URL, InetAddress) => PinnedResponse

  val pinnedFetcher: Fetcher = (url, address) =>
    PinnedConnection.fetch(url, address, ConnectTimeoutMs, ReadTimeoutMs, MaxBytes)

  /**
   * Fetch and parse a client metadata document.
   *
   * The address is resolved and checked once, and the connection is pinned to
   * it. There is no second resolution and no after-the-fact verification: by
   * the time a response exists the request has already been made, which is the
   * part that mattered.
   */
  def fetch(mapper: ObjectMapper, clientId: String,
            open: Fetcher = pinnedFetcher,
            resolve: Resolver = systemResolver,
            allowLoopback: Boolean = loopbackAllowed()): Either[ClientMetadataError, OAuthClient] =
    for {
      uri <- validateClientId(clientId)
      address <- permittedAddress(uri.getHost, resolve, allowLoopback)
      body <- read(uri.toURL, address, open)
      client <- parse(mapper, clientId, body)
    } yield client

  private def read(url: URL, address: InetAddress,
                   open: Fetcher): Either[ClientMetadataError, String] =
    try {
      val response = open(url, address)
      if (response.status >= 300 && response.status < 400)
        Left(ClientMetadataError.Rejected("client metadata must not redirect"))
      else if (response.status != 200)
        Left(ClientMetadataError.Unreachable(s"client metadata returned HTTP ${response.status}"))
      else Right(response.body)
    } catch {
      case e: Exception => Left(ClientMetadataError.Unreachable(
        s"client metadata could not be fetched: ${e.getClass.getSimpleName}"))
    }

  def parse(mapper: ObjectMapper, clientId: String, body: String): Either[ClientMetadataError, OAuthClient] =
    try {
      val node: JsonNode = mapper.readTree(body)
      if (node == null || !node.isObject)
        Left(ClientMetadataError.Malformed("client metadata is not a JSON object"))
      else {
        val declaredId = Option(node.get("client_id")).filter(_.isTextual).map(_.asText())
        // A document that names a different client_id is describing someone
        // else, and treating it as this client's is how one client's redirect
        // list ends up authorising another's.
        if (declaredId.exists(_ != clientId))
          Left(ClientMetadataError.Rejected("client metadata declares a different client_id"))
        else {
          val uris = Option(node.get("redirect_uris")).filter(_.isArray)
            .map(_.elements().asScala.filter(_.isTextual).map(_.asText()).toSet)
            .getOrElse(Set.empty[String])
            .filter(u => u.startsWith("https://") || u.startsWith("http://localhost"))
          if (uris.isEmpty)
            Left(ClientMetadataError.Malformed("client metadata declares no usable redirect_uris"))
          else
            Right(OAuthClient(
              clientId = clientId,
              clientName = Option(node.get("client_name")).filter(_.isTextual)
                .map(_.asText()).filter(_.nonEmpty).getOrElse(clientId),
              redirectUris = uris))
        }
      }
    } catch {
      case _: Exception => Left(ClientMetadataError.Malformed("client metadata is not valid JSON"))
    }

  private def isLoopbackHost(host: String): Boolean =
    host != null && (host == "localhost" || host == "127.0.0.1" || host == "[::1]" || host == "::1")

}

package com.newsrx.mcpplugin.oauth

import java.io.{ByteArrayOutputStream, IOException, InputStream}
import java.net.{InetAddress, URL}
import java.nio.charset.StandardCharsets

import javax.net.ssl.HttpsURLConnection
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.HttpGet
import org.apache.http.config.{ConnectionConfig, MessageConstraints, RegistryBuilder}
import org.apache.http.conn.DnsResolver
import org.apache.http.conn.socket.{ConnectionSocketFactory, PlainConnectionSocketFactory}
import org.apache.http.conn.ssl.{DefaultHostnameVerifier, SSLConnectionSocketFactory}
import org.apache.http.impl.client.HttpClients
import org.apache.http.impl.conn.BasicHttpClientConnectionManager

/** What a pinned request came back with. */
final case class PinnedResponse(status: Int, body: String)

/**
 * One HTTP request to an address that was decided in advance.
 *
 * Why the address cannot simply be handed to a URL connection. Checking an
 * address and then passing a *name* checks nothing: the connection resolves
 * the name again, independently, and that second answer decides where the
 * request goes. Verifying afterwards is later still, because a response only
 * exists once the request has been sent — and for a server-side request
 * forgery, sending it is the whole of the damage.
 *
 * Neither `HttpURLConnection` nor `java.net.http.HttpClient` can be told which
 * address to use. Apache HttpClient can: its `DnsResolver` is consulted for
 * the route, so answering with the one address that passed the check is what
 * pins the destination. The name still travels in the request, in SNI and in
 * the certificate check.
 *
 * Why the message parsing is not written here. It was, and it was wrong in two
 * ways that a hand-written parser is exactly prone to: a body that stopped
 * short of its `Content-Length` was returned as though it were complete, and a
 * chunk size that did not parse was treated as the terminating zero chunk. In
 * both cases a truncated prefix of a document became a valid client
 * registration. A complete JSON prefix is not evidence that the HTTP message
 * finished (RFC 9112 §6.3, §7.1, §8). httpcore raises
 * `ConnectionClosedException` and `MalformedChunkCodingException` for these,
 * and it is already on GitBucket's runtime classpath, so this is `compileOnly`
 * and nothing is bundled.
 *
 * What is still deliberately not hand-written: certificate verification. The
 * socket factory is the JVM's default HTTPS factory with the default hostname
 * verifier, so the usual name check happens against the name rather than
 * against the pinned address. Pinning decides where the bytes go; it gives up
 * nothing about who must be at the other end.
 *
 * Redirects are not followed and no proxy is consulted — a proxied request
 * goes wherever the proxy decides, which would make destination control an
 * illusion.
 */
object PinnedConnection {

  /** Header bounds, restored explicitly after the move to a maintained stack. */
  private val MaxHeaderLine = 8 * 1024
  private val MaxHeaderCount = 100

  def fetch(url: URL, address: InetAddress, connectTimeoutMs: Int,
            readTimeoutMs: Int, maxBytes: Int): PinnedResponse = {
    val scheme = Option(url.getProtocol).map(_.toLowerCase).getOrElse("")

    if (scheme == "http" && !address.isLoopbackAddress)
      throw new IOException("plain http is only reachable on a loopback address")
    if (scheme != "http" && scheme != "https")
      throw new IOException(s"unsupported scheme: $scheme")

    // The pin. Every lookup on this client's route answers with the address
    // that was checked, so the socket cannot be opened anywhere else.
    val pinned = new DnsResolver {
      def resolve(host: String): Array[InetAddress] = Array(address)
    }

    // The JVM's default HTTPS factory, so a deployment's own trust
    // configuration applies unchanged, with the default hostname verifier.
    val https = new SSLConnectionSocketFactory(
      HttpsURLConnection.getDefaultSSLSocketFactory, new DefaultHostnameVerifier)
    val registry = RegistryBuilder.create[ConnectionSocketFactory]()
      .register("http", PlainConnectionSocketFactory.getSocketFactory)
      .register("https", https)
      .build()

    val manager = new BasicHttpClientConnectionManager(registry, null, null, pinned)

    // Explicit, finite header bounds. The connection manager's default
    // message constraints are unlimited, which quietly dropped the limits the
    // previous transport had: a single 1 MiB header was accepted. These two
    // are what the maintained stack offers, and together they bound the whole
    // header block -- at most MaxHeaderCount lines of at most MaxHeaderLine
    // bytes each.
    manager.setConnectionConfig(ConnectionConfig.custom()
      .setMessageConstraints(MessageConstraints.custom()
        .setMaxLineLength(MaxHeaderLine)
        .setMaxHeaderCount(MaxHeaderCount)
        .build())
      .build())

    val config = RequestConfig.custom()
      .setConnectTimeout(connectTimeoutMs)
      .setConnectionRequestTimeout(connectTimeoutMs)
      .setSocketTimeout(readTimeoutMs)
      .setRedirectsEnabled(false)
      .setCircularRedirectsAllowed(false)
      .setProxy(null)
      .build()

    // Not `useSystemProperties()`: that would pick up a proxy from the
    // environment and send the request through it.
    val client = HttpClients.custom()
      .setConnectionManager(manager)
      .setDefaultRequestConfig(config)
      .disableRedirectHandling()
      .disableAutomaticRetries()
      .disableCookieManagement()
      .disableAuthCaching()
      .build()

    val get = new HttpGet(url.toURI)
    try {
      val response = client.execute(get)
      var finished = false
      try {
        val status = response.getStatusLine.getStatusCode
        val entity = response.getEntity
        val body =
          if (entity == null) ""
          else readBounded(entity.getContent, maxBytes)
        finished = true
        PinnedResponse(status, body)
      } finally {
        // Closing an entity stream that has not been read to its end makes
        // Apache consume the rest of the framed body first, so a peer that
        // keeps supplying a tail holds the fetch open long after the cap was
        // exceeded -- which is exactly what the cap exists to prevent.
        // Aborting discards the connection instead, with nothing drained.
        if (!finished) try get.abort() catch { case _: Exception => () }
        try response.close() catch { case _: Exception => () }
      }
    } catch {
      case e: IOException => throw e
      case e: Exception   => throw new IOException(e.getMessage, e)
    } finally {
      try client.close() catch { case _: Exception => () }
      try manager.close() catch { case _: Exception => () }
    }
  }

  /**
   * Read the whole body, refusing one that is too large.
   *
   * Refusing rather than truncating: a cap that silently returns a prefix is
   * the same defect as accepting a short body, one step removed. The stream is
   * read to its end, so httpcore's own framing checks run — a body that stops
   * early raises there rather than arriving here as a short string.
   *
   * The stream is deliberately not closed here. On the lawful path it has
   * already reached its end and the response close releases it; on overflow
   * the caller aborts the request instead, because closing a half-read entity
   * drains the remainder first.
   */
  private def readBounded(in: InputStream, maxBytes: Int): String = {
    val out = new ByteArrayOutputStream()
    val buf = new Array[Byte](8192)
    var n = in.read(buf)
    while (n >= 0) {
      if (out.size() + n > maxBytes)
        throw new IOException(s"client metadata is larger than $maxBytes bytes")
      out.write(buf, 0, n)
      n = in.read(buf)
    }
    new String(out.toByteArray, StandardCharsets.UTF_8)
  }
}

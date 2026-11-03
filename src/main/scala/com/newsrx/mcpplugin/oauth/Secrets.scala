package com.newsrx.mcpplugin.oauth

import java.nio.charset.StandardCharsets
import java.security.{MessageDigest, SecureRandom}
import java.util.Base64

/**
 * Generating and comparing the credentials this server issues.
 *
 * Nothing novel: `SecureRandom` for entropy and SHA-256 for storage, both
 * from the JDK. The only decisions here are how much entropy and what gets
 * persisted, and the answer to the second is never the credential itself.
 */
object Secrets {

  private val random = new SecureRandom()

  /**
   * A new credential: 256 bits, base64url without padding.
   *
   * Authorization codes, access tokens and refresh tokens all use this. They
   * have very different lifetimes but the same requirement — unguessable —
   * and a shorter code would only save bytes in a URL nobody reads.
   */
  def newCredential(): String = {
    val bytes = new Array[Byte](32)
    random.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
  }

  /**
   * What goes in the database.
   *
   * A credential is stored as its SHA-256, so a reader of the table cannot
   * authenticate with what they find there. SHA-256 rather than a password
   * hash on purpose: these are 256-bit random values, not passwords, so there
   * is no dictionary to slow down, and a per-request bcrypt would be a
   * denial-of-service surface on the hot path.
   */
  def hash(credential: String): String = {
    val digest = MessageDigest.getInstance("SHA-256")
      .digest(credential.getBytes(StandardCharsets.UTF_8))
    digest.map("%02x".format(_)).mkString
  }

  /** Constant-time comparison, for values where timing could leak a secret. */
  def constantTimeEquals(a: String, b: String): Boolean =
    if (a == null || b == null) false
    else MessageDigest.isEqual(
      a.getBytes(StandardCharsets.UTF_8),
      b.getBytes(StandardCharsets.UTF_8))
}

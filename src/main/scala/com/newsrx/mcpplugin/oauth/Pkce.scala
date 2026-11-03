package com.newsrx.mcpplugin.oauth

import com.nimbusds.oauth2.sdk.pkce.{CodeChallenge, CodeChallengeMethod, CodeVerifier}

/**
 * Proof Key for Code Exchange (RFC 7636), S256 only.
 *
 * The computation and the verifier's syntax rules come from
 * `com.nimbusds:oauth2-oidc-sdk`, which GitBucket already carries on its
 * runtime classpath for its own OpenID Connect sign-in. This object is a thin
 * adapter: it decides the policy — S256 and nothing else — and converts the
 * library's exceptions into a boolean the caller can act on. The base64url
 * SHA-256 is the library's, not ours.
 *
 * The `plain` method is not accepted and not advertised. It protects against
 * nothing an attacker who can read the authorization response cannot also do,
 * and offering it invites a downgrade: a client that can be talked into
 * sending `code_challenge_method=plain` has no protection at all.
 */
object Pkce {

  /** The S256 challenge for a verifier, as RFC 7636 §4.2 defines it. */
  def challengeFor(verifier: String): String =
    CodeChallenge.compute(CodeChallengeMethod.S256, new CodeVerifier(verifier)).getValue

  /**
   * Whether `verifier` is syntactically a code verifier: 43–128 characters
   * from the unreserved set. Nimbus enforces this in `CodeVerifier`'s
   * constructor.
   */
  def isWellFormedVerifier(verifier: String): Boolean =
    try { new CodeVerifier(verifier); true }
    catch { case _: IllegalArgumentException | _: NullPointerException => false }

  /** Whether `verifier` proves possession of the key behind `challenge`. */
  def verify(verifier: String, challenge: String): Boolean =
    try {
      if (challenge == null || challenge.isEmpty) false
      else {
        val computed = CodeChallenge.compute(CodeChallengeMethod.S256, new CodeVerifier(verifier))
        // Nimbus's CodeChallenge equality is a plain String comparison, so the
        // constant-time one is ours. A challenge is not secret, which makes
        // this belt-and-braces rather than load-bearing; the same routine is
        // used below for values that are secret, and one comparison is easier
        // to keep right than two.
        Secrets.constantTimeEquals(computed.getValue, challenge)
      }
    } catch {
      case _: IllegalArgumentException | _: NullPointerException => false
    }

  /** The method this server accepts, for the metadata document. */
  val supportedMethod: CodeChallengeMethod = CodeChallengeMethod.S256
}

package com.newsrx.mcpplugin.oauth

/**
 * The reasons this resource server refuses a bearer token (RFC 6750 §3.1).
 *
 * A closed set, with server-authored text. Error descriptions are the one
 * place where caller-controlled bytes are most tempting to echo — "token
 * `<what they sent>` is malformed" reads helpfully — and the one place where
 * echoing them puts attacker text into a response header, a proxy log and
 * whatever parses either.
 *
 * It is also deliberately uninformative about *why* a token failed. A caller
 * learns that the token is not usable, not whether it was unknown, expired,
 * bound to another resource or revoked; those differences are useful to
 * someone probing with tokens they should not have.
 */
sealed abstract class ChallengeError(val code: String, val description: String, val status: Int)

object ChallengeError {

  /** No credentials at all: not an error, just an invitation to authenticate. */
  case object None_ extends ChallengeError("", "", 401)

  case object InvalidRequest
    extends ChallengeError("invalid_request", "malformed authorization header", 400)

  /** Covers unknown, expired, revoked and wrong-audience alike, on purpose. */
  case object InvalidToken
    extends ChallengeError("invalid_token", "the access token is not valid", 401)

  case object InsufficientScope
    extends ChallengeError("insufficient_scope", "the access token does not grant the required scope", 403)
}

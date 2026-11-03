package com.newsrx.mcpplugin.oauth

import java.sql.{Connection, Timestamp}
import java.time.Instant

/**
 * What a token resolves to: a live consent, by a known account, for a known
 * client and resource, carrying a scope set.
 */
final case class ResolvedGrant(
  grantId: String,
  userName: String,
  clientId: String,
  resource: String,
  scopes: Set[String]
)

/** Why an exchange or a token presentation was refused. */
sealed trait GrantFailure
object GrantFailure {
  case object NotFound extends GrantFailure
  case object Expired extends GrantFailure
  case object Revoked extends GrantFailure
  case object AlreadyUsed extends GrantFailure
  case object ClientMismatch extends GrantFailure
  case object RedirectMismatch extends GrantFailure
  case object ResourceMismatch extends GrantFailure
  case object PkceFailed extends GrantFailure
}

/**
 * Persistence for OAuth grants, codes and tokens.
 *
 * Plain JDBC rather than Slick, deliberately. Every security-relevant
 * operation here is a conditional write whose row count is the answer —
 * "consume this code if it is still unconsumed and unexpired" — and the
 * guarantee depends on that being one statement. Written as SQL it is
 * visible; generated, it would be something to re-derive from the query
 * builder every time the question comes up.
 *
 * The SQL is ANSI and uses no vendor features, so the same statements run on
 * the H2 and PostgreSQL backends GitBucket supports.
 */
object OAuthStore {

  // ── grants ────────────────────────────────────────────────────────────────

  /**
   * Record a consent decision and return its id.
   *
   * A fresh grant per authorization rather than one updated in place: two
   * concurrent authorizations by the same user for the same client are then
   * two independent consents, and revoking one does not silently revoke the
   * other.
   */
  def createGrant(conn: Connection, userName: String, clientId: String,
                  resource: String, scopes: Set[String], now: Instant): String = {
    val grantId = Secrets.newCredential()
    val sql =
      """INSERT INTO MCP_OAUTH_GRANT
        |(GRANT_ID, USER_NAME, CLIENT_ID, RESOURCE, SCOPES, REVOKED, CREATED_AT, UPDATED_AT)
        |VALUES (?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin
    using(conn.prepareStatement(sql)) { st =>
      st.setString(1, grantId)
      st.setString(2, userName)
      st.setString(3, clientId)
      st.setString(4, resource)
      st.setString(5, McpScope.render(scopes))
      st.setBoolean(6, false)
      st.setTimestamp(7, Timestamp.from(now))
      st.setTimestamp(8, Timestamp.from(now))
      st.executeUpdate()
    }
    grantId
  }

  def findGrant(conn: Connection, grantId: String): Option[ResolvedGrant] =
    using(conn.prepareStatement(
      "SELECT USER_NAME, CLIENT_ID, RESOURCE, SCOPES FROM MCP_OAUTH_GRANT WHERE GRANT_ID = ? AND REVOKED = ?")) { st =>
      st.setString(1, grantId)
      st.setBoolean(2, false)
      using(st.executeQuery()) { rs =>
        if (rs.next())
          Some(ResolvedGrant(grantId, rs.getString(1), rs.getString(2), rs.getString(3),
            McpScope.parseRequested(rs.getString(4))))
        else None
      }
    }

  /**
   * Revoke a grant.
   *
   * This is the single revocation primitive. Revoking consent, revoking a
   * refresh token and detecting a replayed refresh token all end here,
   * because they all mean the same thing: this consent is over. Access tokens
   * are not deleted — they are checked against their grant on every request,
   * so a revoked grant stops them at the next authorization check without a
   * second write to race with.
   */
  def revokeGrant(conn: Connection, grantId: String, now: Instant): Boolean =
    using(conn.prepareStatement(
      "UPDATE MCP_OAUTH_GRANT SET REVOKED = ?, UPDATED_AT = ? WHERE GRANT_ID = ? AND REVOKED = ?")) { st =>
      st.setBoolean(1, true)
      st.setTimestamp(2, Timestamp.from(now))
      st.setString(3, grantId)
      st.setBoolean(4, false)
      st.executeUpdate() == 1
    }

  /** Every live grant for an account, for a consent-management view. */
  def grantsFor(conn: Connection, userName: String): List[ResolvedGrant] =
    using(conn.prepareStatement(
      "SELECT GRANT_ID, CLIENT_ID, RESOURCE, SCOPES FROM MCP_OAUTH_GRANT WHERE USER_NAME = ? AND REVOKED = ? ORDER BY CREATED_AT")) { st =>
      st.setString(1, userName)
      st.setBoolean(2, false)
      using(st.executeQuery()) { rs =>
        val out = List.newBuilder[ResolvedGrant]
        while (rs.next())
          out += ResolvedGrant(rs.getString(1), userName, rs.getString(2), rs.getString(3),
            McpScope.parseRequested(rs.getString(4)))
        out.result()
      }
    }

  // ── authorization codes ───────────────────────────────────────────────────

  def issueCode(conn: Connection, grantId: String, codeChallenge: String,
                redirectUri: String, expiresAt: Instant): String = {
    val code = Secrets.newCredential()
    using(conn.prepareStatement(
      """INSERT INTO MCP_OAUTH_CODE (CODE_HASH, GRANT_ID, CODE_CHALLENGE, REDIRECT_URI, EXPIRES_AT, CONSUMED)
        |VALUES (?, ?, ?, ?, ?, ?)""".stripMargin)) { st =>
      st.setString(1, Secrets.hash(code))
      st.setString(2, grantId)
      st.setString(3, codeChallenge)
      st.setString(4, redirectUri)
      st.setTimestamp(5, Timestamp.from(expiresAt))
      st.setBoolean(6, false)
      st.executeUpdate()
    }
    code
  }

  /**
   * Redeem an authorization code, once.
   *
   * The consumption is a conditional UPDATE whose row count decides the
   * winner, so two simultaneous exchanges of the same code produce exactly
   * one token and one `AlreadyUsed`. Checking a flag and then writing it
   * would leave a window between the two in which both callers see an unused
   * code; there is no such window here, on either backend, because the
   * database serialises the update on the row.
   *
   * Everything the code was bound to is verified: the client that is asking,
   * the redirect URI it claims, the resource it wants, and possession of the
   * PKCE verifier. A code is not a bearer credential on its own.
   */
  def redeemCode(conn: Connection, code: String, clientId: String, redirectUri: String,
                 resource: Option[String], codeVerifier: String,
                 now: Instant): Either[GrantFailure, ResolvedGrant] = {
    val hash = Secrets.hash(code)
    val row = using(conn.prepareStatement(
      """SELECT c.GRANT_ID, c.CODE_CHALLENGE, c.REDIRECT_URI, c.EXPIRES_AT, c.CONSUMED,
        |       g.USER_NAME, g.CLIENT_ID, g.RESOURCE, g.SCOPES, g.REVOKED
        |  FROM MCP_OAUTH_CODE c JOIN MCP_OAUTH_GRANT g ON g.GRANT_ID = c.GRANT_ID
        | WHERE c.CODE_HASH = ?""".stripMargin)) { st =>
      st.setString(1, hash)
      using(st.executeQuery()) { rs =>
        if (!rs.next()) None
        else Some((rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant,
          rs.getBoolean(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
          rs.getBoolean(10)))
      }
    }

    row match {
      case None => Left(GrantFailure.NotFound)
      case Some((grantId, challenge, storedRedirect, expiresAt, consumed, userName, grantClient,
                 grantResource, scopes, revoked)) =>
        // Bindings first, and before anything is written.
        //
        // Revoking on a replay only makes sense for a presentation that could
        // have come from the legitimate client. Checking `consumed` first gave
        // that authority to anyone holding the code: a wrong client id, a
        // wrong redirect, a wrong resource or a wrong PKCE verifier all
        // reached the revocation and destroyed a live grant. Possession of a
        // spent code is not evidence, and an unauthenticated caller must not
        // be able to end someone's session with it.
        if (revoked) Left(GrantFailure.Revoked)
        else if (!Secrets.constantTimeEquals(clientId, grantClient)) Left(GrantFailure.ClientMismatch)
        else if (!Secrets.constantTimeEquals(redirectUri, storedRedirect)) Left(GrantFailure.RedirectMismatch)
        else if (resource.exists(r => !Secrets.constantTimeEquals(r, grantResource)))
          Left(GrantFailure.ResourceMismatch)
        else if (!Pkce.verify(codeVerifier, challenge)) Left(GrantFailure.PkceFailed)
        // Expiry is not a replay. An expired code buys nothing, but it is also
        // not evidence that anything was stolen, so it does not revoke.
        else if (!expiresAt.isAfter(now)) Left(GrantFailure.Expired)
        else if (consumed) replayed(conn, grantId, now)
        else if (consumeCode(conn, hash, now))
          Right(ResolvedGrant(grantId, userName, grantClient, grantResource,
            McpScope.parseRequested(scopes)))
        // The claim was lost. Everything above says this presentation was
        // correctly bound, so whatever took the code did so in a race with a
        // request that could have been the legitimate one — the same
        // situation as an outright replay, and it gets the same policy. The
        // row is re-read rather than assumed, so a claim that failed for
        // another reason is not reported as a replay.
        else classifyLostClaim(conn, hash, grantId, now)
    }
  }

  /** The documented reuse policy: the family goes, and the caller is told. */
  private def replayed(conn: Connection, grantId: String,
                       now: Instant): Either[GrantFailure, ResolvedGrant] = {
    // RFC 6749 §10.5 and the OAuth 2.1 guidance: a code presented twice may
    // have been stolen, so what it bought is withdrawn.
    revokeGrant(conn, grantId, now)
    Left(GrantFailure.AlreadyUsed)
  }

  /** Why did the conditional claim match no row? */
  private def classifyLostClaim(conn: Connection, codeHash: String, grantId: String,
                                now: Instant): Either[GrantFailure, ResolvedGrant] =
    using(conn.prepareStatement(
      "SELECT CONSUMED, EXPIRES_AT FROM MCP_OAUTH_CODE WHERE CODE_HASH = ?")) { st =>
      st.setString(1, codeHash)
      using(st.executeQuery()) { rs =>
        if (!rs.next()) Left(GrantFailure.NotFound)
        else if (rs.getBoolean(1)) replayed(conn, grantId, now)
        else if (!rs.getTimestamp(2).toInstant.isAfter(now)) Left(GrantFailure.Expired)
        else Left(GrantFailure.NotFound)
      }
    }

  /** The atomic step: true for exactly one caller. */
  private def consumeCode(conn: Connection, codeHash: String, now: Instant): Boolean =
    using(conn.prepareStatement(
      "UPDATE MCP_OAUTH_CODE SET CONSUMED = ? WHERE CODE_HASH = ? AND CONSUMED = ? AND EXPIRES_AT > ?")) { st =>
      st.setBoolean(1, true)
      st.setString(2, codeHash)
      st.setBoolean(3, false)
      st.setTimestamp(4, Timestamp.from(now))
      st.executeUpdate() == 1
    }

  // ── access tokens ─────────────────────────────────────────────────────────

  def issueAccessToken(conn: Connection, grantId: String, expiresAt: Instant, now: Instant): String = {
    val token = Secrets.newCredential()
    using(conn.prepareStatement(
      "INSERT INTO MCP_OAUTH_TOKEN (TOKEN_HASH, GRANT_ID, EXPIRES_AT, CREATED_AT) VALUES (?, ?, ?, ?)")) { st =>
      st.setString(1, Secrets.hash(token))
      st.setString(2, grantId)
      st.setTimestamp(3, Timestamp.from(expiresAt))
      st.setTimestamp(4, Timestamp.from(now))
      st.executeUpdate()
    }
    token
  }

  /**
   * Resolve a presented access token.
   *
   * One query, joined to the grant, so revocation takes effect here rather
   * than depending on a sweep having run. The guarantee this provides is
   * about the *authorization check*: after a grant is revoked, no later check
   * passes. It says nothing about an operation already past its check and
   * executing — that one finishes.
   */
  def resolveAccessToken(conn: Connection, token: String, now: Instant): Either[GrantFailure, ResolvedGrant] =
    using(conn.prepareStatement(
      """SELECT t.GRANT_ID, t.EXPIRES_AT, g.USER_NAME, g.CLIENT_ID, g.RESOURCE, g.SCOPES, g.REVOKED
        |  FROM MCP_OAUTH_TOKEN t JOIN MCP_OAUTH_GRANT g ON g.GRANT_ID = t.GRANT_ID
        | WHERE t.TOKEN_HASH = ?""".stripMargin)) { st =>
      st.setString(1, Secrets.hash(token))
      using(st.executeQuery()) { rs =>
        if (!rs.next()) Left(GrantFailure.NotFound)
        else if (rs.getBoolean(7)) Left(GrantFailure.Revoked)
        else if (!rs.getTimestamp(2).toInstant.isAfter(now)) Left(GrantFailure.Expired)
        else Right(ResolvedGrant(rs.getString(1), rs.getString(3), rs.getString(4),
          rs.getString(5), McpScope.parseRequested(rs.getString(6))))
      }
    }

  // ── refresh tokens ────────────────────────────────────────────────────────

  def setRefreshToken(conn: Connection, grantId: String, expiresAt: Instant, now: Instant): String = {
    val refresh = Secrets.newCredential()
    insertRefresh(conn, Secrets.hash(refresh), grantId, expiresAt, now)
    refresh
  }

  private def insertRefresh(conn: Connection, hash: String, grantId: String,
                            expiresAt: Instant, now: Instant): Unit =
    using(conn.prepareStatement(
      "INSERT INTO MCP_OAUTH_REFRESH (TOKEN_HASH, GRANT_ID, EXPIRES_AT, ROTATED, CREATED_AT) VALUES (?, ?, ?, ?, ?)")) { st =>
      st.setString(1, hash)
      st.setString(2, grantId)
      st.setTimestamp(3, Timestamp.from(expiresAt))
      st.setBoolean(4, false)
      st.setTimestamp(5, Timestamp.from(now))
      st.executeUpdate()
    }

  /**
   * Rotate a refresh token: the presented one stops working as the new one
   * starts.
   *
   * Rotation is a conditional UPDATE marking the presented row, so concurrent
   * refreshes resolve to exactly one winner. The loser is not merely refused.
   * A token that is present but already rotated is either a race or a stolen
   * copy being replayed, and from the server's side those are
   * indistinguishable, so the grant is revoked — which is what OAuth 2.1 asks
   * for, and why a well-behaved client must not refresh twice in parallel.
   *
   * A binding failure is a different thing from a replay and is handled
   * before any of that: the bindings are checked first, and a presentation
   * that fails one neither rotates the token nor revokes the grant.
   *
   * The superseded rows are kept rather than deleted. That is the point: a
   * grant that remembered only its current refresh hash could not tell a
   * replayed old token from a string that was never issued, and would answer
   * "unknown" to the one case that should end the session. An earlier version
   * of this code did exactly that, and the concurrency test above is what
   * found it.
   */
  /**
   * Exchange a refresh token for its successor.
   *
   * `resource` is the audience this server currently is; `requestedResource`
   * is what the client asked for, when it asked. Both are bindings, checked
   * like the client id and before anything is written: a grant minted for
   * another deployment is not a credential here, however valid it is there.
   */
  def rotateRefreshToken(conn: Connection, presented: String, clientId: String,
                         expiresAt: Instant, now: Instant,
                         resource: Option[String] = None,
                         requestedResource: Option[String] = None
                        ): Either[GrantFailure, (ResolvedGrant, String)] = {
    val presentedHash = Secrets.hash(presented)

    // The bindings are checked before anything is written. A refresh token
    // presented with the wrong client id is a request this server will not
    // answer, but it is not evidence about the token: anyone can send a
    // wrong client id, and rotating or revoking on that would let an
    // unauthenticated caller destroy a credential the legitimate client is
    // still using. Only a correctly bound presentation moves the chain.
    val binding = refreshRow(conn, presentedHash) match {
      case None => Left(GrantFailure.NotFound)
      case Some((grantId, _, _)) =>
        findGrant(conn, grantId) match {
          case None => Left(GrantFailure.Revoked)
          case Some(grant) if !Secrets.constantTimeEquals(clientId, grant.clientId) =>
            Left(GrantFailure.ClientMismatch)
          case Some(grant) if resource.exists(r => !Secrets.constantTimeEquals(r, grant.resource)) =>
            // The grant belongs to a different audience than this server is.
            Left(GrantFailure.ResourceMismatch)
          case Some(grant) if requestedResource.exists(r => !Secrets.constantTimeEquals(r, grant.resource)) =>
            Left(GrantFailure.ResourceMismatch)
          case Some(grant) => Right(grant)
        }
    }

    binding.flatMap { grant =>
      // The single atomic claim: at most one caller can take this token's
      // rotation, whatever the scheduling.
      val rotated = using(conn.prepareStatement(
        "UPDATE MCP_OAUTH_REFRESH SET ROTATED = ? WHERE TOKEN_HASH = ? AND ROTATED = ? AND EXPIRES_AT > ?")) { st =>
        st.setBoolean(1, true)
        st.setString(2, presentedHash)
        st.setBoolean(3, false)
        st.setTimestamp(4, Timestamp.from(now))
        st.executeUpdate() == 1
      }

      if (rotated) {
        val replacement = Secrets.newCredential()
        insertRefresh(conn, Secrets.hash(replacement), grant.grantId, expiresAt, now)
        Right((grant, replacement))
      } else {
        // Read after the failed claim, never before: a concurrent winner that
        // commits between a read and the claim would otherwise make a replay
        // look like an expiry. That gap made the concurrency control
        // intermittently disagree with itself, which is how it was found.
        refreshRow(conn, presentedHash) match {
          // Present but not rotatable: spent, expired, or both. A replay ends
          // the grant; an expiry does not, because it is the client being slow
          // rather than a credential being somewhere it should not be.
          //
          // Both at once is the case that was wrong. A superseded token whose
          // own expiry has since passed was treated as a replay and revoked
          // the family — so an old token, useless to whoever held it, could
          // still destroy the successor the legitimate client was using. Its
          // expiry is checked first: once a credential is dead of old age,
          // presenting it is not evidence of anything.
          case Some((id, rotated, expires)) =>
            if (!expires.isAfter(now)) Left(GrantFailure.Expired)
            else if (rotated) {
              revokeGrant(conn, id, now)
              Left(GrantFailure.AlreadyUsed)
            } else Left(GrantFailure.NotFound)
          case None => Left(GrantFailure.NotFound)
        }
      }
    }
  }

  /** The grant a refresh token belongs to, and whether it has been rotated. */
  /** The grant a refresh row belongs to, whether it is spent, and when it dies. */
  private def refreshRow(conn: Connection, hash: String): Option[(String, Boolean, Instant)] =
    using(conn.prepareStatement(
      "SELECT GRANT_ID, ROTATED, EXPIRES_AT FROM MCP_OAUTH_REFRESH WHERE TOKEN_HASH = ?")) { st =>
      st.setString(1, hash)
      using(st.executeQuery())(rs =>
        if (rs.next()) Some((rs.getString(1), rs.getBoolean(2), rs.getTimestamp(3).toInstant))
        else None)
    }

  private def refreshGrantId(conn: Connection, hash: String): Option[String] =
    refreshRow(conn, hash).map(_._1)

  /** RFC 7009: revoke by either credential type; both end the grant. */
  def revokeByToken(conn: Connection, token: String, now: Instant): Boolean = {
    val hash = Secrets.hash(token)
    val byAccess = using(conn.prepareStatement(
      "SELECT GRANT_ID FROM MCP_OAUTH_TOKEN WHERE TOKEN_HASH = ?")) { st =>
      st.setString(1, hash)
      using(st.executeQuery())(rs => if (rs.next()) Some(rs.getString(1)) else None)
    }
    byAccess.orElse(refreshGrantId(conn, hash))
      .exists(revokeGrant(conn, _, now))
  }

  // ── housekeeping ──────────────────────────────────────────────────────────

  /**
   * Delete expired codes and tokens.
   *
   * Hygiene, not a security control: nothing here is what makes an expired
   * credential stop working, because every read already compares against the
   * clock. If this never runs, the tables grow and the system stays correct.
   */
  def purgeExpired(conn: Connection, now: Instant): Int = {
    val cutoff = Timestamp.from(now)
    val codes = using(conn.prepareStatement("DELETE FROM MCP_OAUTH_CODE WHERE EXPIRES_AT < ?")) { st =>
      st.setTimestamp(1, cutoff); st.executeUpdate()
    }
    val tokens = using(conn.prepareStatement("DELETE FROM MCP_OAUTH_TOKEN WHERE EXPIRES_AT < ?")) { st =>
      st.setTimestamp(1, cutoff); st.executeUpdate()
    }
    val refresh = using(conn.prepareStatement("DELETE FROM MCP_OAUTH_REFRESH WHERE EXPIRES_AT < ?")) { st =>
      st.setTimestamp(1, cutoff); st.executeUpdate()
    }
    codes + tokens + refresh
  }

  private def using[A <: AutoCloseable, B](resource: A)(body: A => B): B =
    try body(resource) finally resource.close()
}

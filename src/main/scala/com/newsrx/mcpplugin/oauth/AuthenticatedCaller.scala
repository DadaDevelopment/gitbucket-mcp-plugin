package com.newsrx.mcpplugin.oauth

import gitbucket.core.model.Account

/**
 * Who is calling, and what they were allowed to ask for.
 *
 * Both authentication methods end here, which is the point: everything
 * downstream sees one shape and cannot accidentally treat an OAuth caller as
 * unrestricted by forgetting to look at a scope field that only one branch
 * populates.
 */
final case class AuthenticatedCaller(
  account: Account,
  scopes: Set[String],
  /** Present only for OAuth callers; a Basic caller holds no grant. */
  grantId: Option[String]
) {
  def permits(required: McpScope): Boolean = required.satisfiedBy(scopes)
}

object AuthenticatedCaller {

  /**
   * A caller who presented the account's own password or personal access
   * token.
   *
   * These credentials are the account, not a delegation of part of it, so
   * there is no narrower scope to apply and every scope is held. This is what
   * keeps the existing Basic connection working exactly as it did: adding
   * OAuth must not quietly restrict a client that was never scoped.
   */
  def basic(account: Account): AuthenticatedCaller =
    AuthenticatedCaller(account, McpScope.all.map(_.name).toSet, None)

  /** A caller presenting an access token, limited to what was consented to. */
  def oauth(account: Account, grant: ResolvedGrant): AuthenticatedCaller =
    AuthenticatedCaller(account, grant.scopes, Some(grant.grantId))
}

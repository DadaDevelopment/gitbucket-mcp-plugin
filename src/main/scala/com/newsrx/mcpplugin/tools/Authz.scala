package com.newsrx.mcpplugin.tools

import gitbucket.core.model.Account
import gitbucket.core.service.RepositoryService
import gitbucket.core.service.RepositoryService.RepositoryInfo
// The Session type GitBucket's services declare, not `slick.jdbc.JdbcBackend.Session`.
// The latter is the Session of the `JdbcBackend` *object*; the services use the
// profile's path-dependent `backend.Session`. An object Session conforms to it
// when passed, which is why production compiled, but a method declared with the
// narrower type overrides nothing — that is what broke the stubs in the tests.
import gitbucket.core.model.Profile.profile.blockingApi.Session

/**
 * Raised when the authenticated caller may not perform the requested
 * operation on the named repository. Distinct from `NoSuchElementException`
 * so the controller can map it to its own JSON-RPC error code.
 */
class AuthorizationError(message: String) extends RuntimeException(message)

/**
 * Repository authorization for MCP tool execution.
 *
 * Authentication alone is not authorization. Before this trait existed, every
 * tool resolved `owner`/`repo` straight from the request arguments and read or
 * wrote it, so any account that could authenticate to the MCP endpoint could
 * reach any repository on the instance, private ones included. These guards
 * put each tool back under the same rules GitBucket's own controllers apply.
 *
 * The rules are GitBucket's, not reimplemented here: the role predicates come
 * from `gitbucket.core.service.RepositoryService` (4.48.0,
 * `hasGuestRole`/`hasDeveloperRole`/`hasOwnerRole`), and the read rule matches
 * what GitBucket's `ReferrerAuthenticator` enforces — a public repository is
 * readable by any authenticated account, a private one only by an account with
 * at least the guest role.
 */
trait RepositoryAuthorization { self: RepositoryService =>

  /**
   * The repository, if the caller may read it.
   *
   * A repository that exists but is not readable and one that does not exist
   * are reported the same way on purpose: answering "forbidden" for a private
   * repository would confirm its existence to a caller who may not see it.
   */
  protected def readableRepository(owner: String, repo: String, account: Account)(implicit
    s: Session
  ): RepositoryInfo =
    getRepository(owner, repo) match {
      case Some(info) if canRead(info, account) => info
      case _ =>
        throw new NoSuchElementException(s"repository $owner/$repo not found or not readable")
    }

  /** The repository, if the caller may write to it (developer role or above). */
  protected def writableRepository(owner: String, repo: String, account: Account)(implicit
    s: Session
  ): RepositoryInfo = {
    val info = readableRepository(owner, repo, account)
    if (!hasDeveloperRole(owner, repo, Some(account))) {
      throw new AuthorizationError(
        s"${account.userName} does not have write access to $owner/$repo"
      )
    }
    info
  }

  /** True when `account` may read `info`. */
  protected def canRead(info: RepositoryInfo, account: Account)(implicit s: Session): Boolean =
    !info.repository.isPrivate ||
      hasGuestRole(info.repository.userName, info.repository.repositoryName, Some(account))

  /**
   * Whether `account` may open an issue in `info`, under the repository's own
   * issue policy.
   *
   * Read access is not the rule GitBucket applies here. `IssueCreationService.
   * isIssueEditable` (4.48.0) branches on `options.issuesOption`, and the
   * native creation route consults it before writing. Reading alone therefore
   * admitted three cases the server itself refuses: a guest of a PRIVATE-issue
   * repository, any reader of a DISABLE repository, and an authenticated
   * outsider of a PUBLIC-issue repository.
   *
   * The branch table is reproduced rather than reused because the native
   * predicate needs a `Context` the tool layer does not have. The role
   * predicates underneath are still GitBucket's.
   */
  protected def canCreateIssue(info: RepositoryInfo, account: Account)(implicit s: Session): Boolean = {
    val owner = info.repository.userName
    val name = info.repository.repositoryName
    info.repository.options.issuesOption match {
      // ALL: an authenticated caller, but only where the repository is public.
      case "ALL"     => !info.repository.isPrivate
      case "PUBLIC"  => hasGuestRole(owner, name, Some(account))
      case "PRIVATE" => hasDeveloperRole(owner, name, Some(account))
      case "DISABLE" => false
      // An unknown setting is refused rather than guessed: a future or
      // corrupted value must not open the route by default.
      case _         => false
    }
  }

  /**
   * The repository, if the caller may open an issue in it.
   *
   * Readability is checked first and reported as a missing repository, so a
   * policy refusal cannot confirm that a private repository exists.
   */
  protected def issuableRepository(owner: String, repo: String, account: Account)(implicit
    s: Session
  ): RepositoryInfo = {
    val info = readableRepository(owner, repo, account)
    if (!canCreateIssue(info, account)) {
      throw new AuthorizationError(
        s"${account.userName} may not open an issue in $owner/$repo under its " +
          s"issues policy (${info.repository.options.issuesOption})"
      )
    }
    info
  }

  /**
   * Developer role on an arbitrary repository, used where the authorized
   * repository is not the one named in the arguments. GitBucket's own
   * `update_draft` route checks the *head* repository of a pull request, not
   * the base one, and this exists so that check can be reproduced exactly
   * rather than approximated.
   */
  protected def requireDeveloperRole(owner: String, repo: String, account: Account)(implicit
    s: Session
  ): Unit =
    if (!hasDeveloperRole(owner, repo, Some(account))) {
      throw new AuthorizationError(
        s"${account.userName} does not have developer access to $owner/$repo"
      )
    }
}

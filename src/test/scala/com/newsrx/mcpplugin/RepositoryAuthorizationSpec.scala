package com.newsrx.mcpplugin

import com.newsrx.mcpplugin.tools.{AuthorizationError, RepositoryAuthorization}
import gitbucket.core.model.{Account, Repository, RepositoryOptions}
import gitbucket.core.service.{AccountService, RepositoryService}
import gitbucket.core.service.RepositoryService.RepositoryInfo
import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import gitbucket.core.model.Profile.profile.blockingApi.Session

/**
 * Fixtures for exercising authorization without a database.
 *
 * The role predicates are GitBucket's and are stubbed here, so these controls
 * verify that the guard consults them and obeys their verdict, and that the
 * refusal shape is right. They do not re-verify GitBucket's own role
 * computation; that needs a live instance and is listed in the runbook.
 */
object AuthzFixtures {
  private val epoch = new java.util.Date(0L)

  private val options = RepositoryOptions(
    issuesOption = "PUBLIC",
    externalIssuesUrl = None,
    wikiOption = "PUBLIC",
    externalWikiUrl = None,
    allowFork = true,
    mergeOptions = "merge-commit",
    defaultMergeOption = "merge-commit",
    safeMode = false
  )

  def repository(owner: String, name: String, isPrivate: Boolean): Repository =
    Repository(
      userName = owner,
      repositoryName = name,
      isPrivate = isPrivate,
      description = None,
      defaultBranch = "main",
      registeredDate = epoch,
      updatedDate = epoch,
      lastActivityDate = epoch,
      originUserName = None,
      originRepositoryName = None,
      parentUserName = None,
      parentRepositoryName = None,
      options = options
    )

  /**
   * Named `repoInfo`, not `info`: a ScalaTest suite already inherits an
   * `info: Informer`, and wildcard-importing a fixture helper of the same
   * name loses to the inherited member, so the call resolved to `Informer`
   * and reported "unknown parameter name isPrivate".
   */
  def repoInfo(owner: String, name: String, isPrivate: Boolean): RepositoryInfo =
    RepositoryInfo(
      owner = owner,
      name = name,
      repository = repository(owner, name, isPrivate),
      issueCount = 0,
      pullCount = 0,
      forkedCount = 0,
      milestoneCount = 0,
      branchList = Seq("main"),
      tags = Nil,
      managers = Nil
    )

  def account(name: String, admin: Boolean = false): Account =
    Account(
      userName = name,
      fullName = name,
      mailAddress = s"$name@example.com",
      password = "",
      isAdmin = admin,
      url = None,
      registeredDate = epoch,
      updatedDate = epoch,
      lastLoginDate = None,
      image = None,
      isGroupAccount = false,
      isRemoved = false,
      description = None
    )
}

/**
 * A world of repositories and roles, with GitBucket's data access replaced.
 *
 * `guests` and `developers` name the accounts holding each role, so a test can
 * state a permission matrix directly. The session is never dereferenced: every
 * method that would touch it is overridden.
 */
class StubAuthz(
  repositories: Map[(String, String), RepositoryInfo],
  guests: Set[(String, String, String)] = Set.empty,
  developers: Set[(String, String, String)] = Set.empty
) extends RepositoryService
    // `RepositoryService` has a self-type of `AccountService`, so it must be
    // mixed in even though none of its queries run here.
    with AccountService
    with RepositoryAuthorization {

  override def getRepository(userName: String, repositoryName: String)(implicit
    s: Session
  ): Option[RepositoryInfo] =
    repositories.get((userName, repositoryName))

  override def hasGuestRole(owner: String, repository: String, loginAccount: Option[Account])(implicit
    s: Session
  ): Boolean =
    loginAccount.exists { a =>
      a.isAdmin || guests.contains((owner, repository, a.userName)) ||
      developers.contains((owner, repository, a.userName))
    }

  override def hasDeveloperRole(owner: String, repository: String, loginAccount: Option[Account])(implicit
    s: Session
  ): Boolean =
    loginAccount.exists(a => a.isAdmin || developers.contains((owner, repository, a.userName)))

  // Exposed so the controls can call the protected guards directly.
  def readable(owner: String, repo: String, account: Account)(implicit s: Session): RepositoryInfo =
    readableRepository(owner, repo, account)

  def writable(owner: String, repo: String, account: Account)(implicit s: Session): RepositoryInfo =
    writableRepository(owner, repo, account)

  def visible(info: RepositoryInfo, account: Account)(implicit s: Session): Boolean =
    canRead(info, account)

  def requireDeveloper(owner: String, repo: String, account: Account)(implicit s: Session): Unit =
    requireDeveloperRole(owner, repo, account)
}

@RunWith(classOf[JUnitRunner])
class RepositoryAuthorizationSpec extends AnyFunSuite with Matchers {

  import AuthzFixtures._

  private implicit val session: Session = null

  private val publicRepo = repoInfo("example-org", "public-repo", isPrivate = false)
  private val privateRepo = repoInfo("example-org", "private-repo", isPrivate = true)
  private val repos = Map(
    ("example-org", "public-repo") -> publicRepo,
    ("example-org", "private-repo") -> privateRepo
  )

  private val outsider = account("outsider")
  private val guest = account("guest")
  private val developer = account("developer")
  private val admin = account("admin", admin = true)

  private def world = new StubAuthz(
    repos,
    guests = Set(("example-org", "private-repo", "guest")),
    developers = Set(("example-org", "private-repo", "developer"), ("example-org", "public-repo", "developer"))
  )

  // ── read ──────────────────────────────────────────────────────────────────

  test("a public repository is readable by any authenticated account") {
    world.readable("example-org", "public-repo", outsider).name shouldBe "public-repo"
  }

  test("a private repository is not readable without a role") {
    a[NoSuchElementException] should be thrownBy world.readable("example-org", "private-repo", outsider)
  }

  test("a private repository is readable with the guest role") {
    world.readable("example-org", "private-repo", guest).name shouldBe "private-repo"
  }

  test("an admin can read a private repository") {
    world.readable("example-org", "private-repo", admin).name shouldBe "private-repo"
  }

  test("an inaccessible private repository is indistinguishable from a missing one") {
    // The point of the control: a caller who may not see the repository must
    // not be able to infer that it exists from a different error.
    val denied = the[NoSuchElementException] thrownBy world.readable("example-org", "private-repo", outsider)
    val missing = the[NoSuchElementException] thrownBy world.readable("example-org", "no-such-repo", outsider)
    denied.getClass shouldBe missing.getClass
    denied.getMessage.replace("private-repo", "X") shouldBe
      missing.getMessage.replace("no-such-repo", "X")
  }

  test("a denial does not leak the private flag or any repository detail") {
    val denied = the[NoSuchElementException] thrownBy world.readable("example-org", "private-repo", outsider)
    denied.getMessage.toLowerCase should not include "private=true"
    denied.getMessage.toLowerCase should not include "forbidden"
  }

  // ── write ─────────────────────────────────────────────────────────────────

  test("a guest cannot write to a repository they can read") {
    // Read access and write access are separate verdicts; conflating them is
    // how a reader would gain the ability to comment or close.
    world.readable("example-org", "private-repo", guest).name shouldBe "private-repo"
    an[AuthorizationError] should be thrownBy world.writable("example-org", "private-repo", guest)
  }

  test("a developer can write") {
    world.writable("example-org", "private-repo", developer).name shouldBe "private-repo"
  }

  test("an outsider writing to a public repository is refused as a write, not as a read") {
    // The repository is readable, so the failure must be the write verdict.
    world.readable("example-org", "public-repo", outsider).name shouldBe "public-repo"
    an[AuthorizationError] should be thrownBy world.writable("example-org", "public-repo", outsider)
  }

  test("a write to an unreadable repository is refused before the write verdict") {
    // Order matters: answering AuthorizationError here would confirm that the
    // private repository exists.
    a[NoSuchElementException] should be thrownBy world.writable("example-org", "private-repo", outsider)
  }

  // ── listing filter ────────────────────────────────────────────────────────

  test("canRead filters a listing to what the caller may see") {
    val w = world
    val all = Seq(publicRepo, privateRepo)
    all.filter(w.visible(_, outsider)).map(_.name) shouldBe Seq("public-repo")
    all.filter(w.visible(_, guest)).map(_.name) should contain theSameElementsAs Seq(
      "public-repo",
      "private-repo"
    )
    all.filter(w.visible(_, admin)).map(_.name) should have size 2
  }

  // ── explicit developer check on a named repository ────────────────────────

  test("requireDeveloperRole applies to the repository it is given, not the one being read") {
    // The readiness transition needs the developer role on the *head*
    // repository of a fork, which is a different repository from the base.
    val w = world
    noException should be thrownBy w.requireDeveloper("example-org", "private-repo", developer)
    an[AuthorizationError] should be thrownBy w.requireDeveloper("example-org", "private-repo", guest)
  }

  // ── sensitivity ───────────────────────────────────────────────────────────

  // An earlier version of this file ended with a "sensitivity" case that built
  // a variant whose read rule ignored privacy and then asserted that the
  // outsider got in. It was removed: a passing test whose assertion is that
  // unauthorized access succeeds reads as a sanctioned behaviour, and it
  // demonstrated nothing about the tools, only about a subclass written for
  // the occasion.
  //
  // What it was reaching for is now done against the real code, by mutating
  // the production source rather than a test double: see
  // `ToolExecutionSpec`, which drives the tools' own `execute`, and the
  // mutation record in `docs/validation-runbook.md`.
}

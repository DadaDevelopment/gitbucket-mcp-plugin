package com.newsrx.mcpplugin.tools

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.newsrx.mcpplugin.AuthzFixtures
import gitbucket.core.model.{Account, Issue, IssueComment, PullRequest, Repository, RepositoryOptions}
import gitbucket.core.service.RepositoryService.RepositoryInfo
import gitbucket.core.model.Profile.profile.blockingApi.Session
import javax.servlet.http.HttpServletRequest
import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable

/**
 * Operation-level controls: the real tools, their real `execute`, with
 * GitBucket's storage replaced.
 *
 * The helper suites exercise the guard and the argument parser in isolation,
 * which does not witness whether a tool actually calls them. These controls
 * do. Every case below goes through the production `execute` of the production
 * tool class, and every storage write is recorded, so "refused" can be told
 * apart from "refused after writing".
 *
 * This file lives in `com.newsrx.mcpplugin.tools` because the service traits
 * the tools are built from (`RepoService`, `IssueService`, `PrService`) are
 * `private[tools]`. Extending them rather than re-assembling the service list
 * keeps the stubs honest: a tool that gains a dependency gains it here too.
 *
 * Scope, stated plainly: GitBucket's role predicates and its persistence are
 * stubbed, so these controls establish tool wiring, policy branching and
 * before/after state. They do not re-verify GitBucket's own role computation
 * or its SQL. The live-instance matrix stays in `docs/validation-runbook.md`.
 */
private[tools] object ExecFixtures {
  val epoch = new java.util.Date(0L)

  def options(issuesOption: String): RepositoryOptions = RepositoryOptions(
    issuesOption = issuesOption,
    externalIssuesUrl = None,
    wikiOption = "PUBLIC",
    externalWikiUrl = None,
    allowFork = true,
    mergeOptions = "merge-commit",
    defaultMergeOption = "merge-commit",
    safeMode = false
  )

  def repoInfo(
    owner: String,
    name: String,
    isPrivate: Boolean = false,
    issuesOption: String = "PUBLIC"
  ): RepositoryInfo =
    RepositoryInfo(
      owner = owner,
      name = name,
      repository = Repository(
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
        options = options(issuesOption)
      ),
      issueCount = 0,
      pullCount = 0,
      forkedCount = 0,
      milestoneCount = 0,
      branchList = Seq("main"),
      tags = Nil,
      managers = Nil
    )

  def account(name: String, admin: Boolean = false): Account = AuthzFixtures.account(name, admin)

  def issue(
    owner: String,
    repo: String,
    number: Int,
    title: String = "title",
    body: Option[String] = Some("body"),
    closed: Boolean = false
  ): Issue =
    Issue(
      userName = owner,
      repositoryName = repo,
      issueId = number,
      openedUserName = "author",
      milestoneId = None,
      priorityId = None,
      title = title,
      content = body,
      closed = closed,
      registeredDate = epoch,
      updatedDate = epoch,
      isPullRequest = true
    )

  def pullRequest(
    baseOwner: String,
    baseRepo: String,
    number: Int,
    headOwner: String,
    headRepo: String,
    head: String = "a" * 40,
    isDraft: Boolean = true,
    merged: Option[String] = None
  ): PullRequest =
    PullRequest(
      userName = baseOwner,
      repositoryName = baseRepo,
      issueId = number,
      branch = "main",
      requestUserName = headOwner,
      requestRepositoryName = headRepo,
      requestBranch = "feature",
      commitIdFrom = "b" * 40,
      commitIdTo = head,
      isDraft = isDraft,
      mergedCommitIds = merged
    )

  def comment(id: Int, author: String, body: String, action: String = "comment"): IssueComment =
    IssueComment(
      userName = "example-org",
      repositoryName = "repo",
      issueId = 7,
      commentId = id,
      action = action,
      commentedUserName = author,
      content = body,
      registeredDate = epoch,
      updatedDate = epoch
    )
}

/** Stored state plus a write log, so a refusal that still wrote is visible. */
private[tools] class ToolWorld {
  val repositories = mutable.Map.empty[(String, String), RepositoryInfo]
  val userRepositories = mutable.Map.empty[String, List[RepositoryInfo]]
  val guests = mutable.Set.empty[(String, String, String)]
  val developers = mutable.Set.empty[(String, String, String)]
  val pullRequests = mutable.Map.empty[(String, String, Int), (Issue, PullRequest)]
  val comments = mutable.Map.empty[Int, IssueComment]
  /** GitBucket's merge record: the issue comment whose action is `merge`. */
  val merges = mutable.Map.empty[(String, String, Int), (IssueComment, Account)]
  val writes = mutable.ArrayBuffer.empty[String]
}

/** Repository reads and role predicates. */
private[tools] trait StubCommon extends RepoService {
  def world: ToolWorld

  // Never dereferenced: everything that would touch a session is overridden.
  // Typed by inference as Null so this conforms to whatever
  // `Database.getSession` returns, without naming that type here.
  override protected def blockingSession(implicit request: HttpServletRequest) = null

  override def getRepository(userName: String, repositoryName: String)(implicit
    s: Session
  ): Option[RepositoryInfo] =
    world.repositories.get((userName, repositoryName))

  override def getUserRepositories(userName: String, withoutPhysicalInfo: Boolean)(implicit
    s: Session
  ): List[RepositoryInfo] =
    world.userRepositories.getOrElse(userName, Nil)

  override def hasGuestRole(owner: String, repository: String, loginAccount: Option[Account])(implicit
    s: Session
  ): Boolean =
    loginAccount.exists { a =>
      a.isAdmin || world.guests.contains((owner, repository, a.userName)) ||
      world.developers.contains((owner, repository, a.userName))
    }

  override def hasDeveloperRole(owner: String, repository: String, loginAccount: Option[Account])(implicit
    s: Session
  ): Boolean =
    loginAccount.exists(a => a.isAdmin || world.developers.contains((owner, repository, a.userName)))
}

/** Issue storage, with every write recorded. */
private[tools] trait StubIssues extends IssueService with StubCommon {
  override def getIssue(owner: String, repository: String, issueId: String)(implicit
    s: Session
  ): Option[Issue] =
    if (issueId.nonEmpty && issueId.forall(_.isDigit))
      world.pullRequests.get((owner, repository, issueId.toInt)).map(_._1)
    else None

  override def getComment(owner: String, repository: String, commentId: String)(implicit
    s: Session
  ): Option[IssueComment] =
    if (commentId.nonEmpty && commentId.forall(_.isDigit)) world.comments.get(commentId.toInt) else None

  override def getComments(owner: String, repository: String, issueId: Int)(implicit
    s: Session
  ): List[IssueComment] =
    world.comments.values.filter(c => c.userName == owner && c.repositoryName == repository && c.issueId == issueId)
      .toList
      .sortBy(_.commentId)

  override def getMergedComment(owner: String, repository: String, issueId: Int)(implicit
    s: Session
  ): Option[(IssueComment, Account)] =
    world.merges.get((owner, repository, issueId))

  override def updateComment(owner: String, repository: String, issueId: Int, commentId: Int, content: String)(
    implicit s: Session
  ): Int = {
    world.writes += s"updateComment($owner/$repository#$issueId comment=$commentId)"
    world.comments.get(commentId).foreach(c => world.comments(commentId) = c.copy(content = content))
    1
  }

  override def updateIssue(owner: String, repository: String, issueId: Int, title: String, content: Option[String])(
    implicit s: Session
  ): Int = {
    world.writes += s"updateIssue($owner/$repository#$issueId)"
    world.pullRequests.get((owner, repository, issueId)).foreach { case (i, p) =>
      world.pullRequests((owner, repository, issueId)) = (i.copy(title = title, content = content), p)
    }
    1
  }

  override def insertIssue(
    owner: String,
    repository: String,
    loginUser: String,
    title: String,
    content: Option[String],
    milestoneId: Option[Int],
    priorityId: Option[Int],
    isPullRequest: Boolean
  )(implicit s: Session): Int = {
    world.writes += s"insertIssue($owner/$repository by $loginUser)"
    42
  }
}

/** Pull request storage, with the draft transition recorded. */
private[tools] trait StubPullRequests extends PrService with StubIssues {
  override def getPullRequest(owner: String, repository: String, issueId: Int)(implicit
    s: Session
  ): Option[(Issue, PullRequest)] =
    world.pullRequests.get((owner, repository, issueId))

  override def updateDraftToPullRequest(owner: String, repository: String, issueId: Int)(implicit
    s: Session
  ): Unit = {
    world.writes += s"updateDraftToPullRequest($owner/$repository#$issueId)"
    world.pullRequests.get((owner, repository, issueId)).foreach { case (i, p) =>
      world.pullRequests((owner, repository, issueId)) = (i, p.copy(isDraft = false))
    }
  }
}

// The production tools, with storage replaced and nothing else.
private[tools] class TestCreateIssueTool(val world: ToolWorld) extends CreateIssueTool with StubIssues
private[tools] class TestUpdateOwnCommentTool(val world: ToolWorld) extends UpdateOwnCommentTool with StubIssues
private[tools] class TestUpdatePullRequestTool(val world: ToolWorld)
    extends UpdatePullRequestTool
    with StubPullRequests
private[tools] class TestMarkReadyTool(val world: ToolWorld) extends MarkReadyForReviewTool with StubPullRequests
private[tools] class TestGetPullRequestTool(val world: ToolWorld) extends GetPullRequestTool with StubPullRequests
private[tools] class TestListRepositoriesTool(val world: ToolWorld) extends ListRepositoriesTool with StubCommon
private[tools] class TestGetRepositoryTool(val world: ToolWorld) extends GetRepositoryTool with StubCommon

@RunWith(classOf[JUnitRunner])
class ToolExecutionSpec extends AnyFunSuite with Matchers {

  import ExecFixtures._

  private val mapper = new ObjectMapper()
  private val request: HttpServletRequest = null

  private def args(json: String): JsonNode = mapper.readTree(json)

  /** Tools answer `{"content":[{"type":"text","text":"<json>"}]}`. */
  private def payload(result: JsonNode): JsonNode =
    mapper.readTree(result.get("content").get(0).get("text").asText())

  // ── P1: create_issue follows the repository's issue policy ────────────────

  private def issueWorld(issuesOption: String, isPrivate: Boolean): ToolWorld = {
    val w = new ToolWorld
    w.repositories(("example-org", "repo")) = repoInfo("example-org", "repo", isPrivate, issuesOption)
    w.guests += (("example-org", "repo", "guest"))
    w.developers += (("example-org", "repo", "developer"))
    w
  }

  private def createIssue(w: ToolWorld, who: Account): JsonNode =
    new TestCreateIssueTool(w).execute(
      args("""{"owner":"example-org","repo":"repo","title":"t","body":"b"}"""),
      who,
      request,
      mapper
    )

  test("ALL on a public repository admits an authenticated outsider") {
    val w = issueWorld("ALL", isPrivate = false)
    payload(createIssue(w, account("outsider"))).get("number").asInt() shouldBe 42
    w.writes should have size 1
  }

  test("ALL on a private repository refuses even a developer, and writes nothing") {
    // GitBucket's ALL branch is `!isPrivate && loggedIn`: privacy, not role,
    // is what closes this one.
    val w = issueWorld("ALL", isPrivate = true)
    an[AuthorizationError] should be thrownBy createIssue(w, account("developer"))
    w.writes shouldBe empty
  }

  test("PUBLIC admits a guest but refuses an outsider, and the refusal writes nothing") {
    val w = issueWorld("PUBLIC", isPrivate = false)
    noException should be thrownBy createIssue(w, account("guest"))
    w.writes should have size 1
    an[AuthorizationError] should be thrownBy createIssue(w, account("outsider"))
    w.writes should have size 1
  }

  test("PRIVATE admits a developer but refuses a guest, and the refusal writes nothing") {
    // The case the previous head got wrong: a guest passed the read guard and
    // reached insertIssue.
    val w = issueWorld("PRIVATE", isPrivate = true)
    an[AuthorizationError] should be thrownBy createIssue(w, account("guest"))
    w.writes shouldBe empty
    noException should be thrownBy createIssue(w, account("developer"))
    w.writes should have size 1
  }

  test("DISABLE refuses everyone including an admin, and writes nothing") {
    val w = issueWorld("DISABLE", isPrivate = false)
    an[AuthorizationError] should be thrownBy createIssue(w, account("developer"))
    an[AuthorizationError] should be thrownBy createIssue(w, account("admin", admin = true))
    w.writes shouldBe empty
  }

  test("an unknown issues setting is refused rather than treated as open") {
    val w = issueWorld("SOMETHING_NEW", isPrivate = false)
    an[AuthorizationError] should be thrownBy createIssue(w, account("developer"))
    w.writes shouldBe empty
  }

  test("an unreadable private repository is reported as missing, not as a policy refusal") {
    // Order matters: an AuthorizationError here would confirm to a stranger
    // that the private repository exists.
    val w = issueWorld("PRIVATE", isPrivate = true)
    a[NoSuchElementException] should be thrownBy createIssue(w, account("stranger"))
    w.writes shouldBe empty
  }

  // ── P2: malformed identifiers refuse, and write nothing ───────────────────

  private def prWorld: ToolWorld = {
    val w = new ToolWorld
    w.repositories(("example-org", "repo")) = repoInfo("example-org", "repo")
    w.developers += (("example-org", "repo", "developer"))
    w.pullRequests(("example-org", "repo", 1)) = (issue("example-org", "repo", 1), pullRequest("example-org", "repo", 1, "example-org", "repo"))
    w.pullRequests(("example-org", "repo", 5)) = (issue("example-org", "repo", 5), pullRequest("example-org", "repo", 5, "example-org", "repo"))
    w
  }

  private val malformedIds = Seq(
    "1.9" -> "a fractional number",
    "4294967297" -> "range",
    "\"1\"" -> "JSON integer",
    "true" -> "JSON integer",
    "null" -> "JSON integer",
    "{}" -> "JSON integer",
    "[]" -> "JSON integer",
    "0" -> "positive"
  )

  test("update_pull_request refuses every malformed identifier and never writes") {
    // Through the inherited helper each of these arrived as pull request 1, so
    // the edit would have landed on a real and unrelated pull request.
    malformedIds.foreach { case (literal, fragment) =>
      val w = prWorld
      val thrown = the[IllegalArgumentException] thrownBy {
        new TestUpdatePullRequestTool(w).execute(
          args(s"""{"owner":"example-org","repo":"repo","number":$literal,"title":"hijacked"}"""),
          account("developer"),
          request,
          mapper
        )
      }
      withClue(s"identifier $literal: ") {
        thrown.getMessage should include(fragment)
        w.writes shouldBe empty
        w.pullRequests(("example-org", "repo", 1))._1.title shouldBe "title"
      }
    }
  }

  test("mark_ready_for_review refuses a malformed identifier and leaves draft state untouched") {
    val w = prWorld
    an[IllegalArgumentException] should be thrownBy new TestMarkReadyTool(w).execute(
      args("""{"owner":"example-org","repo":"repo","number":1.9}"""),
      account("developer"),
      request,
      mapper
    )
    w.writes shouldBe empty
    w.pullRequests(("example-org", "repo", 1))._2.isDraft shouldBe true
  }

  test("update_own_comment refuses a malformed identifier before reading the comment") {
    val w = commentWorld
    an[IllegalArgumentException] should be thrownBy new TestUpdateOwnCommentTool(w).execute(
      args("""{"owner":"example-org","repo":"repo","comment_id":"10","body":"x"}"""),
      account("author"),
      request,
      mapper
    )
    w.writes shouldBe empty
    w.comments(10).content shouldBe "original"
  }

  test("a well-formed identifier still reaches the right object") {
    val w = prWorld
    val result = payload(
      new TestUpdatePullRequestTool(w).execute(
        args("""{"owner":"example-org","repo":"repo","number":5,"title":"new"}"""),
        account("developer"),
        request,
        mapper
      )
    )
    result.get("number").asInt() shouldBe 5
    w.writes should contain("updateIssue(example-org/repo#5)")
    w.pullRequests(("example-org", "repo", 1))._1.title shouldBe "title"
  }

  // ── omitted-field preservation, observed in stored state ──────────────────

  test("updating only the title preserves the stored body") {
    val w = prWorld
    new TestUpdatePullRequestTool(w).execute(
      args("""{"owner":"example-org","repo":"repo","number":1,"title":"renamed"}"""),
      account("developer"),
      request,
      mapper
    )
    val (stored, _) = w.pullRequests(("example-org", "repo", 1))
    stored.title shouldBe "renamed"
    stored.content shouldBe Some("body")
  }

  test("updating only the body preserves the stored title") {
    val w = prWorld
    new TestUpdatePullRequestTool(w).execute(
      args("""{"owner":"example-org","repo":"repo","number":1,"body":"rewritten"}"""),
      account("developer"),
      request,
      mapper
    )
    val (stored, _) = w.pullRequests(("example-org", "repo", 1))
    stored.title shouldBe "title"
    stored.content shouldBe Some("rewritten")
  }

  test("an explicit empty body clears it while the title survives") {
    val w = prWorld
    new TestUpdatePullRequestTool(w).execute(
      args("""{"owner":"example-org","repo":"repo","number":1,"body":""}"""),
      account("developer"),
      request,
      mapper
    )
    val (stored, _) = w.pullRequests(("example-org", "repo", 1))
    stored.title shouldBe "title"
    stored.content shouldBe Some("")
  }

  test("a malformed object body refuses and leaves the stored body untouched") {
    // The coercing helper turned `{}` into "" and erased a real description.
    val w = prWorld
    an[IllegalArgumentException] should be thrownBy new TestUpdatePullRequestTool(w).execute(
      args("""{"owner":"example-org","repo":"repo","number":1,"body":{}}"""),
      account("developer"),
      request,
      mapper
    )
    w.writes shouldBe empty
    w.pullRequests(("example-org", "repo", 1))._1.content shouldBe Some("body")
  }

  test("a reader without the developer role cannot update, and nothing is written") {
    val w = prWorld
    w.guests += (("example-org", "repo", "reader"))
    an[AuthorizationError] should be thrownBy new TestUpdatePullRequestTool(w).execute(
      args("""{"owner":"example-org","repo":"repo","number":1,"title":"x"}"""),
      account("reader"),
      request,
      mapper
    )
    w.writes shouldBe empty
  }

  // ── own-comment authorship, through the real tool ─────────────────────────

  private def commentWorld: ToolWorld = {
    val w = new ToolWorld
    w.repositories(("example-org", "repo")) = repoInfo("example-org", "repo")
    w.comments(10) = comment(10, "author", "original")
    w.comments(11) = comment(11, "author", "", action = "close")
    w
  }

  private def editComment(w: ToolWorld, who: Account, id: Int, body: String = "edited"): JsonNode =
    new TestUpdateOwnCommentTool(w).execute(
      args(s"""{"owner":"example-org","repo":"repo","comment_id":$id,"body":"$body"}"""),
      who,
      request,
      mapper
    )

  test("the author can edit their own comment and the stored text matches exactly") {
    val w = commentWorld
    payload(editComment(w, account("author"), 10)).get("body_saved_exactly").asBoolean() shouldBe true
    w.comments(10).content shouldBe "edited"
  }

  test("another account cannot edit someone else's comment, and nothing is written") {
    val w = commentWorld
    an[AuthorizationError] should be thrownBy editComment(w, account("other"), 10)
    w.writes shouldBe empty
    w.comments(10).content shouldBe "original"
  }

  test("an admin is refused another author's comment rather than silently elevated") {
    // GitBucket would let a repository admin edit it. The gateway deliberately
    // does not, so an agent's edits stay attributable.
    val w = commentWorld
    an[AuthorizationError] should be thrownBy editComment(w, account("admin", admin = true), 10)
    w.writes shouldBe empty
    w.comments(10).content shouldBe "original"
  }

  test("a state-change record is refused as non-editable text") {
    val w = commentWorld
    an[IllegalArgumentException] should be thrownBy editComment(w, account("author"), 11)
    w.writes shouldBe empty
  }

  test("an empty replacement is refused rather than blanking the comment") {
    val w = commentWorld
    an[IllegalArgumentException] should be thrownBy editComment(w, account("author"), 10, body = "")
    w.writes shouldBe empty
    w.comments(10).content shouldBe "original"
  }

  // ── the readiness transition, including the fork repository choice ────────

  private def forkWorld(
    isDraft: Boolean = true,
    closed: Boolean = false,
    merged: Option[String] = None,
    mergeComment: Boolean = false
  ): ToolWorld = {
    val w = new ToolWorld
    w.repositories(("example-org", "base")) = repoInfo("example-org", "base")
    w.repositories(("fork", "head")) = repoInfo("fork", "head")
    w.pullRequests(("example-org", "base", 3)) = (
      issue("example-org", "base", 3, closed = closed),
      pullRequest("example-org", "base", 3, "fork", "head", isDraft = isDraft, merged = merged)
    )
    if (mergeComment) {
      w.merges(("example-org", "base", 3)) =
        (comment(99, "merger", "merge it", action = "merge"), account("merger"))
    }
    w
  }

  private def markReady(w: ToolWorld, who: Account, extra: String = ""): JsonNode =
    new TestMarkReadyTool(w).execute(
      args(s"""{"owner":"example-org","repo":"base","number":3$extra}"""),
      who,
      request,
      mapper
    )

  test("the developer role is required on the head repository, not the base") {
    // A developer of the base repository with no role on the fork is refused.
    // This is the check GitBucket's own update_draft route performs, and
    // getting it wrong would be invisible without distinct repositories.
    val w = forkWorld()
    w.developers += (("example-org", "base", "baseDev"))
    an[AuthorizationError] should be thrownBy markReady(w, account("baseDev"))
    w.writes shouldBe empty
    w.pullRequests(("example-org", "base", 3))._2.isDraft shouldBe true
  }

  test("a developer of the head repository can mark it ready") {
    val w = forkWorld()
    w.developers += (("fork", "head", "forkDev"))
    val result = payload(markReady(w, account("forkDev")))
    result.get("is_draft").asBoolean() shouldBe false
    result.get("was_draft").asBoolean() shouldBe true
    result.get("changed").asBoolean() shouldBe true
    w.writes should contain("updateDraftToPullRequest(example-org/base#3)")
  }

  test("marking an already-ready pull request ready again changes nothing and writes nothing") {
    val w = forkWorld(isDraft = false)
    w.developers += (("fork", "head", "forkDev"))
    val result = payload(markReady(w, account("forkDev")))
    result.get("is_draft").asBoolean() shouldBe false
    result.get("changed").asBoolean() shouldBe false
    w.writes shouldBe empty
  }

  test("a stale expected head is refused and the draft state survives") {
    val w = forkWorld()
    w.developers += (("fork", "head", "forkDev"))
    val thrown = the[IllegalArgumentException] thrownBy {
      markReady(w, account("forkDev"), s""","expected_head_sha":"${"c" * 40}"""")
    }
    thrown.getMessage should include("stale expected_head_sha")
    w.writes shouldBe empty
    w.pullRequests(("example-org", "base", 3))._2.isDraft shouldBe true
  }

  test("a matching expected head is accepted") {
    val w = forkWorld()
    w.developers += (("fork", "head", "forkDev"))
    payload(markReady(w, account("forkDev"), s""","expected_head_sha":"${"a" * 40}""""))
      .get("is_draft")
      .asBoolean() shouldBe false
  }

  test("a malformed precondition is refused, not dropped into an unguarded write") {
    val w = forkWorld()
    w.developers += (("fork", "head", "forkDev"))
    an[IllegalArgumentException] should be thrownBy {
      markReady(w, account("forkDev"), ""","expected_head_sha":"aaaa"""")
    }
    w.writes shouldBe empty
    w.pullRequests(("example-org", "base", 3))._2.isDraft shouldBe true
  }

  test("a closed pull request is refused and nothing is written") {
    val w = forkWorld(closed = true)
    w.developers += (("fork", "head", "forkDev"))
    an[IllegalArgumentException] should be thrownBy markReady(w, account("forkDev"))
    w.writes shouldBe empty
  }

  test("a merged pull request is refused and nothing is written") {
    // Driven by the merge comment, which is what GitBucket reads. Measured on
    // a live 4.48.0 instance: the API merge route leaves `mergedCommitIds`
    // empty, so a column test would have let this through.
    val w = forkWorld(mergeComment = true)
    w.developers += (("fork", "head", "forkDev"))
    val thrown = the[IllegalArgumentException] thrownBy markReady(w, account("forkDev"))
    thrown.getMessage should include("is merged")
    w.writes shouldBe empty
    w.pullRequests(("example-org", "base", 3))._2.isDraft shouldBe true
  }

  test("a merge comment with no recorded commit ids still counts as merged") {
    // The exact live case: `merged_commit_ids` absent, `merged` true.
    val w = forkWorld(isDraft = false, closed = true, mergeComment = true)
    w.developers += (("fork", "head", "forkDev"))
    val payloadNode = payload(
      new TestGetPullRequestTool(w).execute(
        args("""{"owner":"example-org","repo":"base","number":3}"""),
        account("forkDev"),
        request,
        mapper
      )
    )
    payloadNode.get("merged").asBoolean() shouldBe true
    payloadNode.get("merged_by").asText() shouldBe "merger"
    payloadNode.has("merged_at") shouldBe true
    payloadNode.has("merged_commit_ids") shouldBe false
    payloadNode.get("state").asText() shouldBe "closed"
  }

  test("recorded commit ids are published without being the test for merged") {
    val w = forkWorld(isDraft = false, closed = true, merged = Some("aaa, bbb"), mergeComment = true)
    w.developers += (("fork", "head", "forkDev"))
    val n = payload(
      new TestGetPullRequestTool(w).execute(
        args("""{"owner":"example-org","repo":"base","number":3}"""),
        account("forkDev"),
        request,
        mapper
      )
    )
    n.get("merged").asBoolean() shouldBe true
    n.get("merged_commit_ids").get(0).asText() shouldBe "aaa"
    n.get("merged_commit_ids").get(1).asText() shouldBe "bbb"
  }

  test("a closed pull request with no merge comment reports merged=false") {
    // `state` alone cannot say this: closed-without-merging and merged are
    // both closed.
    val w = forkWorld(isDraft = false, closed = true)
    w.developers += (("fork", "head", "forkDev"))
    val n = payload(
      new TestGetPullRequestTool(w).execute(
        args("""{"owner":"example-org","repo":"base","number":3}"""),
        account("forkDev"),
        request,
        mapper
      )
    )
    n.get("state").asText() shouldBe "closed"
    n.get("merged").asBoolean() shouldBe false
    n.has("merged_by") shouldBe false
  }

  test("a stale commit-id column alone does not make a pull request merged") {
    // The inverse of the live defect: the column is set but no merge comment
    // exists, so GitBucket would not call this merged either.
    val w = forkWorld(merged = Some("deadbeef"))
    w.developers += (("fork", "head", "forkDev"))
    val result = payload(markReady(w, account("forkDev")))
    result.get("merged").asBoolean() shouldBe false
    result.get("changed").asBoolean() shouldBe true
  }

  test("the readiness transition leaves the title and body alone") {
    val w = forkWorld()
    w.developers += (("fork", "head", "forkDev"))
    markReady(w, account("forkDev"))
    val (stored, _) = w.pullRequests(("example-org", "base", 3))
    stored.title shouldBe "title"
    stored.content shouldBe Some("body")
    w.writes should not contain ("updateIssue(example-org/base#3)")
  }

  // ── the read guard, inside real tools ─────────────────────────────────────

  test("get_pull_request refuses a private repository the caller cannot read") {
    val w = new ToolWorld
    w.repositories(("example-org", "secret")) = repoInfo("example-org", "secret", isPrivate = true)
    w.pullRequests(("example-org", "secret", 1)) =
      (issue("example-org", "secret", 1), pullRequest("example-org", "secret", 1, "example-org", "secret"))
    a[NoSuchElementException] should be thrownBy new TestGetPullRequestTool(w).execute(
      args("""{"owner":"example-org","repo":"secret","number":1}"""),
      account("outsider"),
      request,
      mapper
    )
  }

  test("list_repositories filters inside the tool, not merely inside the guard") {
    val w = new ToolWorld
    val open = repoInfo("example-org", "open")
    val secret = repoInfo("example-org", "secret", isPrivate = true)
    w.userRepositories("example-org") = List(open, secret)
    w.repositories(("example-org", "open")) = open
    w.repositories(("example-org", "secret")) = secret

    val outsiderView = payload(
      new TestListRepositoriesTool(w).execute(args("""{"owner":"example-org"}"""), account("outsider"), request, mapper)
    )
    outsiderView.get("count").asInt() shouldBe 1
    outsiderView.get("repositories").get(0).get("name").asText() shouldBe "open"

    w.guests += (("example-org", "secret", "guest"))
    payload(
      new TestListRepositoriesTool(w).execute(args("""{"owner":"example-org"}"""), account("guest"), request, mapper)
    ).get("count").asInt() shouldBe 2
  }

  // ── the record that is published is the record that is authorized ─────────
  //
  // The listing reads twice: once to enumerate, once per repository for the
  // counts. Those are different snapshots. A repository that was public when
  // enumerated and private when read in detail used to be published under the
  // first decision -- with the description and counts that only exist in the
  // private state. The fix authorizes the second record, the one actually
  // being serialized.
  //
  // `world.userRepositories` and `world.repositories` are deliberately given
  // *different* objects for the same repository, which is what makes the two
  // snapshots distinguishable at all.

  test("a repository that became private between the two reads is not published") {
    val w = new ToolWorld
    val enumerated = repoInfo("example-org", "transition", isPrivate = false)
    val fresh = enumerated.copy(
      repository = enumerated.repository.copy(
        isPrivate = true, description = Some("private-only-marker")),
      issueCount = 17, pullCount = 9)
    w.userRepositories("example-org") = List(enumerated)
    w.repositories(("example-org", "transition")) = fresh

    val view = payload(new TestListRepositoriesTool(w)
      .execute(args("""{"owner":"example-org"}"""), account("outsider"), request, mapper))

    withClue(s"$view: ") {
      view.get("count").asInt() shouldBe 0
      view.get("repositories").size() shouldBe 0
      // Nothing that exists only in the private snapshot may appear anywhere
      // in the response, counts included.
      view.toString should not include "private-only-marker"
      view.toString should not include "17"
      view.toString should not include "\"is_private\":true"
    }
  }

  test("the detail tool refuses the same transitioned repository") {
    // The two tools have to agree: a listing that publishes what the detail
    // read refuses is the disclosure, whichever way round it is.
    val w = new ToolWorld
    val enumerated = repoInfo("example-org", "transition", isPrivate = false)
    w.userRepositories("example-org") = List(enumerated)
    w.repositories(("example-org", "transition")) =
      enumerated.copy(repository = enumerated.repository.copy(isPrivate = true))

    val thrown = intercept[NoSuchElementException] {
      new TestGetRepositoryTool(w)
        .execute(args("""{"owner":"example-org","repo":"transition"}"""), account("outsider"), request, mapper)
    }
    thrown.getMessage should include("not found or not readable")
  }

  test("stable public data is still published after the second read") {
    // The guard must not cost the ordinary case: the same object in both
    // snapshots is published, with its counts.
    val w = new ToolWorld
    val open = repoInfo("example-org", "open").copy(issueCount = 4, pullCount = 2)
    w.userRepositories("example-org") = List(open)
    w.repositories(("example-org", "open")) = open

    val view = payload(new TestListRepositoriesTool(w)
      .execute(args("""{"owner":"example-org"}"""), account("outsider"), request, mapper))
    view.get("count").asInt() shouldBe 1
    view.get("repositories").get(0).get("issue_count").asInt() shouldBe 4
    view.get("repositories").get(0).get("pr_count").asInt() shouldBe 2
  }

  test("stable private data is published to every caller GitBucket permits") {
    val w = new ToolWorld
    val secret = repoInfo("example-org", "secret", isPrivate = true).copy(issueCount = 5)
    w.userRepositories("example-org") = List(secret)
    w.repositories(("example-org", "secret")) = secret
    w.guests += (("example-org", "secret", "guest"))
    w.developers += (("example-org", "secret", "dev"))

    // Guest, developer and administrator only. These stubs model GitBucket's
    // role predicates, not its ownership rule, so an owner has no role here;
    // the owner's own access to a private repository is covered against real
    // GitBucket in itest/repo_list_gate.py rather than asserted against a
    // fixture that does not implement it.
    Seq("guest", "dev").foreach { who =>
      withClue(s"$who: ") {
        val view = payload(new TestListRepositoriesTool(w)
          .execute(args("""{"owner":"example-org"}"""), account(who), request, mapper))
        view.get("count").asInt() shouldBe 1
        view.get("repositories").get(0).get("issue_count").asInt() shouldBe 5
      }
    }
    val admin = payload(new TestListRepositoriesTool(w)
      .execute(args("""{"owner":"example-org"}"""), account("root", admin = true), request, mapper))
    admin.get("count").asInt() shouldBe 1
  }

  test("a role removed between two invocations stops the repository being published") {
    // The same shape as the privacy transition, from the other direction: the
    // caller's access disappears rather than the repository's visibility
    // changing.
    //
    // The role is removed *between two complete invocations*, so within each
    // one it is constant. That is what this control establishes, and no more:
    // it does not place the removal between the enumeration and the detail
    // read of a single call. Covering that boundary needs a fixture which
    // drops the role during the detail read itself, which is a separate
    // control and not this one.
    val w = new ToolWorld
    val enumerated = repoInfo("example-org", "secret", isPrivate = true)
    w.userRepositories("example-org") = List(enumerated)
    w.repositories(("example-org", "secret")) =
      enumerated.copy(repository = enumerated.repository.copy(
        description = Some("still-private-marker")), issueCount = 11)
    w.guests += (("example-org", "secret", "guest"))

    val permitted = payload(new TestListRepositoriesTool(w)
      .execute(args("""{"owner":"example-org"}"""), account("guest"), request, mapper))
    permitted.get("count").asInt() shouldBe 1

    w.guests -= (("example-org", "secret", "guest"))
    val revoked = payload(new TestListRepositoriesTool(w)
      .execute(args("""{"owner":"example-org"}"""), account("guest"), request, mapper))
    withClue(s"$revoked: ") {
      revoked.get("count").asInt() shouldBe 0
      revoked.toString should not include "still-private-marker"
    }
  }
}

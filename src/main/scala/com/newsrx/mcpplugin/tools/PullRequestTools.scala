package com.newsrx.mcpplugin.tools

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.fasterxml.jackson.databind.node.ObjectNode
import com.newsrx.mcpplugin.oauth.McpScope
import com.newsrx.mcpplugin.mcp.{StrictArgs, Timestamps, ToolDef}
import gitbucket.core.model.{Account, Issue, IssueComment, PullRequest}
import gitbucket.core.service.{AccountService, ActivityService, CommitsService, IssuesService, LabelsService, MergeService, MilestonesService, PrioritiesService, PullRequestService, RepositoryService, RequestCache, SystemSettingsService, WebHookPullRequestReviewCommentService, WebHookPullRequestService, WebHookService}
import gitbucket.core.service.IssuesService.IssueSearchCondition
import gitbucket.core.util.JGitUtil.DiffInfo
import javax.servlet.http.HttpServletRequest

// ── Shared helpers ──────────────────────────────────────────────────────────

private[tools] trait PrService
    extends IssuesService
    with PullRequestService
    with RepositoryService
    with AccountService
    with LabelsService
    with PrioritiesService
    with MilestonesService
    with CommitsService
    with WebHookService
    with WebHookPullRequestService
    with WebHookPullRequestReviewCommentService
    with MergeService
    with ActivityService
    with RequestCache
    with SystemSettingsService
    with SessionSupport
    with RepositoryAuthorization

private[tools] object PrJson {

  /**
   * A pull request as this gateway reports it.
   *
   * Commit identities carry GitBucket's stored meaning, verified against
   * 4.48.0 rather than guessed from the column names:
   *
   *  - `PullRequest.commitIdTo` is the head commit, resolved by
   *    `JGitUtil.updatePullRequest` from `refs/pull/<id>/head`;
   *  - `PullRequest.commitIdFrom` is the *merge base* between the base branch
   *    and the head branch, not the current tip of the base branch.
   *
   * Calling `commitIdFrom` a "base SHA" would therefore be wrong, so it is
   * published as `merge_base_sha`. Both are values stored when the pull
   * request was last updated; neither is a live read of a branch tip now, and
   * `commit_ids_are_stored_snapshot` says so in the payload rather than only
   * in this comment.
   *
   * `mergedComment` is what GitBucket itself treats as the merge record: the
   * issue comment whose action is `merge`, fetched with
   * `IssuesService.getMergedComment`. It is passed in rather than read here
   * because this object holds no session.
   */
  def prNode(
    mapper: ObjectMapper,
    issue: Issue,
    pr: PullRequest,
    mergedComment: Option[(IssueComment, Account)]
  ): ObjectNode = {
    val n = mapper.createObjectNode()
    n.put("number",      issue.issueId)
    n.put("title",       issue.title)
    n.put("body",        issue.content.getOrElse(""))
    n.put("state",       if (issue.closed) "closed" else "open")
    n.put("author",      issue.openedUserName)
    n.put("base_owner",  pr.userName)
    n.put("base_repo",   pr.repositoryName)
    n.put("base_branch", pr.branch)
    n.put("head_user",   pr.requestUserName)
    n.put("head_repo",   pr.requestRepositoryName)
    n.put("head_branch", pr.requestBranch)
    n.put("head_sha",       pr.commitIdTo)
    n.put("merge_base_sha", pr.commitIdFrom)
    n.put("commit_ids_are_stored_snapshot", true)
    n.put("is_draft",    pr.isDraft)
    // Merged state comes from the merge *comment*, which is the record
    // GitBucket's own API reads (`ApiPullRequest.apply` takes
    // `mergedComment` and sets `merged = mergedComment.isDefined`).
    //
    // This used to be derived from `PullRequest.mergedCommitIds`, and that was
    // wrong. Measured on a live 4.48.0 instance: after a merge through
    // `PUT /api/v3/.../pulls/:id/merge`, GitBucket answered `merged: true`
    // with a `merged_at`, while the column was still empty and this gateway
    // answered `merged: false`. Only the web merge route writes that column
    // (`PullRequestsController` line 367, for the revert feature); the API
    // route does not. A closed pull request that was never merged still
    // reports merged=false, which `state` alone cannot say.
    n.put("merged",      mergedComment.isDefined)
    mergedComment.foreach { case (comment, account) =>
      n.put("merged_at", Timestamps.iso8601(comment.registeredDate))
      n.put("merged_by", account.userName)
    }
    // The commit ids of the merge, when GitBucket recorded them. Absent on a
    // merge performed through the API, so a consumer must not read their
    // absence as "not merged" — `merged` is the field that answers that.
    pr.mergedCommitIds.map(_.trim).filter(_.nonEmpty).foreach { ids =>
      val arr = mapper.createArrayNode()
      ids.split(",").map(_.trim).filter(_.nonEmpty).foreach(arr.add)
      n.set[ObjectNode]("merged_commit_ids", arr)
    }
    n.put("created_at",  Timestamps.iso8601(issue.registeredDate))
    n.put("updated_at",  Timestamps.iso8601(issue.updatedDate))
    n
  }
}

// ── list_pull_requests ──────────────────────────────────────────────────────

class ListPullRequestsTool extends ToolDef with PrService {
  val name        = "list_pull_requests"
  val requiredScope = McpScope.Read
  val description = "List pull requests in a GitBucket repository"

  def inputSchema(mapper: ObjectMapper) = objectSchema(
    mapper,
    required = Seq("owner", "repo"),
    "owner" -> strNode(mapper, "Repository owner (user or organization)"),
    "repo"  -> strNode(mapper, "Repository name"),
    "state" -> strEnum(mapper, "Filter by state (default: open)", "open", "closed", "all"),
    "limit" -> intNode(mapper, "Maximum number of results (default: 30, max: 100)")
  )

  def execute(args: JsonNode, account: Account, request: HttpServletRequest, mapper: ObjectMapper): JsonNode = {
    val owner = str(args, "owner")
    val repo  = str(args, "repo")
    val state = strOpt(args, "state").getOrElse("open")
    val limit = math.min(intOpt(args, "limit", 30), 100)

    implicit val session = blockingSession(request)

    val condition = new IssueSearchCondition(
      labels = Set.empty, milestone = None, priority = None,
      author = None, assigned = None, mentioned = None,
      state = state, sort = "created", direction = "desc",
      visibility = None, groups = Set.empty, others = Nil
    )

    readableRepository(owner, repo, account)

    val prs = searchPullRequestByApi(condition, 0, limit, (owner, repo))
    val arr = mapper.createArrayNode()
    prs.foreach { t =>
      arr.add(PrJson.prNode(mapper, t._1, t._4, getMergedComment(owner, repo, t._1.issueId)))
    }

    val root = mapper.createObjectNode()
    root.put("count", prs.size)
    root.set[ObjectNode]("pull_requests", arr)
    jsonContent(mapper, root)
  }
}

// ── get_pull_request ────────────────────────────────────────────────────────

class GetPullRequestTool extends ToolDef with PrService {
  val name        = "get_pull_request"
  val requiredScope = McpScope.Read
  val description = "Get details of a specific pull request"

  def inputSchema(mapper: ObjectMapper) = objectSchema(
    mapper,
    required = Seq("owner", "repo", "number"),
    "owner"  -> strNode(mapper, "Repository owner"),
    "repo"   -> strNode(mapper, "Repository name"),
    "number" -> intNode(mapper, "Pull request number")
  )

  def execute(args: JsonNode, account: Account, request: HttpServletRequest, mapper: ObjectMapper): JsonNode = {
    val owner  = str(args, "owner")
    val repo   = str(args, "repo")
    val number = int(args, "number")

    implicit val session = blockingSession(request)
    readableRepository(owner, repo, account)

    getPullRequest(owner, repo, number) match {
      case None =>
        throw new NoSuchElementException(s"pull request #$number not found in $owner/$repo")
      case Some((issue, pr)) =>
        val node = PrJson.prNode(mapper, issue, pr, getMergedComment(owner, repo, number))
        node.put("comment_count", getComments(owner, repo, number).size)
        jsonContent(mapper, node)
    }
  }
}

// ── add_pr_comment ──────────────────────────────────────────────────────────

class AddPrCommentTool extends ToolDef with PrService {
  val name        = "add_pr_comment"
  val requiredScope = McpScope.Write
  val description = "Add a comment to a pull request"

  def inputSchema(mapper: ObjectMapper) = objectSchema(
    mapper,
    required = Seq("owner", "repo", "number", "body"),
    "owner"  -> strNode(mapper, "Repository owner"),
    "repo"   -> strNode(mapper, "Repository name"),
    "number" -> intNode(mapper, "Pull request number"),
    "body"   -> strNode(mapper, "Comment text (Markdown supported)")
  )

  def execute(args: JsonNode, account: Account, request: HttpServletRequest, mapper: ObjectMapper): JsonNode = {
    val owner  = str(args, "owner")
    val repo   = str(args, "repo")
    val number = int(args, "number")
    val body   = str(args, "body")

    implicit val session = blockingSession(request)
    writableRepository(owner, repo, account)

    getPullRequest(owner, repo, number).getOrElse(
      throw new NoSuchElementException(s"pull request #$number not found in $owner/$repo")
    )

    val commentId = createComment(owner, repo, account.userName, number, body, "comment")

    val result = mapper.createObjectNode()
    result.put("comment_id", commentId)
    result.put("pr_number",  number)
    result.put("author",     account.userName)
    result.put("body",       body)
    jsonContent(mapper, result)
  }
}

// ── close_pull_request ──────────────────────────────────────────────────────

class ClosePullRequestTool extends ToolDef with PrService {
  val name        = "close_pull_request"
  val requiredScope = McpScope.Write
  val description = "Close a pull request without merging"

  def inputSchema(mapper: ObjectMapper) = objectSchema(
    mapper,
    required = Seq("owner", "repo", "number"),
    "owner"  -> strNode(mapper, "Repository owner"),
    "repo"   -> strNode(mapper, "Repository name"),
    "number" -> intNode(mapper, "Pull request number"),
    "reason" -> strNode(mapper, "Optional comment to post when closing")
  )

  def execute(args: JsonNode, account: Account, request: HttpServletRequest, mapper: ObjectMapper): JsonNode = {
    val owner  = str(args, "owner")
    val repo   = str(args, "repo")
    val number = int(args, "number")
    val reason = strOpt(args, "reason")

    implicit val session = blockingSession(request)
    writableRepository(owner, repo, account)

    getPullRequest(owner, repo, number).getOrElse(
      throw new NoSuchElementException(s"pull request #$number not found in $owner/$repo")
    )

    reason.foreach(r => createComment(owner, repo, account.userName, number, r, "close"))
    updateClosed(owner, repo, number, closed = true)

    textContent(mapper, s"Pull request #$number in $owner/$repo closed")
  }
}

// ── reopen_pull_request ─────────────────────────────────────────────────────

class ReopenPullRequestTool extends ToolDef with PrService {
  val name        = "reopen_pull_request"
  val requiredScope = McpScope.Write
  val description = "Reopen a closed pull request"

  def inputSchema(mapper: ObjectMapper) = objectSchema(
    mapper,
    required = Seq("owner", "repo", "number"),
    "owner"  -> strNode(mapper, "Repository owner"),
    "repo"   -> strNode(mapper, "Repository name"),
    "number" -> intNode(mapper, "Pull request number")
  )

  def execute(args: JsonNode, account: Account, request: HttpServletRequest, mapper: ObjectMapper): JsonNode = {
    val owner  = str(args, "owner")
    val repo   = str(args, "repo")
    val number = int(args, "number")

    implicit val session = blockingSession(request)
    writableRepository(owner, repo, account)

    getPullRequest(owner, repo, number).getOrElse(
      throw new NoSuchElementException(s"pull request #$number not found in $owner/$repo")
    )

    createComment(owner, repo, account.userName, number, "", "reopen")
    updateClosed(owner, repo, number, closed = false)

    textContent(mapper, s"Pull request #$number in $owner/$repo reopened")
  }
}

// ── get_pr_diff ─────────────────────────────────────────────────────────────

class GetPrDiffTool extends ToolDef with PrService {
  val name        = "get_pr_diff"
  val requiredScope = McpScope.Read
  val description = "Get the list of files changed in a pull request"

  def inputSchema(mapper: ObjectMapper) = objectSchema(
    mapper,
    required = Seq("owner", "repo", "number"),
    "owner"  -> strNode(mapper, "Repository owner"),
    "repo"   -> strNode(mapper, "Repository name"),
    "number" -> intNode(mapper, "Pull request number")
  )

  def execute(args: JsonNode, account: Account, request: HttpServletRequest, mapper: ObjectMapper): JsonNode = {
    val owner  = str(args, "owner")
    val repo   = str(args, "repo")
    val number = int(args, "number")

    implicit val session = blockingSession(request)
    readableRepository(owner, repo, account)

    val (_, pr) = getPullRequest(owner, repo, number).getOrElse(
      throw new NoSuchElementException(s"pull request #$number not found in $owner/$repo")
    )

    val settings = loadSystemSettings()
    val (_, diffs) = getRequestCompareInfo(
      pr.userName, pr.repositoryName, pr.commitIdFrom,
      pr.requestUserName, pr.requestRepositoryName, pr.requestBranch,
      settings
    )

    val root  = mapper.createObjectNode()
    val files = mapper.createArrayNode()
    diffs.foreach { diff =>
      val f = mapper.createObjectNode()
      f.put("path",        if (diff.newPath.nonEmpty) diff.newPath else diff.oldPath)
      f.put("old_path",    diff.oldPath)
      f.put("new_path",    diff.newPath)
      f.put("change_type", diff.changeType.name())
      diff.patch.foreach(p => f.put("patch", p))
      files.add(f)
    }
    root.put("pr_number",  number)
    root.put("file_count", diffs.size)
    root.set[ObjectNode]("files", files)
    jsonContent(mapper, root)
  }
}

// ── get_thread_comments ─────────────────────────────────────────────────────

/**
 * The comment bodies of one issue or pull request thread.
 *
 * `get_pull_request` and `get_issue` report only a `comment_count`, which is
 * not a read: an agent asked to act on review remarks cannot see them. This
 * returns the text, with the ids and authors needed to answer in the right
 * place, and a bounded page so a long thread cannot be pulled in one call.
 */
class GetThreadCommentsTool extends ToolDef with PrService {
  val name        = "get_thread_comments"
  val requiredScope = McpScope.Read
  val description = "Read the comments of an issue or pull request thread, with ids, authors and bounded pagination"

  val MaxLimit = 50

  def inputSchema(mapper: ObjectMapper) = objectSchema(
    mapper,
    required = Seq("owner", "repo", "number"),
    "owner"  -> strNode(mapper, "Repository owner"),
    "repo"   -> strNode(mapper, "Repository name"),
    "number" -> intNode(mapper, "Issue or pull request number"),
    "offset" -> intNode(mapper, "Comments to skip, oldest first (default: 0)"),
    "limit"  -> intNode(mapper, s"Maximum comments to return (default: 30, max: $MaxLimit)")
  )

  def execute(args: JsonNode, account: Account, request: HttpServletRequest, mapper: ObjectMapper): JsonNode = {
    val owner  = str(args, "owner")
    val repo   = str(args, "repo")
    val number = int(args, "number")
    val offset = math.max(intOpt(args, "offset", 0), 0)
    val limit  = math.min(math.max(intOpt(args, "limit", 30), 1), MaxLimit)

    implicit val session = blockingSession(request)
    readableRepository(owner, repo, account)

    if (getIssue(owner, repo, number.toString).isEmpty) {
      throw new NoSuchElementException(s"issue or pull request #$number not found in $owner/$repo")
    }

    // GitBucket records state changes ("close", "reopen", "merge", …) as
    // comments too. Those carry no author text, so they are reported with
    // their action rather than silently dropped: a thread with the deletions
    // hidden would misrepresent what happened.
    val all = getComments(owner, repo, number)
    val page = all.slice(offset, offset + limit)

    val arr = mapper.createArrayNode()
    page.foreach { c =>
      val n = mapper.createObjectNode()
      n.put("comment_id", c.commentId)
      n.put("author",     c.commentedUserName)
      n.put("action",     c.action)
      n.put("body",       c.content)
      n.put("created_at", Timestamps.iso8601(c.registeredDate))
      n.put("updated_at", Timestamps.iso8601(c.updatedDate))
      arr.add(n)
    }

    val root = mapper.createObjectNode()
    root.put("number",        number)
    root.put("total_count",   all.size)
    root.put("offset",        offset)
    root.put("returned",      page.size)
    root.put("has_more",      offset + page.size < all.size)
    root.put("next_offset",   offset + page.size)
    root.set[ObjectNode]("comments", arr)
    jsonContent(mapper, root)
  }
}

// ── update_pull_request ─────────────────────────────────────────────────────

/**
 * Change a pull request's title or body, and nothing else.
 *
 * GitBucket's `updateIssue` takes both title and content and writes both, so
 * an omitted argument would blank the other field. The current value is read
 * first and passed through unchanged, and the saved row is read back and
 * compared, because this installation has already answered 200 to a REST
 * PATCH whose field it then ignored. Branches are never touched.
 */
class UpdatePullRequestTool extends ToolDef with PrService {
  val name        = "update_pull_request"
  val requiredScope = McpScope.Write
  val description = "Update the title and/or body of a pull request, preserving the field that is not supplied"

  def inputSchema(mapper: ObjectMapper) = objectSchema(
    mapper,
    required = Seq("owner", "repo", "number"),
    "owner"  -> strNode(mapper, "Repository owner"),
    "repo"   -> strNode(mapper, "Repository name"),
    "number" -> intNode(mapper, "Pull request number"),
    "title"  -> strNode(mapper, "New title; omit to keep the current one"),
    "body"   -> strNode(mapper, "New body; omit to keep the current one")
  )

  def execute(args: JsonNode, account: Account, request: HttpServletRequest, mapper: ObjectMapper): JsonNode = {
    val owner  = str(args, "owner")
    val repo   = str(args, "repo")
    // Strict identifier: the inherited `int` coerces 1.9, 4294967297, "1" and
    // true all to 1, which would send the edit to pull request 1.
    val number = StrictArgs.identifier(args, "number")
    // Read through the strict helpers, not the coercing ones: a malformed
    // `body` must not become an empty string and erase the description.
    // An omitted field and a supplied empty string are different requests,
    // so only a JSON string counts as supplied.
    val newTitle = StrictArgs.textOpt(args, "title")
    val newBody  = StrictArgs.textOpt(args, "body")

    if (newTitle.isEmpty && newBody.isEmpty) {
      throw new IllegalArgumentException("supply at least one of: title, body")
    }
    // Empty-title policy: a pull request must keep a title, so an empty one is
    // refused. An empty body is accepted, because clearing a description is a
    // normal edit and there is no equivalent reason to forbid it.
    if (newTitle.contains("")) {
      throw new IllegalArgumentException("argument 'title' must not be empty")
    }

    implicit val session = blockingSession(request)
    writableRepository(owner, repo, account)

    val (issue, _) = getPullRequest(owner, repo, number).getOrElse(
      throw new NoSuchElementException(s"pull request #$number not found in $owner/$repo")
    )

    val title   = newTitle.getOrElse(issue.title)
    val content = newBody.orElse(issue.content)

    updateIssue(owner, repo, number, title, content)

    // Read back and compare the stored bytes. A write that reports success
    // without this proves nothing about what was saved.
    val (saved, savedPr) = getPullRequest(owner, repo, number).getOrElse(
      throw new IllegalStateException(s"pull request #$number disappeared during update")
    )
    val titleOk = saved.title == title
    val bodyOk  = saved.content.getOrElse("") == content.getOrElse("")

    val root = PrJson.prNode(mapper, saved, savedPr, getMergedComment(owner, repo, number))
    root.put("title_saved_exactly", titleOk)
    root.put("body_saved_exactly",  bodyOk)
    if (!titleOk || !bodyOk) {
      throw new IllegalStateException(
        s"update of pull request #$number was not stored exactly: " +
          s"title_saved_exactly=$titleOk body_saved_exactly=$bodyOk"
      )
    }
    jsonContent(mapper, root)
  }
}

// ── mark_ready_for_review ───────────────────────────────────────────────────

/**
 * Take a draft pull request out of draft, using GitBucket's own operation.
 *
 * `PullRequestService.updateDraftToPullRequest` (4.48.0) writes `isDraft =
 * false` and nothing else. It is one-way: it never sets a pull request back to
 * draft, so repeating this call preserves the ready state rather than toggling
 * it. GitBucket's `update_draft` route fires no hook or event, and this does
 * not add one.
 *
 * Authorization reproduces that route exactly, including the part that is easy
 * to get wrong: read access to the *base* repository plus the developer role
 * on the pull request's *head* repository, which for a fork is a different
 * repository from the one named in the arguments.
 *
 * Race guarantee, stated precisely: `expected_head_sha` is compared against
 * the stored head before the transition and the stored head is returned after
 * it, both inside the request's transaction, but this is a check followed by a
 * write and not an atomic compare-and-swap. A push that lands between them is
 * not prevented. Do not describe it as compare-and-swap.
 */
class MarkReadyForReviewTool extends ToolDef with PrService {
  val name        = "mark_ready_for_review"
  val requiredScope = McpScope.Write
  val description = "Take an open, unmerged draft pull request out of draft using GitBucket's native operation"

  def inputSchema(mapper: ObjectMapper) = objectSchema(
    mapper,
    required = Seq("owner", "repo", "number"),
    "owner"             -> strNode(mapper, "Repository owner of the base repository"),
    "repo"              -> strNode(mapper, "Base repository name"),
    "number"            -> intNode(mapper, "Pull request number"),
    "expected_head_sha" -> strNode(
      mapper,
      "Full 40-character commit id. If given, refuse when the stored head differs. " +
        "Checked before the write, not atomically with it."
    )
  )

  def execute(args: JsonNode, account: Account, request: HttpServletRequest, mapper: ObjectMapper): JsonNode = {
    val owner    = str(args, "owner")
    val repo     = str(args, "repo")
    val number   = StrictArgs.identifier(args, "number")
    // A supplied precondition is validated, never discarded: silently
    // dropping a malformed one would perform the unguarded write the caller
    // explicitly asked to avoid.
    val expected = StrictArgs.commitIdOpt(args, "expected_head_sha")

    implicit val session = blockingSession(request)

    // Base repository must be readable by the caller; the write permission
    // that matters lives on the head repository, checked below.
    readableRepository(owner, repo, account)

    val (issue, pr) = getPullRequest(owner, repo, number).getOrElse(
      throw new NoSuchElementException(s"pull request #$number not found in $owner/$repo")
    )

    requireDeveloperRole(pr.requestUserName, pr.requestRepositoryName, account)

    if (issue.closed) {
      throw new IllegalArgumentException(
        s"pull request #$number is closed; only an open pull request can be marked ready"
      )
    }
    // Merged is read from the merge comment, the same record GitBucket's own
    // API reads. `mergedCommitIds` is not written by the API merge route, so
    // testing it here would have let a merged pull request through whenever it
    // had somehow been reopened.
    if (getMergedComment(owner, repo, number).isDefined) {
      throw new IllegalArgumentException(
        s"pull request #$number is merged; only an unmerged pull request can be marked ready"
      )
    }
    expected.foreach { want =>
      if (!want.equalsIgnoreCase(pr.commitIdTo)) {
        throw new IllegalArgumentException(
          s"stale expected_head_sha: pull request #$number records head ${pr.commitIdTo}, caller expected $want"
        )
      }
    }

    val wasDraft = pr.isDraft
    // Already-ready is not an error: the requested end state is the same, and
    // the native operation is one-way, so calling it again changes nothing.
    if (wasDraft) {
      updateDraftToPullRequest(owner, repo, number)
    }

    val (afterIssue, afterPr) = getPullRequest(owner, repo, number).getOrElse(
      throw new IllegalStateException(s"pull request #$number disappeared during the transition")
    )

    val root = PrJson.prNode(mapper, afterIssue, afterPr, getMergedComment(owner, repo, number))
    root.put("was_draft", wasDraft)
    root.put("changed",   wasDraft && !afterPr.isDraft)
    root.put("race_guarantee", "checked-then-written; not compare-and-swap")
    if (afterPr.isDraft) {
      throw new IllegalStateException(
        s"pull request #$number is still a draft after the native transition"
      )
    }
    jsonContent(mapper, root)
  }
}

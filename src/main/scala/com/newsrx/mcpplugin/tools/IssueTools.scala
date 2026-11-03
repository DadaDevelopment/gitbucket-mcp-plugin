package com.newsrx.mcpplugin.tools

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.fasterxml.jackson.databind.node.ObjectNode
import com.newsrx.mcpplugin.oauth.McpScope
import com.newsrx.mcpplugin.mcp.{StrictArgs, Timestamps, ToolDef}
import gitbucket.core.model.{Account, Issue}
import gitbucket.core.service.{AccountService, IssuesService, LabelsService, MilestonesService, PrioritiesService, RepositoryService}
import gitbucket.core.service.IssuesService.IssueSearchCondition
import javax.servlet.http.HttpServletRequest

// ── Shared helpers ──────────────────────────────────────────────────────────

private[tools] trait IssueService
    extends IssuesService
    with RepositoryService
    with AccountService
    with LabelsService
    with PrioritiesService
    with MilestonesService
    with SessionSupport
    with RepositoryAuthorization

private[tools] object IssueJson {
  def issueNode(mapper: ObjectMapper, issue: Issue): ObjectNode = {
    val n = mapper.createObjectNode()
    n.put("number",     issue.issueId)
    n.put("title",      issue.title)
    n.put("body",       issue.content.getOrElse(""))
    n.put("state",      if (issue.closed) "closed" else "open")
    n.put("author",     issue.openedUserName)
    n.put("is_pr",      issue.isPullRequest)
    n.put("created_at", Timestamps.iso8601(issue.registeredDate))
    n.put("updated_at", Timestamps.iso8601(issue.updatedDate))
    n
  }
}

// ── list_issues ─────────────────────────────────────────────────────────────

class ListIssuesTool extends ToolDef with IssueService {
  val name        = "list_issues"
  val requiredScope = McpScope.Read
  val description = "List issues in a GitBucket repository (excludes pull requests)"

  def inputSchema(mapper: ObjectMapper) = objectSchema(
    mapper,
    required = Seq("owner", "repo"),
    "owner" -> strNode(mapper, "Repository owner"),
    "repo"  -> strNode(mapper, "Repository name"),
    "state" -> strEnum(mapper, "Filter by state (default: open)", "open", "closed", "all"),
    "limit" -> intNode(mapper, "Maximum results (default: 30, max: 100)")
  )

  def execute(args: JsonNode, account: Account, request: HttpServletRequest, mapper: ObjectMapper): JsonNode = {
    val owner = str(args, "owner")
    val repo  = str(args, "repo")
    val state = strOpt(args, "state").getOrElse("open")
    val limit = math.min(intOpt(args, "limit", 30), 100)

    implicit val session = blockingSession(request)
    readableRepository(owner, repo, account)

    val condition = new IssueSearchCondition(
      labels = Set.empty, milestone = None, priority = None,
      author = None, assigned = None, mentioned = None,
      state = state, sort = "created", direction = "desc",
      visibility = None, groups = Set.empty, others = Nil
    )

    val all = searchIssueByApi(condition, 0, limit, (owner, repo))
    val arr = mapper.createArrayNode()
    all.foreach { t =>
      if (!t._1.isPullRequest) arr.add(IssueJson.issueNode(mapper, t._1))
    }

    val root = mapper.createObjectNode()
    root.put("count", arr.size)
    root.set[ObjectNode]("issues", arr)
    jsonContent(mapper, root)
  }
}

// ── get_issue ───────────────────────────────────────────────────────────────

class GetIssueTool extends ToolDef with IssueService {
  val name        = "get_issue"
  val requiredScope = McpScope.Read
  val description = "Get details of a specific issue"

  def inputSchema(mapper: ObjectMapper) = objectSchema(
    mapper,
    required = Seq("owner", "repo", "number"),
    "owner"  -> strNode(mapper, "Repository owner"),
    "repo"   -> strNode(mapper, "Repository name"),
    "number" -> intNode(mapper, "Issue number")
  )

  def execute(args: JsonNode, account: Account, request: HttpServletRequest, mapper: ObjectMapper): JsonNode = {
    val owner  = str(args, "owner")
    val repo   = str(args, "repo")
    val number = int(args, "number")

    implicit val session = blockingSession(request)
    readableRepository(owner, repo, account)

    getIssue(owner, repo, number.toString) match {
      case None =>
        throw new NoSuchElementException(s"issue #$number not found in $owner/$repo")
      case Some(issue) =>
        val node = IssueJson.issueNode(mapper, issue)
        node.put("comment_count", getComments(owner, repo, number).size)
        jsonContent(mapper, node)
    }
  }
}

// ── create_issue ─────────────────────────────────────────────────────────────

class CreateIssueTool extends ToolDef with IssueService {
  val name        = "create_issue"
  val requiredScope = McpScope.Write
  val description = "Create a new issue in a GitBucket repository"

  def inputSchema(mapper: ObjectMapper) = objectSchema(
    mapper,
    required = Seq("owner", "repo", "title"),
    "owner" -> strNode(mapper, "Repository owner"),
    "repo"  -> strNode(mapper, "Repository name"),
    "title" -> strNode(mapper, "Issue title"),
    "body"  -> strNode(mapper, "Issue body (Markdown supported)")
  )

  def execute(args: JsonNode, account: Account, request: HttpServletRequest, mapper: ObjectMapper): JsonNode = {
    val owner = str(args, "owner")
    val repo  = str(args, "repo")
    val title = str(args, "title")
    val body  = strOpt(args, "body")

    implicit val session = blockingSession(request)
    // Read access is not the rule: GitBucket branches on the repository's
    // `issuesOption` before writing, and reading alone admitted cases the
    // server itself refuses.
    issuableRepository(owner, repo, account)

    val issueId = insertIssue(owner, repo,
      loginUser     = account.userName,
      title         = title,
      milestoneId   = None,
      priorityId    = None,
      content       = body,
      isPullRequest = false
    )

    val result = mapper.createObjectNode()
    result.put("number", issueId)
    result.put("title",  title)
    result.put("state",  "open")
    result.put("author", account.userName)
    jsonContent(mapper, result)
  }
}

// ── close_issue ──────────────────────────────────────────────────────────────

class CloseIssueTool extends ToolDef with IssueService {
  val name        = "close_issue"
  val requiredScope = McpScope.Write
  val description = "Close an issue"

  def inputSchema(mapper: ObjectMapper) = objectSchema(
    mapper,
    required = Seq("owner", "repo", "number"),
    "owner"  -> strNode(mapper, "Repository owner"),
    "repo"   -> strNode(mapper, "Repository name"),
    "number" -> intNode(mapper, "Issue number"),
    "reason" -> strNode(mapper, "Optional closing comment")
  )

  def execute(args: JsonNode, account: Account, request: HttpServletRequest, mapper: ObjectMapper): JsonNode = {
    val owner  = str(args, "owner")
    val repo   = str(args, "repo")
    val number = int(args, "number")
    val reason = strOpt(args, "reason")

    implicit val session = blockingSession(request)
    writableRepository(owner, repo, account)

    getIssue(owner, repo, number.toString).getOrElse(
      throw new NoSuchElementException(s"issue #$number not found in $owner/$repo")
    )

    reason.foreach(r => createComment(owner, repo, account.userName, number, r, "close"))
    updateClosed(owner, repo, number, closed = true)

    textContent(mapper, s"Issue #$number in $owner/$repo closed")
  }
}

// ── reopen_issue ─────────────────────────────────────────────────────────────

class ReopenIssueTool extends ToolDef with IssueService {
  val name        = "reopen_issue"
  val requiredScope = McpScope.Write
  val description = "Reopen a closed issue"

  def inputSchema(mapper: ObjectMapper) = objectSchema(
    mapper,
    required = Seq("owner", "repo", "number"),
    "owner"  -> strNode(mapper, "Repository owner"),
    "repo"   -> strNode(mapper, "Repository name"),
    "number" -> intNode(mapper, "Issue number")
  )

  def execute(args: JsonNode, account: Account, request: HttpServletRequest, mapper: ObjectMapper): JsonNode = {
    val owner  = str(args, "owner")
    val repo   = str(args, "repo")
    val number = int(args, "number")

    implicit val session = blockingSession(request)
    writableRepository(owner, repo, account)

    getIssue(owner, repo, number.toString).getOrElse(
      throw new NoSuchElementException(s"issue #$number not found in $owner/$repo")
    )

    createComment(owner, repo, account.userName, number, "", "reopen")
    updateClosed(owner, repo, number, closed = false)

    textContent(mapper, s"Issue #$number in $owner/$repo reopened")
  }
}

// ── add_issue_comment ────────────────────────────────────────────────────────

class AddIssueCommentTool extends ToolDef with IssueService {
  val name        = "add_issue_comment"
  val requiredScope = McpScope.Write
  val description = "Add a comment to an issue"

  def inputSchema(mapper: ObjectMapper) = objectSchema(
    mapper,
    required = Seq("owner", "repo", "number", "body"),
    "owner"  -> strNode(mapper, "Repository owner"),
    "repo"   -> strNode(mapper, "Repository name"),
    "number" -> intNode(mapper, "Issue number"),
    "body"   -> strNode(mapper, "Comment text (Markdown supported)")
  )

  def execute(args: JsonNode, account: Account, request: HttpServletRequest, mapper: ObjectMapper): JsonNode = {
    val owner  = str(args, "owner")
    val repo   = str(args, "repo")
    val number = int(args, "number")
    val body   = str(args, "body")

    implicit val session = blockingSession(request)
    writableRepository(owner, repo, account)

    getIssue(owner, repo, number.toString).getOrElse(
      throw new NoSuchElementException(s"issue #$number not found in $owner/$repo")
    )

    val commentId = createComment(owner, repo, account.userName, number, body, "comment")

    val result = mapper.createObjectNode()
    result.put("comment_id",    commentId)
    result.put("issue_number",  number)
    result.put("author",        account.userName)
    result.put("body",          body)
    jsonContent(mapper, result)
  }
}

// ── update_own_comment ──────────────────────────────────────────────────────

/**
 * Edit a comment the caller wrote.
 *
 * Authorship is enforced here rather than assumed: GitBucket lets a
 * repository administrator edit anyone's comment, but a gateway that silently
 * exercised that power would make an agent's edits indistinguishable from a
 * person's. This tool edits the caller's own comments only, and an admin
 * calling it on someone else's comment is refused rather than quietly
 * elevated. The saved text is read back and compared before success is
 * reported.
 */
class UpdateOwnCommentTool extends ToolDef with IssueService {
  val name        = "update_own_comment"
  val requiredScope = McpScope.Write
  val description = "Edit a comment written by the authenticated account, with exact read-back of the stored text"

  def inputSchema(mapper: ObjectMapper) = objectSchema(
    mapper,
    required = Seq("owner", "repo", "comment_id", "body"),
    "owner"      -> strNode(mapper, "Repository owner"),
    "repo"       -> strNode(mapper, "Repository name"),
    "comment_id" -> intNode(mapper, "Comment id, as returned by get_thread_comments"),
    "body"       -> strNode(mapper, "Replacement comment text")
  )

  def execute(args: JsonNode, account: Account, request: HttpServletRequest, mapper: ObjectMapper): JsonNode = {
    val owner     = str(args, "owner")
    val repo      = str(args, "repo")
    val commentId = StrictArgs.identifier(args, "comment_id")
    // Strict: a malformed body must not be coerced into empty text and
    // overwrite a real comment. An empty replacement is refused outright,
    // since blanking a comment is a deletion wearing an edit's clothes.
    val body      = StrictArgs.requiredText(args, "body")

    implicit val session = blockingSession(request)
    readableRepository(owner, repo, account)

    val comment = getComment(owner, repo, commentId.toString).getOrElse(
      throw new NoSuchElementException(s"comment $commentId not found in $owner/$repo")
    )

    if (comment.commentedUserName != account.userName) {
      throw new AuthorizationError(
        s"comment $commentId was written by ${comment.commentedUserName}; " +
          s"${account.userName} may edit only their own comments through this gateway"
      )
    }
    if (comment.action != "comment") {
      throw new IllegalArgumentException(
        s"comment $commentId is a '${comment.action}' record, not editable comment text"
      )
    }

    updateComment(owner, repo, comment.issueId, commentId, body)

    val saved = getComment(owner, repo, commentId.toString).getOrElse(
      throw new IllegalStateException(s"comment $commentId disappeared during update")
    )
    if (saved.content != body) {
      throw new IllegalStateException(
        s"comment $commentId was not stored exactly as supplied"
      )
    }

    val n = mapper.createObjectNode()
    n.put("comment_id",         commentId)
    n.put("issue_number",       comment.issueId)
    n.put("author",             saved.commentedUserName)
    n.put("body",               saved.content)
    n.put("body_saved_exactly", true)
    n.put("updated_at",         Timestamps.iso8601(saved.updatedDate))
    jsonContent(mapper, n)
  }
}

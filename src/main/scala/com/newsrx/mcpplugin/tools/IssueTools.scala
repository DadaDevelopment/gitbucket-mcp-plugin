package com.newsrx.mcpplugin.tools

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.fasterxml.jackson.databind.node.ObjectNode
import com.newsrx.mcpplugin.mcp.ToolDef
import gitbucket.core.model.{Account, Issue, IssueAssignee, IssueComment, Label, Milestone, Priority}
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

private[tools] object IssueJson {
  def issueNode(
      mapper: ObjectMapper,
      issue: Issue,
      comments: Seq[IssueComment],
      labels: Seq[Label],
      assignees: Seq[IssueAssignee],
      milestone: Option[Milestone],
      priority: Option[Priority]
  ): ObjectNode = {
    val n = mapper.createObjectNode()
    n.put("number",     issue.issueId)
    n.put("owner",      issue.userName)
    n.put("repo",       issue.repositoryName)
    n.put("title",      issue.title)
    n.put("body",       issue.content.getOrElse(""))
    n.put("state",      if (issue.closed) "closed" else "open")
    n.put("closed",     issue.closed)
    n.put("author",     issue.openedUserName)
    n.put("is_pr",      issue.isPullRequest)
    n.put("created_at", issue.registeredDate.toString)
    n.put("updated_at", issue.updatedDate.toString)
    issue.milestoneId match {
      case Some(id) => n.put("milestone_id", id)
      case None     => n.putNull("milestone_id")
    }
    issue.priorityId match {
      case Some(id) => n.put("priority_id", id)
      case None     => n.putNull("priority_id")
    }

    val commentNodes = mapper.createArrayNode()
    comments.foreach { comment =>
      val commentNode = mapper.createObjectNode()
      commentNode.put("id",         comment.commentId)
      commentNode.put("action",     comment.action)
      commentNode.put("author",     comment.commentedUserName)
      commentNode.put("body",       comment.content)
      commentNode.put("created_at", comment.registeredDate.toString)
      commentNode.put("updated_at", comment.updatedDate.toString)
      commentNodes.add(commentNode)
    }
    n.put("comment_count", comments.size)
    n.set[com.fasterxml.jackson.databind.node.ArrayNode]("comments", commentNodes)

    val labelNodes = mapper.createArrayNode()
    labels.foreach { label =>
      val labelNode = mapper.createObjectNode()
      labelNode.put("id",         label.labelId)
      labelNode.put("name",       label.labelName)
      labelNode.put("color",      label.color)
      labelNode.put("font_color", label.fontColor)
      labelNodes.add(labelNode)
    }
    n.set[com.fasterxml.jackson.databind.node.ArrayNode]("labels", labelNodes)

    val assigneeNodes = mapper.createArrayNode()
    assignees.foreach(assignee => assigneeNodes.add(assignee.assigneeUserName))
    n.set[com.fasterxml.jackson.databind.node.ArrayNode]("assignees", assigneeNodes)

    milestone match {
      case Some(value) =>
        val milestoneNode = mapper.createObjectNode()
        milestoneNode.put("id",    value.milestoneId)
        milestoneNode.put("title", value.title)
        value.description match {
          case Some(description) => milestoneNode.put("description", description)
          case None              => milestoneNode.putNull("description")
        }
        value.dueDate match {
          case Some(date) => milestoneNode.put("due_date", date.toString)
          case None       => milestoneNode.putNull("due_date")
        }
        value.closedDate match {
          case Some(date) => milestoneNode.put("closed_at", date.toString)
          case None       => milestoneNode.putNull("closed_at")
        }
        n.set[ObjectNode]("milestone", milestoneNode)
      case None => n.putNull("milestone")
    }

    priority match {
      case Some(value) =>
        val priorityNode = mapper.createObjectNode()
        priorityNode.put("id",          value.priorityId)
        priorityNode.put("name",        value.priorityName)
        value.description match {
          case Some(description) => priorityNode.put("description", description)
          case None              => priorityNode.putNull("description")
        }
        priorityNode.put("is_default",  value.isDefault)
        priorityNode.put("ordering",    value.ordering)
        priorityNode.put("color",       value.color)
        priorityNode.put("font_color",  value.fontColor)
        n.set[ObjectNode]("priority", priorityNode)
      case None => n.putNull("priority")
    }
    n
  }
}

// ── list_issues ─────────────────────────────────────────────────────────────

class ListIssuesTool extends ToolDef with IssueService {
  val name        = "list_issues"
  val description = "List issues with comments and related metadata (excludes pull requests)"

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

    val condition = new IssueSearchCondition(
      labels = Set.empty, milestone = None, priority = None,
      author = None, assigned = None, mentioned = None,
      state = state, sort = "created", direction = "desc",
      visibility = None, groups = Set.empty, others = Nil
    )

    val all = searchIssueByApi(condition, 0, limit, (owner, repo))
    val arr = mapper.createArrayNode()
    all.foreach { t =>
      if (!t._1.isPullRequest) {
        val issue = t._1
        arr.add(IssueJson.issueNode(
          mapper,
          issue,
          getComments(owner, repo, issue.issueId),
          getIssueLabels(owner, repo, issue.issueId),
          getIssueAssignees(owner, repo, issue.issueId),
          issue.milestoneId.flatMap(id => getMilestone(owner, repo, id)),
          issue.priorityId.flatMap(id => getPriority(owner, repo, id))
        ))
      }
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
  val description = "Get all issue fields, comments, and related metadata"

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

    getIssue(owner, repo, number.toString) match {
      case None =>
        throw new NoSuchElementException(s"issue #$number not found in $owner/$repo")
      case Some(issue) =>
        val node = IssueJson.issueNode(
          mapper,
          issue,
          getComments(owner, repo, issue.issueId),
          getIssueLabels(owner, repo, issue.issueId),
          getIssueAssignees(owner, repo, issue.issueId),
          issue.milestoneId.flatMap(id => getMilestone(owner, repo, id)),
          issue.priorityId.flatMap(id => getPriority(owner, repo, id))
        )
        jsonContent(mapper, node)
    }
  }
}

// ── create_issue ─────────────────────────────────────────────────────────────

class CreateIssueTool extends ToolDef with IssueService {
  val name        = "create_issue"
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

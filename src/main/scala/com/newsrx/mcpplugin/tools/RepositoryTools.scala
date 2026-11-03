package com.newsrx.mcpplugin.tools

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.fasterxml.jackson.databind.node.ObjectNode
import com.newsrx.mcpplugin.oauth.McpScope
import com.newsrx.mcpplugin.mcp.ToolDef
import gitbucket.core.model.Account
import gitbucket.core.service.{AccountService, RepositoryService}
import javax.servlet.http.HttpServletRequest

private[tools] trait RepoService
    extends RepositoryService
    with AccountService
    with SessionSupport
    with RepositoryAuthorization

// ── list_repositories ───────────────────────────────────────────────────────

class ListRepositoriesTool extends ToolDef with RepoService {
  val name        = "list_repositories"
  val requiredScope = McpScope.Read
  val description = "List repositories accessible to a GitBucket user"

  def inputSchema(mapper: ObjectMapper) = objectSchema(
    mapper,
    required = Seq("owner"),
    "owner"          -> strNode(mapper, "User or organization name"),
    "without_forked" -> boolNode(mapper, "Exclude repositories that are themselves forks (default: false)")
  )

  def execute(args: JsonNode, account: Account, request: HttpServletRequest, mapper: ObjectMapper): JsonNode = {
    val owner         = str(args, "owner")
    val withoutForked = bool(args, "without_forked", default = false)

    implicit val session = blockingSession(request)

    // Enumerate without physical information. That second argument is
    // `withoutPhysicalInfo`, not a fork filter: passing the caller's
    // `without_forked` into it skipped the Git reads and published
    // GitBucket's -1 placeholder as a fork count. Here it is deliberately
    // true, because this pass only decides *which* repositories the caller
    // may see; nothing from it is published.
    val candidates = getUserRepositories(owner, withoutPhysicalInfo = true)

    // `getUserRepositories` answers for `owner`, not for the caller: without
    // this filter the listing hands every private repository of any account to
    // anyone who can authenticate. Each entry is kept only if the caller may
    // read it under GitBucket's own rule.
    val visible = candidates.filter(canRead(_, account))

    // GitBucket's own fork test, the one its REST API uses:
    // `fork = repository.originUserName.isDefined`. A fork *count* says how
    // many forks exist of this lineage and is no indication that this
    // repository is itself a fork.
    val selected =
      if (withoutForked) visible.filterNot(_.repository.originUserName.isDefined) else visible

    // Counts come from GitBucket's own detail read, for the repositories the
    // caller may read and no others. `createRepositoryInfo`, which the
    // enumeration above uses, builds a RepositoryInfo through the constructor
    // GitBucket documents as "without issue, pull request, and milestone
    // count" -- those fields are 0 because nothing counted them, not because
    // the repository is empty. Publishing them as measurements reported every
    // repository as having no issues and no pull requests.
    //
    // The cost: one native detail read per *readable* repository, in place of
    // the physical read the enumeration used to perform for every repository
    // including the ones the caller cannot see.
    val arr = mapper.createArrayNode()
    var published = 0
    selected.foreach { candidate =>
      val r = candidate.repository
      // The record that gets published is authorized, not the one that was
      // enumerated. Those are two separate reads, and between them a
      // repository can become private: the enumeration snapshot said public,
      // the detail read returns the new private description and counts, and
      // publishing that under the earlier decision discloses it. The early
      // filter above still has its own job -- it keeps repositories the caller
      // never had access to from being read at all -- but it cannot speak for
      // a record it has not seen.
      //
      // A repository that stops being readable in between is omitted, and the
      // count is of what was published, so the two stay consistent. This
      // claims no atomicity: a decision taken before a change cannot be
      // withdrawn afterwards. It only refuses to attach an old decision to
      // newly read data.
      getRepository(r.userName, r.repositoryName).filter(canRead(_, account)).foreach { info =>
        val n = mapper.createObjectNode()
        n.put("name",           info.repository.repositoryName)
        n.put("owner",          info.repository.userName)
        n.put("description",    info.repository.description.getOrElse(""))
        n.put("is_private",     info.repository.isPrivate)
        n.put("default_branch", info.repository.defaultBranch)
        n.put("issue_count",    info.issueCount)
        n.put("pr_count",       info.pullCount)
        n.put("fork_count",     info.forkedCount)
        n.put("is_fork",        info.repository.originUserName.isDefined)
        arr.add(n)
        published += 1
      }
    }

    val root = mapper.createObjectNode()
    root.put("count", published)
    root.set[ObjectNode]("repositories", arr)
    // The counters mean what GitBucket means by them, which is narrower than
    // the names suggest, so the payload says so rather than leaving a reader
    // to assume totals.
    root.put("issue_count_semantics", "open issues, excluding pull requests")
    root.put("pr_count_semantics",    "open pull requests")
    root.put("fork_count_semantics",
             "forks of this repository's origin; not a flag that this repository is a fork")
    jsonContent(mapper, root)
  }
}

// ── get_repository ───────────────────────────────────────────────────────────

class GetRepositoryTool extends ToolDef with RepoService {
  val name        = "get_repository"
  val requiredScope = McpScope.Read
  val description = "Get details of a specific GitBucket repository"

  def inputSchema(mapper: ObjectMapper) = objectSchema(
    mapper,
    required = Seq("owner", "repo"),
    "owner" -> strNode(mapper, "Repository owner"),
    "repo"  -> strNode(mapper, "Repository name")
  )

  def execute(args: JsonNode, account: Account, request: HttpServletRequest, mapper: ObjectMapper): JsonNode = {
    val owner = str(args, "owner")
    val repo  = str(args, "repo")

    implicit val session = blockingSession(request)

    readableRepository(owner, repo, account) match {
      case info =>
        val r = info.repository
        val n = mapper.createObjectNode()
        n.put("name",           r.repositoryName)
        n.put("owner",          r.userName)
        n.put("description",    r.description.getOrElse(""))
        n.put("is_private",     r.isPrivate)
        n.put("default_branch", r.defaultBranch)
        n.put("issue_count",    info.issueCount)
        n.put("pr_count",       info.pullCount)
        n.put("fork_count",     info.forkedCount)
        n.put("is_fork",        r.originUserName.isDefined)
        n.put("issue_count_semantics", "open issues, excluding pull requests")
        n.put("pr_count_semantics",    "open pull requests")
        n.put("fork_count_semantics",
              "forks of this repository's origin; not a flag that this repository is a fork")

        val branches = mapper.createArrayNode()
        info.branchList.foreach(branches.add)
        n.set[ObjectNode]("branches", branches)

        val tags = mapper.createArrayNode()
        info.tags.foreach(t => tags.add(t.name))
        n.set[ObjectNode]("tags", tags)

        jsonContent(mapper, n)
    }
  }
}

package com.newsrx.mcpplugin.tools

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.fasterxml.jackson.databind.node.ObjectNode
import com.newsrx.mcpplugin.mcp.ToolDef
import gitbucket.core.model.{Account, Milestone}
import gitbucket.core.service.MilestonesService
import javax.servlet.http.HttpServletRequest

import java.time.format.DateTimeParseException
import java.time.{Instant, LocalDate, OffsetDateTime, ZoneOffset}
import java.util.Date

private[tools] trait MilestoneService extends MilestonesService with SessionSupport

private[tools] object MilestoneJson {
  def node(mapper: ObjectMapper, milestone: Milestone, openIssues: Int, closedIssues: Int): ObjectNode = {
    val result = mapper.createObjectNode()
    result.put("number", milestone.milestoneId)
    result.put("title", milestone.title)
    milestone.description match {
      case Some(value) => result.put("description", value)
      case None        => result.putNull("description")
    }
    result.put("state", if (milestone.closedDate.isDefined) "closed" else "open")
    milestone.dueDate match {
      case Some(value) => result.put("due_date", Instant.ofEpochMilli(value.getTime).toString)
      case None        => result.putNull("due_date")
    }
    milestone.closedDate match {
      case Some(value) => result.put("closed_at", Instant.ofEpochMilli(value.getTime).toString)
      case None        => result.putNull("closed_at")
    }
    result.put("open_issues", openIssues)
    result.put("closed_issues", closedIssues)
    result
  }

  def parseDueDate(value: String): Date = {
    val instant = try {
      LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant
    } catch {
      case _: DateTimeParseException =>
        try {
          OffsetDateTime.parse(value).toInstant
        } catch {
          case _: DateTimeParseException =>
            throw new IllegalArgumentException("due_date must be an ISO-8601 date or timestamp")
        }
    }
    Date.from(instant)
  }
}

class GetMilestoneTool extends ToolDef with MilestoneService {
  val name = "get_milestone"
  val description = "Get milestone details and open/closed issue counts"

  def inputSchema(mapper: ObjectMapper) = objectSchema(
    mapper,
    required = Seq("owner", "repo", "number"),
    "owner"  -> strNode(mapper, "Repository owner"),
    "repo"   -> strNode(mapper, "Repository name"),
    "number" -> intNode(mapper, "Milestone number")
  )

  def execute(args: JsonNode, account: Account, request: HttpServletRequest, mapper: ObjectMapper): JsonNode = {
    val owner = str(args, "owner")
    val repo = str(args, "repo")
    val number = int(args, "number")
    require(number > 0, "number must be positive")

    implicit val session = blockingSession(request)

    val entry = getMilestonesWithIssueCount(owner, repo).find(_._1.milestoneId == number)
      .getOrElse(throw new NoSuchElementException(s"milestone #$number not found in $owner/$repo"))
    jsonContent(mapper, MilestoneJson.node(mapper, entry._1, entry._2, entry._3))
  }
}

class ListMilestonesTool extends ToolDef with MilestoneService {
  val name = "list_milestones"
  val description = "List repository milestones with issue counts"

  def inputSchema(mapper: ObjectMapper) = objectSchema(
    mapper,
    required = Seq("owner", "repo"),
    "owner" -> strNode(mapper, "Repository owner"),
    "repo"  -> strNode(mapper, "Repository name"),
    "state" -> strEnum(mapper, "Filter by state (default: open)", "open", "closed", "all")
  )

  def execute(args: JsonNode, account: Account, request: HttpServletRequest, mapper: ObjectMapper): JsonNode = {
    val owner = str(args, "owner")
    val repo = str(args, "repo")
    val state = strOpt(args, "state").getOrElse("open")
    require(Set("open", "closed", "all").contains(state), s"invalid milestone state: $state")

    implicit val session = blockingSession(request)

    val milestones = getMilestonesWithIssueCount(owner, repo).filter { entry =>
      val isClosed = entry._1.closedDate.isDefined
      state match {
        case "open"   => !isClosed
        case "closed" => isClosed
        case "all"    => true
        case _        => false
      }
    }

    val items = mapper.createArrayNode()
    milestones.foreach { entry =>
      items.add(MilestoneJson.node(mapper, entry._1, entry._2, entry._3))
    }
    val result = mapper.createObjectNode()
    result.put("count", items.size)
    result.set[com.fasterxml.jackson.databind.node.ArrayNode]("milestones", items)
    jsonContent(mapper, result)
  }
}

class CreateMilestoneTool extends ToolDef with MilestoneService {
  val name = "create_milestone"
  val description = "Create a repository milestone"

  def inputSchema(mapper: ObjectMapper) = objectSchema(
    mapper,
    required = Seq("owner", "repo", "title"),
    "owner"      -> strNode(mapper, "Repository owner"),
    "repo"       -> strNode(mapper, "Repository name"),
    "title"      -> strNode(mapper, "Milestone title"),
    "description" -> strNode(mapper, "Optional milestone description"),
    "due_date"   -> strNode(mapper, "Optional ISO-8601 date or timestamp")
  )

  def execute(args: JsonNode, account: Account, request: HttpServletRequest, mapper: ObjectMapper): JsonNode = {
    val owner = str(args, "owner")
    val repo = str(args, "repo")
    val title = str(args, "title")
    require(title.trim.nonEmpty, "title must not be empty")
    val description = strOpt(args, "description")
    val dueDate = strOpt(args, "due_date").map(MilestoneJson.parseDueDate)

    implicit val session = blockingSession(request)

    val number = createMilestone(owner, repo, title, description, dueDate)
    val entry = getMilestonesWithIssueCount(owner, repo).find(_._1.milestoneId == number)
      .getOrElse(throw new NoSuchElementException(s"milestone #$number not found after creation in $owner/$repo"))
    jsonContent(mapper, MilestoneJson.node(mapper, entry._1, entry._2, entry._3))
  }
}

class UpdateMilestoneTool extends ToolDef with MilestoneService {
  val name = "update_milestone"
  val description = "Update milestone details or open/close its state"

  def inputSchema(mapper: ObjectMapper) = objectSchema(
    mapper,
    required = Seq("owner", "repo", "number"),
    "owner"       -> strNode(mapper, "Repository owner"),
    "repo"        -> strNode(mapper, "Repository name"),
    "number"      -> intNode(mapper, "Milestone number"),
    "title"       -> strNode(mapper, "New milestone title"),
    "description" -> strNode(mapper, "New description; an empty string clears it"),
    "due_date"    -> strNode(mapper, "New ISO-8601 date or timestamp; an empty string clears it"),
    "state"       -> strEnum(mapper, "Set milestone state", "open", "closed")
  )

  def execute(args: JsonNode, account: Account, request: HttpServletRequest, mapper: ObjectMapper): JsonNode = {
    val owner = str(args, "owner")
    val repo = str(args, "repo")
    val number = int(args, "number")
    require(number > 0, "number must be positive")

    val state = strOpt(args, "state")
    state.foreach { value =>
      require(value == "open" || value == "closed", s"invalid milestone state: $value")
    }

    implicit val session = blockingSession(request)

    val current = getMilestone(owner, repo, number)
      .getOrElse(throw new NoSuchElementException(s"milestone #$number not found in $owner/$repo"))

    val title = Option(args.get("title")).map(_.asText()).getOrElse(current.title)
    require(title.trim.nonEmpty, "title must not be empty")
    val description = if (args.has("description")) strOpt(args, "description") else current.description
    val dueDate = if (args.has("due_date")) strOpt(args, "due_date").map(MilestoneJson.parseDueDate) else current.dueDate
    val updated = current.copy(title = title, description = description, dueDate = dueDate)
    updateMilestone(updated)

    state.foreach {
      case "open" if updated.closedDate.isDefined => openMilestone(updated)
      case "closed" if updated.closedDate.isEmpty => closeMilestone(updated)
      case _ => ()
    }

    val entry = getMilestonesWithIssueCount(owner, repo).find(_._1.milestoneId == number)
      .getOrElse(throw new NoSuchElementException(s"milestone #$number not found after update in $owner/$repo"))
    jsonContent(mapper, MilestoneJson.node(mapper, entry._1, entry._2, entry._3))
  }
}

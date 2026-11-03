package com.newsrx.mcpplugin.mcp

import com.fasterxml.jackson.databind.JsonNode

/**
 * Argument reading for operations that change stored data.
 *
 * `MCPController.handleToolCall` hands `arguments` to a tool without checking
 * it against the advertised schema, so whatever a client sends is what the
 * tool sees. The inherited helpers in [[ToolDef]] go through Jackson's
 * `asText("")`, which answers `""` for an object, an array or a null and
 * stringifies numbers and booleans. For a read that is merely sloppy. For a
 * write it is destructive in two distinct ways:
 *
 *  - `{"body": {}}` becomes an empty string, so a malformed request silently
 *    erases a description and the read-back then faithfully confirms the
 *    text the caller never asked for;
 *  - `{"expected_head_sha": {}}` becomes `""`, which the old `strOpt` then
 *    discarded as absent, turning a supplied precondition into no
 *    precondition at all.
 *
 * These helpers fail closed instead. Absent stays absent; a supplied value
 * must be a JSON string, because an empty string is a legitimate edit and
 * cannot therefore double as the error signal; everything else is refused
 * before any write happens.
 */
object StrictArgs {

  /**
   * A supplied JSON string, or `None` when the key is absent.
   *
   * `""` is returned as `Some("")`: clearing a body is a real edit, and the
   * caller's intent to clear it must survive the trip. Any non-string value,
   * JSON `null` included, is an error rather than a silent default.
   */
  def textOpt(args: JsonNode, key: String): Option[String] =
    Option(args.get(key)) match {
      case None => None
      case Some(node) if node.isTextual => Some(node.asText())
      case Some(node) =>
        throw new IllegalArgumentException(
          s"argument '$key' must be a JSON string, received ${typeName(node)}"
        )
    }

  /** A supplied, non-empty JSON string. Absent and empty are both refused. */
  def requiredText(args: JsonNode, key: String): String =
    textOpt(args, key) match {
      case Some(value) if value.nonEmpty => value
      case Some(_) =>
        throw new IllegalArgumentException(s"argument '$key' must not be empty")
      case None =>
        throw new IllegalArgumentException(s"missing required argument: $key")
    }

  /**
   * A supplied commit id, validated as 40 hexadecimal characters.
   *
   * A precondition that cannot be parsed is refused rather than dropped: the
   * caller asked for a guarded write, and performing an unguarded one in its
   * place would be the opposite of what was requested. Abbreviated ids are
   * refused too, because a prefix comparison against a stored full id would
   * quietly never match.
   */
  def commitIdOpt(args: JsonNode, key: String): Option[String] =
    textOpt(args, key).map { value =>
      if (value.length != 40 || !value.forall(isHexDigit)) {
        throw new IllegalArgumentException(
          s"argument '$key' must be a full 40-character hexadecimal commit id"
        )
      }
      value
    }

  /**
   * A supplied object identifier: a JSON integral number inside Int range and
   * strictly positive.
   *
   * The inherited `ToolDef.int` goes through `asInt(-1)` and admits anything
   * that comes back non-negative. Measured on Jackson 2.15.2, `1.9`,
   * `4294967297`, `"1"` and `true` all arrive as 1, so a malformed target
   * silently became pull request or comment number 1 and the mutation went to
   * the wrong object. `isIntegralNumber` rejects the fractional and textual
   * and boolean forms, `canConvertToInt` rejects the out-of-range ones, and
   * GitBucket's issue, pull request and comment ids start at 1, so zero and
   * negatives are outside the domain rather than merely odd.
   */
  def identifier(args: JsonNode, key: String): Int =
    Option(args.get(key)) match {
      case None =>
        throw new IllegalArgumentException(s"missing required argument: $key")
      case Some(node) if !node.isIntegralNumber =>
        throw new IllegalArgumentException(
          s"argument '$key' must be a JSON integer, received ${typeName(node)}"
        )
      case Some(node) if !node.canConvertToInt =>
        throw new IllegalArgumentException(
          s"argument '$key' is outside the supported range: ${node.asText()}"
        )
      case Some(node) if node.asInt() < 1 =>
        throw new IllegalArgumentException(
          s"argument '$key' must be a positive identifier, received ${node.asInt()}"
        )
      case Some(node) => node.asInt()
    }

  private def isHexDigit(c: Char): Boolean =
    (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')

  private def typeName(node: JsonNode): String =
    if (node.isNull) "null"
    else if (node.isObject) "an object"
    else if (node.isArray) "an array"
    else if (node.isBoolean) "a boolean"
    else if (node.isFloatingPointNumber) "a fractional number"
    else if (node.isNumber) "a number"
    else node.getNodeType.toString.toLowerCase
}

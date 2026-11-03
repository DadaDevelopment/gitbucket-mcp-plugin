package com.newsrx.mcpplugin.mcp

import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Date

/**
 * Timestamps as a client can actually read them.
 *
 * Every date this gateway published used to go through `java.util.Date.
 * toString`, which produces `"Fri Oct 23 15:38:41 EEST 2026"`. Three things
 * are wrong with that for a machine-facing payload:
 *
 *  - it is rendered in the server JVM's default time zone, so the same instant
 *    reads differently on two hosts and the string carries an abbreviation
 *    (`EEST`) that is ambiguous and not parseable back to an offset;
 *  - the month and day names are locale-dependent;
 *  - it cannot be compared with GitBucket's own REST API, which publishes ISO
 *    8601 in UTC, so a consumer holding both answers cannot tell whether they
 *    agree.
 *
 * Measured, not assumed: the live gate's own `merged_at` comparison refused to
 * run against a JVM in `Europe/Kiev` because it could not turn `EEST` into an
 * offset. Earlier runs happened to be on a UTC host, which is exactly why this
 * stayed invisible.
 *
 * All timestamps are therefore published as ISO 8601 instants in UTC, to the
 * second, matching GitBucket's own API: `2026-10-23T12:38:41Z`.
 */
object Timestamps {

  private val iso = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")

  /** An instant in UTC, ISO 8601, second precision. */
  def iso8601(date: Date): String =
    iso.format(date.toInstant.atZone(ZoneOffset.UTC))
}

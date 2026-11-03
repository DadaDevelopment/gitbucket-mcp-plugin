package com.newsrx.mcpplugin

import java.util.Properties
import scala.util.Using

/**
 * The plugin descriptor as it ships inside the JAR.
 *
 * Read from the classpath rather than written into the code a second time, so
 * this cannot drift from the descriptor GitBucket itself loads. It remains
 * self-reported: it identifies what the running JAR says about itself, not
 * what produced its bytes.
 *
 * Owned by the plugin's root package rather than by `tools`, because both the
 * controller and the tool layer read it. Scoped to this package so it stays
 * an internal detail of the plugin.
 */
private[mcpplugin] object PluginDescriptor {
  private val props: Properties = {
    val p = new Properties()
    Option(getClass.getClassLoader.getResourceAsStream("gitbucket-plugin.properties")).foreach { in =>
      Using.resource(in)(p.load)
    }
    p
  }

  def get(key: String): String = Option(props.getProperty(key)).getOrElse("")
}

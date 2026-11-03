/**
 * The class GitBucket loads.
 *
 * GitBucket finds a plugin by loading the class literally named `Plugin` from
 * the root package of the JAR, so this is the only declaration that has any
 * effect at runtime: the `versions`, `anonymousAccessiblePaths` and
 * `controllers` GitBucket reads are the ones it finds here.
 *
 * It therefore extends the real definition rather than repeating it. The two
 * were separate declarations of the same plugin before, which is a shape that
 * fails quietly: a migration added to the other class was never run, the
 * plugin still loaded, and the missing table only surfaced as an error on the
 * first request that needed it.
 */
class Plugin extends com.newsrx.mcpplugin.MCPPlugin

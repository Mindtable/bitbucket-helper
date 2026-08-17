package com.mindtable.bitbuckethelper.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import java.io.OutputStream
import java.nio.file.Path
import kotlinx.coroutines.runBlocking

/**
 * Production dependencies for the product-only command tree exported to bootstrap assembly.
 * [globalOutputMode] is evaluated at leaf execution after the assembly-owned root has run.
 */
data class ProductCommandDependencies(
    val socketPath: Path,
    val standardOut: OutputStream = System.out,
    val standardErr: OutputStream = System.err,
    val terminal: TerminalCapability = TerminalCapability(System.console() != null),
    val openUrl: OpenUrl = MacOsOpenUrl(),
    val sleeper: Sleeper = CoroutineSleeper,
    val clientFactory: (Path) -> LocalApiClient = { path -> UnixSocketLocalApiClient(path) },
    val globalOutputMode: () -> OutputMode? = { null },
)

/** Returns unattached product commands; bootstrap remains the sole owner of the root command. */
fun productCommands(dependencies: ProductCommandDependencies): List<CliktCommand> = listOf(
    PullRequestCommandGroup(dependencies),
    InboxCliCommand(dependencies),
    OpenCliCommand(dependencies),
    AcknowledgeCliCommand(dependencies),
    RefreshCliCommand(dependencies),
    WorkspaceCommandGroup(dependencies),
    RepositoryCommandGroup(dependencies),
)

private class OutputSelection {
    var inherited: OutputMode? = null
}

private abstract class ProductCliCommand(
    name: String,
    protected val dependencies: ProductCommandDependencies,
    private val outputSelection: OutputSelection,
) : CliktCommand(name = name) {
    private val requestedOutput by option(
        "--output",
        metavar = "human|json",
        help = "Output format: human or json (default: human).",
    )

    protected fun publishOutputSelection() {
        requestedOutput?.let { outputSelection.inherited = parseOutputMode(it) }
    }

    protected fun execute(
        action: suspend (LocalApiClient, CliOutput, OutputMode) -> CliExit,
    ) {
        val mode = requestedOutput?.let(::parseOutputMode)
            ?: outputSelection.inherited
            ?: dependencies.globalOutputMode()
            ?: OutputMode.HUMAN
        val output = CliOutput(dependencies.standardOut, dependencies.standardErr, dependencies.terminal)
        val exit = dependencies.clientFactory(dependencies.socketPath).use { client ->
            runBlocking { action(client, output, mode) }
        }
        if (exit != CliExit.SUCCESS) throw ProgramResult(exit.code)
    }

    protected fun required(value: String?, parameterName: String): String = value
        ?: throw UsageError("missing required parameter $parameterName", parameterName, CliExit.USAGE_ERROR.code)

    private fun parseOutputMode(value: String): OutputMode = when (value) {
        "human" -> OutputMode.HUMAN
        "json" -> OutputMode.JSON
        else -> throw UsageError(
            "invalid value for --output: $value (choose human or json)",
            "--output",
            CliExit.USAGE_ERROR.code,
        )
    }
}

private class PullRequestCommandGroup(
    dependencies: ProductCommandDependencies,
    private val outputSelection: OutputSelection = OutputSelection(),
) : ProductCliCommand("pr", dependencies, outputSelection) {
    init {
        subcommands(
            PullRequestListCliCommand(dependencies, outputSelection),
            PullRequestShowCliCommand(dependencies, outputSelection),
        )
    }

    override fun help(context: Context): String = "Inspect pull requests from the local service."

    override fun run() = publishOutputSelection()
}

private class PullRequestListCliCommand(
    dependencies: ProductCommandDependencies,
    outputSelection: OutputSelection,
) : ProductCliCommand("list", dependencies, outputSelection) {
    override fun help(context: Context): String = "List configured pull requests."

    override fun run() = execute { client, output, mode -> PullRequestCommands(client, output).list(mode) }
}

private class PullRequestShowCliCommand(
    dependencies: ProductCommandDependencies,
    outputSelection: OutputSelection,
) : ProductCliCommand("show", dependencies, outputSelection) {
    private val pullRequestId by argument(
        name = "pull-request-id",
        help = "Opaque pull-request ID returned by the service.",
    ).optional()

    override fun help(context: Context): String = "Show one pull request."

    override fun run() {
        val id = required(pullRequestId, "<pull-request-id>")
        execute { client, output, mode -> PullRequestCommands(client, output).show(id, mode) }
    }
}

private class InboxCliCommand(
    dependencies: ProductCommandDependencies,
) : ProductCliCommand("inbox", dependencies, OutputSelection()) {
    override fun help(context: Context): String = "Show actionable inbox items."

    override fun run() = execute { client, output, mode -> InboxCommand(client, output).execute(mode) }
}

private class OpenCliCommand(
    dependencies: ProductCommandDependencies,
) : ProductCliCommand("open", dependencies, OutputSelection()) {
    private val pullRequestId by argument(
        name = "pull-request-id",
        help = "Opaque pull-request ID returned by the service.",
    ).optional()

    override fun help(context: Context): String = "Open one pull request in the browser."

    override fun run() {
        val id = required(pullRequestId, "<pull-request-id>")
        execute { client, output, mode -> OpenCommand(client, output, dependencies.openUrl).open(id, mode) }
    }
}

private class AcknowledgeCliCommand(
    dependencies: ProductCommandDependencies,
) : ProductCliCommand("ack", dependencies, OutputSelection()) {
    private val actionItemId by argument(
        name = "action-item-id",
        help = "Opaque action-item ID returned by the service.",
    ).optional()
    private val activityVersion by argument(
        name = "activity-version",
        help = "Exact opaque activity version to acknowledge.",
    ).optional()

    override fun help(context: Context): String = "Acknowledge one exact activity version."

    override fun run() {
        val itemId = required(actionItemId, "<action-item-id>")
        val version = required(activityVersion, "<activity-version>")
        execute { client, output, mode -> AcknowledgeCommand(client, output).acknowledge(itemId, version, mode) }
    }
}

private class RefreshCliCommand(
    dependencies: ProductCommandDependencies,
) : ProductCliCommand("refresh", dependencies, OutputSelection()) {
    private val repositoryIds by option(
        "--repository",
        metavar = "repository-id",
        help = "Refresh this opaque repository ID; repeat for more repositories.",
    ).multiple()
    private val noWait by option(
        "--no-wait",
        help = "Return after registration instead of polling to completion.",
    ).flag()

    override fun help(context: Context): String = "Refresh configured repository data."

    override fun run() = execute { client, output, mode ->
        RefreshCommand(client, output, dependencies.sleeper).refresh(repositoryIds, noWait, mode)
    }
}

private class WorkspaceCommandGroup(
    dependencies: ProductCommandDependencies,
    private val outputSelection: OutputSelection = OutputSelection(),
) : ProductCliCommand("workspace", dependencies, outputSelection) {
    init {
        subcommands(
            WorkspaceShowCliCommand(dependencies, outputSelection),
            WorkspaceConfigureCliCommand(dependencies, outputSelection),
        )
    }

    override fun help(context: Context): String = "Inspect or configure the workspace identity."

    override fun run() = publishOutputSelection()
}

private class WorkspaceShowCliCommand(
    dependencies: ProductCommandDependencies,
    outputSelection: OutputSelection,
) : ProductCliCommand("show", dependencies, outputSelection) {
    override fun help(context: Context): String = "Show the configured workspace."

    override fun run() = execute { client, output, mode -> WorkspaceCommands(client, output).show(mode) }
}

private class WorkspaceConfigureCliCommand(
    dependencies: ProductCommandDependencies,
    outputSelection: OutputSelection,
) : ProductCliCommand("configure", dependencies, outputSelection) {
    private val apiBaseUrl by option(
        "--api-base-url",
        metavar = "url",
        help = "Bitbucket API base URL for the immutable workspace identity.",
    )
    private val slug by option(
        "--slug",
        metavar = "slug",
        help = "Bitbucket workspace slug.",
    )

    override fun help(context: Context): String = "Configure the immutable workspace identity."

    override fun run() {
        val url = required(apiBaseUrl, "--api-base-url")
        val workspaceSlug = required(slug, "--slug")
        execute { client, output, mode -> WorkspaceCommands(client, output).configure(url, workspaceSlug, mode) }
    }
}

private class RepositoryCommandGroup(
    dependencies: ProductCommandDependencies,
    private val outputSelection: OutputSelection = OutputSelection(),
) : ProductCliCommand("repository", dependencies, outputSelection) {
    init {
        subcommands(
            RepositoryAddCliCommand(dependencies, outputSelection),
            RepositoryRemoveCliCommand(dependencies, outputSelection),
        )
    }

    override fun help(context: Context): String = "Maintain the configured repository allowlist."

    override fun run() = publishOutputSelection()
}

private class RepositoryAddCliCommand(
    dependencies: ProductCommandDependencies,
    outputSelection: OutputSelection,
) : ProductCliCommand("add", dependencies, outputSelection) {
    private val repositorySlug by argument(
        name = "slug",
        help = "Bitbucket repository slug.",
    ).optional()

    override fun help(context: Context): String = "Add a repository by slug."

    override fun run() {
        val slug = required(repositorySlug, "<slug>")
        execute { client, output, mode -> RepositoryCommands(client, output).add(slug, mode) }
    }
}

private class RepositoryRemoveCliCommand(
    dependencies: ProductCommandDependencies,
    outputSelection: OutputSelection,
) : ProductCliCommand("remove", dependencies, outputSelection) {
    private val repositoryId by argument(
        name = "repository-id",
        help = "Opaque repository ID returned by the service.",
    ).optional()

    override fun help(context: Context): String = "Remove a configured repository."

    override fun run() {
        val id = required(repositoryId, "<repository-id>")
        execute { client, output, mode -> RepositoryCommands(client, output).remove(id, mode) }
    }
}

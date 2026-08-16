package com.mindtable.bitbuckethelper.cli

import com.mindtable.bitbuckethelper.generated.api.v1.model.InboxAvailableResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.InboxResponse
import com.mindtable.bitbuckethelper.generated.api.v1.model.WorkspaceNotConfiguredResult

/** Reads the service-provided actionable inbox without deriving local policy. */
class InboxCommand(
    private val client: LocalApiClient,
    private val output: CliOutput,
) {
    suspend fun execute(mode: OutputMode): CliExit = executeRead(
        output = output,
        mode = mode,
        request = { client.get(INBOX_PATH, InboxResponse.serializer()) },
    ) { response, terminal ->
        when (val result = response?.result) {
            is InboxAvailableResult -> renderInbox(result, terminal)
            is WorkspaceNotConfiguredResult -> "Workspace is not configured. Run ${result.setupCommand}."
            null -> "The service returned an invalid response."
        }
    }

    private fun renderInbox(result: InboxAvailableResult, terminal: TerminalCapability): String {
        if (result.inbox.items.isEmpty()) return "No actionable inbox items."
        return buildString {
            appendLine(terminal.bold("Actionable inbox"))
            result.inbox.items.forEachIndexed { index, action ->
                if (index > 0) appendLine()
                append(renderActionItem(action, "Inbox item:"))
            }
        }.trimEnd()
    }

    private companion object {
        const val INBOX_PATH = "/api/v1/inbox"
    }
}

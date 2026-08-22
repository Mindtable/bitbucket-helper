# Manual local service run

This runbook starts the verified V1 service in the foreground. It is intended
for local development and smoke verification, not installation or deployment.
The current CLI implements `service run`; LaunchAgent-backed `service install`,
`start`, `stop`, `status`, `logs`, update, and uninstall flows are follow-ups.

## Prerequisites

- macOS with Unix-domain socket and secure directory-stream support;
- JDK 25;
- this repository and a populated local Gradle dependency cache;
- an executable `desktop-notifications` provider compatible with the pinned
  contract in
  [desktop-notifications-consumer.md](../contracts/desktop-notifications-consumer.md);
- `BITBUCKET_USERNAME` and `BITBUCKET_APP_PASSWORD` already loaded into the
  current shell from a secure local source.

Never paste credential values into a command line, commit them, or print them as
part of verification. The legacy-named `BITBUCKET_APP_PASSWORD` variable holds a
current Bitbucket API token.

Check only that the two credential variables are present:

```bash
test -n "${BITBUCKET_USERNAME:-}" || { printf '%s\n' 'BITBUCKET_USERNAME is missing' >&2; exit 2; }
test -n "${BITBUCKET_APP_PASSWORD:-}" || { printf '%s\n' 'BITBUCKET_APP_PASSWORD is missing' >&2; exit 2; }
```

These checks do not print either value.

## Build without network access

```bash
./gradlew --offline clean check verifyApiV1Generated
./gradlew --offline buildFatJar
```

Offline mode never downloads a missing dependency. If the local cache is
incomplete, populate it separately under the appropriate network policy, then
repeat this runbook.

The executable artifact is
`build/libs/bitbucket-helper-0.1.0-all.jar`.

## Prepare private local paths

The service requires the socket parent to exist, belong to the current user,
have exact mode `0700`, and support secure directory access. The database parent
has the same current-user ownership, exact `0700`, writability, and secure
directory-access requirements. The logging parent must also be explicitly
created with exact mode `0700`; the final `var/log` directory may be missing and
the service creates it owner-only. Missing intermediate directories are not
created by the service, so a default `var` parent must exist before startup.
`var/` is ignored by Git.

```bash
install -d -m 700 "$PWD/var"
export BITBUCKET_HELPER_DATABASE_PATH="$PWD/var/bitbucket-helper.sqlite"
export BITBUCKET_HELPER_UNIX_SOCKET_PATH="$PWD/var/bitbucket-helper.sock"
export BITBUCKET_HELPER_HTTP_PORT=8080
export BITBUCKET_HELPER_NOTIFICATION_EXECUTABLE="/absolute/path/to/desktop-notifications"
export BITBUCKET_HELPER_LOG_DIRECTORY="$PWD/var/log"
# Optional: DEBUG is the default in both terminal and JSON destinations.
export BITBUCKET_HELPER_LOG_LEVEL=DEBUG

test -x "$BITBUCKET_HELPER_NOTIFICATION_EXECUTABLE"
test "$(stat -f '%Lp' "$PWD/var")" = 700
test ! -e "$PWD/var/log"
```

Replace the notification path with the real absolute executable path. It is not
a credential. If the database file is missing, the service creates it atomically
with exact mode `0600`; missing private database directories are created with
exact mode `0700`. An existing database and any existing `-journal`, `-wal`, or
`-shm` sidecar must each be a current-user-owned, writable regular file with
exact mode `0600`.

Database entries and their managed parent directories must not be symbolic
links. Startup also rejects an ancestry that another user could replace: every
canonical ancestor must be owned by the current user or root, and an ancestor
writable by another user is accepted only when sticky-directory ownership keeps
the managed child replacement-safe. A startup error naming
`BITBUCKET_HELPER_DATABASE_PATH` therefore means to check ownership, exact modes,
symlinks, secure directory-stream support, and replacement-safe ancestry before
retrying. Do not merely follow or recreate an unsafe path.

The service reads non-secret environment overrides before the matching
`application.conf` values. The browser bind address is always `127.0.0.1`.

Logging is backend-only and starts before persistence, clients, the scheduler,
or HTTP servers. The active file is
`var/log/bitbucket-helper.jsonl` by default. Both terminal and JSON Lines
outputs use the configured level, which defaults to `DEBUG`. The file rotates
at UTC-day boundaries or 10 MiB, whichever comes first; archives are gzip
compressed, retained for at most 14 days, and capped at 200 MiB combined.
The final directory and active/archive files are owner-only (`0700` and
`0600`). Unsafe parents, symlinks, ownership, permissions, or replacement-risk
ancestry stop startup before runtime resources open; the service never creates
missing intermediate parents.

## Start and verify

Start the service in the foreground:

```bash
java -jar build/libs/bitbucket-helper-0.1.0-all.jar service run
```

Keep that terminal open. In a second shell, export only the same non-secret
socket and port settings, then check the loopback health envelope:

The first successful startup creates the final log directory and active file;
verify their owner-only modes before continuing:

```bash
test "$(stat -f '%Lp' "$PWD/var/log")" = 700
test "$(stat -f '%Lp' "$PWD/var/log/bitbucket-helper.jsonl")" = 600
```

```bash
export BITBUCKET_HELPER_UNIX_SOCKET_PATH="$PWD/var/bitbucket-helper.sock"
export BITBUCKET_HELPER_HTTP_PORT=8080

curl --fail-with-body --silent --show-error \
  "http://127.0.0.1:${BITBUCKET_HELPER_HTTP_PORT}/api/v1/health"
```

The health route returns a typed HTTP `200` envelope even when a component is
`degraded` or `unhealthy`. A `4xx` or `500` is not a business-health result.

Verify Unix-socket discovery through a product command:

```bash
java -jar build/libs/bitbucket-helper-0.1.0-all.jar workspace show --output json
```

Before configuration, `workspaceNotConfigured` is an expected HTTP `200`
read result and `workspace show` exits `0`. The JSON document remains on stdout.
Product commands load the socket path without requiring Bitbucket credentials,
the database path, or the provider path.

Search the JSON Lines file by a value you already observed in a response. Keep
the placeholder unchanged until you replace it with the corresponding stable
identifier; these commands do not print credentials or private values:

```bash
jq -c 'select(.request_id == "req_REPLACE_WITH_ID")' var/log/bitbucket-helper.jsonl
jq -c 'select(.refresh_run_id == "rr_REPLACE_WITH_ID")' var/log/bitbucket-helper.jsonl
jq -c 'select(.repository_id == "repo_REPLACE_WITH_ID")' var/log/bitbucket-helper.jsonl
jq -c 'select(.scheduler_execution_id == "se_REPLACE_WITH_ID")' var/log/bitbucket-helper.jsonl
jq -c 'select(.notification_intent_id == "ni_REPLACE_WITH_ID")' var/log/bitbucket-helper.jsonl
```

Payloads, upstream bodies, notification content, and exception messages are
intentionally absent. Unexpected failures retain safe exception class names,
cause relationships, stack-frame class/method/file/line locations, and explicit
truncation metadata. Do not diagnose an incident by searching for a raw URL,
request body, SQL value, or exception message; use the stable event and
correlation fields instead.

To configure a real workspace, supply only its non-secret API base URL and slug:

```bash
java -jar build/libs/bitbucket-helper-0.1.0-all.jar \
  workspace configure \
  --api-base-url https://api.bitbucket.org/2.0 \
  --slug WORKSPACE_SLUG \
  --output json
```

## User-executed assembled-system acceptance checklist

This is a manual acceptance checklist for a live Bitbucket account. It has not
been executed by the automated suite and must not be treated as an automated
end-to-end result.

1. Start only the Java service with `java -jar
   build/libs/bitbucket-helper-0.1.0-all.jar service run`; do not start Node,
   npm, or Vite.
2. In another shell with `BITBUCKET_HELPER_UNIX_SOCKET_PATH` set, configure the
   workspace:

   ```bash
   java -jar build/libs/bitbucket-helper-0.1.0-all.jar \
     workspace configure --api-base-url https://api.bitbucket.org/2.0 \
     --slug WORKSPACE_SLUG
   ```

3. Add a repository and run (or await) its refresh:

   ```bash
   java -jar build/libs/bitbucket-helper-0.1.0-all.jar repository add REPOSITORY_SLUG
   java -jar build/libs/bitbucket-helper-0.1.0-all.jar refresh
   ```

4. Open `http://127.0.0.1:8080/` and verify the workspace, repository, and PR
   state shown by the configured backend.
5. Open a PR drawer and verify detail, live exact-version content, and an
   acknowledgment against the live account.
6. Confirm no Node/Vite process is running and that browser API calls are
   same-origin (`http://127.0.0.1:8080`).

Record the result of these steps separately. Live Bitbucket end-to-end
acceptance remains pending until a user executes this checklist.

## Missing dependency versus business outcome

- The service resolves the configured provider path against its working
  directory and normalizes it. If the result is missing, non-regular, or
  non-executable, configuration fails before runtime resources open. The process
  exits `2` and names `BITBUCKET_HELPER_NOTIFICATION_EXECUTABLE` without printing
  the configured path or credentials.
- If no service is listening on the discovered Unix socket, a product command
  writes the fixed CLI-owned `SERVICE_UNAVAILABLE` JSON document in JSON mode
  and exits `4`. The current message mentions future status/start commands; for
  this V1 use the foreground `service run` procedure above.
- A valid mutation or refresh request whose typed `result.type` says the
  requested business state was not achieved still came from HTTP `200`; the
  CLI preserves that envelope and exits `3`. Read-only `workspace show` exits
  `0` for both configured and not-configured states.

Do not diagnose a missing provider or missing service as a Bitbucket business
outcome.

## Stop and clean up

Return to the foreground service terminal and press `Ctrl-C`. The shutdown hook
stops request acceptance, shuts down Quartz, joins the service coroutine scope,
closes the Bitbucket client and SQLite, and removes the captured Unix socket.

Verify shutdown from the second shell:

```bash
test ! -e "$BITBUCKET_HELPER_UNIX_SOCKET_PATH"
java -jar build/libs/bitbucket-helper-0.1.0-all.jar workspace show --output json
test "$?" -eq 4
```

The SQLite file is durable state; keep or back it up unless this was explicitly
a disposable run. Do not remove the socket path while the service is running.
Finally remove credentials and local overrides from both shells:

```bash
unset BITBUCKET_USERNAME BITBUCKET_APP_PASSWORD
unset BITBUCKET_HELPER_DATABASE_PATH BITBUCKET_HELPER_UNIX_SOCKET_PATH
unset BITBUCKET_HELPER_HTTP_PORT BITBUCKET_HELPER_NOTIFICATION_EXECUTABLE
unset BITBUCKET_HELPER_LOG_LEVEL BITBUCKET_HELPER_LOG_DIRECTORY
```

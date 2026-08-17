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
directory-access requirements. `var/` is ignored by Git.

```bash
install -d -m 700 "$PWD/var"
export BITBUCKET_HELPER_DATABASE_PATH="$PWD/var/bitbucket-helper.sqlite"
export BITBUCKET_HELPER_UNIX_SOCKET_PATH="$PWD/var/bitbucket-helper.sock"
export BITBUCKET_HELPER_HTTP_PORT=8080
export BITBUCKET_HELPER_NOTIFICATION_EXECUTABLE="/absolute/path/to/desktop-notifications"

test -x "$BITBUCKET_HELPER_NOTIFICATION_EXECUTABLE"
test "$(stat -f '%Lp' "$PWD/var")" = 700
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

## Start and verify

Start the service in the foreground:

```bash
java -jar build/libs/bitbucket-helper-0.1.0-all.jar service run
```

Keep that terminal open. In a second shell, export only the same non-secret
socket and port settings, then check the loopback health envelope:

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

To configure a real workspace, supply only its non-secret API base URL and slug:

```bash
java -jar build/libs/bitbucket-helper-0.1.0-all.jar \
  workspace configure \
  --api-base-url https://api.bitbucket.org/2.0 \
  --slug WORKSPACE_SLUG \
  --output json
```

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
```

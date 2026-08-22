# Install and access the Web UI

This guide builds and runs Bitbucket Helper from a source checkout and opens
the current Vue Web UI. It describes the capabilities available in this
repository today.

> **Current limitation:** Bitbucket Helper does not yet provide `service
> install`, `start`, `stop`, or other background-service commands. The Kotlin
> service runs in the foreground. The Web UI is a separate fixture-backed
> development application; it does not call the Kotlin service or display live
> Bitbucket data yet.

## Prerequisites

Install or provide:

- macOS;
- JDK 25, with `java` available on `PATH`;
- Node.js `^22.22.2`, `^24.15.0`, or `>=26.0.0` and npm `11.17.0` for
  the Web UI;
- a current Bitbucket API token and its Atlassian account email;
- the compatible `desktop-notifications` executable; and
- network access for the first Gradle and npm dependency download, unless the
  required caches are already populated.

The notification provider is maintained in the independent
`desktop-notifications` project. If its source checkout is available next to
this repository, install it with UV from the Bitbucket Helper repository root:

```bash
uv tool install ../desktop-notifications
command -v desktop-notifications
desktop-notifications --version
```

The provider requires Python 3.12, UV, and `terminal-notifier` 2.0.0. If the
provider checkout is elsewhere, pass its path to `uv tool install`. Record the
absolute path printed by `command -v`; the service validates and invokes that
exact executable. See the
[`desktop-notifications` consumer contract](contracts/desktop-notifications-consumer.md)
for the pinned compatible provider version and process contract.

## 1. Build the service

From the Bitbucket Helper repository root, confirm the JDK and build the
executable fat JAR:

```bash
java -version
./gradlew clean check verifyApiV1Generated
./gradlew buildFatJar
```

The resulting executable is:

```text
build/libs/bitbucket-helper-0.1.0-all.jar
```

When all Gradle dependencies are already cached, add `--offline` to both
Gradle commands to prevent network access.

## 2. Load credentials

Load these variables into the service shell from a secure local source:

- `BITBUCKET_USERNAME`: the Atlassian account email used for Basic
  authentication;
- `BITBUCKET_APP_PASSWORD`: a legacy variable name whose value must be a
  current Bitbucket API token, not a retired app password.

The token needs at least `read:user:bitbucket` for identity lookup. Do not put
credential values in this repository, configuration files, command arguments,
or diagnostic output.

Check that both variables are present without printing their values:

```bash
test -n "${BITBUCKET_USERNAME:-}" || { printf '%s\n' 'BITBUCKET_USERNAME is missing' >&2; exit 2; }
test -n "${BITBUCKET_APP_PASSWORD:-}" || { printf '%s\n' 'BITBUCKET_APP_PASSWORD is missing' >&2; exit 2; }
```

## 3. Prepare local runtime paths

The service requires private, current-user-owned runtime directories. From the
repository root, run:

```bash
install -d -m 700 "$PWD/var"
export BITBUCKET_HELPER_DATABASE_PATH="$PWD/var/bitbucket-helper.sqlite"
export BITBUCKET_HELPER_UNIX_SOCKET_PATH="$PWD/var/bitbucket-helper.sock"
export BITBUCKET_HELPER_HTTP_PORT=8080
export BITBUCKET_HELPER_NOTIFICATION_EXECUTABLE="/absolute/path/to/desktop-notifications"
export BITBUCKET_HELPER_LOG_DIRECTORY="$PWD/var/log"
export BITBUCKET_HELPER_LOG_LEVEL=DEBUG
```

Replace the notification executable placeholder with the absolute path found
earlier. Do not create `var/log` manually for a fresh run; the service creates
it with owner-only permissions. Existing runtime paths must pass the ownership,
mode, regular-file, and symbolic-link checks detailed in the
[manual service runbook](operations/manual-service-run.md).

## 4. Start and verify the service

Start the service in the foreground:

```bash
java -jar build/libs/bitbucket-helper-0.1.0-all.jar service run
```

Keep that terminal open. The browser-facing API binds only to
`127.0.0.1:8080` by default.

In a second terminal opened at the repository root, verify the health endpoint:

```bash
curl --fail-with-body --silent --show-error \
  http://127.0.0.1:8080/api/v1/health
```

A valid health request returns HTTP `200` with a typed health result, including
when a component reports a degraded or unhealthy business state.

To configure the workspace, make the Unix socket discoverable in the second
terminal and run:

```bash
export BITBUCKET_HELPER_UNIX_SOCKET_PATH="$PWD/var/bitbucket-helper.sock"
java -jar build/libs/bitbucket-helper-0.1.0-all.jar \
  workspace configure \
  --api-base-url https://api.bitbucket.org/2.0 \
  --slug WORKSPACE_SLUG \
  --output json
```

Replace `WORKSPACE_SLUG` with the Bitbucket workspace slug. Product CLI
commands communicate with the running service through the Unix socket; they do
not use the loopback HTTP port.

## 5. Start and open the Web UI

Open a third terminal at the repository root and install the frontend
dependencies:

```bash
cd web
npm ci
npm run dev
```

Keep the Vite process running, then open:

<http://127.0.0.1:5173/>

Vite prints the authoritative URL; use its printed port if `5173` was already
occupied. The default page uses the `healthy-refresh` fixture journey. Other
deterministic UI journeys are listed in the [Web UI README](../web/README.md).

The UI currently reads only in-process fixture data. Starting or configuring
the Kotlin service does not change what the UI displays. Conversely, the UI can
be explored without starting the Kotlin service.

For a production-build preview instead of the development server, run:

```bash
cd web
npm ci
npm run build
npm run preview
```

Open the loopback URL printed by Vite.

## Stop the processes

Press `Ctrl-C` in the Vite terminal and in the foreground service terminal. The
service removes its captured Unix socket during an orderly shutdown. The SQLite
database and logs under `var/` are durable local state and remain in place.

For security checks, diagnostic log verification, failure interpretation, and
cleanup details, continue with the
[manual local service runbook](operations/manual-service-run.md).

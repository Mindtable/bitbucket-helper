# Install and access the Web UI

This guide builds and runs Bitbucket Helper from a source checkout, configures a
workspace through the product CLI, and opens the assembled Web UI. The running
fat JAR serves both the Vue UI and the Kotlin V1 API.

> **Current limitation:** Bitbucket Helper does not yet provide `service
> install`, `start`, `stop`, or other background-service commands. The Kotlin
> service runs in the foreground. The Web UI is part of the fat JAR in normal
> product use; Node.js, npm, and Vite are build-time/development tools only.

## Prerequisites

Install or provide:

- macOS;
- JDK 25, with `java` available on `PATH`;
- Node.js `^22.22.2`, `^24.15.0`, or `>=26.0.0` and npm `11.17.0` to build the
  Web UI from source (not to run the product);
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

## 1. Build the service and embedded UI

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

Gradle runs the production Web UI build and embeds it in this artifact. When all
Gradle dependencies are already cached, add `--offline` to both Gradle commands
to prevent network access. Do not run npm to use the built product.

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

## 4. Start, configure, and refresh

Start only the Java service in the foreground:

```bash
java -jar build/libs/bitbucket-helper-0.1.0-all.jar service run
```

Keep that terminal open. The browser-facing UI and API bind only to
`127.0.0.1:8080` by default. In a second terminal opened at the repository
root, export the same non-secret socket setting and configure the workspace:

```bash
export BITBUCKET_HELPER_UNIX_SOCKET_PATH="$PWD/var/bitbucket-helper.sock"
java -jar build/libs/bitbucket-helper-0.1.0-all.jar \
  workspace configure \
  --api-base-url https://api.bitbucket.org/2.0 \
  --slug WORKSPACE_SLUG \
  --output json
java -jar build/libs/bitbucket-helper-0.1.0-all.jar repository add REPOSITORY_SLUG
java -jar build/libs/bitbucket-helper-0.1.0-all.jar refresh
```

Replace `WORKSPACE_SLUG` and `REPOSITORY_SLUG` with Bitbucket slugs. Product
CLI commands communicate with the running service through the Unix socket; they
do not use the loopback HTTP port. `refresh` may be run explicitly or awaited
when the scheduled refresh supplies the state you need.

You can verify the health endpoint in the second terminal:

```bash
curl --fail-with-body --silent --show-error \
  http://127.0.0.1:8080/api/v1/health
```

A valid health request returns HTTP `200` with a typed health result, including
when a component reports a degraded or unhealthy business state.

## 5. Open the assembled Web UI

Open the backend root in a browser:

```bash
open http://127.0.0.1:8080/
```

The browser uses same-origin API calls to the Java service. There is no Vite
server in this product flow, and npm is not a runtime dependency.

## User-executed manual acceptance checklist

This live Bitbucket assembled-system checklist is manual and unexecuted. It is
not an automated end-to-end success claim.

1. Start only the Java service; do not run Node, npm, or Vite.
2. Configure the workspace with `workspace configure`.
3. Add a repository with `repository add <slug>`.
4. Run or await `refresh`.
5. Open the configured backend root at `http://127.0.0.1:8080/`.
6. Verify workspace, repository, and PR state; drawer detail; live
   exact-version content; and acknowledgment.
7. Confirm no Node/Vite process is running and browser API calls are
   same-origin.

Live Bitbucket end-to-end acceptance remains pending until a user completes and
records this checklist.

## Stop the service

Press `Ctrl-C` in the foreground service terminal. The service removes its
captured Unix socket during an orderly shutdown. The SQLite database and logs
under `var/` are durable local state and remain in place.

For security checks, diagnostic log verification, failure interpretation, and
cleanup details, continue with the
[manual local service runbook](operations/manual-service-run.md).

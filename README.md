# Bitbucket Helper

## High-level idea

1. Fetch my open pull requests.
2. Fetch their comments and actionable activity.
3. Notify when another author creates activity that needs attention.
4. Remind hourly about unacknowledged activity.
5. Provide a small web page for acknowledgment and pull-request grouping.
6. Treat my reply in the same thread as acknowledgment.

## Kotlin walking skeleton

The current Kotlin/JVM service proves the infrastructure path from an immediate
Quartz refresh through the OpenAPI-generated Bitbucket client, the application
use case, jOOQ and SQLite, and the Ktor status endpoint.

`BITBUCKET_USERNAME` must contain the Atlassian account email used for Basic
authentication. `BITBUCKET_APP_PASSWORD` is only a legacy variable name: its
value must be a current Bitbucket API token, not a retired app password. The token
requires the `read:user:bitbucket` scope.

Build and run the service with JDK 25:

```bash
export BITBUCKET_USERNAME='person@example.com'
export BITBUCKET_APP_PASSWORD='<current Bitbucket API token>'
./gradlew clean check
./gradlew buildFatJar
java -jar build/libs/bitbucket-helper-0.1.0-all.jar service run
curl http://127.0.0.1:8080/api/v1/bitbucket/status
```

Every valid status request returns `200 OK`; the versioned response body carries
the connection outcome:

- `pending` means no refresh result has been persisted yet.
- `healthy` includes the latest authenticated account and success timestamps.
- `failed` includes a sanitized failure and preserves any last-known-good account.

## Architecture direction

The approved scope and boundaries for this implementation are documented in the
[Kotlin walking-skeleton design](docs/superpowers/specs/2026-08-15-kotlin-walking-skeleton-design.md)
and its [implementation plan](docs/superpowers/plans/2026-08-15-kotlin-walking-skeleton.md).
The broader product structure remains documented in the
[architecture specification](docs/superpowers/specs/2026-08-14-bitbucket-assistant-architecture-design.md).

The approved SPA-to-Kotlin JSON boundary is documented in the
[API contract specification](docs/superpowers/specs/2026-08-15-spa-kotlin-api-contract-design.md).

The approved fixture-backed Vue project structure is documented in the
[Vue project structure specification](docs/superpowers/specs/2026-08-15-vue-project-structure-design.md).
The runnable workspace lives under `web/` and remains disconnected from Kotlin
until the canonical OpenAPI document and generated client are available.

The reusable generic Python notification foundation now lives in the independent
sibling repository `../desktop-notifications`. Its public CLI boundary and
externally observable macOS delivery behavior are approved in
`desktop-notifications/docs/superpowers/specs/2026-08-15-desktop-notifications-cli-contract-design.md`;
implementation remains pending.

## Deferred Testcontainers integration suite

A separate follow-up will design and implement a dedicated Testcontainers-backed
integration suite; it is intentionally outside this walking skeleton.

## Shell prototype

The untracked `source/` directory is an independent preserved shell prototype. It
is not part of the Kotlin build or the sibling Python package.

```bash
bash source/tests/run.sh
```

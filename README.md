# Bitbucket Helper

## High-level idea

1. Fetch my open pull requests.
2. Fetch their comments and actionable activity.
3. Notify when another author creates activity that needs attention.
4. Remind hourly about unacknowledged activity.
5. Provide a small web page for acknowledgment and pull-request grouping.
6. Treat my reply in the same thread as acknowledgment.

## Architecture direction

Bitbucket Helper is designed as a Kotlin/JVM application. Its approved structure,
boundaries, and deferred implementation gates are documented in the
[architecture specification](docs/superpowers/specs/2026-08-14-bitbucket-assistant-architecture-design.md).
The Kotlin/Gradle foundation has not been created yet.

The reusable generic Python notification foundation now lives in the independent
sibling repository `../desktop-notifications`. Its public CLI and macOS delivery
behavior remain separate design work.

## Shell prototype

The untracked `source/` directory is an independent preserved shell prototype. It
is not part of the future Kotlin build or the sibling Python package.

```bash
bash source/tests/run.sh
```

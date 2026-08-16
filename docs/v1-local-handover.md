# V1 local handover

Snapshot: 2026-08-16. The immutable shared baseline is
`aaaf435458ae4e0489686b8f74c390be51b2f155`.

## Active lanes

| Lane | Branch | Worktree | Snapshot HEAD | State |
| --- | --- | --- | --- | --- |
| Core and persistence | `codex/v1-core-persistence` | `.worktrees/v1-core-persistence` | `526ef9ee58480a6bec3887fd6b53941bce927979` | Clean; implementation reaches refresh-run monitor terminalization and includes notification fact timestamps. |
| API and transports | `codex/v1-api-transports` | `.worktrees/v1-api-transports` | `a7ecb02dc8da01eb67a9e3a0e0679b0ce47cac68` | Clean; selected client generation and Bitbucket installation identity work are committed. |
| Product CLI | `codex/v1-product-cli` | `.worktrees/v1-product-cli` | `aaaf435458ae4e0489686b8f74c390be51b2f155` | Clean; no lane implementation commits yet. |
| Notifications and scheduler | `codex/v1-notifications-scheduler` | `.worktrees/v1-notifications-scheduler` | `c46fc1c2cdfa827e52f26808e0c6fd0c7887d12e` | Clean; Tasks 1-4 are committed through the pure notification-intent policy. Tasks 5-9 remain. |

The older `.worktrees/v1-baseline-preparation` checkout is retained for reference
and is not one of the four active lanes. The four implementation prompts live in
`.superpowers/handoffs/v1-b0/prompts/`.

The primary checkout also has deliberately uncommitted user work:
`docs/project-backlog.md` and the independent `source/` shell prototype. Neither
is included in a Git branch or bundle unless it is copied separately.

## Set up on another PC

Install Git and JDK 25. Web work additionally needs Node `^22.22.2`, `^24.15.0`,
or `>=26`, with npm 11.17.0. No Git remote is currently configured, so choose one
transfer route.

### Route A: publish to a private remote

On this PC:

```bash
git remote add origin <private-repository-url>
git push -u origin main codex/v1-core-persistence codex/v1-api-transports codex/v1-product-cli codex/v1-notifications-scheduler
```

On the other PC:

```bash
git clone <private-repository-url> bitbucket-helper
cd bitbucket-helper
git fetch origin
./scripts/setup-v1-worktrees.sh
```

### Route B: stay local with a Git bundle

On this PC, after committing any additional work that must travel:

```bash
git bundle create ../bitbucket-helper-v1.bundle --all
```

Copy the bundle to the other PC, then:

```bash
git clone /path/to/bitbucket-helper-v1.bundle bitbucket-helper
cd bitbucket-helper
./scripts/setup-v1-worktrees.sh
```

A bundle contains commits and refs, not uncommitted files. To carry the preserved
root WIP separately, export `docs/project-backlog.md` with `git diff --binary`
and copy the `source/` directory as an archive.

## Hydrate and verify

The first Gradle build on a new PC needs network access to populate its cache;
subsequent checks may use `--offline`.

```bash
./gradlew clean check verifyApiV1Generated
cd web
npm ci
npm run check
```

Run those commands in each lane that it affects. Live Bitbucket access additionally
requires `BITBUCKET_USERNAME` and `BITBUCKET_APP_PASSWORD` as documented in the
root README; tests do not require credentials. Live desktop delivery also depends
on the separate sibling `desktop-notifications` project.

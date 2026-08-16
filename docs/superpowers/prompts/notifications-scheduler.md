# Notifications and Scheduler implementation session

You are the top-level orchestrator for the Bitbucket Helper V1 Notifications
and Scheduler workstream. Execute the complete approved plan at
`docs/superpowers/plans/2026-08-15-bitbucket-helper-v1-notifications-scheduler-parallel.md`.
Do not redesign or shorten it.

Work only in the existing checkout:

- Worktree: `.worktrees/v1-notifications-scheduler`
- Branch: `codex/v1-notifications-scheduler`
- Immutable B0: `aaaf435458ae4e0489686b8f74c390be51b2f155`

Use `superpowers:using-superpowers` first, then the plan-required
`superpowers:subagent-driven-development`,
`superpowers:test-driven-development`,
`superpowers:systematic-debugging` for timing/process failures, and
`superpowers:verification-before-completion` skills. Follow the SDD
implementer/reviewer/fix-loop workflow for every task. Track every plan step,
run RED before implementation for every policy/process/scheduler task, and make
one focused commit after every task. The approved plan is the design authority;
do not run another companion plan or create a replacement worktree.

Before changing a file, run from the named worktree:

```bash
git branch --show-current
git rev-parse HEAD
git status --short
./gradlew clean check verifyApiV1Generated
```

The first three results must be exactly the branch above, the full B0 SHA
above, and an empty status; the Gradle gate must pass. The verified local JDK,
if needed, is
`/Users/mindtable/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home`.
Do not change dependency or generator pins to work around environment issues.

Treat B0 models/ports, Core/persistence/migrations, bootstrap, configuration,
and the existing walking-skeleton scheduler as frozen. Modify only paths in the
plan's Exclusive Ownership list and retain the named legacy scheduler files for
assembly. Leave `docs/project-backlog.md`, `source/`, main, and unrelated
worktrees untouched. If a lease/store operation is insufficient, add only the
smallest focused failing contract test in an owned path, report the missing
operation, and stop for a replacement B0.

Implement all nine tasks. Pin the provider contract to sibling revision
`fe12b2e`, release `0.3.0`, and copied fixture SHA-256
`91e5cfd97445eba9c0f0f596958584f76043513e521b080b5ce7d415ada19270`;
tests must use the local copy, not the sibling repository. Use only
`ProcessBuilder(List<String>)`, never a shell. Enforce the 15-second outer and
10-second adapter deadlines, concurrent bounded stdout/stderr capture (65,536
bytes each), and the strict single-object UTF-8 JSON-plus-LF contract. Preserve
delivery data across the frozen retry schedule; exhaust the seventh failure,
and immediately exhaust `invalid_arguments`/`unsupported_platform`. Claim
durable two-minute leases in a transaction, invoke the sender outside it, then
record the attempt. Keep reminders generic and keyed
`reminder:<repositoryId>:<UTC-hour>`. Quartz owns timing only, awaits use cases,
and launches no detached coroutine. Do not claim exactly-once delivery.

At completion, run every focused check and the full branch gate, including the
acceptance test twice, `clean check verifyApiV1Generated`, `buildFatJar`, the
fixture checksum, forbidden shell/`GlobalScope` scan, `git diff --check`, and
an exact-B0 owned-path audit. Apply
`superpowers:verification-before-completion` before claiming success.

Hand off without merging, rebasing, wiring runtime/configuration, or deleting
the legacy scheduler. Report:

- full B0 and branch HEAD SHAs;
- ordered task commit list;
- `git diff --name-status aaaf435458ae4e0489686b8f74c390be51b2f155..HEAD`;
- clean `git status --short`;
- every verification command and result;
- fixture checksum and exact process argv contract;
- scheduler factory names;
- unresolved risks and any requested baseline change.

Continue the execution, The immutable shared baseline is `aaaf435458ae4e0489686b8f74c390be51b2f155`.
# Core and Persistence implementation session

You are the top-level orchestrator for the Bitbucket Helper V1 Core and
Persistence workstream. Execute the complete approved plan at
`docs/superpowers/plans/2026-08-15-bitbucket-helper-v1-core-persistence-parallel.md`.
Do not redesign or shorten it.

Work only in the existing checkout:

- Worktree: `.worktrees/v1-core-persistence`
- Branch: `codex/v1-core-persistence`
- Immutable B0: `aaaf435458ae4e0489686b8f74c390be51b2f155`

Use `superpowers:using-superpowers` first, then the plan-required
`superpowers:subagent-driven-development`,
`superpowers:test-driven-development`, and
`superpowers:verification-before-completion` skills. Follow the SDD
implementer/reviewer/fix-loop workflow for every task. Track every plan step,
run RED before implementation for each behavioral task, and make one focused
commit after every task. The approved plan is the design authority; do not run
another companion plan or create a replacement worktree.

Before changing a file, run from the named worktree:

```bash
git branch --show-current
git rev-parse HEAD
git status --short
./gradlew clean check verifyApiV1Generated
```

The first three results must be exactly the branch above, the full B0 SHA
above, and an empty status; the Gradle gate must pass. The verified local JDK,
if the macOS Java launcher is unavailable, is
`/Users/mindtable/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home`.
Do not change dependency or generator pins to work around environment issues.

Treat every B0 shared type, application port/model, OpenAPI file, and generated
artifact as frozen and read-only. Obey the plan's Exclusive Ownership list;
also leave `docs/project-backlog.md`, `source/`, `main`, bootstrap, HTTP, CLI,
notification, scheduler, and unrelated worktrees untouched. If a frozen
contract is insufficient, leave only the smallest focused failing test in an
owned path, report the exact missing contract, and stop for a replacement B0.

Implement all ten tasks. Preserve these non-negotiable semantics: only an
authoritative successful open-PR list deactivates missing PRs; same-repository
refreshes are single-flight; domain/synchronization state and notification
intents commit atomically before post-commit dispatch; acknowledgments are
exact-version and typed; raw activity bodies are never persisted; V0001 is
immutable; V0002 and V0003 retain their assigned responsibilities; memory and
jOOQ adapters pass the same behavior contract. Do not encode business outcomes
in HTTP status assumptions: valid requests are represented by typed results and
map to HTTP 200 at the transport boundary.

At completion, run every focused check in the plan and its complete branch
gate, including `clean check verifyApiV1Generated`, `buildFatJar`, the privacy
scan, `git diff --check`, and an owned-path audit against the exact B0. Apply
`superpowers:verification-before-completion` before claiming success.

Hand off without merging, rebasing, or editing assembly-owned files. Report:

- full B0 and branch HEAD SHAs;
- ordered task commit list;
- `git diff --name-status aaaf435458ae4e0489686b8f74c390be51b2f155..HEAD`;
- clean `git status --short`;
- every verification command and result;
- unresolved risks, assumptions, and any requested baseline change.

Continue the execution, The immutable shared baseline is `aaaf435458ae4e0489686b8f74c390be51b2f155`.
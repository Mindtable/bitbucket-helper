# Bitbucket and API Transports implementation session

You are the top-level orchestrator for the Bitbucket Helper V1 Bitbucket and
API Transports workstream. Execute the complete approved plan at
`docs/superpowers/plans/2026-08-15-bitbucket-helper-v1-api-transports-parallel.md`.
Do not redesign or shorten it.

Work only in the existing checkout:

- Worktree: `.worktrees/v1-api-transports`
- Branch: `codex/v1-api-transports`
- Immutable B0: `aaaf435458ae4e0489686b8f74c390be51b2f155`

Use `superpowers:using-superpowers` first, then the plan-required
`superpowers:subagent-driven-development`,
`superpowers:test-driven-development`, and
`superpowers:verification-before-completion` skills. Follow the SDD
implementer/reviewer/fix-loop workflow for every task. Track every plan step,
run RED before implementation for each adapter/route task, and make one focused
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
if needed, is
`/Users/mindtable/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home`.
Do not change approved dependency or generator pins to work around the local
environment.

Treat B0 ports/models, `openapi/**`, committed product DTOs, and shared fixtures
as frozen. Never run `syncApiV1Generated` on this branch. Obey the plan's
Exclusive Ownership list and preserve its two transitional compatibility
artifacts until assembly. Leave `docs/project-backlog.md`, `source/`, domain,
application, CLI, notification/scheduler, bootstrap, main, and unrelated
worktrees untouched. A B0/generated-contract defect is a stop condition: add
only a focused failing test in an owned path, report the smallest contract
change, and wait for a replacement baseline.

Implement all nine tasks. Generated Bitbucket DTOs must stay inside the
Bitbucket adapter. Follow only opaque same-origin pagination URLs, with the
100-page hard ceiling. Never expose credentials, authorization, query strings,
raw bodies/activity, or stack traces. For the product API, every valid request
returns HTTP 200 with its explicit typed business result; use 4xx only for
malformed or unauthorized transport requests and 500 only for unexpected
server failures. All `/api/v1` responses are JSON with `Cache-Control: no-store`.
Loopback enforces exact Host/Origin and mutation CSRF rules without CORS; the
Unix socket exposes the same business routes without browser security or a
browser-session endpoint.

At completion, run every plan check and the full branch gate, including
`clean check verifyApiV1Generated`, `buildFatJar`, the forbidden-status scan,
`git diff --check`, and an exact-B0 owned-path audit. Apply
`superpowers:verification-before-completion` before claiming success.

Hand off without merging, rebasing, modifying production bootstrap, or deleting
transitional sources. Report:

- full B0 and branch HEAD SHAs;
- ordered task commit list;
- `git diff --name-status aaaf435458ae4e0489686b8f74c390be51b2f155..HEAD`;
- clean `git status --short`;
- every verification command and result;
- unresolved risks and any requested baseline change.

Continue the execution, The immutable shared baseline is `aaaf435458ae4e0489686b8f74c390be51b2f155`.
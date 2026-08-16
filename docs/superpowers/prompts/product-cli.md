# Product CLI implementation session

You are the top-level orchestrator for the Bitbucket Helper V1 Product CLI
workstream. Execute the complete approved plan at
`docs/superpowers/plans/2026-08-15-bitbucket-helper-v1-product-cli-parallel.md`.
Do not redesign or shorten it.

Work only in the existing checkout:

- Worktree: `.worktrees/v1-product-cli`
- Branch: `codex/v1-product-cli`
- Immutable B0: `aaaf435458ae4e0489686b8f74c390be51b2f155`

Use `superpowers:using-superpowers` first, then the plan-required
`superpowers:subagent-driven-development`,
`superpowers:test-driven-development`, and
`superpowers:verification-before-completion` skills. Follow the SDD
implementer/reviewer/fix-loop workflow for every task. Track every plan step,
run RED before implementation for each client/command task, and make one
focused commit after every task. The approved plan is the design authority; do
not run another companion plan or create a replacement worktree.

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

Treat `openapi/**`, generated DTOs, and B0 service/application contracts as
frozen and never run `syncApiV1Generated`. Modify only the CLI paths and
documentation listed under Exclusive Ownership. Leave bootstrap command wiring,
`docs/project-backlog.md`, `source/`, main, and unrelated worktrees untouched.
If a generated/B0 contract is insufficient, add only the smallest focused
failing test in an owned path, report the requested change, and stop for a new
B0.

Implement all eight tasks. Every business command must use the configured Unix
socket and no in-process fallback. Preserve original response bytes in JSON
mode plus exactly one LF. Human output must not recompute domain rules. Enforce
the frozen exit-code contract (0 achieved/read, 2 usage, 3 unmet typed business
result, 4 service/protocol/transport, 1 unexpected local failure) and stderr
policy. Browser opening must use `ProcessBuilder` with the exact argv list,
never a shell. The CLI consumes typed business results that originated from
valid HTTP 200 API responses; it must not infer business outcomes from 202/409
or other transport statuses. Tests must not launch a GUI or sleep in real time.

At completion, run all focused tests and the full branch gate, including the
entire CLI test package, `clean check verifyApiV1Generated`, `buildFatJar`, the
forbidden-dependency scan, `git diff --check`, and an exact-B0 owned-path audit.
Apply `superpowers:verification-before-completion` before claiming success.

Hand off without merging, rebasing, or attaching commands to bootstrap. Report:

- full B0 and branch HEAD SHAs;
- ordered task commit list;
- `git diff --name-status aaaf435458ae4e0489686b8f74c390be51b2f155..HEAD`;
- clean `git status --short`;
- every verification command and result;
- exact `ProductCommandFactory` signature;
- unresolved risks and any requested baseline change.

Continue the execution, The immutable shared baseline is `aaaf435458ae4e0489686b8f74c390be51b2f155`.
# `desktop-notifications` consumer contract

Bitbucket Helper consumes the generic CLI contract from the independent sibling
repository `../desktop-notifications`. The verified provider input is pinned to:

- provider revision: `fe12b2e`;
- provider release: `0.3.0`;
- shared send-fixture SHA-256:
  `91e5cfd97445eba9c0f0f596958584f76043513e521b080b5ce7d415ada19270`;
- fixture: [`contracts/desktop-notifications-send-cases.json`](../../contracts/desktop-notifications-send-cases.json);
- pin metadata: [`contracts/desktop-notifications-provider.txt`](../../contracts/desktop-notifications-provider.txt).

The Kotlin contract test verifies the fixture checksum before strict decoding.
A provider upgrade requires a reviewed pin/fixture update and both repositories'
contract verification; do not silently accept a different revision.

## Process boundary

The service resolves and normalizes the configured provider path; the resulting
path must identify an executable regular file. Bitbucket Helper starts it
directly with `ProcessBuilder`; there is no shell and no command-string parsing.
One delivery uses this argv shape:

```text
desktop-notifications send
  --delivery-key <generic-delivery-key>
  --title <generic-title>
  --body <generic-summary>
  [--open-url <safe-url>]
  --sound <lowercase-sound>
```

The actual argv is one flat argument vector; line breaks above are explanatory.
The boundary contains generic notification concepts only. It carries no
Bitbucket credentials and no raw activity/comment/thread body. Bitbucket Helper
constructs summaries such as repository/item counts, a PR number needing
attention, or a build-green transition. Safe links are optional.

Supported Kotlin sound values map to lowercase provider values: `default`,
`basso`, `blow`, `bottle`, `frog`, `funk`, `glass`, `hero`, `morse`, `ping`,
`pop`, `purr`, `sosumi`, `submarine`, and `tink`.

## Provider result

The provider emits exactly one UTF-8 JSON object followed by one `LF`:

```json
{"status":"accepted"}
```

with exit `0`, or:

```json
{"status":"failed","error":{"code":"...","message":"..."}}
```

with exit `1`. The fixture covers `invalid_arguments`,
`unsupported_platform`, `dependency_unavailable`, `delivery_timeout`,
`delivery_failed`, and `internal_error`.

Bitbucket Helper decodes strictly: unexpected fields, malformed or non-UTF-8
output, missing final `LF`, overflow, exit/JSON mismatch, unexpected exit, and
signal-shaped termination are failures. Stdout and stderr capture is bounded to
65,536 bytes each. The outer invocation deadline is 15 seconds, after which the
direct process and captured descendants are terminated and reaped. Provider
message text and captured output are not copied into durable failure records or
public diagnostics; only safe failure categories and ambiguity are retained.

## Durable dispatch and restart behavior

The application creates a generic notification intent in the same durable
transaction as the core transition that requires it. Only after commit does it
invoke the provider. Before process launch, the dispatcher durably claims the
intent with a two-minute lease. An attempt result and resulting intent state are
then committed transactionally.

If the service stops after an intent was committed, the pending intent remains
recoverable. A restart can reclaim an expired lease. Retrying preserves the
intent's delivery key and complete provider payload; it does not invent a new
identity or mutate argv between attempts.

Accepted delivery moves the intent to `ACCEPTED`. Terminal provider failures
`invalid_arguments` and `unsupported_platform` move it directly to
`EXHAUSTED`. Other failures use bounded backoff after completed attempts:

| Completed attempt | Next delay |
|---:|---:|
| 1 | 1 minute |
| 2 | 5 minutes |
| 3 | 15 minutes |
| 4 | 1 hour |
| 5 | 6 hours |
| 6 | 24 hours |

A failure on attempt 7 exhausts the intent. Quartz checks due pending intents
once per minute. Ambiguous timeouts, termination, or cancellation are recorded
as failures and may be retried under the same bounded policy.

## Delivery guarantee

The contract deliberately does **not** promise exactly-once delivery. A crash or
ambiguous process outcome can occur after the provider displayed a notification
but before Bitbucket Helper committed acceptance. The expired lease then permits
a retry with the same delivery key and payload. The stable key gives the provider
a chance to replace or deduplicate according to its own contract, but it does
not remove the cross-process crash window.

The verified guarantee is durable commit-before-launch, bounded lease/recovery,
stable delivery identity and payload across retries, strict result parsing,
bounded cleanup, and eventual `ACCEPTED` or `EXHAUSTED` state under completed
attempts.

## Privacy boundary

Notification intents persist generic title/body/link/sound/delivery-key data,
not raw upstream content. Credentials and raw activity/comment/thread bodies are
absent from the provider argv, captured diagnostics, logs, and stored attempt
failure details. Any future provider or contract change must preserve this
boundary.

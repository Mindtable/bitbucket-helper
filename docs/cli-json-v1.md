# Bitbucket Helper product CLI and JSON v1

This document describes the stable product-command surface. Product commands
are clients of the local Bitbucket Helper service over its configured Unix
socket. They never fall back to a TCP endpoint, the database, Bitbucket, or an
in-process application service.

## Commands

The exact product commands are:

```text
bitbucket-helper pr list
bitbucket-helper pr show <pull-request-id>
bitbucket-helper inbox
bitbucket-helper open <pull-request-id>
bitbucket-helper ack <action-item-id> <activity-version>
bitbucket-helper refresh [--repository <repository-id>]... [--no-wait]
bitbucket-helper workspace show
bitbucket-helper workspace configure --api-base-url <url> --slug <slug>
bitbucket-helper repository add <slug>
bitbucket-helper repository remove <repository-id>
```

`--repository` can be repeated. With no `--repository`, `refresh` targets all
configured repositories. `--no-wait` returns after the service registers the
refresh run. There is no force or ignore-backoff option.

Pull-request, action-item, activity-version, repository, and refresh-run IDs are
opaque. Pass them back exactly as returned by the API. In particular, `ack`
acknowledges the supplied activity version and never discovers or substitutes a
newer version.

Workspace configuration accepts only the API base URL and workspace slug. The
CLI has no username, password, or app-password option.

## Output selection

Every executable product command accepts `--output human|json` locally. `human`
is the default when no placement selects a mode. Place the option after the
command being executed, for example:

```text
bitbucket-helper pr list --output json
bitbucket-helper workspace show --output human
```

For the `pr`, `workspace`, and `repository` groups, the option can also precede
the subcommand, such as `bitbucket-helper pr --output json list`. A leaf option
overrides its command-group option. The assembled root does not accept
`--output` before the product command; use group or leaf placement only.

Human output is for people and is not a stable parsing interface. Terminal
styling is used only when terminal capability is enabled; redirected human
output contains no ANSI styling. API-provided C0/C1 controls, `DEL`, carriage
returns, line feeds, and terminal escape bytes are rendered as visible escape
text in human output; this does not alter JSON output bytes.

## JSON stdout contract

For a valid HTTP `200 OK` API response, JSON mode writes the original UTF-8 API
response envelope to stdout byte for byte, followed by one ASCII line feed
(`LF`, byte `0x0a`). The CLI does not wrap, reserialize, reorder, normalize, or
trim the document.

The framing rule is literal:

```text
stdout = original API response bytes + LF
```

If the retained API response already ends in `LF`, both that original byte and
the appended framing byte remain, so stdout ends in two `LF` bytes.

Expected business outcomes remain the service's versioned envelope, for
example an envelope with `apiVersion`, `requestId`, and a typed `result`. HTTP
status is transport/request status, not business state. A pending, stale,
partial, unavailable, rejected, not-found, or otherwise unachieved business
result is represented by `result.type` in an HTTP `200 OK` body. The CLI does
not interpret HTTP `202 Accepted` or `409 Conflict` as refresh or
acknowledgment business outcomes; a non-`200` response is a service/protocol
failure.

When the socket is unavailable, the response is malformed or too large, the
request times out, the HTTP status is not `200`, or the response violates the
expected generated contract, JSON mode writes this fixed local document plus
one `LF`:

```json
{"cliVersion":"1","error":{"code":"SERVICE_UNAVAILABLE","message":"Bitbucket Helper service is unavailable. Run 'bitbucket-helper service status' and then 'bitbucket-helper service start'."}}
```

This local document is not an API v1 envelope; `cliVersion` and `error.code`
identify the CLI-owned failure contract.

## Refresh JSON behavior

Refresh polling produces exactly one stdout document:

- with `--no-wait`, it is the refresh-registration API envelope;
- after terminal completion or typed unavailability, it is that final API
  envelope;
- if the run expires before completion, it is the last applicable API envelope
  (the registration envelope before the first poll, otherwise the latest
  in-progress envelope);
- after a local service/protocol failure, it is the fixed
  `SERVICE_UNAVAILABLE` document.

Registration and intermediate polling envelopes are not streamed to stdout.
The same original-bytes-plus-one-`LF` framing rule applies to the selected last
API envelope.

Refresh registration classifies every repository disposition in API order.
`started` and `joinedExisting` achieve the requested registration state. Any
`deferredByBackoff` or `repositoryNotConfigured` disposition makes the command
exit `3`, including with `--no-wait`; that exit remains `3` after polling even
when every started repository completes successfully. Waiting JSON mode still
writes only the final applicable envelope, while `--no-wait` writes only the
registration envelope.

## Exit status and stderr

Exit statuses are stable:

| Exit | Meaning |
|---:|---|
| `0` | Read completed, mutation achieved, or idempotent requested state already held. |
| `1` | Unexpected local failure, such as failure to launch an otherwise safe browser URL. |
| `2` | Invalid command syntax, missing required input, unsupported output value, or malformed opaque ID. |
| `3` | A typed `result.type` says the requested business outcome was not achieved. |
| `4` | Local service, transport, HTTP-status, decoding, or API-protocol failure. |

API results, typed business outcomes, and the fixed service-unavailable result
do not write business content to stderr. Stderr is reserved for parser/usage
diagnostics and unexpected local diagnostics. In JSON mode, automation should
read the single stdout document and use the process exit status; it must not
combine stderr with stdout and attempt to parse the result as JSON.

Automation must branch on versioned discriminators: `result.type` for an API
envelope and `error.code` for an error envelope. Do not branch on field order,
human-readable messages, or human output text.

# Bitbucket client template override

- OpenAPI Generator: `7.24.0`
- Built-in source: `kotlin-client/libraries/jvm-ktor/infrastructure/HttpResponse.kt.mustache`
- Built-in SHA-256: `6bd429a18841c1e0c990a6a6663d6ccfaec45222356dc4cde7eb4767582d6c7e`
- Override: `libraries/jvm-ktor/infrastructure/HttpResponse.kt.mustache`

The pinned generator eagerly copies every response header while constructing its
generated `HttpResponse`, before handwritten code can inspect the status. The
local override changes only `headers` to a memoized lazy property. Explicit
callers retain the same `Map<String, List<String>>` behavior on first access,
while status-only error handling does not copy credential-bearing headers.

When updating the generator pin, compare the upstream template and checksum,
then remove this override if upstream defers header mapping.

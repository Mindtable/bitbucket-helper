# Project Guidance

## HTTP API status semantics

- HTTP status codes describe request and transport success or failure; they are
  not a place for business logic.
- Return `200 OK` when a valid API request was processed, including when its
  business outcome is pending, stale, partial, unavailable, rejected by a domain
  rule, or otherwise unsuccessful.
- Represent every business outcome explicitly in the versioned response body.
- Use `4xx` only for client/request errors and `500` for an unexpected server
  error.
- Do not use statuses such as `202 Accepted` or `409 Conflict` to encode refresh
  lifecycle or exact-version acknowledgment outcomes.

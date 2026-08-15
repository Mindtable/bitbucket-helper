# Bitbucket Cloud OpenAPI Snapshot

- Canonical source: `https://api.bitbucket.org/swagger.json`
- Retrieved (UTC): `2026-08-15`
- SHA-256: `ecf7b8905dc6eab269040d04fa0766b9ca31d8f3b6105a2acedbf24665598705`
- Source format: `Swagger/OpenAPI 2.0`
- OpenAPI Generator validation version: `7.24.0`
- Generated client library: `jvm-ktor`
- Reduced-spec compatibility: `definitions.object.additionalProperties` omitted; canonical snapshot unchanged

## Update and review procedure

1. Run `./gradlew updateBitbucketOpenApiSpec` explicitly; normal builds never run it.
2. Review the complete `openapi.json` diff, especially `GET /user` and recursively referenced schemas.
3. Run `./gradlew clean check` to regenerate and compile the selected client.
4. Inspect `build/generated/sources/bitbucket/src/main/kotlin` before committing the snapshot and metadata together.

# Liquibase Migration Guidance

- Every migration in this directory is Liquibase XML; never add a `.sql` migration.
- Name versioned files `V%04d__<snake_case_description>.xml`.
- Example: `V0001__create_bitbucket_connection_snapshot.xml`.
- Filenames must match `^V[0-9]{4}__[a-z0-9]+(?:_[a-z0-9]+)*\.xml$`.
- Version `0000` and duplicate numeric prefixes are invalid; version gaps are allowed.
- Give every Liquibase changeSet a repository-unique ID and author.
- Include an explicit rollback whenever Liquibase can reverse the change safely.
- Never edit a migration after it has been applied; add a new versioned XML migration.

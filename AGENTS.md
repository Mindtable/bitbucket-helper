# Project Instructions

## Keep UV project structure documentation synchronized

`docs/uv-project-structure.md` is the canonical description of this repository's
Python/UV structure and the reusable recipe for creating equivalent projects.

Whenever a change affects project layout, package or test paths, Python version,
build backend, runtime dependencies, dependency groups, pytest/Ruff/mypy settings,
UV cache or lockfile strategy, or documented setup and verification commands,
update `docs/uv-project-structure.md` in the same change.

A project-structure change is incomplete until the guide matches the repository.

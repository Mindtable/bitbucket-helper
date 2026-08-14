# Bitbucket Helper UV Scaffold Design

**Date:** 2026-08-14  
**Status:** Awaiting revised-spec review

## Context

The reference UV project lives at `english-cards/scripts`. It uses Python 3.12,
Hatchling, a `src` package layout, a UV lockfile, pytest with coverage, Ruff, and
strict mypy. Its runtime dependencies are specific to the Anki application and
must not be copied into Bitbucket Helper.

Bitbucket Helper already has a minimal root `pyproject.toml`. Its current UV cache
setting incorrectly points into the English Cards repository. The untracked
`source/` directory contains an existing shell prototype and must remain unchanged.

## Goals

- Turn the Bitbucket Helper repository root into an installable UV-managed Python
  project modeled on the English Cards scaffold.
- Add `atlassian-python-api>=4.0.7` as the Python package's sole direct runtime
  dependency.
- Provide reproducible setup, packaging, linting, type-checking, and test commands.
- Establish unit and integration test locations without implementing Bitbucket
  behavior.
- Document the scaffold as a reusable recipe for creating equivalent UV projects.
- Add a repository-level agent instruction that keeps the reusable structure guide
  synchronized with future structural changes.
- Preserve every file under `source/` byte-for-byte.

## Non-goals

- Migrating, wrapping, deleting, renaming, formatting, or staging the shell
  prototype under `source/`.
- Implementing Bitbucket API calls, authentication, notifications, scheduling, a
  web interface, or shell/Python interoperability.
- Copying the English Cards runtime dependencies `jsonschema` and `requests`, or
  its `types-jsonschema` development dependency.
- Turning the reusable structure guide into a generic Python or UV reference beyond
  the conventions exercised by this scaffold.

## Project architecture

The Python project will live at the repository root because `pyproject.toml` is
already there. Hatchling will build an importable `bitbucket_helper` package from
`src/bitbucket_helper`. Python 3.12 will be the local interpreter selected by UV,
and the project metadata will support Python 3.12 or newer.

The initial package will expose only `__version__ = "0.1.0"`. There is no production
data flow yet: `uv sync` installs the project and its development group, and the
smoke test imports the package and verifies its version. Network access and
credentials are therefore unnecessary for the initial test suite.

## Dependencies and tooling

Runtime dependencies:

- `atlassian-python-api>=4.0.7`

Development dependencies adapted from English Cards:

- `mypy>=1.13`
- `pytest>=8`
- `pytest-cov>=5`
- `pytest-httpserver>=1.1`
- `requests-mock>=1.12`
- `ruff>=0.8`
- `types-requests>=2.32`

Hatchling will be the PEP 517 build backend. Pytest will discover tests below
`tests`, define `unit`, `integration`, and `live` markers, exclude `live` tests by
default, and report coverage for `bitbucket_helper`. Ruff will use a 100-character
line length and Python 3.12 target. Mypy will run in strict mode.

The UV cache will be `.uv-cache` relative to Bitbucket Helper rather than an
absolute path into another repository. UV will generate and commit `uv.lock` so
transitive dependency versions and artifacts are reproducible.

## Reusable structure documentation

`docs/uv-project-structure.md` will be the canonical explanation of this UV project
shape and a replication guide for future repositories. It will contain:

- An annotated directory tree covering root `AGENTS.md`, project metadata, the
  `src` package, unit/integration tests, documentation, the lockfile, ignored
  generated files, and the preserved `source/` shell prototype.
- The responsibilities of `pyproject.toml`, `.python-version`, `uv.lock`, the
  package directory, each test boundary, and the repository instruction file, plus
  an explanation that project-specific legacy areas such as `source/` need not be
  copied into new UV projects.
- Reusable `pyproject.toml` examples for Hatchling, PEP 621 metadata, runtime and
  development dependencies, pytest, Ruff, mypy, and a project-local UV cache.
- Bootstrap and verification commands, including lockfile generation and package
  building.
- An adaptation checklist identifying which names, descriptions, runtime
  dependencies, CLI entry points, test markers, and Python versions a new project
  must consciously choose rather than copy blindly.
- A maintenance section defining which structural changes require the guide to be
  updated.

The guide will distinguish reusable conventions from Bitbucket Helper-specific
choices. Generic examples may use explicit metavariables such as `your-package`
and `your_package`; they are instructional substitution points, not unfinished
requirements.

A new root `AGENTS.md` will apply to the whole repository. It will state that any
change to project layout, package or test paths, Python version, build backend,
dependency groups, tool configuration, UV cache/lock strategy, or documented setup
and verification commands must update `docs/uv-project-structure.md` in the same
change. It will also state that a structural change is incomplete until the guide
matches the repository.

## File changes

- Modify `pyproject.toml` with build metadata, the runtime and development
  dependencies, test/tool configuration, Hatchling's package path, and the local
  UV cache path.
- Create `.python-version` containing `3.12`.
- Create `uv.lock` through UV dependency resolution.
- Create `src/bitbucket_helper/__init__.py` with the package version.
- Create `tests/__init__.py` and `tests/conftest.py` to mirror the reference test
  package.
- Create `tests/unit/test_package.py` as a hermetic import/version smoke test.
- Create `tests/integration/.gitkeep` so the intended integration-test boundary is
  represented before API behavior exists.
- Expand `.gitignore` for virtual environments, Python caches, test/tool caches,
  coverage output, UV cache, build output, and package metadata while retaining
  the existing IDE exclusions.
- Expand `README.md` with Python 3.12 and UV prerequisites, `uv sync` installation,
  and verification commands.
- Create `docs/uv-project-structure.md` with the reusable structure, configuration
  examples, replication steps, verification commands, adaptation checklist, and
  maintenance contract defined above.
- Create root `AGENTS.md` with the project-wide documentation synchronization rule
  defined above.
- Make no changes under `source/`.

## Error handling and test isolation

The scaffold introduces no production error-handling policy because it performs no
Bitbucket operations. Setup, build, lint, type, and test failures will return their
native nonzero command status. Normal tests must be hermetic; future tests requiring
real Bitbucket credentials must use the `live` marker and remain deselected by
default. HTTP fakes and request mocks are available for future unit and integration
tests.

## Verification

The completed scaffold must pass:

```bash
uv sync
uv lock --check
uv run pytest
uv run ruff check .
uv run mypy src
uv build
bash source/tests/run.sh
```

Before implementation, record a SHA-256 manifest of all files under `source/` and
compare it after verification. The manifests must be identical, and `git diff`
must show no changes under `source/`.

## Acceptance criteria

- A clean checkout can run `uv sync` using Python 3.12 and the committed lockfile.
- `import bitbucket_helper` succeeds from the UV environment and reports version
  `0.1.0`.
- Pytest, Ruff, strict mypy, and package building pass.
- Existing shell tests still pass.
- The README accurately documents setup and verification.
- `docs/uv-project-structure.md` is sufficient to reproduce the same UV/Hatchling
  package and test layout in another repository without relying on English Cards.
- Root `AGENTS.md` requires the structure guide to be updated in the same change as
  every project-structure change covered by its maintenance contract.
- The existing `source/` file list and SHA-256 contents are unchanged.

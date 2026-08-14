# Bitbucket Helper UV Scaffold Design

**Date:** 2026-08-14  
**Status:** Approved

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
- Preserve every file under `source/` byte-for-byte.

## Non-goals

- Migrating, wrapping, deleting, renaming, formatting, or staging the shell
  prototype under `source/`.
- Implementing Bitbucket API calls, authentication, notifications, scheduling, a
  web interface, or shell/Python interoperability.
- Copying the English Cards runtime dependencies `jsonschema` and `requests`, or
  its `types-jsonschema` development dependency.

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
- The existing `source/` file list and SHA-256 contents are unchanged.

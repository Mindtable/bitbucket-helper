# Bitbucket Helper UV Scaffold Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a reproducible Python 3.12 UV/Hatchling package at the Bitbucket
Helper repository root while preserving the existing shell prototype and documenting
the scaffold for reuse.

**Architecture:** The repository root owns one installable `src`-layout Python package
named `bitbucket_helper`, with dependencies and tool configuration centralized in
`pyproject.toml` and exact transitive resolution in `uv.lock`. Hermetic unit tests and
future fake-backed integration tests are separated below `tests`; the existing
untracked `source/` shell prototype remains an independent, byte-identical area.

**Tech Stack:** Python 3.12, UV 0.12+, Hatchling, `atlassian-python-api`, pytest,
pytest-cov, pytest-httpserver, requests-mock, Ruff, and strict mypy.

## Global Constraints

- Use Python `3.12` locally and declare `requires-python = ">=3.12"`.
- Use Hatchling and package only `src/bitbucket_helper`.
- Declare `atlassian-python-api>=4.0.7` as the sole direct runtime dependency.
- Declare `mypy>=1.13`, `pytest>=8`, `pytest-cov>=5`,
  `pytest-httpserver>=1.1`, `requests-mock>=1.12`, `ruff>=0.8`, and
  `types-requests>=2.32` in the `dev` dependency group.
- Use `.uv-cache` as the project-local UV cache and commit `uv.lock`.
- Exclude `.uv-cache`, `.venv`, `dist`, temporary build archives, and `source/`
  from Hatchling source distributions.
- Do not add Bitbucket API behavior, authentication, notifications, scheduling, web
  UI behavior, or shell/Python interoperability.
- Do not modify, rename, format, stage, or commit any path under `source/`.
- Keep `docs/uv-project-structure.md` synchronized with every structural choice made
  by this plan, and enforce that maintenance rule in root `AGENTS.md`.
- Use `apply_patch` for hand-authored file changes.

The protected `source/` baseline is:

```text
c9eb82dcf8cd4d7c5d6a659440257b62cbb7730d539615caaf688c743380e709  source/docs/bitbucket-api.md
9d72632f2c3b25412e2d39849d07f8b5470e54129baf00705cb60c744b6d9b92  source/get_pr_state.sh
fb2b0e41eae1300085be6dcb2614850e278a31f060a4bb32e670cb61f302e6f7  source/lib/bitbucket.sh
bf17e5b933a904de0aa27012a915734f8d1176b1669bc06ade89c99df7b3d782  source/list-my-prs.sh
a1ea6bdd8e1b32ce23a0613d052acd4d2793eea128b245701866385f5150335d  source/PR_NOTIFIER.md
9b6cfbd7bcb49aeb5d58ff3d98adea8ab37b5a10d7ab42d111741d362f1d3d98  source/tests/bitbucket_lib_test.sh
97627bf7383743e67c5dc3137fd9534f775b01414a1dfb7b97aca72af1c653f1  source/tests/get_pr_state_test.sh
99dd0178d9ab704ed1c7aae1cae8b36885b1e246498b83946378163c708e1019  source/tests/list_my_prs_test.sh
cab6bc0606b2e39dbd0c89ec85b3304c92192817a63a402620b8f5f52fd05b0a  source/tests/run.sh
76405660e1bd7f72e7abc455efa5e7e7b4528879d764ae3bd7940821c9fe4dec  source/tests/test_helpers.sh
```

## File Map

- Modify `pyproject.toml`: package metadata, dependencies, build backend, and tool
  configuration.
- Modify `.gitignore`: Python, UV, test, type, lint, coverage, and build artifacts.
- Modify `README.md`: prerequisites, setup, layout, and verification commands.
- Create `.python-version`: select Python 3.12.
- Create `uv.lock`: exact dependency resolution.
- Create `src/bitbucket_helper/__init__.py`: initial import surface and version.
- Create `tests/__init__.py`: test package marker.
- Create `tests/conftest.py`: shared pytest configuration hook location.
- Create `tests/unit/test_package.py`: package import/version contract.
- Create `tests/integration/.gitkeep`: preserve the future integration-test boundary.
- Create `docs/uv-project-structure.md`: canonical current structure and reusable UV
  project recipe.
- Create `AGENTS.md`: repository-wide rule requiring structure documentation updates.
- Preserve `source/**`: existing shell prototype and tests.

---

### Task 1: Testable UV Python Package Scaffold

**Files:**

- Modify: `.gitignore`
- Modify: `pyproject.toml`
- Create: `.python-version`
- Create: `uv.lock`
- Create: `src/bitbucket_helper/__init__.py`
- Create: `tests/__init__.py`
- Create: `tests/conftest.py`
- Create: `tests/unit/test_package.py`
- Create: `tests/integration/.gitkeep`

**Interfaces:**

- Consumes: Python 3.12 selected by `.python-version`; dependencies declared by
  `pyproject.toml`.
- Produces: importable package `bitbucket_helper` with public string constant
  `bitbucket_helper.__version__ == "0.1.0"`; UV commands for tests, linting, typing,
  and builds.

- [ ] **Step 1: Confirm the protected baseline and tracked-file boundary**

Run:

```bash
rg --files source -0 | sort -z | xargs -0 shasum -a 256
git ls-files source
```

Expected: the SHA-256 output exactly matches the Global Constraints manifest, and
`git ls-files source` prints nothing.

- [ ] **Step 2: Write the package contract test before production code**

Create `tests/__init__.py` with:

```python
"""Bitbucket Helper test suite."""
```

Create `tests/conftest.py` with:

```python
"""Shared pytest configuration."""
```

Create an empty `tests/integration/.gitkeep`, and create
`tests/unit/test_package.py` with:

```python
from importlib import import_module
from importlib.util import find_spec
import unittest


class PackageContractTest(unittest.TestCase):
    def test_package_exports_initial_version(self) -> None:
        module_spec = find_spec("bitbucket_helper")
        self.assertIsNotNone(module_spec, "src/bitbucket_helper must be importable")

        package = import_module("bitbucket_helper")
        self.assertEqual(package.__version__, "0.1.0")


if __name__ == "__main__":
    unittest.main()
```

This test catches a missing/misplaced package or a broken initial public version
contract. It exercises the real import system and uses a hand-checked literal.

- [ ] **Step 3: Run the test and verify the red state**

Run:

```bash
env PYTHONPATH=src python3 tests/unit/test_package.py
```

Expected: `FAIL` because `find_spec("bitbucket_helper")` returns `None`, with the
message `src/bitbucket_helper must be importable`. The failure must be caused by the
missing package, not by a syntax or test-runner error.

- [ ] **Step 4: Add the minimal package implementation**

Create `src/bitbucket_helper/__init__.py` with:

```python
"""Bitbucket pull request monitoring and notification helpers."""

__version__ = "0.1.0"
```

- [ ] **Step 5: Run the test and verify the green state**

Run:

```bash
env PYTHONPATH=src python3 tests/unit/test_package.py
```

Expected: one test passes with output ending in `OK`.

- [ ] **Step 6: Configure the build, dependency, and quality toolchain**

Replace `pyproject.toml` with:

```toml
[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"

[project]
name = "bitbucket-helper"
version = "0.1.0"
description = "Bitbucket pull request monitoring and notification helpers."
readme = "README.md"
requires-python = ">=3.12"
dependencies = [
    "atlassian-python-api>=4.0.7",
]

[dependency-groups]
dev = [
    "mypy>=1.13",
    "pytest>=8",
    "pytest-cov>=5",
    "pytest-httpserver>=1.1",
    "requests-mock>=1.12",
    "ruff>=0.8",
    "types-requests>=2.32",
]

[tool.pytest.ini_options]
testpaths = ["tests"]
markers = [
    "unit: hermetic unit tests",
    "integration: tests against local HTTP fakes",
    "live: requires Bitbucket credentials and network access (deselected by default)",
]
addopts = "-ra -m 'not live' --cov=bitbucket_helper --cov-report=term-missing"

[tool.hatch.build.targets.sdist]
exclude = [
    "/.uv-cache",
    "/.venv",
    "/dist",
    "/source",
    "/tmp*.tar.gz",
]

[tool.hatch.build.targets.wheel]
packages = ["src/bitbucket_helper"]

[tool.ruff]
line-length = 100
target-version = "py312"
exclude = [".venv", ".uv-cache"]

[tool.mypy]
strict = true

[tool.uv]
cache-dir = ".uv-cache"
```

Create `.python-version` containing exactly:

```text
3.12
```

Replace `.gitignore` with:

```gitignore
.idea/
.venv/
*.iml
__pycache__/
.pytest_cache/
pytest-of-*
.ruff_cache/
.mypy_cache/
.uv-cache/
.coverage
htmlcov/
*.egg-info/
dist/
build/
```

- [ ] **Step 7: Resolve and synchronize the project**

Run:

```bash
uv sync
```

Expected: UV selects Python 3.12, creates `.venv`, resolves
`atlassian-python-api>=4.0.7` plus the `dev` group, installs the editable
`bitbucket-helper` package, and creates `uv.lock` without modifying `source/`.

- [ ] **Step 8: Verify the package scaffold**

Run:

```bash
uv lock --check
uv run pytest
uv run ruff check .
uv run mypy src
uv build
```

Expected: the lockfile is current; one pytest test passes with coverage for
`bitbucket_helper`; Ruff reports `All checks passed!`; mypy reports success with no
issues; and UV builds one source distribution and one wheel under ignored `dist/`.
The source distribution contains no `.uv-cache`, `.venv`, `dist`, temporary tar
archive, or `source/` entry.

- [ ] **Step 9: Commit the package scaffold without the shell prototype**

Run:

```bash
git add .gitignore .python-version pyproject.toml uv.lock src tests \
  docs/superpowers/plans/2026-08-14-bitbucket-helper-uv-scaffold.md \
  docs/superpowers/specs/2026-08-14-bitbucket-helper-uv-scaffold-design.md
git status --short
git commit -m "chore: scaffold UV Python package"
```

Expected: the staged set contains only the listed Python scaffold files and the
synchronized spec/plan. Every `source/` file remains untracked and unstaged.

---

### Task 2: Reusable Structure Documentation and Maintenance Rule

**Files:**

- Modify: `README.md`
- Create: `docs/uv-project-structure.md`
- Create: `AGENTS.md`

**Interfaces:**

- Consumes: the exact package layout and commands produced by Task 1.
- Produces: a self-contained replication guide at `docs/uv-project-structure.md` and
  a repository-wide instruction requiring that guide to change with the structure.

- [ ] **Step 1: Create the repository-wide documentation maintenance rule**

Create root `AGENTS.md` with:

```markdown
# Project Instructions

## Keep UV project structure documentation synchronized

`docs/uv-project-structure.md` is the canonical description of this repository's
Python/UV structure and the reusable recipe for creating equivalent projects.

Whenever a change affects project layout, package or test paths, Python version,
build backend, runtime dependencies, dependency groups, pytest/Ruff/mypy settings,
UV cache or lockfile strategy, or documented setup and verification commands,
update `docs/uv-project-structure.md` in the same change.

A project-structure change is incomplete until the guide matches the repository.
```

- [ ] **Step 2: Create the canonical reusable UV structure guide**

Create `docs/uv-project-structure.md` with:

````markdown
# Reusable UV Python Project Structure

This guide documents Bitbucket Helper's current Python scaffold and the reusable
decisions needed to create an equivalent UV-managed project. The shell prototype
under `source/` is specific to this repository; it is shown so the current tree is
accurate, but it is not part of the reusable Python layout.

## Current Bitbucket Helper layout

```text
.
├── AGENTS.md                         # Keeps this guide synchronized with structure changes
├── .gitignore                        # Excludes generated Python, UV, test, and build files
├── .python-version                   # Selects Python 3.12 for UV and compatible tools
├── pyproject.toml                    # Package, dependency, build, test, lint, and type config
├── uv.lock                           # Committed exact dependency resolution
├── README.md                         # Product context plus setup and development commands
├── docs/
│   ├── uv-project-structure.md       # This reusable guide
│   └── superpowers/
│       ├── plans/                    # Approved implementation plans
│       └── specs/                    # Approved design specifications
├── src/
│   └── bitbucket_helper/
│       └── __init__.py               # Importable package and initial public version
├── tests/
│   ├── __init__.py                   # Test package marker
│   ├── conftest.py                   # Shared pytest hooks and fixtures
│   ├── unit/
│   │   └── test_package.py           # Hermetic package contract test
│   └── integration/
│       └── .gitkeep                  # Reserved for fake-backed integration tests
└── source/                            # Existing shell prototype; project-specific, do not copy
```

Generated `.venv/`, `.uv-cache/`, test/tool caches, coverage data, and `dist/` are
intentionally absent from the tree because `.gitignore` excludes them.

## Responsibilities

| Path | Responsibility |
| --- | --- |
| `AGENTS.md` | Requires this guide to change whenever the project structure or its commands change. |
| `.python-version` | Gives UV and developer tools one shared local Python selection. |
| `pyproject.toml` | Defines PEP 621 metadata, direct dependencies, Hatchling build and file selection, dependency groups, and quality-tool settings. |
| `uv.lock` | Pins the complete resolved dependency graph for reproducible environments; commit it for applications and tools. |
| `src/<import_name>/` | Holds importable application code outside the repository root, preventing accidental imports from an uninstalled checkout. |
| `tests/unit/` | Holds fast, hermetic tests for one package boundary at a time. |
| `tests/integration/` | Holds tests that combine boundaries against local fakes; tests requiring real services use the `live` marker. |
| `docs/` | Holds durable design, implementation, and operating guidance. |
| `.venv/` | Contains the synchronized environment; UV recreates it, so it is ignored. |
| `.uv-cache/` | Contains downloaded and built dependency artifacts; it is local, reproducible, and ignored. |
| `source/` | Contains Bitbucket Helper's pre-existing shell prototype. It is not a reusable part of the Python scaffold. |

The `src` layout makes an import prove that the package was installed or that `src`
was deliberately placed on `PYTHONPATH`. Separating unit and integration tests keeps
the default suite fast while leaving a clear home for HTTP-fake coverage. Committing
`uv.lock` makes CI and other developers resolve the same artifacts.

## Reusable `pyproject.toml`

In this template, replace `your-package` with the distribution name,
`your_package` with the Python import name, and
`your-runtime-dependency>=1.0` with a real direct dependency. These are explicit
substitution examples, not values to copy literally. Remove HTTP test helpers if the
project has no HTTP boundary.

```toml
[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"

[project]
name = "your-package"
version = "0.1.0"
description = "Describe the project in one sentence."
readme = "README.md"
requires-python = ">=3.12"
dependencies = [
    "your-runtime-dependency>=1.0",
]

[dependency-groups]
dev = [
    "mypy>=1.13",
    "pytest>=8",
    "pytest-cov>=5",
    "pytest-httpserver>=1.1",
    "requests-mock>=1.12",
    "ruff>=0.8",
    "types-requests>=2.32",
]

[tool.pytest.ini_options]
testpaths = ["tests"]
markers = [
    "unit: hermetic unit tests",
    "integration: tests against local fakes",
    "live: requires real external services (deselected by default)",
]
addopts = "-ra -m 'not live' --cov=your_package --cov-report=term-missing"

[tool.hatch.build.targets.sdist]
exclude = [
    "/.uv-cache",
    "/.venv",
    "/dist",
    "/tmp*.tar.gz",
]

[tool.hatch.build.targets.wheel]
packages = ["src/your_package"]

[tool.ruff]
line-length = 100
target-version = "py312"
exclude = [".venv", ".uv-cache"]

[tool.mypy]
strict = true

[tool.uv]
cache-dir = ".uv-cache"
```

Pair the template with a tracked `.python-version` containing the selected minor
version, such as `3.12`, and ignore these generated paths:

```gitignore
.venv/
__pycache__/
.pytest_cache/
pytest-of-*
.ruff_cache/
.mypy_cache/
.uv-cache/
.coverage
htmlcov/
*.egg-info/
dist/
build/
```

## Bootstrap a new project

1. Choose the distribution name (`your-package`), import name (`your_package`),
   one-sentence description, Python support floor, and direct runtime dependencies.
2. Create `src/your_package`, `tests/unit`, `tests/integration`, and `docs`, plus the
   root files shown in the reusable tree.
3. Write the package contract test before creating `src/your_package/__init__.py`.
   For a standard-library red-green cycle, adapt this test:

   ```python
   from importlib import import_module
   from importlib.util import find_spec
   import unittest


   class PackageContractTest(unittest.TestCase):
       def test_package_exports_initial_version(self) -> None:
           module_spec = find_spec("your_package")
           self.assertIsNotNone(module_spec, "src/your_package must be importable")

           package = import_module("your_package")
           self.assertEqual(package.__version__, "0.1.0")


   if __name__ == "__main__":
       unittest.main()
   ```

4. Run `env PYTHONPATH=src python3 tests/unit/test_package.py` and confirm it fails
   because the package is missing. Create the minimal package with
   `__version__ = "0.1.0"`, rerun, and confirm it passes.
5. Add the selected values to `pyproject.toml`, write `.python-version`, and run
   `uv sync`. UV creates the environment and lockfile.
6. Run the complete verification set below, then commit `uv.lock` with the source,
   tests, and configuration.

## Verification commands

```bash
uv sync
uv lock --check
uv run pytest
uv run ruff check .
uv run mypy src
uv build
```

- `uv sync` makes the environment match `pyproject.toml` and `uv.lock`.
- `uv lock --check` proves the committed lockfile is current without changing it.
- `uv run pytest` runs the default hermetic and fake-backed tests with coverage.
- `uv run ruff check .` enforces the configured static lint rules.
- `uv run mypy src` checks the production package in strict mode.
- `uv build` proves Hatchling can produce both the source archive and wheel.

## Adaptation checklist

- [ ] Choose distinct PEP 621 distribution and Python import names deliberately.
- [ ] Replace the description and confirm whether `README.md` is the package readme.
- [ ] Select a Python support floor available in development and CI.
- [ ] Include only direct runtime dependencies the package imports or exposes.
- [ ] Remove unused development helpers and add domain-specific test tools only when
      a real boundary requires them.
- [ ] Add `[project.scripts]` only for actual CLI entry points and test those commands.
- [ ] Define unit, integration, and live markers that reflect the project's external
      boundaries; keep real-service tests deselected by default.
- [ ] Point pytest coverage and Hatchling's package path at the chosen import name.
- [ ] Decide whether legacy or non-Python areas belong in the new repository; do not
      copy Bitbucket Helper's `source/` directory by default, and explicitly exclude
      any retained legacy area from source distributions.
- [ ] Copy the `AGENTS.md` maintenance rule and keep this guide aligned with future
      structural changes.

## Bitbucket Helper-specific choices

- Local Python selection: `3.12`; supported Python: `>=3.12`.
- Distribution name: `bitbucket-helper`; import name: `bitbucket_helper`.
- Runtime dependency: `atlassian-python-api>=4.0.7`.
- Build backend: Hatchling with `src/bitbucket_helper` as the wheel package.
- Source distributions exclude local environments/caches, build output, temporary
  archives, and the protected `source/` directory.
- Test boundaries: hermetic unit tests, local-fake integration tests, and deselected
  live tests for real Bitbucket access.
- `source/` remains a byte-preserved, untracked shell prototype and is not part of
  the Python package or the reusable template.

## Maintenance contract

Whenever a change affects project layout, package or test paths, Python version,
build backend, runtime dependencies, dependency groups, pytest/Ruff/mypy settings,
UV cache or lockfile strategy, or documented setup and verification commands,
update this guide in the same change.

A project-structure change is incomplete until this guide matches the repository.
````

- [ ] **Step 3: Expand the repository README with working setup instructions**

Keep the existing product idea and notification notes. Append:

````markdown
## Python project setup

The Python package uses [uv](https://docs.astral.sh/uv/) with Python 3.12.

```bash
uv sync
```

The installable package lives under `src/bitbucket_helper`. The existing `source/`
directory is an independent shell prototype and is intentionally left unchanged.
See [the reusable UV project structure guide](docs/uv-project-structure.md) for the
layout, configuration, and replication checklist.

## Development checks

```bash
uv lock --check
uv run pytest
uv run ruff check .
uv run mypy src
uv build
bash source/tests/run.sh
```
````

Use a four-backtick outer fence while authoring the README patch so the nested Bash
blocks render correctly; the final README contains only the Markdown shown above.

- [ ] **Step 4: Review documentation against the real files and commands**

Run:

```bash
git diff --check
test -f AGENTS.md
test -f docs/uv-project-structure.md
test -f src/bitbucket_helper/__init__.py
test -f tests/unit/test_package.py
test -f tests/integration/.gitkeep
uv lock --check
uv run pytest
uv run ruff check .
uv run mypy src
uv build
```

Expected: whitespace validation and all file checks succeed; every documented
Python/UV command succeeds. Read the rendered Markdown source once to confirm that
the tree, generic template, Bitbucket-specific choices, and maintenance contract
match the repository. Human-facing prose is reviewed directly rather than tested by
asserting exact text.

- [ ] **Step 5: Commit the reusable documentation without the shell prototype**

Run:

```bash
git add AGENTS.md README.md docs/uv-project-structure.md
git status --short
git commit -m "docs: document reusable UV project structure"
```

Expected: only the three named documentation files are staged. `source/` remains
untracked and unstaged.

---

### Task 3: Full Verification and Protected-Source Audit

**Files:**

- Verify only: all files created or modified in Tasks 1–2.
- Preserve: `source/**`.

**Interfaces:**

- Consumes: the complete Python scaffold, reusable guide, and shell prototype.
- Produces: fresh evidence that the lock, test, lint, type, build, shell, package,
  documentation, and source-integrity contracts all hold together.

- [ ] **Step 1: Run the complete Python and packaging verification**

Run:

```bash
uv sync --locked
uv lock --check
uv run pytest
uv run ruff check .
uv run mypy src
uv build
uv run python -c "import bitbucket_helper; assert bitbucket_helper.__version__ == '0.1.0'"
tar -tzf dist/bitbucket_helper-0.1.0.tar.gz
```

Expected: every command exits zero; one package test passes; lint and type checks
are clean; sdist and wheel build; and the installed package reports `0.1.0`.
The tar listing contains no `.uv-cache`, `.venv`, nested `dist`, temporary tar
archive, or `source/` path.

- [ ] **Step 2: Run the preserved shell test suite**

Run:

```bash
bash source/tests/run.sh
```

Expected: output ends with `PASS: all Bitbucket script checks`.

- [ ] **Step 3: Prove that `source/` is byte-identical and unstaged**

Run:

```bash
rg --files source -0 | sort -z | xargs -0 shasum -a 256
git ls-files source
git status --short --untracked-files=all
```

Expected: the SHA-256 output exactly matches the ten-line Global Constraints
manifest; `git ls-files source` prints nothing; status lists all ten `source/` files
as untracked and none as staged or modified.

- [ ] **Step 4: Review the implementation against every acceptance criterion**

Read the approved design and this plan, then confirm:

```text
[ ] Python 3.12 and requires-python >=3.12 are aligned.
[ ] atlassian-python-api>=4.0.7 is a direct dependency in pyproject.toml and uv.lock.
[ ] Hatchling builds src/bitbucket_helper.
[ ] Hatchling's sdist excludes local/generated state and the protected source/ tree.
[ ] Unit and integration test boundaries exist; live tests are deselected.
[ ] Ruff, strict mypy, pytest coverage, and UV cache settings match the spec.
[ ] README setup commands are accurate.
[ ] docs/uv-project-structure.md can reproduce the scaffold independently.
[ ] AGENTS.md requires same-change updates to the structure guide.
[ ] source/ is byte-identical, untracked, and unstaged.
```

If any item is false, return to the owning task, correct it, and rerun that task's
verification plus Tasks 3.1–3.3.

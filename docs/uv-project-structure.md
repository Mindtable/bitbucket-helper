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
project has no HTTP boundary. If it has no direct runtime dependencies, use
`dependencies = []` instead of keeping the example dependency.

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
   import unittest
   from importlib import import_module
   from importlib.util import find_spec


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

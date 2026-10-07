# Contributing to SharifTranslate

Thank you for taking the time to contribute. This document covers everything you need to know to get started.

---

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Ways to Contribute](#ways-to-contribute)
- [Development Setup](#development-setup)
- [Branch Strategy](#branch-strategy)
- [Commit Messages](#commit-messages)
- [Opening a Pull Request](#opening-a-pull-request)
- [Architecture Overview](#architecture-overview)
- [Running Tests](#running-tests)

---

## Code of Conduct

Be respectful. Disagreements about code are fine; personal attacks are not. We are all here to build something good.

---

## Ways to Contribute

- **Bug reports** — open an issue using the Bug Report template
- **Feature requests** — open an issue using the Feature Request template
- **Bug fixes** — fork, fix, open a PR against `main`
- **New features** — open an issue first to discuss before writing code
- **UI translations** — see [Adding a Language](wiki/Adding-a-Language.md)
- **Plugins** — see [Creating a Plugin](wiki/Creating-a-Plugin.md)
- **Documentation** — PRs improving the wiki or README are always welcome

---

## Development Setup

**Requirements**
- JDK 17 for Gradle build logic and JDK 21 for compilation and packaging
- Use the included Gradle wrapper and configured Kotlin version

**Steps**

```bash
git clone https://github.com/AliEs85ir/Sharif-Translate.git
cd Sharif-Translate
./gradlew build
./gradlew :app:run -DappData="path/to/your/test/data"
```

On Windows, run `./gradlew.bat` instead. The included Gradle wrapper resolves Java 21 toolchains for compilation. The Windows executable needs a Windows host and `:app:windowsZip`.

## Branch Strategy

`main` is the only permanent branch. Create a short-lived branch from `main`, open a PR targeting `main`, and delete the branch after merging. No `develop` branch or `upstream` remote is required.

```bash
git checkout main
git pull origin main
git checkout -b feature/my-feature
# Make changes, then run the build and tests.
git push origin feature/my-feature
```

## Commit Messages

We follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <short summary>

[optional body]

[optional footer]
```

**Types**

| Type | When to use |
|------|-------------|
| `feat` | A new feature |
| `fix` | A bug fix |
| `refactor` | Code change that is neither a fix nor a feature |
| `docs` | Documentation only |
| `test` | Adding or fixing tests |
| `chore` | Build system, dependencies, CI |
| `style` | Formatting, whitespace (no logic change) |

**Scopes** (optional but helpful): `core`, `api`, `ui`, `plugins`, `ci`

**Examples**

```
feat(core): add backward translation to TranslateTextUseCase
fix(ui): prevent font combos from blocking EDT on AppearancePanel
docs: add plugin submission guide to wiki
chore(ci): add release workflow for tag pushes
```

The summary line should be under 72 characters and written in the imperative mood ("add", "fix", "remove" — not "added", "fixed", "removes").

---

## Opening a Pull Request

1. Update your branch from `main`: `git fetch origin` then `git rebase origin/main`.
2. Run the full build and tests locally: `./gradlew build test`.
3. Push your branch and open a PR targeting `main`.
4. Describe what changed, why, and any associated issue.

**Review checklist (we check these)**
- [ ] Builds without warnings
- [ ] All existing tests pass
- [ ] New behaviour is covered by tests where practical
- [ ] MVI architecture respected — no logic in UI components
- [ ] Coroutine dispatchers used correctly (`Dispatchers.IO` for blocking I/O)
- [ ] No raw exceptions thrown for expected failures — use `Result`
- [ ] KDoc on public API surface

A maintainer will review within a few days. We may request changes — this is normal and not a rejection.

---

## Architecture Overview

SharifTranslate follows Clean Architecture with MVI for the UI layer. The full architecture guide is in [wiki/Architecture.md](wiki/Architecture.md). The key rules:

- **`:api`** — interfaces and data types only. No implementations. Plugins depend on this.
- **`:core`** — business logic, use cases, stores, repositories. No Swing imports.
- **`:ui-swing`** — Swing UI components. Implements `Renderable<State>`, dispatches `Intent`s.
- **`:app`** — composition root. Wires everything together. As thin as possible.
- **`:plugins/*`** — plugin implementations. Depend only on `:api`.

UI components must be "dumb" — they render state and dispatch intents, nothing more.

---

## Tests

Run the existing test suite with `./gradlew test`; add focused regression tests for behavior changes.

If you want to help:

- Pick a use case or repository class (e.g. `TranslateTextUseCase`, `PluginLoader`, `SettingsRepository`) and write unit tests for it
- Open an issue titled `test: add tests for <class name>` so others know what you are working on — we will label it [`good first issue`](README.md)
- We plan to use **JUnit 5** with **`kotlinx-coroutines-test`** for testing suspend functions

If you are new to testing Kotlin coroutines, the [official testing guide](https://kotlinlang.org/docs/coroutines-testing.html) is a good starting point.

> **CI note:** The test step in CI currently runs with `continue-on-error: true` so it does not block PRs. This flag will be removed once a meaningful test suite is in place.
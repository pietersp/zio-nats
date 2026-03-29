# Development

This document covers the local setup expected for contributors working on `zio-nats`.

## Local Tooling

This repository uses `devenv` for a reproducible development shell and `skillshare` to sync shared AI skills into local tool directories.

- `devenv` provides the Scala/JDK tooling used for everyday development.
- `skillshare` keeps project-local skills in sync for tools such as OpenCode and Claude Code.

## First-Time Setup

1. Install `devenv` and `direnv` if you do not already have them.
2. Install `skillshare` by following the instructions at `https://skillshare.runkids.cc/`.
3. From the repository root, allow direnv:

```sh
direnv allow
```

This loads the `devenv` shell automatically via `.envrc`. If you do not use `direnv`, you can enter the shell manually with:

```sh
devenv shell
```

## Required After Checkout

After cloning or checking out the repository on a new machine, run the project skill sync once:

```sh
skillshare sync -p
```

This syncs skills from `.skillshare/skills` into the local tool-specific directories used by this repository, such as `.agents/skills`.

If you are already inside the `devenv` shell, you can use the helper command instead:

```sh
sync-skills
```

It is also a good idea to rerun the sync after pulling changes that update `.skillshare/`.

## Common Commands

Inside the `devenv` shell, these helper commands are available:

- `sync-skills` - run `skillshare sync -p`
- `format` - run `sbt scalafmtAll`
- `check` - run formatting checks and compile the project
- `run-examples` - run the example application

For the full test suite:

```sh
sbt zioNatsTest/test
```

## Release Labels

This repository uses Release Drafter to build draft GitHub releases from merged pull requests. PR labels affect both the release notes grouping and the suggested semantic version bump, so please add the most accurate release label before merging.

Recommended labels:

- `feat`, `feature`, `enhancement` - new functionality; grouped under Features; suggests a minor version bump
- `fix`, `bug`, `bugfix` - bug fixes; grouped under Fixes; suggests a patch version bump
- `docs`, `documentation` - documentation-only changes; grouped under Documentation; suggests a patch version bump
- `chore`, `build`, `dependencies`, `deps`, `perf`, `refactor`, `test`, `ci` - maintenance, tooling, dependency, performance, refactor, test, or CI work; grouped under Maintenance; suggests a patch version bump
- `breaking`, `major`, `breaking-change` - breaking changes; suggests a major version bump
- `skip-changelog` - exclude the PR from drafted release notes

Guidelines:

- Prefer exactly one primary release label per PR unless the change is genuinely cross-cutting.
- Use a breaking label on any PR that changes public API or behavior in a non-compatible way, even if it also has `feat` or `fix`.
- If no release label is present, Release Drafter can still include the PR, but the category and version suggestion may be less useful.

## Releasing

Releases are now created from GitHub rather than by creating tags locally.

1. Merge PRs into `master` with the correct release labels.
2. Open the draft release in GitHub Releases.
3. Review the generated notes and confirm the proposed version tag.
4. Publish the release in GitHub.

When you publish the release, GitHub creates the tag and the release workflow publishes artifacts from that tag. You do not need to run `git tag` or push tags manually for normal releases.

## Notes

- `.skillshare/skills` is the source of truth for shared project skills.
- Generated target directories such as `.agents/skills` and `.claude/skills` are synced locally and are git-ignored.
- If `sync-skills` reports that `skillshare` is missing, install it first and rerun the command.

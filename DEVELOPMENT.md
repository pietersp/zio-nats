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

## Notes

- `.skillshare/skills` is the source of truth for shared project skills.
- Generated target directories such as `.agents/skills` and `.claude/skills` are synced locally and are git-ignored.
- If `sync-skills` reports that `skillshare` is missing, install it first and rerun the command.

# Contributing to Shard

## Sign your commits

Every commit must carry a `Signed-off-by` line. It is not a copyright assignment and gives nobody
extra rights over your work - it is your statement that you wrote the patch, or otherwise have the
right to contribute it under the project's licence. The full text is the
[Developer Certificate of Origin](https://developercertificate.org/).

Git adds the line for you:

```bash
git commit -s -m "fix(check): stop the buffer from decaying while offline"
```

If you forgot it on the last commit:

```bash
git commit --amend -s --no-edit
```

CI checks every commit in the pull request, merge commits aside. A pull request without a sign-off
will not be merged.

## Before you open a pull request

Format first, then run the three checks CI runs. `test` and `detekt` have to be separate calls - a
combined one fails while Gradle recomputes the task graph.

```bash
./gradlew spotlessApply
```

```bash
./gradlew test --rerun-tasks
```

```bash
./gradlew detekt --rerun-tasks
```

```bash
./gradlew --no-daemon containerTest
```

`containerTest` needs Docker: it runs the database migrations against a real MariaDB.

## Style

The formatter decides layout - run `spotlessApply` and take what it gives.

Comments are the exception rather than the rule. Write one when the *why* is not obvious from the
code: a hidden API constraint, a counter-intuitive invariant, a workaround for someone else's bug.
Do not restate what the line already says.

Commit subjects follow Conventional Commits and describe the change, not the goal:
`fix(mitigation): stamp projectiles through the vanilla launch event`, not
`fix: make projectiles work`.

## Changing the database

Migrations live in `src/main/resources/db/migration/{sqlite,mysql}` and come in pairs - one file per
dialect, same version number. A released migration is frozen: once a version has shipped, change it
only by adding a new one on top.

## Reporting a security issue

Do not open a public issue - write to the maintainer directly instead.

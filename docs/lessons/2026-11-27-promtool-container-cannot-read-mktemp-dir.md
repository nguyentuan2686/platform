# Containerised tool could not read a 0700 mktemp directory

- Date: 2026-11-27
- Tags: build | process

## Symptom
`scripts/alerts-promtool.sh` passed locally (Docker Desktop) but failed in CI job `ops-scripts`:
`promtool: error: stat rules.generated.yaml: permission denied`.

## Root cause
`mktemp -d` creates a 0700 directory owned by the runner user. The pinned `prom/prometheus` image runs as `nobody`, so on a native
Linux Docker host (the CI runner) the bind-mounted directory is unreadable inside the container. Docker Desktop on Windows ignores
those permissions, so the local run could not show it.

## Fix
`chmod -R a+rX "$tmp"` before mounting the temp dir read-only into the container.

## How to catch earlier
Any script that bind-mounts a `mktemp -d` directory into a container that is not root must make it world-readable. Local Docker
Desktop runs do not prove Linux permission behaviour; the first CI run of a new Docker-based script is its real test.

## Escalation
none (first occurrence)

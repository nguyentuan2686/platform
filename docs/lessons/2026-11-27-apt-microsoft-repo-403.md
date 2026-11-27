# notify-svc verify failed on `apt-get update`: packages.microsoft.com answered 403

- Date: 2026-11-27
- Tags: infra, build

## Symptom
PR #38 run 45951299248 (head 655f4a4): `notify-svc / verify` failed in its first step after 22 s. `sudo apt-get update` (installs cmake etc. for rdkafka) exited 100 with `E: Failed to fetch https://packages.microsoft.com/ubuntu/24.04/prod/dists/noble/InRelease 403 Forbidden`, so `ci-gate` failed. The PR touched no Rust code.

## Root cause
A transient refusal from the Microsoft apt repository that GitHub's ubuntu-24.04 image preconfigures. `apt-get update` fails on any broken source, even though we install nothing from that repo. No code or workflow defect.

## Fix
`gh run rerun --failed`, no code change (CLAUDE.md CI triage: a 4xx/5xx from a package registry is an external flake).

## How to catch earlier
It is the same class as a Maven Central 429. Read the log; an apt "Failed to fetch ... 403/5xx" on a third-party source means rerun.

## Escalation
none (first occurrence for apt). If it recurs: drop `/etc/apt/sources.list.d/microsoft-prod.list` before `apt-get update` in `.github/workflows/_rust-service.yml` (we need nothing from it), i.e. make it a hard fix.

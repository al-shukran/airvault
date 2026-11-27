#!/usr/bin/env bash
# Fails when a workflow guard that stops a bad run is removed or reordered. The group check compares the concurrency expression
# as a string, and the release check reads the steps parsed to JSON, so a YAML comment never counts. Neither evaluates the GitHub
# expressions.
#
# comment-run.yml: the concurrency group keeps the trusted-/run- gate, so a comment that is not a trusted /run- command cannot
# cancel a running command (#1209, #1407).
# release.yml: the step that refuses a tag whose commit is not on master comes before the first step that reads any secret other
# than GITHUB_TOKEN, in the same job, so an off-master tag is never signed (#624, #1408).
# pr-fast.yml: the required check pr-ok waits for the design gate and its guard, its aggregate fails on the gate, the gate has no condition that
# can skip it, the guard runs the base commit's copy of guard.py (the design gate, tools/design-frames/README.md), the guard is re-run when
# design:approved-change is added or removed (design-guard-rerun.yml, #1452), and the design lane keeps a 10-minute cap so a slow setup step
# does not cancel the required check (#1458).
#
# Usage: scripts/ci/check-workflow-guards.sh   (the ci lane: scripts/ci/run-partition.sh ci, in pr-fast.yml)
# WORKFLOWS_DIR names another directory of workflows; scripts/ci/selftest-guards.sh uses it to check the guard against broken copies.

set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$root"
workflows=${WORKFLOWS_DIR:-.github/workflows}

failed=0
fail() {
    echo "error: $*" >&2
    failed=1
}

# (a) comment-run.yml: the concurrency group is exactly the trusted-/run- gate: the /run- prefix, the exact trusted-association
# list, and the per-run fallback. Any other text in the expression (a bare author_association, a "true ||" prefix) fails.
group=$(yq '.concurrency.group' "$workflows/comment-run.yml")
gate="startsWith(github.event.comment.body, '/run-') && contains(fromJSON('[\"OWNER\",\"MEMBER\",\"COLLABORATOR\"]'), github.event.comment.author_association) && 'command' || github.run_id"
expected="comment-run-\${{ github.event.issue.number }}-\${{ $gate }}"
[ "$group" = "$expected" ] || fail "comment-run.yml concurrency.group is not exactly: $expected"

# (b) release.yml: in every job, a refusal step comes before the first step that reads a secret other than GITHUB_TOKEN.
#  - A secret read is any "secrets." or "secrets[" reference, or toJSON(secrets), in the whole step as JSON (env, with, run, a shell
#    comment in run, which Actions expands too). Job-level env, a job-level secrets: key, and workflow-level text count as before step 0.
#  - The refusal is a step with only the keys name, run and id (no if, env, shell, continue-on-error) whose run is exactly the
#    canonical form, line for line (lines split at "\n" only, as bash does; blank lines aside): `set -euo pipefail`, the `git fetch` of origin/master, the top-level
#    `if ! git merge-base --is-ancestor "$GITHUB_SHA" origin/master; then`, any number of `echo "::error::..."` lines that use
#    no expansion but $GITHUB_REF_NAME and $GITHUB_SHA, `exit 1`, `fi`. Any other statement (an earlier `exit 0`, a trap, an alias,
#    a change to GITHUB_SHA or to origin/master) or an operator the shell could read differently is refused, so the shell cannot
#    skip, swallow or redirect the exit (#1451, #1465, #1470).
#  - Only the checkout, pinned by SHA with persist-credentials false and fetch-depth 0, may run before the refusal: no other step can write
#    BASH_ENV or PATH to $GITHUB_ENV / $GITHUB_PATH or run a local action ahead of it.
#  - The workflow and each job have only the keys name, on, permissions, concurrency, jobs / name, runs-on, timeout-minutes, steps, so
#    no defaults.run.shell, defaults.run.working-directory or env (BASH_ENV, PATH, GIT_DIR) reaches the refusal step.
yq -o=json '.' "$workflows/release.yml" | python3 -I -c '
import json
import re
import sys

doc = json.load(sys.stdin)
SECRET = re.compile(r"(?<![\w.-])secrets\s*(?:\.\s*([\w-]+)|\[|(?=\s*[),}]))", re.I)
COMMAND = "git merge-base --is-ancestor \"$GITHUB_SHA\" origin/master"
HEAD = ["set -euo pipefail", "git fetch --no-tags origin \x27+refs/heads/master:refs/remotes/origin/master\x27", "if ! " + COMMAND + "; then"]
ERROR = re.compile(r"echo \"::error::(?:[^\"$`\\\\!]|\$GITHUB_REF_NAME|\$GITHUB_SHA)*\"")
TAIL = ["exit 1", "fi"]
CHECKOUT = re.compile(r"actions/checkout@[0-9a-f]{40}")
WORKFLOW_KEYS = {"name", "on", "permissions", "concurrency", "jobs"}
JOB_KEYS = {"name", "runs-on", "timeout-minutes", "steps"}
errors = []


def reads_secret(obj):
    return any((m.group(1) or "").lower() != "github_token" for m in SECRET.finditer(json.dumps(obj)))


def early_ok(step):
    return set(step) <= {"name", "uses", "with"} and bool(CHECKOUT.fullmatch(str(step.get("uses", "")))) and step.get("with") == {"persist-credentials": False, "fetch-depth": 0}


def refuses(step):
    if not set(step) <= {"name", "run", "id"}:
        return False
    lines = [line.strip(" \t") for line in str(step.get("run", "")).split("\n") if line.strip(" \t")]
    return (
        lines[: len(HEAD)] == HEAD
        and lines[len(lines) - len(TAIL) :] == TAIL
        and all(ERROR.fullmatch(line) for line in lines[len(HEAD) : len(lines) - len(TAIL)])
    )


if set(doc) - WORKFLOW_KEYS:
    errors.append("release.yml has workflow keys outside %s (defaults and env change how the refusal step runs): %s" % (sorted(WORKFLOW_KEYS), sorted(set(doc) - WORKFLOW_KEYS)))
if reads_secret({k: v for k, v in doc.items() if k != "jobs"}):
    errors.append("release.yml reads a secret outside its jobs, before any step can refuse")
refused = False
for name, job in doc.get("jobs", {}).items():
    steps = job.get("steps", [])
    refuse = next((i for i, step in enumerate(steps) if refuses(step)), None)
    secret = next((i for i, step in enumerate(steps) if reads_secret(step)), None)
    if set(job) - JOB_KEYS:
        errors.append("release.yml job %s has keys outside %s (defaults and env change how the refusal step runs): %s" % (name, sorted(JOB_KEYS), sorted(set(job) - JOB_KEYS)))
    if refuse is not None and not all(early_ok(step) for step in steps[:refuse]):
        errors.append("release.yml job %s runs a step other than the SHA-pinned checkout (persist-credentials false, fetch-depth 0) before the refusal" % name)
    if "secrets" in job or reads_secret({k: v for k, v in job.items() if k != "steps"}):
        errors.append("release.yml job %s reads a secret outside its steps (job env, secrets:, a reusable workflow), before any step can refuse" % name)
    elif secret is not None and refuse is None:
        errors.append("release.yml job %s reads a secret in step %d and has no valid step that refuses a tag not on master" % (name, secret))
    elif secret is not None and refuse >= secret:
        errors.append("release.yml job %s refuses a tag not on master in step %d, not before the secret in step %d" % (name, refuse, secret))
    refused = refused or refuse is not None
if not refused:
    errors.append("release.yml has no valid step that refuses a tag not on master (only name, run and id keys; if ! git merge-base --is-ancestor against origin/master, then exit 1)")
for message in errors:
    print("error: " + message, file=sys.stderr)
sys.exit(1 if errors else 0)
' || failed=1

# (b2) No workflow other than release.yml reads a secret except GITHUB_TOKEN and NVD_API_KEY (nightly.yml): the keystore secrets are
# read only where the refusal comes first. The scan is over the raw text, so a comment, a dynamic secrets[...] and toJSON(secrets) count too.
find "$workflows" -maxdepth 1 \( -name '*.yml' -o -name '*.yaml' \) ! -name release.yml -print0 | xargs -0 python3 -I -c '
import re
import sys

SECRET = re.compile(r"(?<![\w.-])secrets\s*(?:\.\s*([\w-]+)|\[)|toJSON\(\s*secrets|secrets:\s*inherit", re.I)
bad = 0
for path in sys.argv[1:]:
    for m in SECRET.finditer(open(path).read()):
        if (m.group(1) or "").lower() not in {"github_token", "nvd_api_key"}:
            print("error: %s reads a secret other than GITHUB_TOKEN and NVD_API_KEY (%s); only release.yml may read the keystore secrets" % (path, m.group(0)), file=sys.stderr)
            bad = 1
sys.exit(bad)
' || failed=1

# (c) pr-fast.yml: pr-ok needs the design gate and its guard, its aggregate loop lists the gate, the gate cannot be skipped, and the guard job runs
# guard.py from the base commit.
prfast=$workflows/pr-fast.yml
needs=$(yq '.jobs.pr-ok.needs[]' "$prfast")
for job in design design_guard; do
    grep -Fxq -- "$job" <<<"$needs" || fail "pr-fast.yml: pr-ok no longer needs $job"
done
aggregate=$(yq '.jobs.pr-ok.steps[] | select(.name == "Aggregate results") | .run' "$prfast")
grep -Fq -- '"design=$DESIGN"' <<<"$aggregate" || fail "pr-fast.yml: the aggregate of pr-ok no longer checks the design gate"
grep -Fq -- 'DESIGN_GUARD' <<<"$aggregate" || fail "pr-fast.yml: the aggregate of pr-ok no longer checks the design guard"
[ "$(yq '.jobs.design | has("if")' "$prfast")" = false ] || fail "pr-fast.yml: the design job has a condition, so it can be skipped"
grep -Fq -- 'git show "$BASE_SHA:scripts/design/guard.py"' <<<"$(yq '.jobs.design_guard.steps[].run' "$prfast")" || fail "pr-fast.yml: the design guard no longer runs guard.py from the base commit"
grep -Fq -- 'run-partition.sh design' <<<"$(yq '.jobs.design.steps[].run' "$prfast")" || fail "pr-fast.yml: the design job no longer runs the design lane"
# design-guard-rerun.yml re-runs the guard job when design:approved-change is added or removed (#1452).
rerun=$workflows/design-guard-rerun.yml
if [ -f "$rerun" ]; then
    types=$(yq '.on.pull_request.types[]' "$rerun")
    for type in labeled unlabeled; do
        grep -Fxq -- "$type" <<<"$types" || fail "design-guard-rerun.yml: pull_request types lack $type"
    done
    [ "$(yq '.jobs.rerun.if' "$rerun")" = "github.event.label.name == 'design:approved-change'" ] || fail "design-guard-rerun.yml: the job does not run for exactly the label design:approved-change"
    [ "$(yq '.jobs.rerun.permissions.actions' "$rerun")" = write ] || fail "design-guard-rerun.yml: the job lacks actions: write"
    steps=$(yq '.jobs.rerun.steps[].run' "$rerun")
    grep -Fq -- '.name == "design guard"' <<<"$steps" || fail "design-guard-rerun.yml: the job does not look up the design guard job"
    grep -Fq -- 'gh run rerun "$run_id" --job' <<<"$steps" || fail "design-guard-rerun.yml: the job does not re-run the design guard job"
else
    fail "design-guard-rerun.yml is missing, so removing design:approved-change does not re-run the design guard"
fi
[ "$(yq '.jobs.design.timeout-minutes' "$prfast")" -ge 10 ] 2>/dev/null || fail "pr-fast.yml: the design job has a cap under 10 minutes, so a slow setup step can cancel it"

if [ "$failed" -ne 0 ]; then
    exit 1
fi
echo "check-workflow-guards: comment-run concurrency gate, release refusal order and the pr-ok design gate hold"

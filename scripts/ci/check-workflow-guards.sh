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
# can skip it, and the guard runs the base commit's copy of guard.py (the design gate, tools/design-frames/README.md).
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
#  - The refusal is a step with only the keys name, run and id (no if, env, shell, continue-on-error) whose run holds the top-level
#    line `if ! git merge-base --is-ancestor "$GITHUB_SHA" origin/master; then` followed by an unconditional `exit 1` in that block
#    (not after `&&`/`||`, in an `else`, or in a nested if). Echoing the command or commenting it out does not count.
yq -o=json '.' "$workflows/release.yml" | python3 -I -c '
import json
import re
import sys

doc = json.load(sys.stdin)
SECRET = re.compile(r"(?<![\w.-])secrets\s*(?:\.\s*([\w-]+)|\[|(?=\s*[),}]))", re.I)
OPENER = re.compile(r"(if|case|while|until|for|select)\b|.*\{$")
COMMAND = "git merge-base --is-ancestor \"$GITHUB_SHA\" origin/master"
errors = []


def reads_secret(obj):
    return any((m.group(1) or "").lower() != "github_token" for m in SECRET.finditer(json.dumps(obj)))


def refuses(step):
    if not set(step) <= {"name", "run", "id"}:
        return False
    depth = 0
    armed = False
    chained = False
    for line in str(step.get("run", "")).splitlines():
        text = line.strip()
        if not text or text.startswith("#"):
            continue
        if depth == 0 and not chained and text == "if ! " + COMMAND + "; then":
            armed = True
        elif armed and depth == 1 and not chained and text == "exit 1":
            return True
        elif armed and text.startswith(("else", "elif")):
            armed = False
        if OPENER.match(text):
            depth += 1
        elif text in ("fi", "esac", "done", "}"):
            depth -= 1
            armed = armed and depth > 0
        chained = text.endswith(("\\", "&&", "||", "|"))
    return False


if reads_secret({k: v for k, v in doc.items() if k != "jobs"}):
    errors.append("release.yml reads a secret outside its jobs, before any step can refuse")
refused = False
for name, job in doc.get("jobs", {}).items():
    steps = job.get("steps", [])
    refuse = next((i for i, step in enumerate(steps) if refuses(step)), None)
    secret = next((i for i, step in enumerate(steps) if reads_secret(step)), None)
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

if [ "$failed" -ne 0 ]; then
    exit 1
fi
echo "check-workflow-guards: comment-run concurrency gate, release refusal order and the pr-ok design gate hold"

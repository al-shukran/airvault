#!/usr/bin/env bash
# Fails when a workflow guard that stops a bad run is removed or reordered. Both checks are string checks on the workflow
# files; they do not evaluate the GitHub expressions.
#
# comment-run.yml: the concurrency group keeps the trusted-/run- gate, so a comment that is not a trusted /run- command cannot
# cancel a running command (#1209, #1407).
# release.yml: the step that refuses a tag whose commit is not on master comes before the first step that reads a keystore
# secret, so an off-master tag is never signed (#624, #1408).
# pr-fast.yml: the required check pr-ok waits for the design gate and its guard, its aggregate fails on the gate, the gate has no condition that
# can skip it, and the guard runs the base commit's copy of guard.py (the design gate, tools/design-frames/README.md).
#
# Usage: scripts/ci/check-workflow-guards.sh   (the ci lane: scripts/ci/run-partition.sh ci, in pr-fast.yml)

set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$root"

failed=0
fail() {
    echo "error: $*" >&2
    failed=1
}

# (a) comment-run.yml: the concurrency group keeps the /run- and author_association gate, and the per-run fallback.
group=$(yq '.concurrency.group' .github/workflows/comment-run.yml)
for required in "startsWith(github.event.comment.body, '/run-')" "author_association" "github.run_id"; do
    grep -Fq -- "$required" <<<"$group" || fail "comment-run.yml concurrency.group lost '$required'"
done

# (b) release.yml: the merge-base refusal runs before the first keystore secret is read.
release=.github/workflows/release.yml
refuse_line=$(grep -nF 'git merge-base --is-ancestor "$GITHUB_SHA" origin/master' "$release" | head -n 1 | cut -d: -f1 || true)
secret_line=$(grep -nE '\$\{\{ *secrets\.[A-Z0-9_]*KEYSTORE_' "$release" | head -n 1 | cut -d: -f1 || true)
if [ -z "$refuse_line" ]; then
    fail "release.yml lost the step that refuses a tag not on master (git merge-base --is-ancestor against origin/master)"
elif [ -z "$secret_line" ]; then
    fail "release.yml reads no keystore secret, so the refusal cannot be checked against signing"
elif [ "$refuse_line" -ge "$secret_line" ]; then
    fail "release.yml refuses a tag not on master at line $refuse_line, after the keystore secret at line $secret_line"
fi

# (c) pr-fast.yml: pr-ok needs the design gate and its guard, its aggregate loop lists the gate, the gate cannot be skipped, and the guard job runs
# guard.py from the base commit.
prfast=.github/workflows/pr-fast.yml
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

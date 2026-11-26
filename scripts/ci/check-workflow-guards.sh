#!/usr/bin/env bash
# Fails when a workflow guard that stops a bad run is removed or reordered. The group check is a string check on the concurrency
# expression, and the release check reads the parsed steps, so a YAML or shell comment never counts. Neither evaluates the GitHub
# expressions.
#
# comment-run.yml: the concurrency group keeps the trusted-/run- gate, so a comment that is not a trusted /run- command cannot
# cancel a running command (#1209, #1407).
# release.yml: the step that refuses a tag whose commit is not on master comes before the first step that reads a keystore
# secret, so an off-master tag is never signed (#624, #1408).
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

# (a) comment-run.yml: the concurrency group keeps the whole trusted-/run- gate: the /run- prefix, the exact trusted-association
# list, and the per-run fallback. A bare author_association is not enough, because every commenter has one.
group=$(yq '.concurrency.group' "$workflows/comment-run.yml")
gate="startsWith(github.event.comment.body, '/run-') && contains(fromJSON('[\"OWNER\",\"MEMBER\",\"COLLABORATOR\"]'), github.event.comment.author_association) && 'command' || github.run_id"
grep -Fq -- "$gate" <<<"$group" || fail "comment-run.yml concurrency.group lost the trusted-commenter gate: $gate"

# (b) release.yml: in every job, the merge-base refusal is a step that runs before the first step that reads a keystore secret.
# The steps are parsed, so a commented-out command does not count; a comment line inside a run block is dropped too.
release=$workflows/release.yml
keystore='secrets\.[A-Z0-9_]*KEYSTORE_'
refused=0
for job in $(yq '.jobs | keys | .[]' "$release"); do
    refuse_index=-1
    secret_index=-1
    if yq ".env, .jobs.\"$job\".env" "$release" | grep -Eq "$keystore"; then
        secret_index=0
    fi
    count=$(yq ".jobs.\"$job\".steps | length" "$release")
    for ((i = 0; i < count; i++)); do
        if [ "$refuse_index" -lt 0 ] && yq ".jobs.\"$job\".steps[$i].run // \"\"" "$release" | grep -v '^[[:space:]]*#' |
            grep -Fq 'git merge-base --is-ancestor "$GITHUB_SHA" origin/master'; then
            refuse_index=$i
        fi
        if [ "$secret_index" -lt 0 ] && yq ".jobs.\"$job\".steps[$i] | [.env, .with]" "$release" | grep -Eq "$keystore"; then
            secret_index=$i
        fi
    done
    if [ "$secret_index" -ge 0 ]; then
        if [ "$refuse_index" -lt 0 ]; then
            fail "release.yml job $job reads a keystore secret and has no step that refuses a tag not on master (git merge-base --is-ancestor against origin/master)"
        elif [ "$refuse_index" -ge "$secret_index" ]; then
            fail "release.yml job $job refuses a tag not on master in step $refuse_index, not before the keystore secret in step $secret_index"
        fi
    fi
    [ "$refuse_index" -ge 0 ] && refused=1
done
[ "$refused" -eq 1 ] || fail "release.yml has no step that refuses a tag not on master (git merge-base --is-ancestor against origin/master)"

if [ "$failed" -ne 0 ]; then
    exit 1
fi
echo "check-workflow-guards: comment-run concurrency gate and release refusal order hold"

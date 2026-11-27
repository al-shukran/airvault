#!/usr/bin/env bash
# Checks the CI guards against broken inputs: each case must make its guard fail, and the real files must still pass. Needs yq and
# python3, no Gradle and no network. Runs in the ci lane (run-partition.sh ci), so a guard that stops rejecting a case fails a pull request.
#   check-cleartext-sources.sh   cleartext allowed outside src/debug/ (#1406, #1441)
#   check-workflow-guards.sh     comment-run trusted gate and release refusal order (#1407, #1408, #1442, #1451, #1465, #1470)
#   close-refresh-pr.sh          the refresh pull request is closed and the comment posted once (#1396, #1443)
#
# Usage: scripts/ci/selftest-guards.sh

set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$root"
work=$(mktemp -d "${TMPDIR:-/tmp}/selftest-guards.XXXXXX")
trap 'rm -rf "$work"' EXIT

failed=0
fail() {
    echo "selftest-guards: FAIL: $*" >&2
    failed=1
}
# expect_fail <name> <command...> and expect_pass <name> <command...> run the guard quietly and compare its exit status. A rejection
# counts only when the guard says "error:", so a crash of the guard itself (a yq or python failure) is not mistaken for one.
expect_fail() {
    local name=$1 output
    shift
    if output=$("$@" 2>&1); then
        fail "$name was accepted"
    elif ! grep -q 'error:' <<<"$output"; then
        fail "$name made the guard stop without an error message: $output"
    fi
}
expect_pass() {
    local name=$1
    shift
    "$@" >/dev/null 2>&1 || fail "$name was rejected"
}

# --- check-cleartext-sources.sh: each variant is written into a fresh tree.
cleartext=$root/scripts/ci/check-cleartext-sources.sh
tree() {
    # tree <path> <xml attribute text>: a tree with one manifest-like file at <path>.
    rm -rf "$work/tree"
    mkdir -p "$work/tree/$(dirname "$1")"
    printf '<manifest xmlns:android="http://schemas.android.com/apk/res/android" xmlns:a="http://schemas.android.com/apk/res/android">\n  <application %s/>\n</manifest>\n' "$2" >"$work/tree/$1"
}
mkdir "$work/tree"
expect_pass "a tree with no cleartext" "$cleartext" "$work/tree"
tree lumio/src/debug/res/xml/network_security_config.xml 'android:usesCleartextTraffic="true"'
expect_pass "cleartext in src/debug/" "$cleartext" "$work/tree"
tree lumio/src/main/AndroidManifest.xml 'android:usesCleartextTraffic="false"'
expect_pass "cleartext set to false" "$cleartext" "$work/tree"
tree lumio/build/intermediates/AndroidManifest.xml 'android:usesCleartextTraffic="true"'
expect_pass "a build directory" "$cleartext" "$work/tree"
tree lumio/src/main/AndroidManifest.xml 'android:usesCleartextTraffic="true"'
expect_fail "double quotes" "$cleartext" "$work/tree"
tree lumio/src/main/AndroidManifest.xml "android:usesCleartextTraffic='true'"
expect_fail "single quotes" "$cleartext" "$work/tree"
tree app/src/main/res/xml/network_security_config.xml 'cleartextTrafficPermitted = "true"'
expect_fail "spaces around =" "$cleartext" "$work/tree"
tree lumio/src/main/AndroidManifest.xml 'android:usesCleartextTraffic="TRUE"'
expect_fail "upper case TRUE" "$cleartext" "$work/tree"
tree lumio/src/main/AndroidManifest.xml 'a:usesCleartextTraffic="true"'
expect_fail "another namespace prefix" "$cleartext" "$work/tree"
tree shared/src/main/res/xml/network_security_config.xml 'cleartextTrafficPermitted="true"'
expect_fail "a shared resource" "$cleartext" "$work/tree"
tree lumio/src/release/AndroidManifest.xml 'android:usesCleartextTraffic="true"'
expect_fail "a release source set" "$cleartext" "$work/tree"
tree lumio/src/main/AndroidManifest.xml 'android:usesCleartextTraffic="@bool/cleartext"'
expect_fail "a resource reference that can resolve to true" "$cleartext" "$work/tree"
tree lumio/src/main/AndroidManifest.xml 'android:usesCleartextTraffic="${cleartext}"'
expect_fail "a manifest placeholder that can resolve to true" "$cleartext" "$work/tree"
tree lumio/src/main/res/src/debug/network_security_config.xml 'cleartextTrafficPermitted="true"'
expect_fail "a src/debug directory nested in a main source set" "$cleartext" "$work/tree"
tree lumio/src/main/res/xml/network_security_config.XML 'cleartextTrafficPermitted="true"'
expect_fail "an upper case .XML extension" "$cleartext" "$work/tree"
tree lumio/src/main/build/network_security_config.xml 'cleartextTrafficPermitted="true"'
expect_fail "a build directory inside src" "$cleartext" "$work/tree"
tree chains/ethereum/signer/src/main/AndroidManifest.xml 'android:usesCleartextTraffic="true"'
expect_fail "a nested module" "$cleartext" "$work/tree"
tree chains/ethereum/signer/src/debug/AndroidManifest.xml 'android:usesCleartextTraffic="true"'
expect_pass "src/debug of a nested module" "$cleartext" "$work/tree"
tree lumio/src/main/AndroidManifest.xml 'android:usesCleartextTraffic="true"'
echo '<broken' >"$work/tree/lumio/src/main/res.xml"
rm "$work/tree/lumio/src/main/AndroidManifest.xml"
expect_fail "XML that cannot be parsed" "$cleartext" "$work/tree"
expect_pass "the repository sources" "$cleartext"

# --- check-workflow-guards.sh: each mutation is applied to a copy of the real workflows.
guards=$root/scripts/ci/check-workflow-guards.sh
mutate() {
    # mutate <file> <yq expression or "sed:<script>">: a fresh copy of the workflows with one change.
    rm -rf "$work/workflows"
    cp -R .github/workflows "$work/workflows"
    case "$2" in
        sed:*) sed -i.bak "${2#sed:}" "$work/workflows/$1" && rm "$work/workflows/$1.bak" ;;
        *) yq -i "$2" "$work/workflows/$1" ;;
    esac
    if cmp -s ".github/workflows/$1" "$work/workflows/$1"; then fail "the mutation of $1 changed nothing: $2"; fi
}
guard_with() { WORKFLOWS_DIR=$work/workflows "$guards"; }
expect_pass "the real workflows" "$guards"
rm -rf "$work/workflows"
cp -R .github/workflows "$work/workflows"
expect_pass "an unchanged copy of the workflows" guard_with
mutate release.yml '.jobs.release.steps[0].env.TOKEN = "${{ secrets.GITHUB_TOKEN }}"'
expect_pass "GITHUB_TOKEN read before the refusal" guard_with
mutate comment-run.yml 'sed:s/contains(fromJSON(.\[[^]]*\].), github.event.comment.author_association)/github.event.comment.author_association/'
expect_fail "a bare author_association" guard_with
mutate comment-run.yml 'sed:s/"OWNER","MEMBER","COLLABORATOR"/"OWNER","MEMBER","COLLABORATOR","NONE"/'
expect_fail "NONE in the trusted list" guard_with
mutate comment-run.yml 'sed:s/ || github.run_id }}/ }}/'
expect_fail "no per-run fallback" guard_with
mutate release.yml 'sed:s/^\( *\)if ! git merge-base/\1# if ! git merge-base/'
expect_fail "a commented-out refusal" guard_with
mutate release.yml '(.jobs.release.steps[] | select(.name | test("^Refuse")) | .run) = "true"'
{ echo '# git merge-base --is-ancestor "$GITHUB_SHA" origin/master'; cat "$work/workflows/release.yml"; } >"$work/release.yml"
mv "$work/release.yml" "$work/workflows/release.yml"
expect_fail "a refusal that only a YAML comment quotes" guard_with
mutate release.yml '(.jobs.release.steps[] | select(.name | test("^Refuse")) | .run) = "true" | .jobs.release.steps += [{"name": "late", "run": "git merge-base --is-ancestor \"$GITHUB_SHA\" origin/master"}]'
expect_fail "a refusal after the keystore secret" guard_with
mutate release.yml '.jobs.release.steps[0].env.EARLY = "${{ secrets.LENSLY_KEYSTORE_PASS }}"'
expect_fail "a keystore secret read before the refusal" guard_with
mutate release.yml 'del(.jobs.release.steps[] | select(.name | test("^Refuse")))'
expect_fail "no refusal step" guard_with
mutate comment-run.yml 'sed:s/-\${{ startsWith(/-${{ true || startsWith(/'
expect_fail "a true || prefix in the group" guard_with
mutate comment-run.yml 'sed:s/-\${{ startsWith(/-${{ !startsWith(/'
expect_fail "a negated /run- test in the group" guard_with
# The design gate stays in pr-ok, cannot be skipped, and its guard runs the base commit's copy.
mutate pr-fast.yml '.jobs.pr-ok.needs -= ["design_guard"]'
expect_fail "pr-ok without the design guard" guard_with
mutate pr-fast.yml '.jobs.pr-ok.needs -= ["design"]'
expect_fail "pr-ok without the design gate" guard_with
mutate pr-fast.yml '.jobs.design.if = "false"'
expect_fail "a design gate that can be skipped" guard_with
mutate pr-fast.yml 'sed:s/design=\$DESIGN/design=success/'
expect_fail "an aggregate that ignores the design gate" guard_with
mutate pr-fast.yml 'sed:s/git show "\$BASE_SHA:scripts/git show "HEAD:scripts/'
expect_fail "a design guard that runs the pull request's copy" guard_with
mutate design-guard-rerun.yml '.on.pull_request.types -= ["unlabeled"]'
expect_fail "a rerun workflow that ignores a removed label" guard_with
mutate design-guard-rerun.yml '.jobs.rerun.if = "true"'
expect_fail "a rerun workflow for every label" guard_with
mutate design-guard-rerun.yml '.jobs.rerun.permissions.actions = "read"'
expect_fail "a rerun workflow without actions: write" guard_with
mutate design-guard-rerun.yml 'sed:s/gh run rerun/gh run view/'
expect_fail "a rerun workflow that does not re-run" guard_with
rm "$work/workflows/design-guard-rerun.yml"
expect_fail "no rerun workflow" guard_with
mutate pr-fast.yml '.jobs.design.timeout-minutes = 5'
expect_fail "a design lane capped at 5 minutes" guard_with
# A secret read before the refusal, in any form the old text match and an env/with scan would miss.
for read in 'echo ${{ secrets.LENSLY_KEYSTORE_PASS }}' "echo \${{ secrets['LENSLY_KEYSTORE_PASS'] }}" 'echo ${{ secrets.lensly_keystore_pass }}' \
    'echo # ${{ secrets.LENSLY_KEYSTORE_PASS }}' 'echo ${{ toJSON(secrets) }}'; do
    mutate release.yml ".jobs.release.steps[0].run = \"$read\""
    expect_fail "a secret read in run before the refusal: $read" guard_with
done
mutate release.yml '.jobs.release.steps[0].env.ALL = "${{ toJSON(secrets) }}"'
expect_fail "toJSON(secrets) in env before the refusal" guard_with
mutate release.yml '.jobs.release.env.PASS = "${{ secrets.LENSLY_KEYSTORE_PASS }}"'
expect_fail "a job-level secret" guard_with
mutate release.yml '.jobs.sign = {"uses": "./.github/workflows/sign.yml", "secrets": "inherit"}'
expect_fail "a reusable workflow that inherits secrets" guard_with
mutate release.yml '.jobs.sign = {"runs-on": "ubuntu-24.04", "steps": [{"run": "echo ${{ secrets.LENSLY_KEYSTORE_PASS }}"}]}'
expect_fail "a second job that reads a secret with no refusal" guard_with
mutate release.yml '(.jobs.release.steps[] | select(.name | test("^Refuse"))) += {"if": "false"}'
expect_fail "a refusal with if: false" guard_with
mutate release.yml '(.jobs.release.steps[] | select(.name | test("^Refuse"))) += {"continue-on-error": true}'
expect_fail "a refusal with continue-on-error" guard_with
mutate release.yml '(.jobs.release.steps[] | select(.name | test("^Refuse")) | .run) = "echo git merge-base --is-ancestor \"$GITHUB_SHA\" origin/master"'
expect_fail "a refusal that only echoes the command" guard_with
mutate release.yml 'sed:s/^\( *\)exit 1$/\1true/'
expect_fail "a refusal without exit 1" guard_with
mutate release.yml 'sed:s/^\( *\)if ! git merge-base \(.*\); then$/\1echo ok # if ! git merge-base \2; then/'
expect_fail "a refusal behind a trailing comment" guard_with
# The refusal step may only have name, run and id, and its exit must run whenever the ancestor check fails (#1448).
refusal='(.jobs.release.steps[] | select(.name | test("^Refuse")))'
check='git merge-base --is-ancestor \"$GITHUB_SHA\" origin/master'
mutate release.yml "$refusal += {\"env\": {\"GITHUB_SHA\": \"abc\"}}"
expect_fail "a refusal with its own env" guard_with
mutate release.yml "$refusal += {\"shell\": \"python\"}"
expect_fail "a refusal with another shell" guard_with
mutate release.yml "$refusal += {\"working-directory\": \"/tmp\"}"
expect_fail "a refusal with a working-directory" guard_with
mutate release.yml "($refusal | .run) = \"if ! $check; then\\n  if false; then\\n    exit 1\\n  fi\\nfi\""
expect_fail "an exit 1 nested in dead code" guard_with
mutate release.yml "($refusal | .run) = \"if false; then\\n  if ! $check; then\\n    exit 1\\n  fi\\nfi\""
expect_fail "a refusal inside an outer if" guard_with
mutate release.yml "($refusal | .run) = \"if ! $check; then\\n  false &&\\n  exit 1\\nfi\""
expect_fail "an exit 1 after &&" guard_with
mutate release.yml "($refusal | .run) = \"if ! $check; then\\n  true ||\\n  exit 1\\nfi\""
expect_fail "an exit 1 after ||" guard_with
mutate release.yml "($refusal | .run) = \"if ! $check; then\\n  echo ok\\nelse\\n  exit 1\\nfi\""
expect_fail "an exit 1 in the else branch" guard_with
mutate release.yml "($refusal | .run) = \"f() {\\n  if ! $check; then\\n    exit 1\\n  fi\\n}\""
expect_fail "a refusal inside a function" guard_with
# The refusal lines must be code the shell runs, not text inside a heredoc or a string that spans lines (#1451).
for open_close in "cat <<'NOTE'|NOTE" 'cat <<NOTE|NOTE' 'cat <<"NOTE"|NOTE' 'cat <<-NOTE|NOTE' 'echo "|"' ": '|'"; do
    opener=${open_close%|*} closer=${open_close#*|}
    mutate release.yml "($refusal | .run) = \"set -euo pipefail\\n${opener//\"/\\\"}\\nif ! $check; then\\n  exit 1\\nfi\\n${closer//\"/\\\"}\""
    expect_fail "a refusal inside $opener" guard_with
done
# The exit 1 must run in the step's own shell, not inside a command substitution or subshell that swallows it (#1465).
mutate release.yml "($refusal | .run) = \"if ! $check; then\\n  echo \\\"::error::refused\\\" \$(\\n    exit 1\\n  )\\n  exit 0\\nfi\""
expect_fail "an exit 1 inside \$( )" guard_with
mutate release.yml "($refusal | .run) = \"if ! $check; then\\n  echo \\\"::error::refused\\\" \`\\n    exit 1\\n  \`\\n  exit 0\\nfi\""
expect_fail "an exit 1 inside backticks" guard_with
mutate release.yml "($refusal | .run) = \"if ! $check; then\\n  (\\n    exit 1\\n  )\\nfi\""
expect_fail "an exit 1 inside a subshell" guard_with
mutate release.yml "($refusal | .run) = \"if ! $check; then\\n  echo hi |&\\n  exit 1\\nfi\""
expect_fail "an exit 1 in the pipeline that a line ending in |& continues" guard_with
# The step's run must be exactly the canonical refusal: no statement before or inside it may skip, override or neuter it (#1470).
canon=$(yq "$refusal | .run" .github/workflows/release.yml)
refuse_with() {
    run=$1 mutate release.yml "($refusal | .run) = strenv(run)"
}
refuse_with "exit 0"$'\n'"$canon"
expect_fail "an exit 0 before the refusal" guard_with
refuse_with "trap 'exit 0' EXIT"$'\n'"$canon"
expect_fail "an EXIT trap that overrides the exit status" guard_with
refuse_with "GITHUB_SHA=origin/master"$'\n'"$canon"
expect_fail "a GITHUB_SHA that is origin/master" guard_with
refuse_with 'git update-ref refs/remotes/origin/master "$GITHUB_SHA"'$'\n'"$canon"
expect_fail "an origin/master moved to the tag commit" guard_with
refuse_with "shopt -s expand_aliases"$'\n'"alias exit=:"$'\n'"$canon"
expect_fail "exit aliased to a no-op" guard_with
refuse_with "${canon%exit 1*}exit 0"$'\n'"exit 1"$'\n'fi
expect_fail "an exit 0 before the exit 1 in the block" guard_with
refuse_with "${canon%exit 1*}"'echo hi |&'$'\n'"exit 1"$'\n'fi
expect_fail "an exit 1 in the pipeline that a line ending in |& continues (canonical form)" guard_with
refuse_with "$canon"
expect_pass "the canonical refusal rewritten from the workflow" guard_with
mutate release.yml "$refusal += {\"id\": \"refuse\"}"
expect_pass "a refusal with an id" guard_with

# --- close-refresh-pr.sh: a stub gh whose first close fails (and records the comment first, as the old gh pr close --comment did).
# Whether the first gh pr comment fails before posting ("drop") or after it ("lost"), the pull request ends closed with one comment.
bin=$work/bin
mkdir -p "$bin"
cat >"$bin/gh" <<'STUB'
#!/usr/bin/env bash
# State lives in $GH_STUB_DIR: state, comments (one per line), close-calls, comment-calls, comment-mode (ok, drop or lost).
d=$GH_STUB_DIR
bump() { local n=$(($(cat "$d/$1") + 1)); echo "$n" >"$d/$1"; echo "$n"; }
record() { while [ $# -gt 0 ]; do [ "$1" = "$FLAG" ] && echo "$2" >>"$d/comments"; shift; done; }
case "$1 $2" in
    "pr view")
        case "$*" in
            *"--json state"*) cat "$d/state" ;;
            *"--json comments"*) cat "$d/comments" ;;
        esac ;;
    "pr close")
        n=$(bump close-calls)
        FLAG=--comment record "$@"
        [ "$n" -gt 1 ] || exit 1
        echo CLOSED >"$d/state" ;;
    "pr comment")
        n=$(bump comment-calls)
        mode=$(cat "$d/comment-mode")
        [ "$mode $n" = "drop 1" ] && exit 1
        FLAG=--body record "$@"
        [ "$mode $n" = "lost 1" ] && exit 1
        exit 0 ;;
esac
STUB
chmod +x "$bin/gh"
export GH_STUB_DIR=$work/stub GH_REPO=owner/repo RUN_URL=https://github.com/owner/repo/actions/runs/1
close_pr() { PATH=$bin:$PATH "$root/scripts/ci/close-refresh-pr.sh" 7 >/dev/null 2>&1; }
comment_count() { wc -l <"$GH_STUB_DIR/comments" | tr -d ' '; }
for mode in ok drop lost; do
    rm -rf "$GH_STUB_DIR"
    mkdir -p "$GH_STUB_DIR"
    echo OPEN >"$GH_STUB_DIR/state"
    : >"$GH_STUB_DIR/comments"
    echo 0 >"$GH_STUB_DIR/close-calls"
    echo 0 >"$GH_STUB_DIR/comment-calls"
    echo "$mode" >"$GH_STUB_DIR/comment-mode"
    for attempt in 1 2 3 4; do
        close_pr && break
    done
    [ "$(cat "$GH_STUB_DIR/state")" = CLOSED ] || fail "close-refresh-pr.sh left the pull request open ($mode)"
    [ "$(comment_count)" = 1 ] || fail "close-refresh-pr.sh posted $(comment_count) comments, not 1 (comment $mode)"
    close_pr || fail "close-refresh-pr.sh failed on a closed pull request ($mode)"
    [ "$(comment_count)" = 1 ] || fail "close-refresh-pr.sh commented again on a pull request it had already closed ($mode)"
done
RUN_URL= expect_fail "an empty RUN_URL" env PATH="$bin:$PATH" "$root/scripts/ci/close-refresh-pr.sh" 7

if [ "$failed" -ne 0 ]; then
    exit 1
fi
echo "selftest-guards: ok"

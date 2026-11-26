#!/usr/bin/env bash
# Checks the CI guards against broken inputs: each case must make its guard fail, and the real files must still pass. Needs yq and
# python3, no Gradle and no network. Runs in the ci lane (run-partition.sh ci), so a guard that stops rejecting a case fails a pull request.
#   check-cleartext-sources.sh   cleartext allowed outside src/debug/ (#1406, #1441)
#   check-workflow-guards.sh     comment-run trusted gate and release refusal order (#1407, #1408, #1442)
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

# --- close-refresh-pr.sh: a stub gh whose first close fails. Whether it records the comment first (the old gh pr close --comment)
# or not, the pull request ends closed with the comment once.
bin=$work/bin
mkdir -p "$bin"
cat >"$bin/gh" <<'STUB'
#!/usr/bin/env bash
# State lives in $GH_STUB_DIR: state, comments (one per line), close-calls.
d=$GH_STUB_DIR
case "$1 $2" in
    "pr view")
        case "$*" in
            *"--json state"*) cat "$d/state" ;;
            *"--json comments"*) cat "$d/comments" ;;
        esac ;;
    "pr close")
        n=$(($(cat "$d/close-calls") + 1)); echo "$n" >"$d/close-calls"
        # A comment is recorded before the close, as gh pr close --comment does.
        while [ $# -gt 0 ]; do
            [ "$1" = --comment ] && echo "$2" >>"$d/comments"
            shift
        done
        [ "$n" -gt 1 ] || exit 1
        echo CLOSED >"$d/state" ;;
    "pr comment")
        while [ $# -gt 0 ]; do
            [ "$1" = --body ] && echo "$2" >>"$d/comments"
            shift
        done ;;
esac
STUB
chmod +x "$bin/gh"
export GH_STUB_DIR=$work/stub GH_REPO=owner/repo RUN_URL=https://github.com/owner/repo/actions/runs/1
rm -rf "$GH_STUB_DIR"
mkdir -p "$GH_STUB_DIR"
echo OPEN >"$GH_STUB_DIR/state"
: >"$GH_STUB_DIR/comments"
echo 0 >"$GH_STUB_DIR/close-calls"
for attempt in 1 2 3; do
    PATH=$bin:$PATH "$root/scripts/ci/close-refresh-pr.sh" 7 >/dev/null 2>&1 && break
done
[ "$(cat "$GH_STUB_DIR/state")" = CLOSED ] || fail "close-refresh-pr.sh left the pull request open"
[ "$(wc -l <"$GH_STUB_DIR/comments" | tr -d ' ')" = 1 ] || fail "close-refresh-pr.sh posted $(wc -l <"$GH_STUB_DIR/comments" | tr -d ' ') comments, not 1"
PATH=$bin:$PATH "$root/scripts/ci/close-refresh-pr.sh" 7 >/dev/null 2>&1 || fail "close-refresh-pr.sh failed on a closed pull request"
[ "$(wc -l <"$GH_STUB_DIR/comments" | tr -d ' ')" = 1 ] || fail "close-refresh-pr.sh commented again on a pull request it had already closed"

if [ "$failed" -ne 0 ]; then
    exit 1
fi
echo "selftest-guards: ok"

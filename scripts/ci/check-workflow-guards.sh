#!/usr/bin/env bash
# Fails when a workflow guard that stops a bad run is removed or reordered. The group check compares the concurrency expression
# as a string, and the release check reads the steps parsed to JSON, so a YAML comment never counts. Neither evaluates the GitHub
# expressions.
#
# comment-run.yml: the concurrency group keeps the trusted-/run- gate, so a comment that is not a trusted /run- command cannot
# cancel a running command (#1209, #1407).
# release.yml: the step that refuses a tag whose commit is not on master comes before the first step that reads any secret other
# than GITHUB_TOKEN, in the same job, so an off-master tag is never signed (#624, #1408). Every job that reads a signing secret
# also has environment: release (whose tag rules keep a branch workflow from reading the secrets), contents: read as its only
# permission, no Gradle, the secrets of one app only (#1493, GitHub advisory on repository-level signing secrets) and no step
# other than the six of SIGN_STEPS (#1531, #1532).
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

# (b) Secrets, in every workflow file, and the refusal order in release.yml (the python below; #624, #1408, #1451, #1465, #1470, #1481).
# Every secret a workflow may read is listed here, one "file expression" per line. Anything else that mentions the secrets context
# fails: secrets.*, secrets['x'], toJSON(secrets), a name not listed, a keystore name outside release.yml. needs.secrets.result is
# the result of the pr-fast.yml job named "secrets" (gitleaks), not a secret read.
secret_allow='
cache-cleanup.yml secrets.GITHUB_TOKEN
cla.yml secrets.GITHUB_TOKEN
dco.yml secrets.GITHUB_TOKEN
nightly.yml secrets.NVD_API_KEY
pr-fast.yml needs.secrets.result
release.yml secrets.LENSLY_KEYSTORE_B64
release.yml secrets.LENSLY_KEYSTORE_PASS
release.yml secrets.LUMIO_KEYSTORE_B64
release.yml secrets.LUMIO_KEYSTORE_PASS
'
# For each workflow file, as raw text and as the parsed YAML (every string, keys included, so "${{ \x73ecrets.X }}" and a quoted
# "secrets": inherit are seen as written), a ${{ }} expression is allowed only when it is exactly a listed expression. Text after a
# ${{ that still names secrets (NFKC, case-folded) fails, so a join(secrets.*, ''), a secrets['x'], a toJSON(secrets) or a "}}" inside
# a string literal cannot hide. A mapping key named secrets fails anywhere (any letter case directly under the workflow or a job)
# except as a job id: no workflow uses a reusable workflow.
#
# release.yml also needs, and checks on the parsed steps (never on text, so a YAML comment never counts):
#  - The refusal is a step with only the keys name, run and id (no if, env, shell, continue-on-error) whose run is exactly the
#    canonical form, line for line (lines split at "\n" only, as bash does; blank lines aside): `set -euo pipefail`, the `git fetch` of origin/master, the top-level
#    `if ! git merge-base --is-ancestor "$GITHUB_SHA" origin/master; then`, any number of `echo "::error::..."` lines that use
#    no expansion but $GITHUB_REF_NAME and $GITHUB_SHA, `exit 1`, `fi`. Any other statement (an earlier `exit 0`, a trap, an alias,
#    a change to GITHUB_SHA or to origin/master) or an operator the shell could read differently is refused, so the shell cannot
#    skip, swallow or redirect the exit.
#  - Only the checkout, pinned by SHA with persist-credentials false and fetch-depth 0, may run before the refusal: no other step can write
#    BASH_ENV or PATH to $GITHUB_ENV / $GITHUB_PATH or run a local action ahead of it.
#  - A secret expression appears only in the env of a step that has a run and no uses, never in a uses: step (its with or env: a remote
#    action's pre script runs before every step, so it would get the secret before the refusal) and never in the run text.
#  - on: is exactly a push of the tags lensly-v* and lumio-v* (no pull_request_target, workflow_dispatch, workflow_run, workflow_call,
#    schedule, branches).
#  - No workflow file has a YAML merge key (<<) or merge tag, which yq drops, so keys hidden in one are never checked.
#  - Names are compared NFKC-normalised and case-folded, so a long s or a full-width letter cannot spell secrets past the check.
#  - The first step that mentions the secrets context comes after the refusal, in the same job. Job-level env, a job-level secrets: key,
#    and workflow-level text count as before step 0.
#  - The workflow and each job have only the keys name, on, permissions, concurrency, jobs / name, needs, if, runs-on, timeout-minutes,
#    environment, permissions, outputs, steps, so no defaults.run.shell, defaults.run.working-directory or env (BASH_ENV, PATH, GIT_DIR)
#    reaches the refusal step, and no continue-on-error can let a job pass after its refusal failed. A job-level if is one of JOB_IF
#    only (the app of the tag, or publish after a sign job succeeded); the refusal is a step of the same job, so it still runs.
#  - A job that reads a secret has environment exactly "release", permissions exactly contents: read (no id-token, no attestations,
#    no write), no Gradle (the word gradle or gradlew as a token, after quotes, backslashes and invisible characters are removed, so
#    gr""adlew counts and an unrelated word does not), and secrets of one app only (LENSLY_ or LUMIO_), so a poisoned build step never
#    shares a runner with a key, and a Lensly release never exposes the Lumio key.
#  - Such a job has exactly the steps of SIGN_STEPS, in that order, and each has exactly the keys and values the script expects. The
#    only run steps are the refusal, `bash scripts/ci/sign-release.sh` and the keystore cleanup, so no extra step can send the
#    decoded keystore (in $RUNNER_TEMP) anywhere, and the two artifact steps move only the unsigned and the signed APK.
#  - Every step after the refusal has only the keys name, id, run, env, uses and with, so no if (in any letter case), continue-on-error
#    or timeout-minutes can make a step run, or keep running, after the refusal failed. The one exception is the keystore cleanup
#    step: name "Delete the decoded keystore", only name, if and run, with if exactly "always()", which reads no secret.
SECRET_ALLOW=$secret_allow python3 -I - "$workflows" <<'PY' || failed=1
import json
import os
import re
import subprocess
import sys
import unicodedata
from pathlib import Path

ALLOW = {}
for line in os.environ["SECRET_ALLOW"].splitlines():
    if line.strip():
        name, expression = line.split(None, 1)
        ALLOW.setdefault(name, set()).add(expression.strip())
EXPRESSION = re.compile(r"\$\{\{(.*?)\}\}", re.S)
# A YAML merge key (<<: ..., "<<": ..., ? <<) or a merge tag in a line that is not a comment. yq drops an inline merge, so its keys
# (an if, a continue-on-error) would never be checked.
MERGE = re.compile(r"(?:^|[\s{,\[])[\"']?<<[\"']?\s*:(?:\s|$)|\?\s*[\"']?<<|!!merge|tag:yaml\.org,2002:merge")
# release.yml runs on a push of one of these tags and on nothing else: any other trigger could give the refusal a master commit.
TRIGGER = {"push": {"tags": ["lensly-v*", "lumio-v*"]}}
COMMAND = "git merge-base --is-ancestor \"$GITHUB_SHA\" origin/master"
HEAD = ["set -euo pipefail", "git fetch --no-tags origin '+refs/heads/master:refs/remotes/origin/master'", "if ! " + COMMAND + "; then"]
ERROR = re.compile(r"echo \"::error::(?:[^\"$`\\!]|\$GITHUB_REF_NAME|\$GITHUB_SHA)*\"")
TAIL = ["exit 1", "fi"]
CHECKOUT = re.compile(r"actions/checkout@[0-9a-f]{40}")
WORKFLOW_KEYS = {"name", "on", "permissions", "concurrency", "jobs"}
JOB_KEYS = {"name", "needs", "if", "runs-on", "timeout-minutes", "environment", "permissions", "outputs", "steps"}
# The only job-level if expressions: the sign job of the tagged app, and publish once one of them succeeded.
JOB_IF = {
    "startsWith(github.ref_name, 'lensly-v')",
    "startsWith(github.ref_name, 'lumio-v')",
    "${{ !cancelled() && (needs.sign_lensly.result == 'success' || needs.sign_lumio.result == 'success') }}",
}
SECRET_OF_APP = re.compile(r"secrets\.([a-z]+)_")
STEP_KEYS = {"name", "id", "run", "env", "uses", "with"}
CLEANUP = "Delete the decoded keystore"
CLEANUP_RUN = 'rm -f "$RUNNER_TEMP/release.keystore"'
# The steps of a job that reads a signing secret, in order. release.yml's sign jobs are exactly this list (#1531).
SIGN_STEPS = ["checkout", "refusal", "download", "sign", "cleanup", "upload"]
SIGN_RUN = "bash scripts/ci/sign-release.sh"
SIGN_ENV = {"APP", "EXPECTED_SHA256", "KEYSTORE_B64", "KEYSTORE_PASS"}
DOWNLOAD = re.compile(r"actions/download-artifact@[0-9a-f]{40}")
UPLOAD = re.compile(r"actions/upload-artifact@[0-9a-f]{40}")
DOWNLOAD_WITH = {"name": "unsigned-apk", "path": "unsigned"}
UPLOAD_WITH = {"name": "signed-apk", "path": "signed/", "if-no-files-found": "error", "retention-days": 3}
# gradle or gradlew as a token: not inside a longer word, so gradle/actions, setup-gradle, ./gradlew and gradlew.bat match and an
# unrelated word does not. Quotes, backslashes and invisible characters are removed first, so ./gr""adlew and gr\adlew match too.
GRADLE = re.compile(r"(?<!\w)gradlew?(?!\w)")
errors = []


def fold(text):
    # The form names are compared in: NFKC (a full-width or long s becomes s), then case-folded.
    return unicodedata.normalize("NFKC", text).casefold()


def runs_gradle(text):
    plain = "".join(c for c in fold(text) if c not in "\"'`\\" and unicodedata.category(c) != "Cf")
    return bool(GRADLE.search(plain))


class Duplicate(ValueError):
    pass


def no_duplicates(pairs):
    keys = [key for key, _ in pairs]
    if len(keys) != len(set(keys)):
        raise Duplicate("a mapping repeats a key")
    return dict(pairs)


def parse(path):
    out = subprocess.run(["yq", "-o=json", "explode(.)", str(path)], capture_output=True, text=True, check=True).stdout
    decoder, pos, docs = json.JSONDecoder(object_pairs_hook=no_duplicates), 0, []
    while pos < len(out):
        if out[pos].isspace():
            pos += 1
        else:
            doc, pos = decoder.raw_decode(out, pos)
            docs.append(doc)
    return docs


def strings(obj):
    if isinstance(obj, dict):
        for key, value in obj.items():
            yield str(key)
            yield from strings(value)
    elif isinstance(obj, list):
        for item in obj:
            yield from strings(item)
    elif isinstance(obj, str):
        yield obj


def unlisted(text, allowed, per_line=False):
    # The text without its listed expressions: a ${{ followed by secrets (on the same line, for raw text) is a read that is not listed.
    rest = EXPRESSION.sub(lambda m: "" if m.group(1).strip() in allowed else m.group(0), text)
    parts = [fold(part) for part in (rest.split("\n") if per_line else [rest])]
    return any("${{" in part and "secrets" in part[part.index("${{") :] for part in parts)


def reads_secret(obj):
    return any(unlisted(text, ()) for text in strings(obj))


def secret_keys(doc):
    jobs = doc.get("jobs") if isinstance(doc, dict) else None
    # Directly under the workflow or a job, any letter case; anywhere else only the exact key (env: SECRETS is a variable name).
    top = [doc] + (list(jobs.values()) if isinstance(jobs, dict) else [])
    found = []

    def walk(obj):
        if isinstance(obj, dict):
            found.extend(key for key in obj if obj is not jobs and (key == "secrets" or (any(obj is t for t in top) and fold(str(key)) == "secrets")))
            for value in obj.values():
                walk(value)
        elif isinstance(obj, list):
            for item in obj:
                walk(item)

    walk(doc)
    return found


def early_ok(step):
    return set(step) <= {"name", "uses", "with"} and bool(CHECKOUT.fullmatch(str(step.get("uses", "")))) and step.get("with") == {"persist-credentials": False, "fetch-depth": 0}


def refuses(step):
    if not isinstance(step, dict) or not set(step) <= {"name", "run", "id"}:
        return False
    lines = [line.strip(" \t") for line in str(step.get("run", "")).split("\n") if line.strip(" \t")]
    return (
        lines[: len(HEAD)] == HEAD
        and lines[len(lines) - len(TAIL) :] == TAIL
        and all(ERROR.fullmatch(line) for line in lines[len(HEAD) : len(lines) - len(TAIL)])
    )


def step_kind(step):
    keys = set(step)
    if early_ok(step):
        return "checkout"
    if refuses(step):
        return "refusal"
    uses = str(step.get("uses", ""))
    if keys == {"name", "uses", "with"} or keys == {"uses", "with"}:
        if DOWNLOAD.fullmatch(uses) and step["with"] == DOWNLOAD_WITH:
            return "download"
        if UPLOAD.fullmatch(uses) and step["with"] == UPLOAD_WITH:
            return "upload"
    if keys - {"name"} == {"env", "run"} and step["run"] == SIGN_RUN and isinstance(step["env"], dict) and set(step["env"]) == SIGN_ENV:
        return "sign"
    if keys == {"name", "if", "run"} and step["name"] == CLEANUP and step["if"] == "always()" and step["run"] == CLEANUP_RUN:
        return "cleanup"
    return "other"


def check_release(doc):
    if set(doc) - WORKFLOW_KEYS:
        errors.append("release.yml has workflow keys outside %s (defaults and env change how the refusal step runs): %s" % (sorted(WORKFLOW_KEYS), sorted(set(doc) - WORKFLOW_KEYS)))
    if doc.get("on") != TRIGGER:
        errors.append("release.yml on: is not exactly a push of the tags %s (any other trigger, a branch or a second event could run the release from a master commit)" % TRIGGER["push"]["tags"])
    if reads_secret({k: v for k, v in doc.items() if k != "jobs"}):
        errors.append("release.yml reads a secret outside its jobs, before any step can refuse")
    refused = False
    for name, job in doc.get("jobs", {}).items():
        steps = job.get("steps", [])
        if not all(isinstance(step, dict) for step in steps):
            errors.append("release.yml job %s has a step that is not a mapping" % name)
            continue
        refuse = next((i for i, step in enumerate(steps) if refuses(step)), None)
        secret = next((i for i, step in enumerate(steps) if reads_secret(step)), None)
        if set(job) - JOB_KEYS:
            errors.append("release.yml job %s has keys outside %s (defaults and env change how the refusal step runs): %s" % (name, sorted(JOB_KEYS), sorted(set(job) - JOB_KEYS)))
        if refuse is not None and not all(early_ok(step) for step in steps[:refuse]):
            errors.append("release.yml job %s runs a step other than the SHA-pinned checkout (persist-credentials false, fetch-depth 0) before the refusal" % name)
        if job.get("if") is not None and job["if"] not in JOB_IF:
            errors.append("release.yml job %s has an if that is not one of the expressions listed in JOB_IF in check-workflow-guards.sh: %s" % (name, job["if"]))
        if reads_secret(job):
            if job.get("environment") != "release":
                errors.append("release.yml job %s reads a signing secret without environment: release (a workflow on any branch could read repository-level secrets; the environment's tag rules cannot be reached from a branch)" % name)
            if job.get("permissions") != {"contents": "read"}:
                errors.append("release.yml job %s reads a signing secret but its permissions are not exactly contents: read (no id-token, attestations or write)" % name)
            kinds = [step_kind(step) for step in steps]
            if kinds != SIGN_STEPS:
                errors.append("release.yml job %s reads a signing secret, so its steps must be exactly %s (a SHA-pinned checkout, the refusal, download-artifact of the unsigned APK, `%s`, the keystore cleanup, upload-artifact of the signed APK), but they are %s" % (name, SIGN_STEPS, SIGN_RUN, kinds))
            if any(runs_gradle(text) for text in strings(job)):
                errors.append("release.yml job %s reads a signing secret and runs Gradle (build dependencies would share a runner with the key)" % name)
            apps = {m for text in strings(job) for m in SECRET_OF_APP.findall(fold(text))}
            if len(apps) > 1:
                errors.append("release.yml job %s reads the secrets of more than one app (%s)" % (name, ", ".join(sorted(apps))))
        if reads_secret({k: v for k, v in job.items() if k != "steps"}):
            errors.append("release.yml job %s reads a secret outside its steps (job env, secrets:, a reusable workflow), before any step can refuse" % name)
        elif secret is not None and refuse is None:
            errors.append("release.yml job %s reads a secret in step %d and has no valid step that refuses a tag not on master" % (name, secret))
        elif secret is not None and refuse >= secret:
            errors.append("release.yml job %s refuses a tag not on master in step %d, not before the secret in step %d" % (name, refuse, secret))
        for i, step in enumerate(steps):
            if reads_secret(step) and not ("run" in step and "uses" not in step and isinstance(step.get("env"), dict) and not reads_secret({k: v for k, v in step.items() if k != "env"})):
                errors.append("release.yml job %s step %d reads a secret outside the env of a run step (a uses: step, its with or env, or a run text; a remote action's pre script runs before the refusal)" % (name, i))
        for i, step in enumerate(steps[refuse + 1 :] if refuse is not None else [], (refuse or 0) + 1):
            if step.get("name") == CLEANUP:
                if set(step) != {"name", "if", "run"} or step["if"] != "always()" or reads_secret(step):
                    errors.append("release.yml job %s step %d (%s) is not exactly name, if: always() and a run that reads no secret" % (name, i, CLEANUP))
            elif set(step) - STEP_KEYS:
                errors.append("release.yml job %s step %d, after the refusal, has keys outside %s (an if of any letter case, continue-on-error or timeout-minutes can run it after the refusal fails): %s" % (name, i, sorted(STEP_KEYS), sorted(set(step) - STEP_KEYS)))
        refused = refused or refuse is not None
    if not refused:
        errors.append("release.yml has no valid step that refuses a tag not on master (only name, run and id keys; if ! git merge-base --is-ancestor against origin/master, then exit 1)")


directory = Path(sys.argv[1])
files = sorted(p for p in directory.iterdir() if p.suffix in (".yml", ".yaml") and p.is_file())
if not (directory / "release.yml").is_file():
    errors.append("release.yml is missing from %s" % directory)
for path in files:
    allowed = ALLOW.get(path.name, set())
    try:
        text = path.read_text()
        if any(MERGE.search(line) for line in text.split("\n") if not line.lstrip().startswith("#")):
            errors.append("%s has a YAML merge key (<<) or merge tag, which yq drops and GitHub does not document" % path.name)
        docs = parse(path)
    except (OSError, UnicodeDecodeError, subprocess.CalledProcessError, ValueError) as e:
        errors.append("%s cannot be scanned for secret reads: %s" % (path.name, e))
        continue
    if unlisted(text, allowed, per_line=True) or any(unlisted(s, allowed) for doc in docs for s in strings(doc)):
        errors.append("%s mentions the secrets context in a way that is not an expression listed for it in secret_allow in check-workflow-guards.sh (secrets.*, secrets['x'], toJSON(secrets), another name, a keystore name outside release.yml)" % path.name)
    for doc in docs:
        for key in secret_keys(doc):
            errors.append("%s has a mapping key named %s (a reusable workflow passes secrets with it; none is used)" % (path.name, key))
    if path.name == "release.yml":
        if len(docs) == 1 and isinstance(docs[0], dict):
            check_release(docs[0])
        else:
            errors.append("release.yml is not a single YAML mapping")
for message in errors:
    print("error: " + message, file=sys.stderr)
sys.exit(1 if errors else 0)
PY

if [ "$failed" -ne 0 ]; then
    exit 1
fi
echo "check-workflow-guards: comment-run concurrency gate, release refusal order and signing environment hold"

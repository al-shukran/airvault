#!/usr/bin/env bash
# Closes the goldens-refresh pull request and says why, each exactly once, however often this script runs again after a failed
# GitHub call (nightly.yml retries it). The close goes first and carries no comment, so a close that fails posts nothing. The comment
# then goes only when the pull request has none from this run yet, so a comment whose reply was lost is not posted twice (#1443).
#
# Usage: GH_REPO=owner/repo RUN_URL=<nightly run url> scripts/ci/close-refresh-pr.sh <pull request number>
set -euo pipefail

pr=$1
: "${GH_REPO:?error: set GH_REPO}" "${RUN_URL:?error: set RUN_URL to the nightly run URL}"

state=$(gh pr view "$pr" --repo "$GH_REPO" --json state -q .state)
if [ "$state" = OPEN ]; then
    gh pr close "$pr" --repo "$GH_REPO"
fi
# Into a variable first: grep -q on a pipe can end it early, and pipefail would read that as a miss.
comments=$(gh pr view "$pr" --repo "$GH_REPO" --json comments -q '.comments[].body')
if ! grep -Fq -- "$RUN_URL" <<<"$comments"; then
    gh pr comment "$pr" --repo "$GH_REPO" \
        --body "The nightly recording ($RUN_URL) matches the committed goldens again, so this refresh is not needed."
fi

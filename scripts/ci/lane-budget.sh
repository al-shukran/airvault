#!/usr/bin/env bash
# The lane budget of pr-fast: every job of this run must finish within LANE_BUDGET_SECONDS (default 180) of its start. A
# step whose name starts with "Save " (the build-cache upload on master) is left out: a pull request never waits on it.
# pr-ok runs it after every lane. It writes each job's time to the step summary and a warning for each job over the budget.
# With FILE_ISSUE=true (a push to master) it also opens, or comments on, the one open `ci-budget` issue, so a slow lane is
# tracked until someone fixes it. It gates nothing and never reruns anything. A cancelled or skipped job is not counted.
set -euo pipefail

run_id="${GITHUB_RUN_ID:?GITHUB_RUN_ID is not set}"
attempt="${GITHUB_RUN_ATTEMPT:-1}"
budget="${LANE_BUDGET_SECONDS:-180}"
title="CI: a pr-fast lane took longer than ${budget} s"

rows="$(gh api --paginate "repos/{owner}/{repo}/actions/runs/$run_id/attempts/$attempt/jobs?per_page=100" \
    --jq '.jobs[]
        | select(.status == "completed" and (.conclusion == "success" or .conclusion == "failure"))
        | ([.steps[]? | select((.name | startswith("Save ")) and .completed_at != null and .started_at != null)
            | (.completed_at | fromdate) - (.started_at | fromdate)] | add // 0) as $save
        | [.name, ((.completed_at | fromdate) - (.started_at | fromdate) - $save), .html_url] | @tsv')"

table="| job | seconds |"$'\n'"|---|---|"
over=""
while IFS=$'\t' read -r name seconds url; do
    [ -n "$name" ] || continue
    table+=$'\n'"| $name | $seconds |"
    if [ "$seconds" -gt "$budget" ]; then
        echo "::warning::lane budget: $name took $seconds s (budget $budget s)"
        over+="- [$name]($url): $seconds s"$'\n'
    fi
done <<< "$rows"

{
    echo "### Lane times (budget $budget s)"
    echo
    echo "$table"
} >> "${GITHUB_STEP_SUMMARY:-/dev/stdout}"

if [ -z "$over" ]; then
    echo "lane-budget: every lane within $budget s"
    exit 0
fi
if [ "${FILE_ISSUE:-false}" != true ]; then
    exit 0
fi

run_url="${GITHUB_SERVER_URL:-https://github.com}/${GH_REPO:?GH_REPO is not set}/actions/runs/$run_id"
body="Run $run_url (${GITHUB_SHA:-}) went over the lane budget of $budget s:"$'\n\n'"$over"
issue="$(gh issue list --label ci-budget --state open --limit 1 --json number --jq '.[0].number // empty')"
if [ -n "$issue" ]; then
    gh issue comment "$issue" --body "$body"
else
    gh issue create --title "$title" --label ci-budget --label area:ci --body "$body"$'\n'"Close this issue once the lanes are back under budget. Do not rerun runs to make them green."
fi

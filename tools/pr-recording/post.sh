#!/usr/bin/env bash
# The only way PR media reaches a pull request. Posts one compose.sh output directory as one comment.
#
#   post.sh --pr 1030 --dir out-1.3 --title "Font Scale 1.3"
#
# The comment is "### TITLE", then the comparison (or single) GIF if the directory has one and otherwise the
# PNG, then the MP4 on its own line when there is one. A Before/After pair is a full-width Markdown image; a
# single device is <img width="380">. The files are uploaded with gh pr comment --attach (gh 2.99 or later).
# Run it once per comparison. See README.md.
set -euo pipefail

usage() { echo 'usage: post.sh --pr NUMBER --dir COMPOSE_OUT_DIR --title "Font Scale 1.3"' >&2; exit 2; }

pr='' dir='' title=''
while [ $# -gt 0 ]; do
  case $1 in
    --pr) pr=${2:?}; shift 2 ;;
    --dir) dir=${2:?}; shift 2 ;;
    --title) title=${2:?}; shift 2 ;;
    *) usage ;;
  esac
done
[ -n "$pr" ] && [ -n "$dir" ] && [ -n "$title" ] || usage
command -v gh >/dev/null || { echo "gh not found" >&2; exit 1; }
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

name=''
for n in comparison single; do
  [ -f "$dir/$n.png" ] && { name=$n; break; }
done
[ -n "$name" ] || { echo "$dir has no comparison.png or single.png; run compose.sh first" >&2; exit 1; }

work=$(mktemp -d "${TMPDIR:-/tmp}/pr-post.XXXXXX")
trap 'rm -rf "$work"' EXIT
image=$name.png
[ -f "$dir/$name.gif" ] && image=$name.gif
check_compose "$dir/$image" || exit 1
cp "$dir/$image" "$work/"
files=("./$image")
printf '### %s\n\n![image](./%s)\n' "$title" "$image" >"$work/body.md"
if [ -f "$dir/$name.mp4" ]; then
  check_compose "$dir/$name.mp4" || exit 1
  cp "$dir/$name.mp4" "$work/"
  files+=("./$name.mp4")
  printf '\n![video](./%s)\n' "$name.mp4" >>"$work/body.md"
fi

# gh rewrites the ./ references to the uploaded assets but cannot set a width. So post, read the asset URLs
# back, and rewrite the comment into the fixed block.
repo=$(gh repo view --json nameWithOwner -q .nameWithOwner)
cd "$work"
attach=()
for f in "${files[@]}"; do attach+=(--attach "$f"); done
comment_url=$(gh pr comment "$pr" --repo "$repo" --body-file body.md "${attach[@]}")
id=${comment_url##*issuecomment-}
urls=($(gh api "repos/$repo/issues/comments/$id" --jq .body | grep -o 'https://github.com/user-attachments/assets/[0-9a-f-]*'))
[ "${#urls[@]}" -eq "${#files[@]}" ] || { echo "expected ${#files[@]} uploaded files, found ${#urls[@]}; see $comment_url" >&2; exit 1; }
{
  if [ "$name" = single ]; then
    printf '### %s\n\n<img width="380" src="%s">\n' "$title" "${urls[0]}"
  else
    printf '### %s\n\n![%s](%s)\n' "$title" "$title" "${urls[0]}"
  fi
  [ "${#urls[@]}" -lt 2 ] || printf '\n%s\n' "${urls[1]}"
} >body.md
gh api -X PATCH "repos/$repo/issues/comments/$id" -F body=@body.md --silent
echo "$comment_url"

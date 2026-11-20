#!/usr/bin/env bash
# Tags a release as v<versionName>+<versionCode>, the format F-Droid's update check reads.
# The versionCode is the commit count, the same number the Play builds and app/build.gradle.kts use.
# Usage: scripts/tag-release.sh [commit]   (defaults to HEAD)
set -euo pipefail

commit=$(git rev-parse --verify "${1:-HEAD}^{commit}")
name=$(git show "$commit:app/build.gradle.kts" | sed -n 's/^ *versionName = "\(.*\)"$/\1/p')
code=$(git rev-list --count "$commit")

if [[ ! $name =~ ^[0-9]+(\.[0-9]+)*$ ]]; then
  echo "Couldn't read a numeric versionName from app/build.gradle.kts at $commit (got '$name')." >&2
  exit 1
fi

tag="v$name+$code"
git tag -a "$tag" -m "Social Web $name ($code)" "$commit"
echo "Tagged $commit as $tag. Push it with: git push origin $tag"

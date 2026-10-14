#!/usr/bin/env bash
# Amelia.Net release checks, run by .github/workflows/build.yml before the build.
#
# Reads versionCode, versionName and minVersionCode from V2rayNG/app/build.gradle.kts and the
# release notes from changelog/ru/<versionCode>.txt. On a tag build (refs/tags/v*) it fails when:
#   - the tag is not v<versionName>;
#   - changelog/ru/<versionCode>.txt is missing or empty;
#   - versionCode is not greater than in the latest published GitHub release.
# Always fails when minVersionCode > versionCode.
#
# Writes code, name, min_code and notes_file to $GITHUB_OUTPUT (or prints them).
# Env: GITHUB_REF, GITHUB_REF_NAME, GITHUB_REPOSITORY, GH_TOKEN; GH overrides the gh binary (tests).
set -euo pipefail

GRADLE="${GRADLE_FILE:-V2rayNG/app/build.gradle.kts}"
GH="${GH:-gh}"

fail() { echo "::error::$*"; exit 1; }

CODE=$(grep -oP '^\s*versionCode = \K[0-9]+' "$GRADLE" || true)
NAME=$(grep -oP '^\s*versionName = "\K[^"]+' "$GRADLE" || true)
MIN=$(grep -oP '^\s*val minVersionCode = \K[0-9]+' "$GRADLE" || true)
MIN=${MIN:-1}
[[ -n "$CODE" && -n "$NAME" ]] || fail "versionCode/versionName not found in $GRADLE"
(( MIN >= 1 && MIN <= CODE )) || fail "minVersionCode=$MIN must be between 1 and versionCode=$CODE"

NOTES_FILE="changelog/ru/$CODE.txt"
REF="${GITHUB_REF:-}"

if [[ "$REF" == refs/tags/v* ]]; then
  TAG="${GITHUB_REF_NAME:-${REF#refs/tags/}}"
  [[ "$TAG" == "v$NAME" ]] || fail "Tag $TAG does not match versionName $NAME (expected v$NAME)"
  [[ -s "$NOTES_FILE" ]] || fail "Release notes $NOTES_FILE are missing or empty (see RELEASING.md)"

  # Latest published release (not draft, not pre-release); none yet -> 0
  PREV_TAG=$("$GH" api "repos/$GITHUB_REPOSITORY/releases/latest" --jq '.tag_name' 2>/dev/null || true)
  PREV_CODE=0
  if [[ -n "$PREV_TAG" && "$PREV_TAG" != "$TAG" ]]; then
    PREV_JSON=$("$GH" release download "$PREV_TAG" --repo "$GITHUB_REPOSITORY" --pattern version.json --output - || true)
    PREV_CODE=$(grep -oP '"versionCode"\s*:\s*\K[0-9]+' <<< "$PREV_JSON" || echo 0)
  fi
  (( CODE > PREV_CODE )) || fail "versionCode $CODE must be greater than $PREV_CODE of the latest release $PREV_TAG"
  echo "Tag $TAG: versionName $NAME, versionCode $CODE (latest release $PREV_TAG has $PREV_CODE), minVersionCode $MIN"
else
  echo "Not a release tag: versionName $NAME, versionCode $CODE, minVersionCode $MIN"
  [[ -s "$NOTES_FILE" ]] || echo "::warning::$NOTES_FILE not found; version.json notes will be empty"
fi

if [[ -s "$NOTES_FILE" ]]; then
  LEN=$(wc -m < "$NOTES_FILE")
  (( LEN <= 600 )) || echo "::warning::$NOTES_FILE is $LEN characters; keep it to 1-3 short sentences"
fi

OUT="${GITHUB_OUTPUT:-/dev/stdout}"
{
  echo "code=$CODE"
  echo "name=$NAME"
  echo "min_code=$MIN"
  echo "notes_file=$NOTES_FILE"
} >> "$OUT"

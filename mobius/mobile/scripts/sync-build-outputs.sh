#!/usr/bin/env bash
# Trigger GitHub Actions 4-platform build, wait, then download artifacts to ./data/
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DATA_DIR="${REPO_ROOT}/data"
WORKFLOW_FILE="build-all-formats.yml"

# Prefer MOMO_REMOTE env, else infer from the `github` remote, else fall back.
if [ -n "${MOMO_REMOTE:-}" ]; then
  REMOTE_URL="$MOMO_REMOTE"
elif REMOTE_URL="$(git -C "$REPO_ROOT" remote get-url github 2>/dev/null)"; then
  :
else
  REMOTE_URL="Louis-ZhangLe/momo-mobile"
fi

# Normalize SSH/HTTPS URL to owner/repo for `gh` commands.
# Strips trailing .git and any scheme/host prefix.
OWNER_REPO="${REMOTE_URL%.git}"
OWNER_REPO="${OWNER_REPO#*github.com[:/]}"
OWNER_REPO="${OWNER_REPO#git@github.com:}"
if [[ ! "$OWNER_REPO" =~ ^[^/]+/[^/]+$ ]]; then
  echo "FAIL: cannot parse owner/repo from '$REMOTE_URL'" >&2
  exit 1
fi

ARTIFACTS=(android-apk windows-exe ios-simulator-app macos-dmg)

mkdir -p "$DATA_DIR"

echo "==> Repository: $OWNER_REPO"

echo "==> 1. Look for an in-progress or queued run to attach to (pick earliest)"
RUN_ID="$(gh run list \
  --workflow "$WORKFLOW_FILE" \
  --repo "$OWNER_REPO" \
  --status in_progress \
  --limit 20 --json databaseId,createdAt -q 'sort_by(.createdAt) | .[0].databaseId' 2>/dev/null || true)"
if [ -z "$RUN_ID" ]; then
  RUN_ID="$(gh run list \
    --workflow "$WORKFLOW_FILE" \
    --repo "$OWNER_REPO" \
    --status queued \
    --limit 20 --json databaseId,createdAt -q 'sort_by(.createdAt) | .[0].databaseId' 2>/dev/null || true)"
fi

if [ -n "$RUN_ID" ]; then
  echo "Attaching to existing run: $RUN_ID"
else
  echo "==> No active run found — dispatching a new one"
  gh workflow run "$WORKFLOW_FILE" --repo "$OWNER_REPO"
  echo "(workflow_run dispatched)"
  sleep 5
  RUN_ID="$(gh run list \
    --workflow "$WORKFLOW_FILE" \
    --repo "$OWNER_REPO" \
    --limit 1 --json databaseId -q '.[0].databaseId')"
fi
echo "Run id: $RUN_ID"
echo "View: https://github.com/$OWNER_REPO/actions/runs/$RUN_ID"

echo "==> 3. Watch run to completion (this can take 20-40 min)"
# Do NOT use --exit-status: we want to download whatever artifacts succeeded
# even if some jobs fail.
gh run watch "$RUN_ID" --repo "$OWNER_REPO" || echo "(some jobs failed; will still try to download available artifacts)"

echo "==> 4. Download artifacts to $DATA_DIR"
for art in "${ARTIFACTS[@]}"; do
  echo "  - $art"
  # Clear the destination first: gh run download does not overwrite
  # existing files, so a re-run with identical artifact names would
  # silently leave stale bytes on disk.
  rm -rf "$DATA_DIR/$art"
  gh run download "$RUN_ID" \
    --repo "$OWNER_REPO" \
    --name "$art" \
    --dir "$DATA_DIR/$art" 2>&1 || echo "    missing ($art job may have failed)"
done

echo "==> 5. Summary"
echo "Artifacts:"
find "$DATA_DIR" -type f \( \
  -name "*.apk" -o -name "*.exe" -o -name "*.app" -o \
  -name "*.dmg" -o -name "*.zip" -o -name "sha256.txt" \) | while read -r f; do
  size="$(du -h "$f" | cut -f1)"
  echo "  [$size] $f"
done

echo "==> Done"

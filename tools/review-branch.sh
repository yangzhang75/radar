#!/usr/bin/env bash
# Orchestrator helper: verify a worker's delivery by running its module tests in an
# isolated worktree, without disturbing the main checkout.
#
# Usage:   tools/review-branch.sh <branch> <gradle-test-task>
# Example: tools/review-branch.sh feat/comms-image :comms-core:test
#          tools/review-branch.sh feat/ui-color   :ui-core:test
set -euo pipefail

BRANCH="${1:?need a branch, e.g. feat/comms-image}"
TASK="${2:?need a gradle test task, e.g. :comms-core:test}"
WT="../wt-review-${BRANCH//\//-}"

echo ">>> reviewing $BRANCH  (task: $TASK)"
git fetch --all --quiet 2>/dev/null || true
git worktree add --force "$WT" "$BRANCH"
trap 'echo ">>> leaving review worktree at $WT (remove: git worktree remove --force $WT)"' EXIT

( cd "$WT" && ./gradlew "$TASK" --console=plain )
echo ">>> PASS: $BRANCH $TASK"

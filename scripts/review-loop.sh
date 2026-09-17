#!/usr/bin/env bash
# Runs after a `git push`: reviews what was pushed, lets a second headless
# Claude Code call apply fixes, re-reviews, and posts each round to Slack.
# Invoked by .githooks/pre-push in the background — never blocks the push.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

[ -f "$REPO_ROOT/.env.review-bot" ] && source "$REPO_ROOT/.env.review-bot"

CLAUDE="${CLAUDE_BIN:-claude}"
MAX_ROUNDS=3
LOG_FILE="$REPO_ROOT/.review-loop.log"

RANGE="$1"   # e.g. "old-sha..new-sha", passed in by the pre-push hook
BRANCH="$2"  # e.g. "refs/heads/main"

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" >> "$LOG_FILE"; }

slack_post() {
  local text="$1"
  if [ -z "${SLACK_WEBHOOK_URL:-}" ]; then
    log "SLACK_WEBHOOK_URL not set — skipping Slack post: $text"
    return 0
  fi
  # Write the JSON payload to a file via node (guarantees correct UTF-8 bytes,
  # sidesteps any argv/locale mis-transcoding when passing Korean text through
  # bash -> curl on this Windows/Git-Bash setup) and post from that file.
  # mktemp's MSYS-style path isn't resolvable by the native Windows node.exe,
  # so use a path under the repo instead.
  local payload_file="$REPO_ROOT/.slack-payload-$$.json"
  text="$text" node -e 'const fs=require("fs"); fs.writeFileSync(process.argv[1], JSON.stringify({ text: process.env.text }))' "$payload_file"
  curl -s -X POST -H 'Content-Type: application/json; charset=utf-8' \
    --data-binary "@$payload_file" \
    "$SLACK_WEBHOOK_URL" >/dev/null 2>&1
  rm -f "$payload_file"
}

READ_ONLY_TOOLS="Read Grep Glob Bash(git diff:*) Bash(git log:*) Bash(git show:*) Bash(git status:*)"
FIX_TOOLS="$READ_ONLY_TOOLS Edit Write Bash(git add:*) Bash(git commit:*)"

log "=== review loop started for $RANGE on $BRANCH ==="
slack_post "🔍 *리뷰어* — \`$BRANCH\`에 새 커밋이 푸시됐습니다 (\`$RANGE\`). 리뷰를 시작합니다."

BASE_SHA="${RANGE%%..*}"

round=1
while [ "$round" -le "$MAX_ROUNDS" ]; do
  log "--- round $round: review ---"

  current_range="$BASE_SHA..HEAD"
  review_prompt="다음 범위의 커밋을 리뷰해줘: git diff $current_range (필요하면 git log, git show로 맥락을 더 봐도 됨).
버그, 보안 문제, 명백한 실수만 지적해. 사소한 스타일 지적은 하지 마.
마지막 줄에 정확히 다음 중 하나만 출력해: \"REVIEW_RESULT: APPROVED\" 또는 \"REVIEW_RESULT: CHANGES_NEEDED\".
CHANGES_NEEDED인 경우, 그 위에 무엇을 고쳐야 하는지 구체적으로 파일:라인과 함께 적어줘."

  review_output="$("$CLAUDE" -p "$review_prompt" --allowedTools "$READ_ONLY_TOOLS" 2>>"$LOG_FILE")"
  review_status=$?
  log "review output (exit $review_status): $review_output"

  if [ "$review_status" -ne 0 ] || [ -z "$review_output" ]; then
    log "=== review call failed, aborting loop ==="
    slack_post "⚠️ 리뷰 호출이 실패했습니다 (exit $review_status). \`.review-loop.log\`를 확인해주세요."
    exit 1
  fi

  slack_post "🔍 *리뷰어* (${round}/${MAX_ROUNDS}차):
$review_output"

  if echo "$review_output" | grep -q "REVIEW_RESULT: APPROVED"; then
    slack_post "✅ *리뷰어* — 승인했습니다. 이번 푸시는 더 손댈 곳이 없어 보입니다."
    log "=== approved on round $round ==="
    exit 0
  fi

  if [ "$round" -eq "$MAX_ROUNDS" ]; then
    slack_post "⏹️ *리뷰어* — ${MAX_ROUNDS}회 반복해도 이슈가 남아있어서 여기서 멈춥니다. 사람이 봐주세요."
    log "=== gave up after $MAX_ROUNDS rounds ==="
    exit 1
  fi

  log "--- round $round: fix ---"
  fix_prompt="방금 리뷰에서 다음 문제가 지적됐어:
$review_output

이 문제들을 직접 고치고, git add 후 'fix: address review feedback (round $round)'로 커밋해줘.
동작을 바꾸지 않는 범위에서 최소한으로 고쳐. 고친 내용을 한두 문장으로 요약해서 마지막에 출력해."

  fix_output="$("$CLAUDE" -p "$fix_prompt" --allowedTools "$FIX_TOOLS" 2>>"$LOG_FILE")"
  fix_status=$?
  log "fix output (exit $fix_status): $fix_output"

  if [ "$fix_status" -ne 0 ] || [ -z "$fix_output" ]; then
    log "=== fix call failed, aborting loop ==="
    slack_post "⚠️ 수정 호출이 실패했습니다 (exit $fix_status). \`.review-loop.log\`를 확인해주세요."
    exit 1
  fi

  slack_post "🛠️ *수정 담당* (${round}/${MAX_ROUNDS}차):
$fix_output"

  round=$((round + 1))
done

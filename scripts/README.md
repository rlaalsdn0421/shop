# Review bot

`git push`할 때마다 자동으로 돌아가는 코드 리뷰 → 수정 → 재리뷰 루프.

## 동작 방식

1. `.githooks/pre-push`가 push되는 커밋 범위를 감지하고 `scripts/review-loop.sh`를 백그라운드로 실행 (push 자체는 막지 않음).
2. `review-loop.sh`가 헤드리스 `claude -p`를 호출해 diff를 리뷰.
3. 문제가 있으면 별도의 `claude -p` 호출이 직접 고치고 커밋.
4. 최대 3회까지 리뷰 ↔ 수정을 반복하고, 각 라운드 결과를 Slack `#code-review-loop` 채널에 올림.

## 최초 설정 (한 번만)

1. `git config core.hooksPath .githooks` (이미 이 저장소에 설정되어 있음)
2. `claude` CLI 로그인: 터미널에서 `claude` 실행 후 `/login`
3. Slack Incoming Webhook 발급 후 `.env.review-bot` 파일에 `SLACK_WEBHOOK_URL=...` 저장 (`.env.review-bot.example` 참고, 이 파일은 gitignore됨)

## 로그

`.review-loop.log`에 각 라운드의 리뷰/수정 출력이 쌓입니다.

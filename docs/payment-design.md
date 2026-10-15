# 결제(포트원 V2) 설계

사용자 결정(2026-10-12): 포트원 V2, 카드 + 간편결제(카카오페이·네이버페이·토스페이), 주문 때 재고를 잡고 **20분** 안에 결제하지 않으면 풀기, 이번 PR은 결제 + 주문 상태 흐름까지(환불·취소는 다음 PR).

## 1. 주문 상태

| 상태 | 뜻 | 재고 |
|---|---|---|
| `PENDING_PAYMENT` | 주문이 만들어졌고 결제를 기다리는 중 | 차감됨(잡아둠) |
| `PAID` | 서버가 포트원에서 결제 완료와 금액을 확인함 | 차감됨 |
| `EXPIRED` | 20분 안에 결제하지 않아 정리됨 | 되돌림 |
| `PAYMENT_REVIEW` | 정리된 뒤에 결제가 들어왔는데 재고가 이미 없음 → 운영자가 포트원 콘솔에서 취소해야 함 | 차감 안 됨 |

- 새 주문은 `PENDING_PAYMENT`로 시작한다(지금까지는 결제도 없이 `PAID`로 저장했다). 기존 주문 행은 그대로 둔다.
- `status`에 CHECK 제약(위 4개)을 둔다. 다음 PR에서 `CANCELLED`/`REFUNDED` 등을 추가할 때 제약을 바꾼다.
- 판매 순위는 `PAID`만 센다(이미 그렇게 동작).

## 2. 결제 흐름

```
장바구니 → POST /api/orders (Idempotency-Key)   → 주문(PENDING_PAYMENT) + 재고 차감, expiresAt = 지금+20분
        → 브라우저 PortOne.requestPayment(paymentId = 주문 id, totalAmount = 서버가 준 totalAmount)
        → POST /api/orders/{id}/payment/complete → 서버가 포트원 GET /payments/{주문 id} 로 직접 확인 → PAID
        (보험) 포트원 웹훅 Transaction.Paid → 같은 확정 로직
```

- `paymentId`는 **주문 id(UUID)**를 그대로 쓴다. 같은 주문의 결제 재시도는 같은 paymentId로 한다(포트원은 성공이 1번만 가능하도록 막는다). 그래서 웹훅의 paymentId로 주문을 바로 찾는다.
- 금액은 **서버 값이 최종**이다. 브라우저가 보내는 금액·결과는 믿지 않고, 확정은 항상 서버가 포트원 API를 직접 호출해 확인한 값으로만 한다.
- 확정 조건(모두 만족해야 PAID): 포트원 `status == "PAID"`, `amount.total == orders.total_amount`, `currency == "KRW"`, `id == 주문 id`, `storeId == 설정된 storeId`, `channel.type == "LIVE"`. 하나라도 다르면 상태를 바꾸지 않고 ERROR 로그(주문 id, paymentId, 서버 금액, 포트원 금액)를 남긴다.
- **테스트 채널 차단**: 채널 키는 `NEXT_PUBLIC_*` 공개값이라 누구나 알 수 있고, 테스트 채널로 한 결제는 돈이 움직이지 않는데도 `status=PAID`로 조회된다. 그래서 `channel.type`이 `LIVE`가 아니면 확정하지 않는다(409 `PAYMENT_CHANNEL_NOT_ALLOWED`). 포트원 테스트 단계에서만 환경변수 `PORTONE_ALLOW_TEST_CHANNEL=true`로 허용하고, **실서비스 오픈 전에 반드시 끄고 테스트 채널은 포트원 콘솔에서 지운다.**
- 포트원 호출은 **DB 트랜잭션 밖에서** 한다(Hikari 풀이 5개뿐이라 외부 호출 동안 연결을 잡고 있으면 안 된다). 순서: ① 주문 읽기(주문자 확인) → ② 포트원 조회(타임아웃 연결 2초·읽기 5초) → ③ 짧은 트랜잭션에서 주문 행을 `FOR UPDATE`로 잠그고 상태 전이.
- 멱등 재생(같은 키로 주문 생성을 다시 요청)에서 기한이 지난 `PENDING_PAYMENT` 주문을 만나면 서버가 그 자리에서 만료 처리(재고 복구)하고 `EXPIRED`로 돌려준다. 그래야 정리 작업이 늦거나 설정되지 않아도 고객이 새 주문을 만들 수 있다.
- 상태 전이는 멱등이다: 이미 `PAID`면 아무것도 안 하고 성공을 돌려준다(웹훅과 브라우저 확정이 동시에 와도 한 번만 반영).
- `PENDING_PAYMENT`인 주문은 `expiresAt`이 지나도 정리 작업이 돌기 전까지는 결제 확정이 가능하다(재고가 아직 잡혀 있으므로).
- `EXPIRED` 주문에 결제가 확인되면: 모든 품목의 재고를 다시 잡아 본다(`decrementStockIfAvailable`, 상품 id 순). 전부 성공하면 `PAID`. 하나라도 실패하면 이미 잡은 것을 되돌리고 `PAYMENT_REVIEW`로 바꾼 뒤 ERROR 로그를 남긴다(자동 환불은 다음 PR이라 운영자가 포트원 콘솔에서 취소).
- 주문 도메인 규칙은 `Order`의 상태 상수와 전이 메서드에 둔다. 서비스는 호출만 한다.

## 3. 백엔드 API

모든 에러 JSON은 기존 GlobalExceptionHandler 형식(`error`, 필요하면 `code`)을 따른다. 주문 생성 응답은 기존 프론트·테스트와의 호환을 위해 `orderId`와 `id`를 **둘 다** 같은 값으로 내려준다.

| 요청 | 인증 | 설명 |
|---|---|---|
| `POST /api/orders` | 로그인 | 응답을 `{ id, status, totalAmount, orderName, expiresAt }`로 확장(`id`는 그대로). `orderName`은 "대표상품명 외 N건"(1건이면 상품명만, 100자 넘으면 자름). 멱등 재생은 지금 상태 그대로 돌려준다 |
| `GET /api/orders`, `GET /api/orders/{id}` | 로그인(주문자만) | 응답에 `status`, `expiresAt`, `paidAt`이 들어간다(`status`는 이미 있으면 유지) |
| `POST /api/orders/{id}/payment/complete` | 로그인(주문자만) | 위 확정 로직. 200 `{ status }`. 다른 사람·없는 주문은 404. 포트원이 아직 결제 완료가 아니면 409 `PAYMENT_NOT_CONFIRMED`, 금액 불일치 409 `PAYMENT_AMOUNT_MISMATCH`, 테스트 채널 결제 409 `PAYMENT_CHANNEL_NOT_ALLOWED`, 정리 후 재고 없음 409 `PAYMENT_NEEDS_REVIEW`, 포트원 장애·타임아웃 503 `PAYMENT_PROVIDER_UNAVAILABLE`(메시지 "결제 확인이 지연되고 있어요. 잠시 뒤 다시 확인해 주세요."), 결제 설정이 비어 있으면 503 `PAYMENT_NOT_CONFIGURED` |
| `POST /api/payments/webhook` | 없음(서명 검증) | 본문은 **원문 문자열 그대로** 받아 서명 검증 후 파싱. 서명·타임스탬프 오류 400. `type == "Transaction.Paid"`만 처리(그 외 타입은 200으로 무시). 알 수 없는 paymentId(우리 주문이 아님)는 200으로 무시하고 INFO 로그. 포트원 조회 장애는 5xx로 돌려 포트원이 재시도하게 한다 |
| `POST /api/internal/orders/expire-pending` | 헤더 `X-Internal-Token` | 만료된 `PENDING_PAYMENT` 주문을 최대 100건 정리하고 `{ expired: n }`을 돌려준다. `INTERNAL_TOKEN`이 비어 있으면 404. 토큰 비교는 상수 시간(`MessageDigest.isEqual`) |

웹훅 서명 검증(Standard Webhooks, 포트원 JVM SDK `WebhookVerifier`와 같은 방식. 외부 SDK는 0.x라 쓰지 않고 표준 라이브러리로 구현):
- 헤더 `webhook-id`, `webhook-timestamp`(초), `webhook-signature`(공백으로 구분된 `v1,<base64>` 여러 개).
- 서명 대상 문자열 `"{webhook-id}.{webhook-timestamp}.{원문 본문}"`, HMAC-SHA256, 키 = 웹훅 시크릿에서 `whsec_` 접두사를 떼고 Base64 디코딩한 바이트.
- 타임스탬프 오차 5분 초과면 거부. 서명 비교는 `MessageDigest.isEqual`.
- 본문 필드: `type`, `timestamp`, `data.paymentId`, `data.storeId`, `data.transactionId`. 모르는 필드·타입은 무시.

포트원 REST(직접 호출, `RestClient`):
- `GET {api-base}/payments/{paymentId}`, 헤더 `Authorization: PortOne {API_SECRET}`. 우리가 읽는 필드: `status`(`PAID` 등), `id`, `storeId`, `amount.total`, `currency`, `transactionId`, `paidAt`. 모르는 필드는 무시한다. 404는 "결제 없음"(= 아직 결제 전)으로 다룬다.

설정(`application.yml`, 값은 환경변수, 비면 결제 기능이 꺼진다):
`portone.api-secret=${PORTONE_API_SECRET:}`, `portone.webhook-secret=${PORTONE_WEBHOOK_SECRET:}`, `portone.store-id=${PORTONE_STORE_ID:}`, `portone.api-base=${PORTONE_API_BASE:https://api.portone.io}`, `internal.token=${INTERNAL_TOKEN:}`. 시크릿 값은 로그·예외 메시지에 절대 넣지 않는다.

## 4. DB (V15)

```sql
SET LOCAL lock_timeout = '5s';
ALTER TABLE orders
    ADD COLUMN expires_at TIMESTAMPTZ,
    ADD COLUMN paid_at TIMESTAMPTZ,
    ADD COLUMN payment_transaction_id VARCHAR(100),
    ADD CONSTRAINT orders_status_valid CHECK (status IN ('PENDING_PAYMENT','PAID','EXPIRED','PAYMENT_REVIEW'));
CREATE INDEX idx_orders_pending_expires ON orders (expires_at) WHERE status = 'PENDING_PAYMENT';
```
기존 행은 `expires_at`/`paid_at`이 NULL이다(결제 없이 만들어진 옛 주문이라는 사실을 그대로 둔다).

## 5. 프론트엔드

- 환경변수(공개값, Vercel): `NEXT_PUBLIC_PORTONE_STORE_ID`, `NEXT_PUBLIC_PORTONE_CHANNEL_KEY_CARD`, `..._KAKAOPAY`, `..._NAVERPAY`, `..._TOSSPAY`. **채널 키가 있는 결제수단만** 선택지로 보여준다. `STORE_ID`가 없거나 보여줄 수단이 하나도 없으면 장바구니에 "결제 준비 중이에요" 안내를 보이고 **주문 자체를 만들지 않는다**(결제할 수 없는 주문이 재고만 잡는 것을 막는다).
- 패키지 `@portone/browser-sdk`(정확한 버전 고정, `import * as PortOne from "@portone/browser-sdk/v2"`). 타입은 `node_modules/@portone/browser-sdk/dist/v2/request/PaymentRequest*.d.ts`가 정답이다(`payMethod`, `easyPay.easyPayProvider` 값은 거기서 확인).
- 결제 요청 값: `storeId`, `channelKey`(선택한 수단), `paymentId = 주문 id`, `orderName`·`totalAmount`는 **주문 생성 응답 값**, `currency: "KRW"`, `payMethod`(카드 `CARD`, 간편결제 `EASY_PAY` + provider), `redirectUrl = {origin}/orders/{id}`, `customer`(이름·전화).
- 결과 처리: SDK가 반환한 객체에 `code`가 있으면 실패/취소(메시지 보여주고 주문 상세에서 다시 결제 가능). 없으면 `POST .../payment/complete`를 부르고 `/orders/{id}`로 이동.
- 모바일 리디렉션 복귀: `/orders/{id}?paymentId=...`(실패면 `code`, `message`도 붙음)로 돌아온다. 주문 상세는 이 경우 `complete`를 부르고 쿼리를 정리한다. `paymentId`가 이 주문 id와 다르면 무시한다.
- 주문 상세·목록 상태 문구: `PENDING_PAYMENT` "결제 대기", `PAID` "결제 완료", `EXPIRED` "주문 만료(결제하지 않아 취소됨)", `PAYMENT_REVIEW` "확인 중(결제 확인 후 취소·환불을 안내해 드려요)". 대기 상태이고 `expiresAt`이 미래면 "결제하기" 버튼(남은 시간 표시)을 보인다.
- 장바구니는 **결제 확인이 끝난 뒤**(`PAID`)에 비운다. 결제 전에 돌아오면 같은 멱등 키·같은 내용이라 같은 대기 주문이 재생된다. 주문 생성 응답이 `EXPIRED`면 저장된 주문 시도(키)를 지우고 새 키로 다시 만든다.
- FAQ 챗봇의 결제 답변(`FaqChatAnswerer`)은 결제 기능이 켜진 뒤에만 사실이다. 이번 PR에서 "카드·간편결제(카카오페이·네이버페이·토스페이)로 결제할 수 있고, 주문 후 20분 안에 결제해야 해요. 이 기간이 지나면 주문이 자동으로 취소돼요."로 바꾸되, 환불 약속은 새로 만들지 않는다.

## 6. 운영 설정(사용자 몫, Cloud Shell 숨김 입력)

실제 명령과 순서는 [payment-setup.md](payment-setup.md)에 있다.

- 시크릿 3개: `portone-api-secret`, `portone-webhook-secret`, `internal-token`(Secret Manager) + Cloud Run 서비스 계정 `secretAccessor`. 값은 채팅에 붙이지 않는다.
- `PORTONE_STORE_ID`는 시크릿이 아니라 일반 환경변수. 테스트 단계에서만 `PORTONE_ALLOW_TEST_CHANNEL=true`를 추가하고, 실서비스 전에 지운다.
- `internal-token`은 32자 이상 임의 값(예: `openssl rand -hex 32`)으로 만든다. 더 짧으면 내부 정리 API가 꺼진다.
- 배포 전 확인: Neon에서 `SELECT status, count(*) FROM orders GROUP BY 1;` 결과가 `PAID`뿐인지(V15의 CHECK가 다른 값을 거부한다).
- Cloud Scheduler 작업 1개(5분마다 `POST .../api/internal/orders/expire-pending`, 헤더 `X-Internal-Token`). 무료 한도(작업 3개) 안.
- 포트원 콘솔: 웹훅 URL `https://shop-backend-mklbqo55qa-as.a.run.app/api/payments/webhook` 등록, 테스트 채널 연결.
- **배포 순서 주의**: `backend-deploy.yml`의 `secrets:`에 시크릿을 추가했는데 Secret Manager에 없으면 배포가 실패한다. 시크릿을 먼저 만든 뒤에 워크플로 변경을 머지한다.

## 7. 이번 PR에서 하지 않는 것(알고 두는 한계)

- 환불·취소(다음 PR, 설계는 [refund-design.md](refund-design.md)). 자동 환불이 없어서 `PAYMENT_REVIEW`와 금액 불일치는 운영자가 포트원 콘솔에서 처리한다.
- 가상계좌·계좌이체(웹훅 `VirtualAccountIssued` 등은 무시).
- 결제 요청 단위 레이트 리밋, 주문 생성 레이트 리밋. 특히 `POST /api/orders/{id}/payment/complete`를 로그인한 사용자가 반복 호출하면 그때마다 포트원 조회가 나가 포트원 API 한도를 쓸 수 있다.
- 포트원 쪽에서 결제를 취소·환불해도(`Transaction.Cancelled`, 분쟁) 주문 상태는 `PAID`로 남는다. 다음 PR(환불)에서 웹훅으로 반영한다.
- 정리 작업(Cloud Scheduler)이 멈춰도 알림이 없다. 모니터링 PR에서 다룬다.
- 같은 사용자가 장바구니를 바꿔 가며 대기 주문을 여러 개 만들면 각각 20분 동안 재고를 잡는다(20분 뒤 자동 정리).
- 결제 없이 만들어졌던 옛 주문 행의 `PAID`는 그대로다.

# 주문 취소·환불 설계

사용자 결정(2026-10-13): ① 배송 시작 전까지 고객이 직접 **전액** 취소 ② 배송 상태(배송중·배송완료)와 관리자 처리를 같이 만든다 ③ **전액 환불만**(부분 환불 없음) ④ 포트원 콘솔에서 판매자가 취소한 결제도 주문에 자동 반영한다.
기존 정책(약관·교환환불 안내): 수령 후 7일 이내 교환·환불(청약철회), 단순 변심의 왕복 배송비는 구매자 부담, 하자·오배송은 수령 후 3개월(또는 안 날부터 30일) 이내 무상.

## 1. 주문 상태 (V16)

| 상태 | 뜻 | 재고 |
|---|---|---|
| `PENDING_PAYMENT` / `EXPIRED` / `PAYMENT_REVIEW` | 기존과 같다 | 기존과 같다 |
| `PAID` | 결제 완료, 아직 배송 전 | 차감 |
| `CANCELING` | 취소 처리 중(포트원 취소 요청을 보냈고 결과 확정 전) | 차감 유지 |
| `CANCELLED` | **배송 전** 취소·환불 완료 | **복구** |
| `SHIPPED` | 판매자가 배송 시작 처리 | 차감 |
| `DELIVERED` | 판매자가 배송 완료 처리(`delivered_at` 기록) | 차감 |
| `REFUNDED` | **배송 후** 환불 완료(반품) | 자동 복구 안 함(반품 검수 뒤 판매자가 재고를 직접 조정) |

허용 전이(그 밖의 전이는 도메인 규칙으로 거부, `Order` 메서드에 둔다):
- `PAID → CANCELING → CANCELLED` (고객 취소 또는 콘솔 취소 웹훅)
- `PAID → SHIPPED → DELIVERED`
- `SHIPPED|DELIVERED → CANCELING → REFUNDED` (관리자 환불, 4절). 약관의 "수령 후 7일"은 화면 경고로만 알리고, 관리자 환불은 7일이 지나도 판매자 재량으로 할 수 있다(하자 등).
- `CANCELING`에서 포트원 취소가 실패하면 직전 상태(`PAID` 또는 `SHIPPED`/`DELIVERED`)로 되돌린다(`cancel_from` 컬럼에 직전 상태를 저장).
- 새 컬럼(모두 NULL 허용): `shipped_at`, `delivered_at`, `cancelled_at`, `cancel_from VARCHAR(20)`, `cancel_reason VARCHAR(200)`. `orders_status_valid` CHECK에 새 상태를 추가한다(lock_timeout 5s, 기존 행은 이미 허용 값이라 통과).
- 판매 순위는 `PAID`, `SHIPPED`, `DELIVERED`만 센다(취소·환불 제외). 지금 `PAID`만 세는 쿼리를 찾아 맞춘다.

## 2. 고객 취소 `POST /api/orders/{id}/cancel`

- 로그인한 주문자만. 다른 사람·없는 주문은 404. 본문 `{ "reason": "선택, 200자 이하" }`.
- **`PAID`일 때만** 가능. `SHIPPED`/`DELIVERED`면 409 `ORDER_NOT_CANCELLABLE`("배송이 시작되어 바로 취소할 수 없어요. 교환·환불은 연락처로 신청해 주세요."). `CANCELING`이면 진행 중이라 같은 요청을 멱등으로 받아 결과를 돌려준다. 이미 `CANCELLED`면 200(멱등).
- 3단계(결제 확정과 같은 구조): ① 짧은 트랜잭션: 주문 행 `FOR UPDATE`, `PAID → CANCELING`(`cancel_from='PAID'`) ② **트랜잭션 밖**에서 포트원 `POST /payments/{paymentId}/cancel`(전액, `reason`, 요청자 고객) ③ 짧은 트랜잭션: 행 잠금, `CANCELING → CANCELLED`, 상품 재고를 id 순으로 복구, `cancelled_at`.
- ②가 실패(4xx/5xx/타임아웃)하면 ③ 대신 `CANCELING → 직전 상태`로 되돌리고 503 `CANCEL_PROVIDER_UNAVAILABLE`. 단 포트원이 "이미 취소됨"을 알려 주는 오류는 성공으로 본다. 결과를 알 수 없는 타임아웃은 `CANCELING`에 둔 채 503을 주고, 웹훅 `Transaction.Cancelled`나 다음 요청이 마무리한다.
- **배송 시작과의 경쟁**: 관리자의 배송 시작(`PAID → SHIPPED`)과 고객 취소(`PAID → CANCELING`)는 모두 주문 행 잠금 아래에서 `PAID`를 확인하므로 둘 중 하나만 성공한다.

## 3. 웹훅 `Transaction.Cancelled` / `PartialCancelled`

- 서명 검증은 기존과 같다. `Transaction.Cancelled`(전액 취소)가 오면 포트원에서 결제를 다시 조회해 `status == "CANCELLED"`이고 `id`가 주문 id일 때만 반영한다(웹훅 본문은 믿지 않는다).
- 주문이 `PAID`/`CANCELING`이면 `CANCELLED`로(재고 복구), `SHIPPED`/`DELIVERED`면 `REFUNDED`로(재고 복구 없음) 바꾼다. 이미 `CANCELLED`/`REFUNDED`면 아무것도 안 한다(멱등). `PENDING_PAYMENT`/`EXPIRED`는 상태를 바꾸지 않고 INFO 로그만 남긴다. `PAYMENT_REVIEW`는 운영자가 콘솔에서 취소한 경우라 `CANCELLED`로 마무리하되 재고는 이미 안 잡혀 있으므로 복구하지 않는다.
- `PartialCancelled`는 우리 정책에 없는 상태라 **ERROR 로그만** 남기고 주문은 그대로 둔다(운영자가 확인).

## 4. 관리자·판매자 API (`ADMIN`, `SELLER`)

| 요청 | 설명 |
|---|---|
| `GET /api/admin/orders?status=&size=&cursor=` | 주문 목록(최신순, 키셋 페이지네이션, 상태 필터). 항목: 주문 id, 상태, 주문자 이름·연락처·주소, 총액, 상품 요약, 생성·결제·배송 시각 |
| `POST /api/admin/orders/{id}/ship` | `PAID → SHIPPED` |
| `POST /api/admin/orders/{id}/deliver` | `SHIPPED → DELIVERED` |
| `POST /api/admin/orders/{id}/refund` | 배송 후 전액 환불: `SHIPPED`/`DELIVERED → CANCELING → REFUNDED`(2절과 같은 3단계, 요청자 판매자, 재고 복구 없음). 본문 `{ "reason": "필수" }` |

- 모든 상태 전이는 주문 행 `FOR UPDATE` 아래에서 현재 상태를 확인한다. 잘못된 상태는 409 `INVALID_ORDER_STATE`.
- 주문자 개인정보(이름·연락처·주소)를 반환하므로 관리자 목록 응답에는 `Cache-Control: no-store`를 붙인다.

## 5. 화면

- 주문 상세: `PAID`일 때 "주문 취소" 버튼(확인 창 + 선택 사유). 상태 문구 추가: `CANCELING` "취소 처리 중", `CANCELLED` "주문 취소됨(환불 완료)", `SHIPPED` "배송 중", `DELIVERED` "배송 완료", `REFUNDED` "환불 완료". `SHIPPED`/`DELIVERED`에는 "교환·환불은 연락처로 신청해 주세요" 안내.
- 주문 목록: 새 상태 문구 반영.
- 관리자: `/admin/orders` 화면(상태 탭, 목록, 상태에 맞는 버튼 "배송 시작" / "배송 완료" / "환불"). `ADMIN`/`SELLER`만(기존 관리자 상품 화면과 같은 방식으로 보호).
- 환불 시점 안내는 약속하지 않는다: "카드사에 따라 며칠 걸릴 수 있어요".

## 6. 약관·안내 문구(같은 PR에서 수정)

- `/refund`: 배송 시작 전에는 주문 내역에서 직접 전액 취소(배송비 포함 환불), 배송 시작 후에는 연락처로 신청. 환불은 결제수단 승인 취소로 처리되고 카드사에 따라 며칠 걸릴 수 있음.
- `/terms` "주문과 결제": 취소 방법 문구 갱신.
- `FaqChatAnswerer`: 취소·환불 답변 갱신(자동 취소는 배송 전까지만).
- `POLICY_VERSION`(프론트)·`PolicyVersion.CURRENT`(백엔드) 시행일은 배포 시점에 같이 올린다(체크리스트).

## 7. 이번에 하지 않는 것(알고 두는 한계)

- 부분 환불·부분 취소(`PartialCancelled` 웹훅은 ERROR 로그만).
- 환불 계좌(가상계좌 환불 등)와 간편결제별 환불 특이사항: 카드 결제 기준이다.
- 고객 취소 요청 횟수 제한(포트원 호출이 늘 수 있음, 기존 `complete`와 같은 한계).
- 관리자 감사 로그(누가 환불했는지): 주문에 `cancel_reason`만 남긴다.
- 환불된 결제의 현금영수증·카드매출전표는 포트원/PG 콘솔에서 확인한다.
- 배송 추적 번호·택배사 연동.

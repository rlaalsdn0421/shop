# 상품 이미지 여러 장과 색상·사이즈 옵션 설계

사용자 결정(2026-10-15): ① 옵션 없는 기존 상품은 그대로 판매 ② **옵션별 재고를 따로 관리** ③ 색상은 **색 견본(원형 칩)** ④ **옵션마다 가격이 다를 수 있음**. 이미지는 여러 장 + 썸네일.
참고한 화면: 네이버 쇼핑(색상 칩, 사이즈 버튼), SHEIN(세로 썸네일, 사이즈, 재고 임박).

## 0. PR 순서(안전한 순서)

주문·재고는 돈이 오가는 경로라서 한 번에 바꾸지 않고, **옛 화면이 깨지지 않는 순서**로 나눈다.

| PR | 내용 | 비고 |
|---|---|---|
| 1 | 이미지 여러 장 + 썸네일 (V17) | 주문과 무관. 옛 상품은 그대로 |
| 2 | 옵션 백엔드: 테이블, 주문 줄, 옵션별 재고 차감·복구, 가격 (V18) | `optionId`는 선택값이라 옛 프론트·옛 상품은 그대로 동작. 옵션 상품이 아직 없으므로 운영 영향 없음 |
| 3 | 옵션 프론트: 상품 등록 화면(옵션 행), 상세 페이지 선택기, 장바구니, 주문 표시 | 이 PR이 머지돼야 옵션 상품을 만들 수 있다 |
| 4 | 상품 재고·가격 수정(관리자) | 지금은 상품을 만들기만 하고 고칠 수 없다. 옵션 재고를 채우고 반품 재고를 되돌리려면 필요 |

## 1. 이미지 여러 장 (PR 1)

- `product_images(id, product_id FK, url VARCHAR(2000) NOT NULL, sort_order INT NOT NULL, UNIQUE(product_id, sort_order))`.
- `products.image_url`은 **대표 이미지**(목록·장바구니·주문에서 쓰는 값)로 그대로 둔다. 새 상품은 첫 번째 이미지를 대표로 한다.
- 상세 응답에 `images: string[]`을 추가한다. `product_images`가 있으면 `sort_order` 순서, 없으면 `[imageUrl]`(옛 상품은 변환 없이 동작). 항상 1개 이상.
- 상품 등록: `imageUrls: string[]`(1~10개)를 받는다. `imageUrl`(옛 필드)만 와도 계속 받는다. 각 URL은 기존 이미지 URL 검증과 같은 규칙(길이 2000, 허용 스킴)을 따른다.
- 화면: 상세 갤러리에 썸네일 줄(2장 이상일 때), 등록 화면에 이미지 주소 행 추가/삭제.

## 2. 옵션 모델 (PR 2, V18)

```sql
CREATE TABLE product_options (
    id             VARCHAR(36) PRIMARY KEY,
    product_id     VARCHAR(36) NOT NULL REFERENCES products(id),
    color_name     VARCHAR(30),
    color_hex      VARCHAR(7),
    size_label     VARCHAR(30),
    price          INTEGER NOT NULL CHECK (price >= 0),
    original_price INTEGER CHECK (original_price IS NULL OR original_price > price),
    stock          INTEGER NOT NULL CHECK (stock >= 0),
    sort_order     INTEGER NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    CHECK (color_name IS NOT NULL OR size_label IS NOT NULL),
    CHECK (color_hex IS NULL OR (color_name IS NOT NULL AND color_hex ~ '^#[0-9A-Fa-f]{6}$'))
);
CREATE UNIQUE INDEX uq_product_options_combo ON product_options (product_id, COALESCE(color_name,''), COALESCE(size_label,''));
CREATE INDEX idx_product_options_product ON product_options (product_id, sort_order);
ALTER TABLE order_items ADD COLUMN option_id VARCHAR(36) REFERENCES product_options(id),
                        ADD COLUMN option_label VARCHAR(80);
ALTER TABLE products ADD COLUMN price_varies BOOLEAN NOT NULL DEFAULT FALSE;
```

- 상품은 **옵션이 없거나**(옛 방식: `products.price`/`stock`으로 판매) **옵션이 1~50개**다. 옵션이 있는 상품은 `products.stock`을 판매에 쓰지 않는다(옵션 재고만 본다).
- 옵션 상품의 `products.price`는 **가장 싼 옵션 가격**(그 옵션의 `original_price`가 `products.original_price`)으로 등록 시 저장해 목록·정렬·베스트 쿼리를 바꾸지 않는다. 옵션 가격이 서로 다르면 `price_varies = true`(목록에서 "N원~"으로 표시).
- 색상 칩의 색은 `#RRGGBB`(DB CHECK + 서버 검증). 색 이름만 있고 색이 없으면 글자 칩으로 보여 준다. 사이즈만 있는 상품(색상 없음)과 색상만 있는 상품도 가능.
- 옵션은 **상품 등록 때 정한다**. 등록 후 옵션 추가·삭제는 이번 범위가 아니다(주문 기록이 가리키므로 삭제 금지). 재고·가격 수정은 PR 4.
- 상품 판매 집계·베스트·판매량은 `order_items.product_id` 기준이라 그대로 동작한다.

## 3. 주문과 재고

- 주문 줄: `{ productId, optionId?, quantity }`. 합산 키는 `(productId, optionId)`.
- 검증(서버가 최종): 상품에 옵션이 있으면 `optionId` 필수, 없으면 `optionId`가 오면 400. 옵션은 그 상품 소속이어야 한다. 금액은 **옵션 가격**으로 계산하고 `order_items.price`에 저장, `option_label`에 "화이트 / M" 문구를 스냅샷으로 저장한다(옵션이 나중에 바뀌어도 주문 기록은 그대로).
- 멱등 요청 해시에 `optionId`를 포함한다(키를 재사용하며 옵션만 바꾼 요청을 거절).
- 재고 차감: 옵션 줄은 `UPDATE product_options SET stock = stock - :q WHERE id = :id AND product_id = :pid AND stock >= :q`, 옵션 없는 줄은 기존 상품 UPDATE. 0행이면 품절 오류(옵션 이름 포함).
- 재고 복구(만료, 취소, 늦은 결제 재확보 롤백, 재생 만료)는 `order_items.option_id`가 있으면 옵션 재고, 없으면 상품 재고를 되돌린다. 결제 후 늦게 들어온 주문의 재고 재확보(`applyPaid`)도 같다.
- **잠금 순서**: 모든 재고 갱신 경로(주문 생성, 만료, 취소, 재확보, 재생 만료)는 같은 정렬 키 `(productId, optionId 또는 "")`로 정렬해서 갱신한다. 정렬을 한 곳(`StockKey` 비교자)에 두고 모든 경로가 쓴다. 이렇게 해야 옵션 줄과 옵션 없는 줄이 섞인 주문끼리도 교착(deadlock)이 없다.
- 배송비는 합계 금액으로 계산하므로 변경 없음. 결제 금액 검증은 `orders.total_amount`라 변경 없음.
- 응답: 내 주문 상세·목록, 관리자 주문 목록, 주문 상세는 항목마다 `optionLabel`(없으면 null)을 내려준다.

## 4. 상품 응답

- 상세: `images`, `options: [{ id, colorName, colorHex, sizeLabel, price, originalPrice, discountRate, stock }]`(없으면 빈 배열), `priceVaries`. 기존 필드는 유지.
- 목록: `priceVaries`를 추가(옵션 상품이 "N원~"로 보이게). 기존 필드 유지.
- 옵션 재고 숫자는 공개 응답에 그대로 내려준다(이미 상세에서 `stock`을 내려주고 있고, 재고 임박 표시에 필요하다).

## 5. 화면 (PR 3)

- 상품 등록: 옵션 사용 토글. 옵션 행마다 색 이름, 색(color input), 사이즈, 가격, 정가(선택), 재고. 행 추가·삭제, 중복 조합 검사, 합계 재고 표시. 옵션을 쓰면 상품 가격·재고 입력은 숨기고 최저가로 자동 계산한다.
- 상세 페이지: 색상 칩 + 사이즈 버튼. 선택하기 전에는 최저가("N원~")와 "옵션을 선택해 주세요", 선택하면 그 옵션의 가격·할인·재고. 품절 조합은 비활성(취소선), 재고 5개 이하 안내는 선택한 옵션 기준. 옵션을 고르기 전에는 구매하기·장바구니가 비활성.
- 장바구니: 항목 키 = `productId + optionId`. 옵션 이름 표시. 옛 장바구니(옵션 없음)는 그대로 동작하고, 옵션이 생긴 상품이 옵션 없이 들어 있으면 서버가 400으로 거절하므로 "옵션을 선택해 주세요" 안내와 상품 링크를 보여 준다.
- 주문 상세·목록·관리자 목록: 항목 아래에 옵션 이름.

## 5-1. 운영 주의(리뷰에서 나온 것)

- **롤백 주의**: 옵션 주문이 생긴 뒤에 옛 버전 서버로 되돌리면, 옛 서버는 `order_items.option_id`를 몰라서 만료·취소 때 옵션 재고 대신 `products.stock`을 올려 준다(옵션 상품의 `products.stock`은 0이어야 하는데 가짜 재고가 생겨 옛 서버가 팔 수 있다). 옵션 주문이 대기 중일 때는 되돌리지 말고 앞으로 고쳐 배포한다. 불가피하게 되돌렸다면 `UPDATE products SET stock = 0 WHERE id IN (SELECT DISTINCT product_id FROM product_options)`로 바로잡는다.
- **PR 4(가격 수정) 주의**: 지금은 옵션 가격을 바꿀 수 없어서 주문이 읽은 가격과 저장 가격이 같다. 가격 수정을 넣을 때는 주문이 읽은 뒤 가격이 바뀌는 경쟁을 막아야 한다(재고 차감 UPDATE의 WHERE에 `AND price = :expected`를 넣거나 옵션 행을 잠그고 읽는다). 가격을 바꾸면 `products.price`, `original_price`, `price_varies`도 함께 다시 계산한다.
- **옵션 상품의 `products.stock`은 0**이라 옛 화면(옵션 UI 이전)은 품절로 보인다. 옵션 UI(PR 3)가 머지되기 전에는 옵션 상품을 만들지 않는다(등록 화면이 없어 만들 수도 없다).
- 옵션 마이그레이션은 V18(테이블, 상품 컬럼), V19(주문 항목 컬럼과 검증 없는 외래키), V20(외래키 검증)으로 나눈다. 한 트랜잭션이 상품 락과 주문 항목 락을 함께 잡지 않게 하고, 외래키 검증의 전체 스캔이 ACCESS EXCLUSIVE 아래에서 돌지 않게 하려는 것이다.
- 빈 문자열 `optionId`는 "없음"으로 정규화한다(옵션 상품이면 "옵션을 선택해 주세요", 옵션 없는 상품이면 그대로 판매). 값이 있는데 맞지 않으면 400.

## 6. 하지 않는 것(알고 두는 한계)

- 옵션별 이미지(색을 고르면 사진이 바뀌는 동작), SKU/바코드, 옵션별 배송비.
- 등록 후 옵션 추가·삭제·이름 변경(PR 4는 재고·가격만).
- 옵션 조합이 많은(50개 초과) 상품.
- 옵션 상품의 `products.stock`은 판매에 쓰지 않는다(관리자 목록에는 옵션 재고 합계를 보여 줌).

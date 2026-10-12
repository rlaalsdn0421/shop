# 결제(포트원) 운영 설정 순서

설계는 [payment-design.md](payment-design.md). 이 문서는 **사용자가 직접 하는 설정**만 순서대로 적는다.
채널은 포트원 콘솔에서 만든 `shop_test`(나이스페이먼츠, `nice_v2`) **하나**로 시작한다.

> **비밀값 규칙**: API 시크릿, 웹훅 시크릿, 내부 토큰은 **채팅·문서·깃에 붙이지 않는다.** 아래 Cloud Shell 명령의 숨김 입력(`read -s`)으로만 넣는다.
> 상점 ID와 채널 키는 공개값(브라우저에 그대로 들어가는 값)이라 괜찮다.

## 1. 포트원 콘솔에서 확인할 값

| 값 | 어디서 | 공개 여부 |
|---|---|---|
| 상점 ID (store id) | 연동 정보 화면 우측 상단 | 공개 가능 |
| 채널 키 (`shop_test`) | 연동 정보 > 채널 관리 > 채널을 눌러 상세 | 공개 가능 |
| API 시크릿 (V2) | 연동 정보 > 식별코드·API Keys | **비밀** |
| 웹훅 시크릿 | 연동 정보 > 결제알림(Webhook) 관리 > 웹훅 시크릿 발급(테스트 환경용) | **비밀** |

웹훅 URL은 콘솔의 결제알림(Webhook) 관리에서 **테스트 환경**에 등록한다. 버전은 가장 최신(`2024-04-25`).

```
https://shop-backend-mklbqo55qa-as.a.run.app/api/payments/webhook
```

## 2. Cloud Shell: 시크릿 3개 만들기

Cloud Shell(프로젝트 선택된 상태)에서 한 블록씩 실행한다. 값을 물으면 **붙여 넣고 Enter**(화면에는 안 보인다).

```bash
PROJECT=$(gcloud config get-value project)
REGION=asia-southeast1
SA=shop-backend@$PROJECT.iam.gserviceaccount.com
echo "$PROJECT / $REGION / $SA"
```

```bash
read -rsp "포트원 API 시크릿: " V; echo
printf %s "$V" | gcloud secrets create portone-api-secret --data-file=- --replication-policy=automatic
unset V
```

```bash
read -rsp "포트원 웹훅 시크릿(whsec_로 시작): " V; echo
printf %s "$V" | gcloud secrets create portone-webhook-secret --data-file=- --replication-policy=automatic
unset V
```

내부 토큰은 직접 만들 필요 없이 임의로 생성한다. 같은 값을 스케줄러에도 써야 해서 같은 블록 안에서 한 번에 처리한다(값은 화면에 출력하지 않는다).

```bash
T=$(openssl rand -hex 32)
printf %s "$T" | gcloud secrets create internal-token --data-file=- --replication-policy=automatic

for S in portone-api-secret portone-webhook-secret internal-token; do
  gcloud secrets add-iam-policy-binding $S \
    --member="serviceAccount:$SA" --role="roles/secretmanager.secretAccessor" >/dev/null
done

gcloud services enable cloudscheduler.googleapis.com
gcloud scheduler jobs create http expire-pending-orders \
  --location=$REGION --schedule="*/5 * * * *" --time-zone="Asia/Seoul" \
  --uri="https://shop-backend-mklbqo55qa-as.a.run.app/api/internal/orders/expire-pending" \
  --http-method=POST --headers="X-Internal-Token=$T" --attempt-deadline=60s
unset T
```

(Cloud Scheduler는 작업 3개까지 무료. 토큰은 32자 이상이어야 서버가 내부 API를 켠다. `openssl rand -hex 32`는 64자다.)

확인(값은 안 나온다):

```bash
gcloud secrets list --filter="name~portone OR name~internal"
gcloud scheduler jobs list --location=$REGION
```

## 3. 배포에 연결하기 (이 PR에 이어서 변경한다)

시크릿이 **만들어진 것을 확인한 뒤** `.github/workflows/backend-deploy.yml`에 아래를 추가한다. 시크릿이 없을 때 먼저 넣으면 배포가 실패한다.

- `secrets:` 에 `PORTONE_API_SECRET=portone-api-secret:latest`, `PORTONE_WEBHOOK_SECRET=portone-webhook-secret:latest`, `INTERNAL_TOKEN=internal-token:latest`
- `env_vars:` 에 `PORTONE_STORE_ID=${{ vars.PORTONE_STORE_ID }}`, `PORTONE_ALLOW_TEST_CHANNEL=${{ vars.PORTONE_ALLOW_TEST_CHANNEL }}`
- GitHub 저장소 **Settings > Secrets and variables > Actions > Variables**에 `PORTONE_STORE_ID`(상점 ID)와 `PORTONE_ALLOW_TEST_CHANNEL=true`(테스트 단계에서만)를 만든다.

## 4. Vercel 환경변수 (공개값)

Vercel 프로젝트 Settings > Environment Variables (Production)에 추가한 뒤 **재배포**한다(`NEXT_PUBLIC_*`는 빌드할 때 들어가므로 재배포해야 반영된다).

```
NEXT_PUBLIC_PORTONE_STORE_ID=<상점 ID>
NEXT_PUBLIC_PORTONE_CHANNEL_KEY_CARD=<shop_test 채널 키>
NEXT_PUBLIC_PORTONE_CHANNEL_KEY_KAKAOPAY=<같은 채널 키>
NEXT_PUBLIC_PORTONE_CHANNEL_KEY_NAVERPAY=<같은 채널 키>
NEXT_PUBLIC_PORTONE_CHANNEL_KEY_TOSSPAY=<같은 채널 키>
```

간편결제가 테스트 채널에서 안 되면 그 수단의 변수만 비운다(화면에서 선택지가 사라진다). **카드 하나만 되게 시작해도 된다.**

## 5. 테스트 결제로 확인하기

1. 머지·배포 후 사이트에서 가입·로그인 → 상품을 담고 주문 → 결제창에서 테스트 카드로 결제.
2. 주문 상세가 "결제 완료"로 바뀌는지, 재고가 1번만 줄었는지 확인한다.
3. 결제창을 열고 닫아 두었다가 20분 뒤 주문이 "주문 만료"가 되고 재고가 돌아오는지 확인한다(스케줄러가 5분마다 돈다).
4. 포트원 콘솔의 웹훅 발송 내역이 성공(200)인지 확인한다.

## 6. 실서비스 오픈 전에 반드시

- 나이스페이먼츠와 정식 계약한 **실결제 채널**을 만들고, 실결제용 채널 키·웹훅 시크릿으로 교체한다.
- `PORTONE_ALLOW_TEST_CHANNEL`을 **끄거나 지운다**(켜져 있으면 테스트 채널 결제가 "결제 완료"로 인정된다). 테스트 채널은 콘솔에서 지운다.
- 처리방침에 실제 결제대행사 상호·국가·연락처를 적고 약관·방침 시행일과 정책 버전을 올린다([legal-launch-checklist.md](legal-launch-checklist.md)).

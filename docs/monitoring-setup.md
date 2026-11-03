# 모니터링 설정 (구글 클라우드 모니터링 + 그라파나 클라우드)

서버(Cloud Run, 요청이 없으면 0개로 줄고 최대 2개)가 **문제가 생겼을 때 바로 알리도록** 만든다. 서버 코드는 바꾸지 않는다. 비용은 $0을 목표로 하고(무료 한도 안), 한도는 가입·설정 화면에서 한 번 확인한다.

## 1. 무엇을 알리나

| 알림 | 기준 | 어디서 재나 |
|---|---|---|
| 서버 오류(5xx) 급증 | 5분 동안 5xx가 3건 이상 | Cloud Run 지표(`request_count`) |
| 응답 느려짐 | 응답 시간 95번째 백분위수가 5초를 10분 넘게 | Cloud Run 지표(`request_latencies`) |
| 서버 다운 | 접속 확인(1분마다 주소를 열어 봄)이 실패 | 업타임 체크 |
| 주문 만료 스케줄러 중단 | 5분마다 도는 만료 작업의 성공 기록이 20분 동안 없음 | 로그 기반 지표(`expire_pending_ok`) |
| 결제·취소 중요 오류 | 아래 문구의 오류 로그가 5분에 1건이라도 | 로그 기반 지표(`payment_errors`) |

- 결제·취소 오류 문구: `PortOne cancel failed`, `PortOne cancel got no answer`, `PortOne lookup failed`, `Payment does not match the order`, `Payment channel not allowed`, `Partial cancellation reported`, `stock is gone`, `Could not revert CANCELING`(서버가 이미 남기는 로그).
- 알림은 이메일로 온다. 규칙마다 같은 문제가 이어지면 30분 뒤에 닫히고, 다시 생기면 새로 알린다.
- 서버 안쪽 수치(주소별 응답 시간, 주문 수, 결제 성공률, DB 연결 수 등)는 이 방식으로 볼 수 없다. 필요해지면 서버에 지표를 추가하는 작업을 따로 한다(두 방식은 부딪히지 않고 같은 그라파나에서 함께 볼 수 있다).

## 2. 준비 (Cloud Shell)

Cloud Shell을 열고 프로젝트를 정한다. 값은 이미 쓰는 것과 같다.

```bash
PROJECT=project-882f3f4e-3aec-4330-9b3
SERVICE=shop-backend
gcloud config set project $PROJECT
gcloud services enable monitoring.googleapis.com logging.googleapis.com
```

## 3. 알림 받을 곳(이메일)

```bash
EMAIL="내이메일@example.com"   # 따옴표 안을 본인 이메일로 바꾼다
echo "$EMAIL"                   # 바꾼 이메일이 찍히는지 확인(비면 다시)
CH=$(gcloud beta monitoring channels create \
  --display-name="shop 알림 이메일" --type=email \
  --channel-labels=email_address="$EMAIL" --format='value(name)')
echo "$CH"
```

`projects/…/notificationChannels/숫자` 한 줄이 나오면 된다(이후 단계가 `$CH`를 쓰므로 같은 Cloud Shell 창에서 계속한다). 만든 뒤 구글이 보내는 확인 메일이 있으면 눌러 준다.

## 4. 로그 기반 지표 2개

```bash
gcloud logging metrics create expire_pending_ok \
  --description="주문 만료 작업이 성공(200)한 횟수" \
  --log-filter='resource.type="cloud_run_revision" AND resource.labels.service_name="shop-backend" AND httpRequest.requestMethod="POST" AND httpRequest.requestUrl:"/api/internal/orders/expire-pending" AND httpRequest.status=200'

gcloud logging metrics create payment_errors \
  --description="결제·취소 중요 오류 로그 수" \
  --log-filter='resource.type="cloud_run_revision" AND resource.labels.service_name="shop-backend" AND textPayload=~"PortOne (cancel|lookup) failed|PortOne cancel got no answer|Payment does not match the order|Payment channel not allowed|Partial cancellation reported|stock is gone|Could not revert CANCELING"'
```

## 5. 알림 규칙 4개

규칙은 JSON 파일로 만들어 등록한다(`$CH`를 쓴다).

```bash
# 5-1. 서버 오류(5xx) 급증
cat > /tmp/p-5xx.json <<EOF
{
  "displayName": "shop-backend 5xx 급증",
  "combiner": "OR",
  "conditions": [{
    "displayName": "5분에 5xx 3건 이상",
    "conditionThreshold": {
      "filter": "resource.type=\"cloud_run_revision\" AND resource.labels.service_name=\"$SERVICE\" AND metric.type=\"run.googleapis.com/request_count\" AND metric.labels.response_code_class=\"5xx\"",
      "aggregations": [{"alignmentPeriod": "300s", "perSeriesAligner": "ALIGN_DELTA", "crossSeriesReducer": "REDUCE_SUM"}],
      "comparison": "COMPARISON_GT",
      "thresholdValue": 2,
      "duration": "0s"
    }
  }],
  "notificationChannels": ["$CH"],
  "alertStrategy": {"autoClose": "1800s"}
}
EOF
gcloud alpha monitoring policies create --policy-from-file=/tmp/p-5xx.json

# 5-2. 응답 느려짐 (95번째 백분위수 5초 초과가 10분 이어짐)
cat > /tmp/p-latency.json <<EOF
{
  "displayName": "shop-backend 응답 느려짐",
  "combiner": "OR",
  "conditions": [{
    "displayName": "p95 5초 초과 10분",
    "conditionThreshold": {
      "filter": "resource.type=\"cloud_run_revision\" AND resource.labels.service_name=\"$SERVICE\" AND metric.type=\"run.googleapis.com/request_latencies\"",
      "aggregations": [{"alignmentPeriod": "300s", "perSeriesAligner": "ALIGN_PERCENTILE_95", "crossSeriesReducer": "REDUCE_MAX"}],
      "comparison": "COMPARISON_GT",
      "thresholdValue": 5000,
      "duration": "600s"
    }
  }],
  "notificationChannels": ["$CH"],
  "alertStrategy": {"autoClose": "1800s"}
}
EOF
gcloud alpha monitoring policies create --policy-from-file=/tmp/p-latency.json

# 5-3. 주문 만료 스케줄러 중단 (성공 기록이 20분 동안 없음)
cat > /tmp/p-sched.json <<EOF
{
  "displayName": "주문 만료 작업 중단",
  "combiner": "OR",
  "conditions": [{
    "displayName": "성공 기록 없음 20분",
    "conditionAbsent": {
      "filter": "metric.type=\"logging.googleapis.com/user/expire_pending_ok\" AND resource.type=\"cloud_run_revision\"",
      "aggregations": [{"alignmentPeriod": "300s", "perSeriesAligner": "ALIGN_RATE"}],
      "duration": "1200s"
    }
  }],
  "notificationChannels": ["$CH"],
  "alertStrategy": {"autoClose": "1800s"}
}
EOF
gcloud alpha monitoring policies create --policy-from-file=/tmp/p-sched.json

# 5-4. 결제·취소 중요 오류 로그
cat > /tmp/p-pay.json <<EOF
{
  "displayName": "결제·취소 중요 오류",
  "combiner": "OR",
  "conditions": [{
    "displayName": "5분에 오류 로그 1건 이상",
    "conditionThreshold": {
      "filter": "metric.type=\"logging.googleapis.com/user/payment_errors\" AND resource.type=\"cloud_run_revision\"",
      "aggregations": [{"alignmentPeriod": "300s", "perSeriesAligner": "ALIGN_DELTA", "crossSeriesReducer": "REDUCE_SUM"}],
      "comparison": "COMPARISON_GT",
      "thresholdValue": 0,
      "duration": "0s"
    }
  }],
  "notificationChannels": ["$CH"],
  "alertStrategy": {"autoClose": "1800s"}
}
EOF
gcloud alpha monitoring policies create --policy-from-file=/tmp/p-pay.json
```

- 5-3은 로그 기반 지표(`expire_pending_ok`)에 **한 번이라도 값이 쌓인 뒤**부터 작동한다. 만든 직후 5~10분 안에 5분 주기 작업이 한 번 성공하면 시작된다.
- `gcloud alpha monitoring`을 처음 쓰면 alpha 구성요소 설치를 묻는다. 허락(y)하면 된다.

## 6. 서버 다운 알림 (접속 확인)

콘솔 화면으로 만들면 알림 채널 목록이 비어 보이거나(콘솔이 다른 프로젝트를 보고 있을 때) 체크가 저장되지 않는 일이 있었다. Cloud Shell 명령으로 만든다. 상품 목록 요청이 DB까지 닿아서 서버뿐 아니라 DB 연결도 살아 있는지 같이 본다.

```bash
gcloud monitoring uptime create "shop-backend 접속 확인" \
  --resource-type=uptime-url \
  --resource-labels=host=shop-backend-mklbqo55qa-as.a.run.app,project_id=$PROJECT \
  --protocol=https --path="/api/products?size=1" --period=1
CHECK_ID=$(gcloud monitoring uptime list-configs --filter='displayName="shop-backend 접속 확인"' --format='value(name)' | sed 's#.*/##')
echo "CHECK_ID=$CHECK_ID"
```

`CHECK_ID=` 뒤에 값이 나와야 체크가 저장된 것이다. 이어서 알림 규칙을 만든다(여러 지역 중 2곳 이상에서 실패해야 울려서 일시적인 한 곳 실패에는 울리지 않는다).

```bash
cat > /tmp/p-uptime.json <<EOF
{
  "displayName": "shop-backend 접속 확인 실패",
  "combiner": "OR",
  "conditions": [{
    "displayName": "2곳 이상에서 접속 실패",
    "conditionThreshold": {
      "filter": "metric.type=\"monitoring.googleapis.com/uptime_check/check_passed\" AND metric.label.check_id=\"$CHECK_ID\" AND resource.type=\"uptime_url\"",
      "aggregations": [{"alignmentPeriod": "1200s", "perSeriesAligner": "ALIGN_NEXT_OLDER", "crossSeriesReducer": "REDUCE_COUNT_FALSE", "groupByFields": ["resource.label.*"]}],
      "comparison": "COMPARISON_GT",
      "thresholdValue": 1,
      "duration": "60s"
    }
  }],
  "notificationChannels": ["$CH"],
  "alertStrategy": {"autoClose": "1800s"}
}
EOF
gcloud alpha monitoring policies create --policy-from-file=/tmp/p-uptime.json
```

## 7. 그라파나 클라우드로 보기 (선택, 대시보드)

알림만으로 충분하면 건너뛰어도 된다.

1. grafana.com에서 무료 계정을 만들고 스택(Grafana Cloud)을 만든다.
2. 읽기 전용 서비스 계정과 키를 만든다(키는 Cloud Shell에 파일로만 생기고 채팅에 붙여 넣지 않는다).

```bash
gcloud iam service-accounts create grafana-reader --display-name="Grafana 읽기 전용"
gcloud projects add-iam-policy-binding $PROJECT \
  --member="serviceAccount:grafana-reader@$PROJECT.iam.gserviceaccount.com" --role="roles/monitoring.viewer"
gcloud iam service-accounts keys create ~/grafana-reader.json \
  --iam-account=grafana-reader@$PROJECT.iam.gserviceaccount.com
cloudshell download ~/grafana-reader.json
```

3. 그라파나 > Connections > **Google Cloud Monitoring** 데이터 소스 추가 > 인증은 서비스 계정 키(JSON) 파일 올리기 > **Save & test**.
4. 올린 뒤 키 파일을 지운다: `shred -u ~/grafana-reader.json`, 내 컴퓨터에 내려받은 파일도 지운다.
5. 데이터 소스의 **Dashboards** 탭에서 Cloud Run 대시보드를 가져오거나, 새 대시보드에 `Cloud Run 요청 수(응답 코드 그룹별)`, `요청 응답 시간(p95)`, `인스턴스 수`, `메모리·CPU`, 로그 기반 지표 두 개를 넣는다.

(키를 만들 수 없게 막혀 있으면 조직 정책 때문이다. 그때는 이 단계를 건너뛰고 알림만 쓴다.)

## 8. 확인

- 5xx 규칙: 서버에 없는 주소를 부르는 것으로는 안 걸린다(404). 규칙이 만들어졌는지만 확인한다: `gcloud alpha monitoring policies list --format="table(displayName,notificationChannels.len())"` 에 규칙 5개(5xx, 응답 느려짐, 만료 작업 중단, 결제·취소 오류, 접속 확인 실패)가 보이고 채널이 모두 1이면 된다.
- 알림 채널: 이메일 채널은 따로 인증 단계가 없고 콘솔에 테스트 버튼이 없을 수 있다. 임시 정책으로 직접 울려 본다. 5분 주기 만료 작업 요청이 있으면 울리는 정책을 만든 뒤(`run.googleapis.com/request_count` 합계 > 0, 5분, 임계값 0, 채널은 위와 같은 것) 5~10분 안에 메일이 오는지 보고, 확인하면 곧바로 지운다(`gcloud alpha monitoring policies delete <정책 이름> --quiet`). 지우지 않으면 요청마다 메일이 온다.
- 콘솔에서 알림 채널·정책이 안 보이면 콘솔이 다른 프로젝트를 보고 있는 것이다. 주소 끝에 `?project=<프로젝트 ID>`를 붙여 연다.
- 만료 작업 지표: 10분쯤 뒤 콘솔 Monitoring > Metrics explorer에서 `logging.googleapis.com/user/expire_pending_ok`에 값이 생기는지 본다.

## 9. 알고 두는 한계

- 알림은 지표가 모인 뒤에 울린다(보통 1~5분 지연). 서버가 아예 안 열리는 경우는 업타임 체크가 가장 빠르다.
- 응답 느려짐은 서버가 막 떠서 느린 첫 요청(콜드 스타트)이 섞여 가끔 울릴 수 있다(5초·10분 기준으로 완화했다). 너무 시끄러우면 기준을 올린다.
- 결제·취소 오류는 PortOne이 일시적으로 느릴 때도 로그가 남아 울릴 수 있다. 그래도 한 번씩 확인할 가치가 있는 신호로 본다.
- 무료 한도는 구글·그라파나의 요금표 기준이며 바뀔 수 있다. 가입·설정 화면에서 한도와 요금 안내를 한 번 확인한다.
- 이 방식은 서버 안쪽 수치를 못 본다(1절). 서버에 지표를 추가하는 작업은 나중에 따로 한다.

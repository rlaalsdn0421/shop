#!/usr/bin/env bash
# GCP 초기 설정 (한 번만 실행). Google Cloud Shell에서 실행하는 것을 전제로 한다.
#
#   PROJECT_ID=내-프로젝트-ID bash scripts/gcp-setup.sh
#
# 하는 일: API 활성화 → Artifact Registry → 서비스 계정 2개 → GitHub OIDC(WIF) 연결 → Secret Manager 시크릿 생성.
# 여러 번 실행해도 안전하다(이미 있는 리소스/시크릿은 건너뜀). 비밀번호는 화면에 표시되지 않고 Secret Manager에만 저장된다.
set -euo pipefail

PROJECT_ID="${PROJECT_ID:?PROJECT_ID 환경변수를 지정하세요 (예: PROJECT_ID=my-shop-123 bash scripts/gcp-setup.sh)}"
REGION="${REGION:-asia-northeast1}"
GITHUB_REPO="${GITHUB_REPO:-rlaalsdn0421/shop}"

gcloud config set project "$PROJECT_ID" >/dev/null
PROJECT_NUMBER="$(gcloud projects describe "$PROJECT_ID" --format='value(projectNumber)')"
DEPLOYER_SA="github-deployer@${PROJECT_ID}.iam.gserviceaccount.com"
RUNTIME_SA="shop-backend@${PROJECT_ID}.iam.gserviceaccount.com"

echo "== 1/5 API 활성화"
gcloud services enable \
  run.googleapis.com \
  artifactregistry.googleapis.com \
  iamcredentials.googleapis.com \
  secretmanager.googleapis.com \
  cloudresourcemanager.googleapis.com

echo "== 2/5 Artifact Registry (이미지 저장소, 최근 3개만 보관해 무료 용량 유지)"
gcloud artifacts repositories describe shop --location="$REGION" >/dev/null 2>&1 \
  || gcloud artifacts repositories create shop --repository-format=docker --location="$REGION"
cat > /tmp/cleanup-policy.json <<'EOF'
[
  {"name": "delete-old", "action": {"type": "Delete"}, "condition": {"tagState": "any"}},
  {"name": "keep-recent", "action": {"type": "Keep"}, "mostRecentVersions": {"keepCount": 3}}
]
EOF
gcloud artifacts repositories set-cleanup-policies shop --location="$REGION" \
  --policy=/tmp/cleanup-policy.json --no-dry-run \
  || echo "  (정리 정책 설정 실패 - 나중에 콘솔에서 수동 설정해도 됩니다)"

echo "== 3/5 서비스 계정 (배포용 / 실행용 분리)"
gcloud iam service-accounts describe "$DEPLOYER_SA" >/dev/null 2>&1 \
  || gcloud iam service-accounts create github-deployer --display-name="GitHub Actions deployer"
gcloud iam service-accounts describe "$RUNTIME_SA" >/dev/null 2>&1 \
  || gcloud iam service-accounts create shop-backend --display-name="Cloud Run runtime (shop-backend)"
for role in roles/run.admin roles/artifactregistry.writer; do
  gcloud projects add-iam-policy-binding "$PROJECT_ID" \
    --member="serviceAccount:${DEPLOYER_SA}" --role="$role" --condition=None >/dev/null
done
gcloud iam service-accounts add-iam-policy-binding "$RUNTIME_SA" \
  --member="serviceAccount:${DEPLOYER_SA}" --role="roles/iam.serviceAccountUser" >/dev/null

echo "== 4/5 GitHub Actions 키 없는 인증 (Workload Identity Federation, ${GITHUB_REPO} 저장소만 허용)"
gcloud iam workload-identity-pools describe github --location=global >/dev/null 2>&1 \
  || gcloud iam workload-identity-pools create github --location=global --display-name="GitHub"
gcloud iam workload-identity-pools providers describe github-provider \
  --location=global --workload-identity-pool=github >/dev/null 2>&1 \
  || gcloud iam workload-identity-pools providers create-oidc github-provider \
    --location=global --workload-identity-pool=github \
    --issuer-uri="https://token.actions.githubusercontent.com" \
    --attribute-mapping="google.subject=assertion.sub,attribute.repository=assertion.repository" \
    --attribute-condition="assertion.repository=='${GITHUB_REPO}'"
gcloud iam service-accounts add-iam-policy-binding "$DEPLOYER_SA" \
  --role="roles/iam.workloadIdentityUser" \
  --member="principalSet://iam.googleapis.com/projects/${PROJECT_NUMBER}/locations/global/workloadIdentityPools/github/attribute.repository/${GITHUB_REPO}" >/dev/null

echo "== 5/5 Secret Manager 시크릿"
secret_exists() { gcloud secrets describe "$1" >/dev/null 2>&1; }
grant_secret_access() { # $1=이름 (기존/신규 관계없이 멱등하게 재적용)
  gcloud secrets add-iam-policy-binding "$1" \
    --member="serviceAccount:${RUNTIME_SA}" --role="roles/secretmanager.secretAccessor" >/dev/null
}
create_secret() { # $1=이름 $2=값 (빈 값은 저장하지 않음)
  if [ -z "$2" ]; then echo "  ! $1: 빈 값은 저장하지 않습니다. 다시 실행하세요."; exit 1; fi
  printf '%s' "$2" | gcloud secrets create "$1" --data-file=- --replication-policy=automatic >/dev/null
  grant_secret_access "$1"
  echo "  - $1: 생성됨"
}
ask_secret() { # $1=이름 $2=안내문구 $3=visible이면 입력이 보임(기본은 숨김)
  if secret_exists "$1"; then grant_secret_access "$1"; echo "  - $1: 이미 있음 (건너뜀)"; return; fi
  local value
  if [ "${3:-hidden}" = "visible" ]; then
    read -rp "  $2: " value
  else
    read -rsp "  $2: " value
    echo
  fi
  create_secret "$1" "$value"
}

if secret_exists db-url; then
  grant_secret_access db-url
  echo "  - db-url: 이미 있음 (건너뜀)"
else
  echo "  Neon 대시보드 Connection Details에서 'Pooled connection'을 끈 직접 연결 주소를 확인하세요."
  read -rp "  Neon 호스트 (예: ep-xxxx.ap-southeast-1.aws.neon.tech): " NEON_HOST
  read -rp "  DB 이름 (예: neondb): " NEON_DB
  if [ -z "$NEON_HOST" ] || [ -z "$NEON_DB" ]; then echo "  ! db-url: 호스트와 DB 이름은 비워둘 수 없습니다. 다시 실행하세요."; exit 1; fi
  create_secret db-url "jdbc:postgresql://${NEON_HOST}/${NEON_DB}?sslmode=require"
fi
ask_secret db-username "Neon DB 사용자 이름" visible
ask_secret db-password "Neon DB 비밀번호"
if secret_exists jwt-secret; then
  grant_secret_access jwt-secret
  echo "  - jwt-secret: 이미 있음 (건너뜀)"
else
  create_secret jwt-secret "$(openssl rand -base64 48)"
fi
# 운영 관리자/판매자 계정: 이메일도 코드에 두지 않고 시크릿으로 둔다(추측하기 어려운 값을 직접 정하세요).
ask_secret admin-email "운영 ADMIN 로그인 이메일" visible
ask_secret admin-password "운영 ADMIN 비밀번호"
ask_secret seller-email "운영 SELLER 로그인 이메일" visible
ask_secret seller-password "운영 SELLER 비밀번호"
# 두 이메일은 서로 달라야 한다(users.email 유니크 제약, 같으면 판매자 계정이 조용히 안 만들어짐).
ADMIN_EMAIL_VALUE="$(gcloud secrets versions access latest --secret=admin-email)"
SELLER_EMAIL_VALUE="$(gcloud secrets versions access latest --secret=seller-email)"
if [ "${ADMIN_EMAIL_VALUE,,}" = "${SELLER_EMAIL_VALUE,,}" ]; then
  echo "  ! admin-email과 seller-email이 같습니다. 서로 다른 이메일이어야 합니다."
  echo "    둘 중 하나를 지우고(gcloud secrets delete seller-email) 이 스크립트를 다시 실행하세요."
  exit 1
fi

cat <<EOF

================ 완료 ================
GitHub 저장소 > Settings > Secrets and variables > Actions > Variables 탭에 아래 값을 추가하세요.

  GCP_PROJECT_ID                   = ${PROJECT_ID}
  GCP_REGION                       = ${REGION}
  GCP_DEPLOYER_SERVICE_ACCOUNT     = ${DEPLOYER_SA}
  GCP_WORKLOAD_IDENTITY_PROVIDER   = projects/${PROJECT_NUMBER}/locations/global/workloadIdentityPools/github/providers/github-provider
  CORS_ALLOWED_ORIGINS             = (Vercel 프론트 주소, 예: https://내프로젝트.vercel.app)

위 값들은 비밀이 아니므로 채팅에 붙여넣어도 됩니다. 시크릿(비밀번호, DB 주소)은 붙여넣지 마세요.
EOF

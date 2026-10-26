import { handleUpload, type HandleUploadBody } from "@vercel/blob/client";
import { NextResponse } from "next/server";
import { IMAGE_TYPES, MAX_IMAGE_BYTES, UPLOAD_PATHNAME } from "@/lib/imageUpload";

const BACKEND_URL = process.env.NEXT_PUBLIC_BACKEND_URL ?? "http://localhost:8080";

class Unauthorized extends Error {}
class BackendUnavailable extends Error {}

// A JWT is three base64url parts; anything else (or absurdly long) is turned away before it costs a backend call.
const JWT_SHAPE = /^[\w-]{1,1500}\.[\w-]{1,1500}\.[\w-]{1,500}$/;
const TOKEN_TTL_MS = 60_000;

// Same rule as the backend's /api/admin/** (ADMIN or SELLER): ask the backend itself, which owns the JWT secret.
// The smallest admin-only call stands in for a "who am I" endpoint.
async function checkAdminToken(token: string): Promise<void> {
  if (!JWT_SHAPE.test(token)) throw new Unauthorized();
  let res: Response;
  try {
    res = await fetch(`${BACKEND_URL}/api/admin/orders?size=1`, {
      headers: { Authorization: `Bearer ${token}` },
      cache: "no-store",
      signal: AbortSignal.timeout(10_000),
    });
  } catch {
    throw new BackendUnavailable();
  }
  if (res.ok) return;
  // Only the backend saying "not you" is a login problem; its own failures must not look like a logout.
  throw res.status === 401 || res.status === 403 ? new Unauthorized() : new BackendUnavailable();
}

/** Hands out a short-lived upload token to a logged-in ADMIN/SELLER; Blob itself enforces the type and size limits. */
export async function POST(request: Request): Promise<Response> {
  if (!process.env.BLOB_READ_WRITE_TOKEN) {
    return NextResponse.json({ error: "이미지 저장소가 설정되지 않았어요." }, { status: 503 });
  }
  let body: HandleUploadBody;
  try {
    body = (await request.json()) as HandleUploadBody;
  } catch {
    return NextResponse.json({ error: "요청 본문을 읽을 수 없습니다." }, { status: 400 });
  }
  try {
    const result = await handleUpload({
      body,
      request,
      onBeforeGenerateToken: async (pathname) => {
        const token = request.headers.get("authorization")?.match(/^Bearer (.+)$/)?.[1];
        if (!token) throw new Unauthorized();
        await checkAdminToken(token);
        if (typeof pathname !== "string" || !UPLOAD_PATHNAME.test(pathname)) throw new Error("bad path");
        return {
          validUntil: Date.now() + TOKEN_TTL_MS,
          allowedContentTypes: [...IMAGE_TYPES],
          maximumSizeInBytes: MAX_IMAGE_BYTES,
          addRandomSuffix: true,
        };
      },
    });
    return NextResponse.json(result);
  } catch (e) {
    if (e instanceof Unauthorized) return NextResponse.json({ error: "권한이 없어요." }, { status: 401 });
    if (e instanceof BackendUnavailable) {
      return NextResponse.json({ error: "서버가 응답하지 않아요. 잠시 뒤 다시 시도해 주세요." }, { status: 503 });
    }
    return NextResponse.json({ error: "요청을 처리할 수 없어요." }, { status: 400 });
  }
}

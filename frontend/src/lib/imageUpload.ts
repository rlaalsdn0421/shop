import { upload } from "@vercel/blob/client";
import { AuthError, checkAdminSession } from "@/lib/api";

export const IMAGE_TYPES = ["image/jpeg", "image/png", "image/webp"] as const;
export const MAX_IMAGE_BYTES = 5 * 1024 * 1024;
export const UPLOAD_PATH = "/api/blob/upload";
export const BLOB_FOLDER = "products/";
/** The only name shape uploadProductImage produces; the route accepts nothing else. */
export const UPLOAD_PATHNAME = /^products\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(jpg|png|webp)$/;

const EXTENSIONS: Record<string, string> = { "image/jpeg": "jpg", "image/png": "png", "image/webp": "webp" };

/** Shown next to the picker; the server route enforces the same rules, this only saves a round trip. */
export function validateImageFile(file: { type: string; size: number }): string | null {
  if (!(IMAGE_TYPES as readonly string[]).includes(file.type)) return "JPG, PNG, WEBP 사진만 올릴 수 있어요.";
  if (file.size <= 0) return "빈 파일이에요. 다른 사진을 골라 주세요.";
  if (file.size > MAX_IMAGE_BYTES) return `사진은 ${MAX_IMAGE_BYTES / 1024 / 1024}MB 이하만 올릴 수 있어요.`;
  return null;
}

export const UPLOAD_FAILED = "사진을 올리지 못했어요. 잠시 뒤 다시 시도해 주세요.";
export const SESSION_EXPIRED = "로그인이 만료됐을 수 있어요. 다시 로그인한 뒤 올려 주세요.";

/**
 * Uploads straight from the browser to Vercel Blob (the file never passes through our server, so the 4.5MB function
 * body limit does not apply). The route at UPLOAD_PATH only hands out a one-minute token after checking the admin's
 * login. The stored name is random, never the original file name.
 */
export async function uploadProductImage(file: File, token: string, signal?: AbortSignal): Promise<string> {
  const invalid = validateImageFile(file);
  if (invalid) throw new Error(invalid);
  if (!token) throw new Error(SESSION_EXPIRED);
  try {
    const blob = await upload(`${BLOB_FOLDER}${crypto.randomUUID()}.${EXTENSIONS[file.type]}`, file, {
      access: "public",
      handleUploadUrl: UPLOAD_PATH,
      headers: { Authorization: `Bearer ${token}` },
      contentType: file.type,
      abortSignal: signal,
    });
    return blob.url;
  } catch (e) {
    if (signal?.aborted) throw e;
    // The SDK hides the route's 401 behind a generic error, so ask the backend whether the login is the cause.
    // No logout here: the admin keeps the form that is not saved yet.
    try {
      await checkAdminSession(token, signal);
    } catch (probe) {
      if (probe instanceof AuthError) throw new Error(SESSION_EXPIRED);
    }
    throw new Error(UPLOAD_FAILED);
  }
}

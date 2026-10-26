"use client";

import { useEffect, useRef, useState } from "react";
import Image from "next/image";
import { IMAGE_TYPES, UPLOAD_FAILED, uploadProductImage, validateImageFile } from "@/lib/imageUpload";

export const IMAGE_HINT = "JPG, PNG, WEBP 사진을 한 장에 5MB까지 올릴 수 있어요.";

type Status = "uploading" | "done" | { error: string };

/** Per-row upload state, keyed by the image row's stable key. Shared by the register and edit forms. */
export function useImageUploads(token: string) {
  const [status, setStatus] = useState<Record<number, Status>>({});
  const controllers = useRef(new Map<number, AbortController>());

  // Leaving the page must not leave an upload that later writes into a form that is gone.
  useEffect(() => {
    const running = controllers.current;
    return () => running.forEach((c) => c.abort());
  }, []);

  const setRow = (key: number, next?: Status) =>
    setStatus((all) => {
      const copy = { ...all };
      if (next) copy[key] = next;
      else delete copy[key];
      return copy;
    });

  /** A removed row drops its upload; the aborted upload never touches the form again. */
  function cancel(key: number) {
    controllers.current.get(key)?.abort();
    controllers.current.delete(key);
    setRow(key);
  }

  async function pick(key: number, file: File, onUrl: (url: string) => void) {
    controllers.current.get(key)?.abort(); // picking again replaces an upload still running
    const invalid = validateImageFile(file);
    if (invalid) {
      controllers.current.delete(key);
      setRow(key, { error: invalid });
      return;
    }
    const controller = new AbortController();
    controllers.current.set(key, controller);
    setRow(key, "uploading");
    try {
      const url = await uploadProductImage(file, token, controller.signal);
      if (controller.signal.aborted) return;
      onUrl(url);
      setRow(key, "done");
    } catch (e) {
      if (controller.signal.aborted) return;
      setRow(key, { error: e instanceof Error ? e.message : UPLOAD_FAILED });
    } finally {
      if (controllers.current.get(key) === controller) controllers.current.delete(key);
    }
  }

  return { status, busy: Object.values(status).includes("uploading"), pick, cancel };
}

type PickerProps = {
  id: string;
  index: number; // 1-based position, the first one is the representative image
  url: string;
  status?: Status;
  inputRef?: (el: HTMLInputElement | null) => void;
  describedBy?: string; // id of a form-level error for this row
  disabled?: boolean; // e.g. while the form is being saved
  onFile: (file: File) => void;
};

export function ImagePicker({ id, index, url, status, inputRef, describedBy, disabled, onFile }: PickerProps) {
  const failed = typeof status === "object";
  // A failed pick never touches the picture the row already has; say so.
  const message =
    status === "uploading" ? "올리는 중이에요..." : status === "done" ? "사진을 올렸어요." : failed ? `${status.error}${url ? " 기존 사진은 그대로예요." : ""}` : null;
  return (
    <div className="min-w-0 flex-1">
      <div className="flex items-center gap-3">
        {url && (
          <Image
            src={url}
            alt={`이미지 ${index} 미리보기`}
            width={64}
            height={64}
            className="h-16 w-16 shrink-0 rounded border object-cover"
            unoptimized
          />
        )}
        {/* The native file input shows no button under the reset styles, so it is hidden and its label is the button. */}
        <input
          id={id}
          ref={inputRef}
          type="file"
          disabled={disabled}
          accept={IMAGE_TYPES.join(",")}
          className="peer sr-only"
          aria-invalid={failed || describedBy ? true : undefined}
          aria-describedby={[message && `${id}-status`, describedBy].filter(Boolean).join(" ") || undefined}
          onChange={(e) => {
            const file = e.target.files?.[0];
            e.target.value = ""; // the same file can be picked again after a failure
            if (file) onFile(file);
          }}
        />
        <label
          htmlFor={id}
          className="flex min-w-0 flex-1 cursor-pointer items-center justify-center rounded border border-dashed px-3 py-4 text-sm hover:bg-gray-50 peer-focus-visible:outline peer-focus-visible:outline-2 peer-focus-visible:outline-offset-2 peer-disabled:cursor-not-allowed peer-disabled:opacity-50"
        >
          {index === 1 ? "대표 이미지 선택" : `이미지 ${index} 선택`}
        </label>
      </div>
      {/* Stays mounted (empty) so the announcement is reliable, like the other inline field messages. */}
      <p id={`${id}-status`} role="status" className={failed ? "mt-1 text-sm text-red-600" : "mt-1 text-sm text-gray-600"}>
        {message}
      </p>
    </div>
  );
}

"use client";

import { useState } from "react";
import Image from "next/image";

// The thumbnail strip only appears with more than one image, so a single-image product looks as before.
export function ProductGallery({ images, name }: { images: string[]; name: string }) {
  const [selected, setSelected] = useState(0);
  const current = images[selected] ?? images[0];
  if (!current) return null;

  return (
    <div className="flex flex-col gap-3">
      <div className="relative aspect-square bg-gray-100 rounded-lg overflow-hidden">
        <Image src={current} alt={name} fill className="object-cover" unoptimized />
      </div>
      {images.length > 1 && (
        <ul aria-label="상품 이미지 목록" className="flex gap-2 overflow-x-auto">
          {images.map((src, i) => (
            <li key={`${src}-${i}`} className="shrink-0">
              <button
                type="button"
                aria-label={`${name} 이미지 ${i + 1} 보기`}
                aria-pressed={i === selected}
                onClick={() => setSelected(i)}
                className={`relative block w-16 h-16 rounded overflow-hidden bg-gray-100 border-2 focus:outline-none focus-visible:ring-2 focus-visible:ring-black focus-visible:ring-offset-2 ${
                  i === selected ? "border-black" : "border-transparent"
                }`}
              >
                <Image src={src} alt="" fill sizes="64px" className="object-cover" unoptimized />
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

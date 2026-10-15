"use client";

import { useState } from "react";
import Image from "next/image";

// The thumbnail strip only appears with more than one image, so a single-image product looks as before.
export function ProductGallery({ images, name }: { images: string[]; name: string }) {
  const [selected, setSelected] = useState(0);
  const current = images[selected] ?? images[0];
  if (!current) return null;

  return (
    // Mobile: big image, thumbnails in a row below. md and up: thumbnails in a vertical strip at the
    // left (row-reverse keeps the image first in the DOM, so reading order stays image then list).
    <div className="flex flex-col gap-3 md:flex-row-reverse">
      <div className="relative aspect-square min-w-0 md:flex-1 bg-gray-100 rounded-lg overflow-hidden">
        <Image src={current} alt={name} fill className="object-cover" unoptimized />
      </div>
      {images.length > 1 && (
        // On md the wrapper takes the big image's height and the list scrolls inside it.
        <div className="md:relative md:w-[4.25rem] md:shrink-0">
          <ul
            aria-label="상품 이미지 목록"
            className="flex gap-2 overflow-x-auto p-0.5 md:absolute md:inset-0 md:flex-col md:overflow-x-hidden md:overflow-y-auto"
          >
            {images.map((src, i) => (
              <li key={`${src}-${i}`} className="shrink-0">
                <button
                  type="button"
                  aria-label={`${name} 이미지 ${i + 1} 보기`}
                  aria-pressed={i === selected}
                  onClick={() => setSelected(i)}
                  className={`relative block w-16 h-16 rounded overflow-hidden bg-gray-100 border-2 focus:outline-none focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-blue-600 ${
                    i === selected ? "border-black" : "border-transparent opacity-70 hover:opacity-100"
                  }`}
                >
                  <Image src={src} alt="" fill sizes="64px" className="object-cover" unoptimized />
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

export const MAX_HASHTAGS = 3;
export const MAX_HASHTAG_LENGTH = 20;

export const HASHTAG_ERRORS = {
  tooMany: "해시태그는 최대 3개까지 입력할 수 있어요.",
  tooLong: "해시태그는 하나당 20자까지 입력할 수 있어요.",
  badChars: "해시태그는 글자, 숫자, 밑줄(_)만 쓸 수 있어요.",
} as const;

// Letters of any language (with combining marks), digits, underscore. Zero-width
// characters and NBSP are not in these classes, so they stay invalid. Mirrors the backend.
const ALLOWED = /^[\p{L}\p{M}\p{N}_]+$/u;

// Only ASCII whitespace separates tags (same as Java's \s); NBSP and the like stay inside a
// tag and make it invalid, exactly as on the backend.
const SEPARATORS = /[ \t\n\x0B\f\r]+/;

// "#a  ＃B a" -> ["a", "B"]: NFC-normalize, split on whitespace, drop leading '#' / '＃'
// (U+FF03), drop empties, de-duplicate ignoring case (first spelling wins).
// Length is counted in code points after NFC, so an astral letter such as 𠮷 counts once.
export function parseHashtags(input: string): { tags: string[]; error: string | null } {
  const seen = new Set<string>();
  const tags: string[] = [];
  for (const raw of input.split(SEPARATORS)) {
    const tag = raw.normalize("NFC").replace(/^[#＃]+/, "");
    if (tag === "" || seen.has(tag.toLowerCase())) continue;
    seen.add(tag.toLowerCase());
    tags.push(tag);
  }
  const error = tags.some((t) => !ALLOWED.test(t))
    ? HASHTAG_ERRORS.badChars
    : tags.some((t) => [...t].length > MAX_HASHTAG_LENGTH)
      ? HASHTAG_ERRORS.tooLong
      : tags.length > MAX_HASHTAGS
        ? HASHTAG_ERRORS.tooMany
        : null;
  return { tags, error };
}

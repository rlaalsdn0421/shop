// Shows a backend instant as a Korean calendar date (e.g. "2026. 10. 12."), always in Korea time
// so the day does not depend on the visitor's device time zone. Invalid input gives "".
export function formatKoreanDate(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "" : d.toLocaleDateString("ko-KR", { timeZone: "Asia/Seoul" });
}

// Same, with the 24-hour clock time (e.g. "2026. 10. 12. 15:30"), for order timelines where the hour matters.
export function formatKoreanDateTime(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? ""
    : d.toLocaleString("ko-KR", {
        timeZone: "Asia/Seoul",
        year: "numeric",
        month: "numeric",
        day: "numeric",
        hour: "2-digit",
        minute: "2-digit",
        hour12: false,
      });
}

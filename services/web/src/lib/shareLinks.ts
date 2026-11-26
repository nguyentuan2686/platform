/** Pure share-link helpers, safe for client components (no server-only imports). */
export const sharePath = (shareId: string): string => `/vi/chia-se/${shareId}`;
export const cardPath = (shareId: string): string => `${sharePath(shareId)}/the.png`;

export function facebookShareUrl(url: string): string {
  return "https://www.facebook.com/sharer/sharer.php?u=" + encodeURIComponent(url);
}

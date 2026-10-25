export const MAX_MAP_EDGE = 320;
export const MAX_SURFACE_AREA = 900_000;
export const MAX_SURFACE_EDGE = 1_800;

/** This budget bounds decorative filters, never the business canvas or its data. */
export function lensGeometry(width: number, height: number, radius: number, strength: number, dpr: number) {
  if (!Number.isFinite(width) || !Number.isFinite(height) || width < 2 || height < 2
      || width * height > MAX_SURFACE_AREA || Math.max(width, height) > MAX_SURFACE_EDGE) return null;
  const finiteRadius = Number.isFinite(radius) ? radius : 20;
  const finiteStrength = Number.isFinite(strength) ? strength : 8;
  const pixelRatio = Number.isFinite(dpr) && dpr > 0 ? dpr : 1;
  return {
    width, height,
    radius: Math.max(0, Math.min(finiteRadius, width / 2, height / 2)),
    refraction: Math.max(0, Math.min(finiteStrength, 16)),
    mapScale: Math.min(2, pixelRatio, MAX_MAP_EDGE / Math.max(width, height)),
  };
}

/** Only explicit, same-origin static image resources can be copied into an art lens. */
export function localArtUrl(src: string, base: string): string | null {
  if (!src) return null;
  try {
    const location = new URL(base);
    const resource = new URL(src, location);
    return resource.origin === location.origin && !resource.username && !resource.password && ['http:', 'https:'].includes(resource.protocol)
      ? resource.href : null;
  } catch { return null; }
}

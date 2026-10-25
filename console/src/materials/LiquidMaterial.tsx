import { useEffect, useRef, useState, type CSSProperties, type HTMLAttributes, type RefObject } from 'react';
import { createGlass, engine, type GlassController } from './vendor/liquid-glass.js';
import { lensGeometry, localArtUrl } from './geometry';
import './materials.css';

export type LiquidVariant = 'navigation' | 'toolbar' | 'inspector' | 'dialog';
export type LiquidMode = 'solid' | 'svg-backdrop' | 'art-refraction';
export interface LiquidMaterialProps {
  variant: LiquidVariant;
  radius?: number;
  strength?: number;
  className?: string;
  enabled?: boolean;
  /** Explicit owned static image. No DOM screenshots or business node copying. */
  artRef?: RefObject<HTMLImageElement | null>;
}

const RADII: Record<LiquidVariant, number> = { navigation: 24, toolbar: 16, inspector: 20, dialog: 20 };

/** Decorative sibling beneath real text/controls. Never place business content inside this layer. */
export function LiquidMaterial({ variant, radius = RADII[variant], strength = 8,
  className = '', enabled = true, artRef }: LiquidMaterialProps) {
  const layerRef = useRef<HTMLDivElement>(null);
  const lensRef = useRef<HTMLDivElement>(null);
  const [mode, setMode] = useState<LiquidMode>('solid');

  useEffect(() => {
    const layer = layerRef.current;
    const target = lensRef.current;
    if (!layer || !target) return;
    let disposed = false;
    let controller: GlassController | undefined;
    let observer: ResizeObserver | undefined;
    let copy: HTMLImageElement | undefined;
    let resizeTimer = 0;
    let alignFrame = 0;
    let activeMode: LiquidMode = 'solid';
    let failed = false;
    const queries = typeof window.matchMedia === 'function'
      ? [window.matchMedia('(prefers-reduced-transparency: reduce)'), window.matchMedia('(forced-colors: active)')]
      : [];
    const source = artRef?.current;

    const align = () => {
      alignFrame = 0;
      if (disposed || !copy || !source) return;
      const a = source.getBoundingClientRect();
      const b = target.getBoundingClientRect();
      copy.style.width = `${a.width}px`;
      copy.style.height = `${a.height}px`;
      copy.style.transform = `translate(${a.left - b.left}px, ${a.top - b.top}px)`;
    };
    const requestAlign = () => {
      if (!disposed && copy && !alignFrame) alignFrame = requestAnimationFrame(align);
    };
    const reset = () => {
      controller?.destroy();
      controller = undefined;
      target.replaceChildren();
      copy = undefined;
      activeMode = 'solid';
      if (!disposed) setMode('solid');
    };
    const failClosed = () => {
      failed = true;
      reset();
    };
    const measure = () => {
      resizeTimer = 0;
      if (disposed) return;
      if (!enabled || failed || queries.some(q => q.matches) || typeof ResizeObserver === 'undefined') {
        reset(); return;
      }
      const geometry = lensGeometry(target.offsetWidth, target.offsetHeight, radius, strength, window.devicePixelRatio);
      if (!geometry || geometry.refraction === 0) { reset(); return; }
      const artUrl = source?.complete && source.naturalWidth > 0
        ? localArtUrl(source.currentSrc || source.src, window.location.href) : null;
      const desiredMode: LiquidMode = engine === 'blink' ? 'svg-backdrop' : artUrl ? 'art-refraction' : 'solid';
      if (desiredMode === 'solid') { reset(); return; }
      if (!controller || activeMode !== desiredMode) {
        reset();
        activeMode = desiredMode;
        if (desiredMode === 'art-refraction' && source && artUrl) {
          copy = document.createElement('img');
          copy.src = artUrl;
          copy.alt = '';
          copy.setAttribute('aria-hidden', 'true');
          copy.draggable = false;
          copy.className = 'sd-liquid-material__art';
          const style = getComputedStyle(source);
          copy.style.objectFit = style.objectFit;
          copy.style.objectPosition = style.objectPosition;
          target.append(copy);
          align();
        }
        try {
          controller = createGlass(target, { ...geometry, mode: desiredMode === 'svg-backdrop' ? 'backdrop' : 'content',
            chroma: 0, blur: 0.2, specular: 0.22, fit: false, clip: true,
            onReady: () => { if (!disposed && !failed) setMode(desiredMode); },
            onError: failClosed,
          });
        } catch { failClosed(); }
      } else {
        try { controller.update(geometry); } catch { failClosed(); }
      }
      requestAlign();
    };
    // Keep a cached map while panels resize; generate once after geometry settles.
    const resized = () => {
      clearTimeout(resizeTimer);
      resizeTimer = window.setTimeout(measure, 120);
      requestAlign();
    };
    const preferenceChanged = () => {
      if (queries.some(q => q.matches)) reset();
      else measure();
    };
    queries.forEach(q => q.addEventListener?.('change', preferenceChanged));
    if (typeof ResizeObserver !== 'undefined') {
      observer = new ResizeObserver(resized);
      observer.observe(target);
      if (source) observer.observe(source);
    }
    window.addEventListener('scroll', requestAlign, { capture: true, passive: true });
    window.addEventListener('resize', resized, { passive: true });
    source?.addEventListener('load', measure);
    source?.addEventListener('error', failClosed);
    measure();
    return () => {
      disposed = true;
      clearTimeout(resizeTimer);
      if (alignFrame) cancelAnimationFrame(alignFrame);
      observer?.disconnect();
      queries.forEach(q => q.removeEventListener?.('change', preferenceChanged));
      window.removeEventListener('scroll', requestAlign, true);
      window.removeEventListener('resize', resized);
      source?.removeEventListener('load', measure);
      source?.removeEventListener('error', failClosed);
      reset();
    };
  }, [variant, radius, strength, enabled, artRef]);

  return <div ref={layerRef} aria-hidden="true" className={`sd-liquid-material ${className}`.trim()}
    data-liquid-variant={variant} data-liquid-mode={mode} data-liquid-ready={mode !== 'solid'}
    style={{ '--liquid-radius': `${Number.isFinite(radius) ? Math.max(0, radius) : 20}px` } as CSSProperties}>
    <div ref={lensRef} className="sd-liquid-material__lens" />
    <div className="sd-liquid-material__finish" />
  </div>;
}

export type LiquidSurfaceProps = HTMLAttributes<HTMLDivElement> & Omit<LiquidMaterialProps, 'className'>;

/** Convenience wrapper; content receives a separate clear foreground, never an optical filter. */
export function LiquidSurface({ children, className = '', variant, radius, strength, enabled, artRef, style, ...props }: LiquidSurfaceProps) {
  const corner = radius ?? RADII[variant];
  return <div {...props} className={`sd-liquid-host sd-liquid-surface ${className}`.trim()}
    style={{ '--liquid-radius': `${Number.isFinite(corner) ? Math.max(0, corner) : 20}px`, ...style } as CSSProperties}>
    <LiquidMaterial variant={variant} radius={radius} strength={strength} enabled={enabled} artRef={artRef} />
    <div className="sd-liquid-surface__content">{children}</div>
  </div>;
}

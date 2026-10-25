import { createRef } from 'react';
import { act, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LiquidMaterial, LiquidSurface } from './LiquidMaterial';
import { lensGeometry, localArtUrl, MAX_MAP_EDGE } from './geometry';

const optics = vi.hoisted(() => ({ engine: 'blink', create: vi.fn(), destroy: vi.fn(), update: vi.fn() }));
vi.mock('./vendor/liquid-glass.js', () => ({
  get engine() { return optics.engine; },
  createGlass: optics.create,
}));

describe('bounded decorative optical material', () => {
  beforeEach(() => {
    optics.engine = 'blink';
    optics.create.mockReset(); optics.destroy.mockReset(); optics.update.mockReset();
    optics.create.mockImplementation((_target, options) => {
      options.onReady();
      return { destroy: optics.destroy, update: optics.update };
    });
    vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} });
    vi.spyOn(HTMLElement.prototype, 'offsetWidth', 'get').mockReturnValue(400);
    vi.spyOn(HTMLElement.prototype, 'offsetHeight', 'get').mockReturnValue(80);
  });
  afterEach(() => { vi.restoreAllMocks(); });

  it('bounds texture allocation independently of high DPR and rejects a canvas-sized filter', () => {
    const geometry = lensGeometry(1_200, 200, 50, 9, 4)!;
    expect(Math.round(geometry.width * geometry.mapScale)).toBeLessThanOrEqual(MAX_MAP_EDGE);
    expect(Math.round(geometry.height * geometry.mapScale)).toBeLessThanOrEqual(MAX_MAP_EDGE);
    expect(lensGeometry(1_600, 1_000, 20, 8, 2)).toBeNull();
    expect(lensGeometry(NaN, 80, 20, 8, 1)).toBeNull();
    expect(lensGeometry(100, 0, 20, 8, 1)).toBeNull();
    expect(lensGeometry(20, 10, Infinity, NaN, Infinity)).toMatchObject({ radius: 5, refraction: 8 });
  });

  it('never accepts a remote, executable or credential-bearing image URL', () => {
    const base = 'https://workspace.example/work';
    expect(localArtUrl('/art/grain.svg', base)).toBe('https://workspace.example/art/grain.svg');
    for (const src of ['https://other.example/art.svg', 'data:image/svg+xml,<svg/>', 'javascript:alert(1)',
      'https://user:secret@workspace.example/art.svg', '']) expect(localArtUrl(src, base)).toBeNull();
  });

  it('filters only the decoration and disposes the optical controller', () => {
    const { container, unmount } = render(<LiquidSurface variant="toolbar"><button>Open evidence</button></LiquidSurface>);
    expect(container.querySelector('.sd-liquid-material')).toHaveAttribute('aria-hidden', 'true');
    expect(container.querySelector('.sd-liquid-material')).toHaveAttribute('data-liquid-mode', 'svg-backdrop');
    expect(optics.create.mock.calls[0]?.[0]).toBe(container.querySelector('.sd-liquid-material__lens'));
    expect(container.querySelector('.sd-liquid-material__lens')).not.toContainElement(screen.getByRole('button'));
    screen.getByRole('button').focus();
    expect(screen.getByRole('button')).toHaveFocus();
    unmount();
    expect(optics.destroy).toHaveBeenCalledOnce();
  });

  it('switches to solid immediately when system transparency is reduced', () => {
    let change: (() => void) | undefined;
    const transparency = { matches: false, addEventListener: (_: string, listener: () => void) => { change = listener; },
      removeEventListener: vi.fn() };
    vi.mocked(window.matchMedia).mockImplementation(query => (query.includes('transparency') ? transparency
      : { ...transparency, addEventListener: vi.fn() }) as unknown as MediaQueryList);
    const { container } = render(<LiquidMaterial variant="navigation" />);
    expect(optics.create).toHaveBeenCalledOnce();
    act(() => { transparency.matches = true; change?.(); });
    expect(container.firstChild).toHaveAttribute('data-liquid-mode', 'solid');
    expect(optics.destroy).toHaveBeenCalledOnce();
  });

  it('uses solid on non-Chromium without an explicit owned art source', () => {
    optics.engine = 'webkit';
    const { container } = render(<LiquidMaterial variant="dialog" />);
    expect(container.firstChild).toHaveAttribute('data-liquid-mode', 'solid');
    expect(optics.create).not.toHaveBeenCalled();
  });

  it('copies only the explicitly selected image and reports a content-refraction path', () => {
    optics.engine = 'webkit';
    vi.spyOn(HTMLImageElement.prototype, 'complete', 'get').mockReturnValue(true);
    vi.spyOn(HTMLImageElement.prototype, 'naturalWidth', 'get').mockReturnValue(800);
    const artRef = createRef<HTMLImageElement>();
    const { container } = render(<><img ref={artRef} src="/art/local.svg" alt="" />
      <LiquidSurface variant="inspector" artRef={artRef}><p>Private business text</p></LiquidSurface></>);
    expect(container.querySelector('.sd-liquid-material')).toHaveAttribute('data-liquid-mode', 'art-refraction');
    expect(container.querySelector('.sd-liquid-material__lens')?.children).toHaveLength(1);
    expect(container.querySelector('.sd-liquid-material__art')).toHaveAttribute('aria-hidden', 'true');
    expect(container.querySelector('.sd-liquid-material__lens')).not.toHaveTextContent('Private business text');
    expect(optics.create.mock.calls[0]?.[1]).toMatchObject({ mode: 'content', chroma: 0 });
  });

  it('does not claim art refraction for a missing or unloaded image', () => {
    optics.engine = 'gecko';
    const artRef = createRef<HTMLImageElement>();
    const { container } = render(<><img ref={artRef} src="/art/not-ready.svg" alt="" />
      <LiquidMaterial variant="navigation" artRef={artRef} /></>);
    expect(container.querySelector('.sd-liquid-material')).toHaveAttribute('data-liquid-mode', 'solid');
    expect(optics.create).not.toHaveBeenCalled();
  });
});

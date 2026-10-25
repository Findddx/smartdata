export const engine: 'blink' | 'webkit' | 'gecko' | 'unknown';
export interface GlassOptions {
  mode?: 'content' | 'backdrop';
  width?: number;
  height?: number;
  radius?: number;
  refraction?: number;
  mapScale?: number;
  chroma?: number;
  blur?: number;
  specular?: number;
  fit?: boolean;
  clip?: boolean;
  onReady?: () => void;
  onError?: (error: unknown) => void;
}
export interface GlassController {
  update(options?: GlassOptions): void;
  flush(): void;
  destroy(): void;
}
export function createGlass(target: HTMLElement, options?: GlassOptions): GlassController;

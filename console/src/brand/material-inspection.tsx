/** Engineering fixture: known local artwork and a ruler, never business DOM. */
import { useRef } from "react";
import { createRoot } from "react-dom/client";
import { LiquidSurface } from "../materials/LiquidMaterial";

function MaterialInspection({ strength, ruler }: { strength: number; ruler: boolean }) {
  const artRef = useRef<HTMLImageElement>(null);
  return (
    <div
      data-material-inspection="scene"
      style={{ position: "relative", width: 640, height: 360, overflow: "hidden", background: "#e9eeed", color: "#172e3d", fontFamily: "system-ui, sans-serif" }}
    >
      <img ref={artRef} src={ruler ? "/brand/optical-ruler.svg" : "/art/observation-study-v1.webp"} alt="" style={{ position: "absolute", inset: 0, width: "100%", height: "100%", objectFit: "cover", opacity: 0.75 }} />
      {!ruler && <div aria-hidden="true" style={{ position: "absolute", inset: 0, backgroundImage: "repeating-linear-gradient(90deg, transparent 0 19px, #31556a 19px 21px, transparent 21px 39px, #b76450 39px 41px), repeating-linear-gradient(0deg, transparent 0 39px, #31556a 39px 41px)", opacity: 0.6 }} />}
      <LiquidSurface
        variant="toolbar"
        radius={28}
        strength={strength}
        artRef={artRef}
        style={{ position: "absolute", left: 126, top: 100, width: 388, height: 150, borderRadius: 28, padding: 24, boxSizing: "border-box" }}
      >
        <div data-material-inspection="foreground" style={{ position: "relative", zIndex: 1, color: "#182b36", fontSize: 16, lineHeight: 1.5 }}>
          <strong>关系与证据</strong>
          <p style={{ margin: "8px 0 0", fontSize: 13 }}>仅检查装饰背景的折射，文字与控件保持原位。</p>
          <span style={{ fontSize: 12 }}>本地静态艺术图 · 已知网格</span>
        </div>
      </LiquidSurface>
    </div>
  );
}

/** Mounted by the browser inspection harness; excluded from the product entry. */
export function mountMaterialInspection(host: HTMLElement, options: { ruler?: boolean } = {}) {
  const root = createRoot(host);
  const render = (strength: number) => root.render(<MaterialInspection strength={strength} ruler={options.ruler ?? false} />);
  render(1);
  return {
    setStrength(strength: number) {
      if (!Number.isFinite(strength) || strength < 0 || strength > 16) throw new RangeError("Inspection strength must be between 0 and 16");
      render(strength);
    },
    destroy() { root.unmount(); },
  };
}

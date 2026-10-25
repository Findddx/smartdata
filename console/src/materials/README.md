# 小范围液态玻璃材质

这一层只装饰导航、工具栏、上下文面板与对话框。文字、控件、数据图和业务节点始终保留在原来的 DOM 中，不参与折射，也不截图。透明度与颜色可用 CSS 变量调整，不携带业务状态含义。

```tsx
import { LiquidMaterial, LiquidSurface } from './materials';

<aside className="sd-liquid-host">
  <LiquidMaterial variant="inspector" radius={20} strength={8} />
  <div style={{ position: 'relative', zIndex: 1 }}>清晰的业务内容</div>
</aside>
```

父容器要有明确尺寸与圆角，`position: relative; isolation: isolate`。材料自行裁切；弹出菜单放在独立 portal，避免父容器裁切菜单。`LiquidSurface` 是带独立正文层的可选 `div` 包装器。若外部 CSS 抬升宿主的直接子元素，必须排除 `.sd-liquid-material`，不能对它添加交互事件。

`LiquidMaterial` 参数：`variant` 为 `navigation | toolbar | inspector | dialog`，另有 `radius`、`strength`（0–16 CSS 像素）、`className`、`enabled` 和可选 `artRef: RefObject<HTMLImageElement | null>`。`enabled=false` 与强度 0 均为实体表面。

实际路径可从装饰层的 `data-liquid-mode` 检查：

- `svg-backdrop`：Chromium 对背景使用 SVG 位移贴图，边缘会真实移动背景像素。
- `art-refraction`：其他引擎只对 `artRef` 指向的同源静态图像生成对齐副本，在副本上应用 SVG 内容滤镜；这不表示任意业务页面的完整背景折射。
- `solid`：尚未完成贴图、无已知图像、不支持所需能力、几何超预算、渲染失败或系统要求减少透明度/强制颜色时，显示清晰实体表面。

`artRef` 只允许调用方明确选择的自有静态本地图像（包括同源部署的 Vite 资源），图片成功加载前保持实体表面。不收集页面其他图片，不复制 HTML 子树，不支持远程、data、blob 或携带账号密码的资源地址。不启动额外图片下载服务、截图工具或外部 CDN。

贴图长边最多 320 像素，DPR 上限 2。表面面积超过 900,000 CSS 平方像素或长边超过 1,800 CSS 像素时使用实体表面，不能用来覆盖主业务画布。尺寸稳定 120 ms 后更新；缓存来自固定上游的 48 项 LRU；没有持续全局 RAF 循环。移动位置不重算光学贴图。卸载时移除滤镜、观察器、定时器和事件监听。高对比度与减少透明度立即停止折射；减少动态效果不影响静态光学，交互动画由上层遵循系统设置。

CSS 可调整 `--liquid-solid`（默认冷矿白 `#f3f6f6`）与 `--liquid-tint`（默认中性浅色透明罩）。这些变量不能替代不同内容下的对比度和真实设备检查。当前不声称任何设备的帧率目标已经通过。

## 来源与许可

仅采用 [gentpan/liquidglass](https://github.com/gentpan/liquidglass) 的 [lib/liquid-glass.js](https://github.com/gentpan/liquidglass/blob/068ad226f53e1ca62d857f78bdc32f58f3c5fce7/lib/liquid-glass.js)，固定提交 `068ad226f53e1ca62d857f78bdc32f58f3c5fce7`，2026-09-19。原文件 SHA-256：`92a62e5b80cfa7e03912b13bdc53aff1c0ae367a5f296de1327e0f62f0579210`。完整 MIT 许可保存在 `vendor/LICENSE.gentpan`，Copyright (c) 2026 gentpan。未采用其 Web Components、WebGL 引擎、网站、壁纸、图标或安装技能脚本。

本地轻微改动：Canvas 上下文不可用时明确失败；渲染完成与错误回调；错误/销毁后不再调度。折射数学和滤镜链不变。封装、预算、无障碍降级与测试由 SmartData 编写；无需 npm 新依赖。原始思路参考 [Aave 官方工程说明](https://aave.com/design/building-glass-for-the-web)，这不是 Aave 官方组件。

# SmartData 界面参考与取舍

核对日期：2026-10-25。参考代码按版本保存在被忽略的 `.research-cache/`，大型仓库只展开相关源码。研究参考与运行代码分别管理，固定版本、许可证和采用边界见根目录 `research-lock.yaml`；本轮采用的光学小核另有完整许可与来源说明。

## 最新反馈：紧凑岛屿与真实折射

用户进一步拒绝了硬分栏与按层级划满屏幕的竖列。本轮重新核对
[VS Code 10月15日官方设计说明](https://code.visualstudio.com/blogs/2026/10/15/modern-ui)及
[固定源码](https://github.com/microsoft/vscode/tree/4ef6bab74bc303c46e8311904c6400b1a31ce761/src/vs/workbench/contrib/modernUI)。
采用统一表面、紧凑间距与清楚的焦点规则，实际实现约8px岛间距；VS Code自己的磨砂
浮层不是本项目液态折射的证明，后者另做源代码和真实像素检查。

| 光学候选 | 本次核对版本 | 取舍 |
|---|---|---|
| [rdev](https://github.com/rdev/liquid-glass-react/tree/ac48eab18d1f7f444ae30002d240cae29c863a21) | npm1.1.1，HEAD仍为2025-06-13；MIT | 热门但并非近期更新；主要是Canvas生成位移图，Safari/Firefox缺位移，不作默认运行时 |
| [simple-liquid-glass](https://github.com/lucaperullo/simple-liquid-glass/tree/53e1e98ec18a42d01971bd08fc05175772a91852) | npm5.4.0；MIT；HEAD与发布源码分开核对 | React19备选，但HTML背景路径包含截图与缓存，不用于整个业务页面 |
| [Glass-HQ](https://github.com/Glass-HQ/liquid-glass/tree/089c396ccb6f46233973a8c7758fad90e1713c97) | npm/tag0.0.2；MIT | 图形加速与组件方案较新，需更多依赖与设备审查，留作后续实验 |
| [gentpan核心](https://github.com/gentpan/liquidglass/tree/068ad226f53e1ca62d857f78bdc32f58f3c5fce7) | 固定提交068ad226；无npm/tag；MIT | 采用其零依赖光学单文件，另加预算、生命周期与可替换适配层，不采用组件、壁纸、安装脚本 |

最终通过了三种真实浏览器内核的[像素对照](../verification/2026-10-25-material-optics.json)，
没有用星数、配置名称或作者自述冒充效果验收。原理另对照
[Aave官方工程说明](https://aave.com/design/building-glass-for-the-web)；采用范围、轻微修改
及完整许可见[材料层说明](../../console/src/materials/README.md)。

画布交互重新参考[Flowith官方节点说明](https://flowith.io/docs/en/canvas/node-interaction/)，
以及[Histography](https://histography.io/)和[The Refugee Project作者说明](https://www.hyperakt.com/work/the-refugee-project)。
借鉴聚焦、全局定位、按需细节与来源解释，不复制品牌或营销页面。其奖项分别见
[Awwwards官方记录](https://www.awwwards.com/sites/histography)与
[Information is Beautiful官方记录](https://www.informationisbeautifulawards.com/showcase/475-the-refugee-project)，
不是本项目获得奖项。D3与React Flow也核对过；本轮保留已有Dagre排布器，以真实依赖
排列节点，未因外观改动先引入多套视角控制器。

## 首轮参考与保留的采用边界

上一版界面已被用户明确指出审美和交互不理想。此前功能测试与以下设计原则不代表
用户已经接受视觉效果。本轮新增研究
[ZCode 固定版本](https://github.com/zai-org/ZCode/tree/3c5b227649371507ddc4eb46ac5133afe6db1cab)，
核对其 Apache-2.0 许可，并重新阅读上方固定版本的 open-claude-ui。

新的工作面以数据血缘为核心：可收起的轻导航、紧凑工具栏、清楚的语义层级与右侧
上下文面板。大标题与装饰性的流程卡让位于实际工作。图谱颜色、图标、节点形状、
箭头与线型分别承担信息，实际观察、人工声明和未来计划不能混淆。

项目标识与类型图标单独设计，不使用 Claude 花形或 ZCode 的品牌。艺术图像只用于
合适的欢迎与空态，不干扰工作图和证据文字。内置 imagegen 首次返回 403，原路径
稍后重试已成功生成两张艺术贴图；本轮未切换备用供应商。手绘 SVG 与实际生图资产
分别记录来源，不相互冒充。

新增轻量布局库 `@dagrejs/dagre 3.1.3` 与 `@dagrejs/graphlib 4.0.6` 为 MIT，
精确锁定。使用 SVG 与普通网页节点承载交互；只有经过实际浏览器检查、用户验收
后，才整理成新的设计技能。参考缓存此前不清理。

## 界面要帮助用户完成什么

负责人打开产品，应能回答四个问题：公司接入了哪些系统；助手发现了什么；哪一步需要我决定；改完后有何证据证明有效。工作台围绕这些问题组织，而不是用聊天记录或装饰卡片代替业务状态。

设计主线是“安静的专业工作室”：温暖的纸色工作区、深炭色导航、少量朱砂或铜色强调。数字、表格和证据保持清晰。玻璃、光影、轻微景深只出现在浮层和关系图等需要表达空间层次的地方。主操作、未知项和危险状态各有固定含义，不能仅靠颜色区分。

## 逐项参考

| 原始参考 | 核对结果 | SmartData 采用什么 | 明确不采用什么 |
| --- | --- | --- | --- |
| [open-claude-ui](https://github.com/Caho1/open-claude-ui/tree/c1f923ae0051a871941b98a0e2e3183a545e6a85) | MIT；核对了仓库 README、源码入口和资产声明 | 持续可见的导航、聚焦的主工作区、输入法合成保护，以及可展开查看的工具授权状态 | Claude 品牌、专有字体与图像、照搬组件；其作者关于“还原官方实现”的说法不作为本项目已验证事实 |
| [liquid-glass-react](https://github.com/rdev/liquid-glass-react/tree/ac48eab18d1f7f444ae30002d240cae29c863a21) | MIT；源码使用 SVG 位移与可选 shader（生成折射效果的小程序）；README 明示部分浏览器效果不完整 | 浮层边缘的细光、轻微磨砂、材料层次；基础操作始终可用的普通表面 | 扭曲证据文字、满屏玻璃、把特定浏览器效果作为可用性前提 |
| [Remotion](https://github.com/remotion-dev/remotion/tree/7830ef2f5553936bd43fc503fc030a150acba491) | 自定义许可，不能按 MIT/Apache 处理；已核对许可及时间线源码 | 将教学过程写成可复演的步骤：接入、发现问题、核查证据、批准、验证；字幕和演示数据独立维护 | 直接放进 Apache-2.0 核心产品、默认假定任何公司可免费商用、为首页装饰引入视频渲染运行时 |
| [Motion](https://github.com/motiondivision/motion/tree/fa2079f101353f1fa1c9463f5ba9709a0ca3d3bf) / [官方文档](https://motion.dev/docs/react) | 核心库 MIT；已核对 MotionConfig 与减少动态效果相关源码 | 随真实状态变化的过渡、保留空间位置的展开、按需加载；系统要求减少动画时提供静态替代 | 长时间入场、阻塞审批的动画、无来源的“AI 工作中”假进度、未经另行许可的 Motion Plus 资产 |

Remotion 的商业资格取决于其[具体许可条款](https://github.com/remotion-dev/remotion/blob/7830ef2f5553936bd43fc503fc030a150acba491/LICENSE.md)。本轮只借鉴教学叙事，不复制它的代码，不引入其运行时。

## 如何把参考转成产品细节

1. 让业务变化可见。系统扫描逐个呈现“等待授权、读取中、已完成、失败”，并显示能看到与不能看到的范围。助手不能用一条流光掩盖未知进度。
2. 让协作有交付物。运维、分析、决策助手分别交付问题记录、带来源的分析、可批准的计划。打开同一问题能看到它们共享的证据，而不是三套独立聊天。
3. 让动效解释关系。选择一个源系统时，高亮它影响的指标和问题；展开证据时保留上下文；完成审批后显示真实状态更新。关系图可以有轻微视差，表格和审批按钮不跟随鼠标漂移。
4. 让材质有边界。细线、微阴影和表面明度建立层次；一处短暂光影用于强调焦点。大面积模糊与色散会降低阅读质量，不能作为高级感的主要来源。
5. 保留原生操作习惯。滚动、文字选择、键盘焦点、复制和浏览器返回都应自然工作。不劫持整页滚动；高风险动作不使用磁吸或位置变化效果。

## 怎么验收，而不是凭形容词验收

- 在常见桌面、窄屏和手机宽度下完成同一条业务流程；文字不溢出，主操作可找到，证据时间与来源不丢失。
- 全键盘操作可打开、关闭和恢复焦点；切换系统的“减少动态效果”设置后，视差与大范围移动停用，业务状态仍可辨认。
- 记录浏览器性能，检查交互是否迟缓、动画是否持续占用资源。昂贵效果仅在可见时启用，低能力设备显示普通表面。Motion 的[无障碍说明](https://motion.dev/docs/react-accessibility)和[按需加载说明](https://motion.dev/docs/react-reduce-bundle-size)提供实现路径。
- 区分真实系统、仿真样例、未连接和失败；样例数字不得显示成客户当前结果。审批前后和来源变化后，都要重新检查界面含义是否正确。
- 以“用户能否理解问题并完成工作”评审视觉表现。Awwwards 级别是质量追求，不是已经获得的奖项或可以用测试自动证明的称号。

## 本机资料与公开仓库

公开仓库收录产品源码、公开说明、许可证和去标识的示例。`resume/` 中的原始思考、聊天及文档留在本机；`.research-cache/`、运行日志、截图、凭据和持久化业务数据也不提交。本轮 `.gitignore` 将根目录 `/data/` 作为本地数据目录，保留 `console/src/data/` 这类产品代码目录，防止新用户下载后缺少必要文件。

本轮仅光学小核进入运行代码，其MIT全文同时保存在源码与构建后的静态分发目录。
其他参考不因此成为运行依赖；若以后复用更多代码、素材或商业功能，仍按具体条款逐项核对。

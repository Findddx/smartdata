# 真实浏览器验收

这里检查实际浏览器与已启动 API 的交互，不拦截网络返回。先按根目录说明启动
本机服务，明确加载“电商演示”，保留三份合成来源。脚本会检查这个前提，拒绝
在空工作区或用户来源上套用演示断言，不清空、不修改来源或任务。

在 `console` 目录运行；需要 CLI 所需的 Chromium，缺少时按其安装提示准备：

```powershell
npx --yes --package=@playwright/cli@0.1.22 playwright-cli -s=smartdata-acceptance open http://127.0.0.1:5173
npx --yes --package=@playwright/cli@0.1.22 playwright-cli -s=smartdata-acceptance run-code --filename=e2e/demo-workspace.browser.js
npx --yes --package=@playwright/cli@0.1.22 playwright-cli -s=smartdata-acceptance close
```

若改了控制台端口，首条命令使用自己的地址。脚本从当前浏览器页面读取本机地址，
只接受 `127.0.0.1`，不会向远程站点发送演示身份。它切换三套主题，打开实际证据，
检查弹窗和手机导航的键盘焦点、搜索与团队切换，验证减少动画偏好、窄屏无横向
溢出及中文文本报告导出。最后恢复矿白主题；截图和下载留在被 Git 忽略的目录。

2026-10-25 在最新本地服务包和此前保存的演示工作区实际通过；这不是全站无障碍
认证，也不代替真实用户测试。继续扩展时应增加真实连接器及浏览器差异矩阵。

## 业务约定的真实交互

`business-brief.browser.js` 要求三份显式合成演示来源，且尚未保存业务约定。
它会真实保存一份标明为演练的背景、目标、范围和指标草稿，再以本地管理员确认
定义。它核对任务确实关联目标、跨页面草稿保留、版本记录焦点、手机可到达口径
以及模型出口说明。它会改变这份演练的业务约定，不触碰真实来源；不要在已有
业务约定或未保存草稿的浏览器上运行。

同一个 CLI 会话可继续运行 `business-brief-review.browser.js`，只读检查这份专用
演练：三套主题、手机布局、审计只读状态、指标编辑器焦点，并确认工作区版本没有
变化。两份脚本的调用方式与上方相同，只需替换 `--filename`。新截图均在
`output/playwright`，公开文档只选用已经审查的合成演练截图。

## 分层图谱与桌面交互

`lineage-workspace.browser.js` 要求真实的图谱接口已经启动，至少有一张本轮可见、
成功盘点的演示数据表。它不拦截或伪造接口响应，只读取元数据，核对节点的真实层级与类型、
字段分页、权限说明、覆盖不全提示、画布键盘操作、节点列表、三套主题和手机定位。
最后核对工作区版本没有变化。脚本就绪与实际运行通过分别记录，运行结果以本轮
阶段验收记录为准。

`lineage-islands.browser.js` 继续检查新岛屿布局、来源结构展开、真实连线、手机工具栏
点击命中，以及连续触屏拖动后缩放。`direction-preview.browser.js` 检查选中真实边时
仅播放两轮方向示意，系统改为减少动画后立即卸载，前后工作区版本不变。
两者都要求明确合成演示，按上方方式替换文件名即可，不拦截网络返回。

## 液态折射是否真的发生

`material-optics.browser.js` 在 Vite 开发预览上挂载一个已知网格与自有本地图像的小样，
不读写业务数据。它只改变折射强度，对比原生截图的边缘与中心像素，并检查正文没有
移动或被套入滤镜；退出时清理小样。脚本从当前会话识别浏览器，Chromium 检查背景
折射，Firefox/WebKit 检查指定本地图像的内容折射，不假设它们能折射任意业务 DOM。

```powershell
npx --yes --package=@playwright/cli@0.1.22 playwright-cli install-browser firefox
npx --yes --package=@playwright/cli@0.1.22 playwright-cli -s=material-firefox open http://127.0.0.1:5173 --browser=firefox
npx --yes --package=@playwright/cli@0.1.22 playwright-cli -s=material-firefox run-code --filename=e2e/material-optics.browser.js
npx --yes --package=@playwright/cli@0.1.22 playwright-cli -s=material-firefox close
```

WebKit 将上方 `firefox` 换成 `webkit`；默认 Chromium 会话直接运行同一个脚本。
它依赖 Vite 的源码检查入口，不用于已打包的静态站点。Windows 上三类真实浏览器
内核均已通过，精确版本与像素结果见[固定记录](../../docs/verification/2026-10-25-material-optics.json)。
这不证明真实 iPhone、全站无障碍或整页帧率已验收。


`lineage-islands.browser.js` 只读核对岛屿版：显式展开来源结构、连线确实来自接口、手机工具栏实际可点、触屏拖动和缩放按钮。`lineage-capture.browser.js` 等页面进入稳定状态、通知消失后，保存矿白、石墨、朱砂三组桌面样图及390像素触屏图；截图关闭动画，但不替换接口或图谱内容。网络失败会留下真实失败图，不能把脚本就绪视作验收通过。

`lineage-edit.browser.js` 会改变专用的合成演练：真实新增、改名、删除映射，并由第二个客户端制造版本冲突，检查草稿保留后显式重载。最后恢复原始名称、字段和依赖，保留实际版本与审计历史；不应在真实业务工作区运行。

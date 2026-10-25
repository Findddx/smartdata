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
溢出及中文文本报告导出。最后恢复亚麻主题；截图和下载留在被 Git 忽略的目录。

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

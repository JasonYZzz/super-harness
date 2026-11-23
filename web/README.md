# AgentScope Web UI

本目录以官方 `examples/web_ui/frontend` 为基线，聊天、任务和审批接入同源 Spring Boot 服务。打开 `http://127.0.0.1:8080/` 首次进入设置页。输入本地调试账号 `admin/admin`，或切换至访问令牌并输入项目根目录下 `./scripts/dev-token.sh` 签发的令牌，连接后进入聊天页或原深链接。

所有交互式 Agent 使用 `/chat` 页面和 `useAguiThread` 发送消息、重连和答复审批。页面通过 `useHarnessChat` 将同一份消息转成 AgentScope 组件使用的格式。接口、事件和重试规则见[交互式 Agent 的前端协议](../docs/ag-ui.md)。

修改前端时，可在本目录运行 `npm ci` 和 `npm run dev`，开发代理指向本机 Java API 的 8080 端口。发布时 Maven 构建静态资源并打入 API jar。浏览器测试使用 `npm test`，可通过 `SH_API_URL` 指定测试服务。[前后端接口说明](../docs/upstream/WEB-UI.md)

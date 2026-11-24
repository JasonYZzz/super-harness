# AgentScope Web UI

本目录以官方 `examples/web_ui/frontend` 为基线，聊天、任务和审批接入同源 Spring Boot 服务。打开 `http://127.0.0.1:8080/` 首次进入设置页。输入本地调试账号 `admin/admin`，或切换至访问令牌并输入项目根目录下 `./scripts/dev-token.sh` 签发的令牌，连接后进入聊天页或原深链接。

所有交互式 Agent 使用 `/chat` 页面和 `useAguiThread` 发送消息、重连和答复审批。页面通过 `useHarnessChat` 将同一份消息转成 AgentScope 组件使用的格式。接口、事件和重试规则见[交互式 Agent 的前端协议](../docs/ag-ui.md)。

修改前端时，可在本目录运行 `npm ci` 和 `npm run dev`，开发代理指向本机 Java API 的 8080 端口。发布时 Maven 构建静态资源并打入 API jar。浏览器测试使用 `npm test`，可通过 `SH_API_URL` 指定测试服务。[前后端接口说明](../docs/upstream/WEB-UI.md)

## 上传与使用自定义 Skill

租户 OWNER 或 EDITOR 在技能中心点击「上传自定义技能」，选择 `SKILL.md` 或 ZIP，填写版本并发布。ZIP 中的 `SKILL.md` 可以直接放在根目录，也可以放在唯一的顶层技能目录。文档需要 YAML 元数据 `name`、`description` 和非空正文。ZIP 最大 1 MiB，正文最多 8000 字符，`references/`、`scripts/`、`assets/` 下共可附带 16 个 UTF-8 文本文件，每个最多 65536 字符。

创建或编辑 Agent 时，在「固定技能版本」中勾选本次发布的版本，再创建会话。每个修订最多选择四种技能，同名技能只能选一个版本。已有会话继续使用创建时固定的修订。

## 会话资源和定时运行

在聊天右上角打开面板菜单。Skill 和 MCP 展示创建该会话时固定的版本。在知识面板选择个人文档库后，后续消息可调用关键词检索工具。知识库页面接收单个最多 65,536 个字符的 UTF-8 文本文件，显示匹配文档和片段。

日程页可选择智能体、模型、运行频率和时区。任务说明作为发送给智能体的消息，说明留空时使用任务名称。打开日程详情可暂停、恢复、删除或进入已生成的会话。

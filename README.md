# campus-reply · 校园集市自动回复助手（DeepDeep Lab 插件）

为 [DeepDeep Lab](https://github.com/sadami3066/DeepDeep) 打造的 `.ddp` 插件：按策略扫描**校园集市（赞噢）**帖子与评论，自动生成并发布回复；三路来源库增强（**中大资料上游**、**逸问**、帖主公开历史），支持精选订阅分发（webhook / 邮箱 / 微信群机器人 / QQ）与定时删除。

> 发布者 keyId：`ed25519:98673f6bfa0cd5f3cbdbdb…d9a22`。覆盖升级需原签名密钥（**不在本仓库**）。

## 功能

- **自动回复**：定时扫描（默认 15 分钟）→ 板块/关键词/提及/历史触发词过滤 → `deepseek-flash` 等 OpenAI 兼容模型生成 → 签名接口发布；发布门禁（频率、每日上限、AI 声明）与记录留痕
- **逸问来源库**（可选，需校园网/VPN）：真实 SSE 接口问答，作为回复的引用上下文
- **中大资料上游**（可选）：真题/课程资料两源检索，考试类帖子自动挂参考
- **精选订阅分发**：关键词/板块/热度过滤 → webhook 等渠道推送
- **定时删除**：发布后 N 分钟自动删除回复

## 构建与验证

需要 JDK 17 + Android SDK 36。配置 `local.properties` 的 `sdk.dir`（或 `ANDROID_HOME`）后：

```powershell
.\gradlew.bat testDebugUnitTest verifyPlugin --max-workers=1
```

签名包输出于 `build/plugin/campus-reply.ddp`，经 DeepDeep Lab「导入插件」安装。

## 逸问接口速览

- 问：`POST {origin}/chat/api/ly-ai-agent/api/mainAgent/yiwen/modelQa/say`（SSE 逐 token 片段，无 SYSTEM 终帧，需累积解析）
- 续票+换身份：`POST {origin}/chat/tryLoginUserInfo`（裸票 + form 空体）→ `{data:{userId,userName,orgId,tokenId}}`
- 必带头：`loginUserId` / `loginUserName` / `loginUserOrgId` + 标准浏览器头
- 完整逆向见 **[yiwen-reverse](https://github.com/shuziyuxingxing-stack/yiwen-reverse)**（协议、签发、差分实验全记录）

## 校园集市接口速览

- 签名与会话机制、`errno` 语义见 **[zanao-reverse](https://github.com/shuziyuxingxing-stack/zanao-reverse)**

## 配置要点

- `模型 API 地址` / `模型名称`（如 `deepseek-flash`）——APIKey 为掩码框，**必须点「保存模型 Token」**才落盘
- `回复最大长度`：集市评论硬上限 **200 字**（服务端 `errno=10 评论内容长度非法`），代码层已 `coerceIn(20, 200)` 截断
- 逸问票（Authorization JWT）：活着靠 6h 心跳自动续；死票需浏览器重新登录抓取

## License

MIT

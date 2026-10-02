# 官方 QQ 回归与本地 Folia 验证

## 自动回归

前置：JDK25、Python3、Gradle 已缓存依赖。先构建实际插件：

```sh
./gradlew :folia:26.1.2:shadowJar --no-daemon
python3 tests/run-official-regressions.py
```

脚本在临时目录编译测试并使用构建出的插件 Jar，不复制实现、也不拿 SQL 脚本冒充 Java 验证。

- `OfficialBindingRegression.java`（15 项）：真实 Java + SQLite 身份安全，覆盖待验证分表、错误/过期/重放、并发抢占、数据库故障回滚、旧版归档与重启。
- `OfficialMediaRegression.java`（10 项）：本地 HTTP 契约，覆盖 prepare→PUT→finish→files 全链路、字节与校验和、预签名 PUT 不带机器人 Authorization、分片序号 0 或 1 两种真实格式、错误停止与非法输入拒绝。
- `CommandParityRegression.java`（16 项）：真实处理器/仓储/HTTP 适配，覆盖公开菜单、未验证权限拒绝、封禁解封、SQL 故障不报成功、数字游戏ID删除、邮件重置、传统 OneBot 表情保留、乱序回复隔离、重复投递、群哈希碰撞、@目标拒绝与缺目标用法提示。

所有 QQ、OpenID、Token 均为测试夹具。HTTP 只监听 127.0.0.1；SMTP 是明确的测试替身。不联网到腾讯、不登录真实 QQ、不修改生产数据库。

原有入口保留：`sh tests/run-official-binding-regression.sh`（等价于运行全部回归）。

## 实际 Folia 烟雾测试

以下两个测试源码仅供隔离测试服使用，**绝不能装进生产服**：

- `OfficialQQFoliaSmoke.java`：在本地 Folia 中调用真实的 OfficialQQBot → BotCommandHandler → BotStatusService，使用本地 HTTP 平台替身接收文件与消息。验证帮助菜单、实际生成 1500×700 PNG 状态卡并发送类型7、自定义触发词与在线人机列表。
- `ConfigAliasSmoke.java`：验证新版分组配置生成与读取。确认插件生成 `qq_bot` / `status-card` 结构、两通道 `card-cmd` 独立、`groups` 已移除，同时旧扁平键仍可读到新分组的值。

两者在测试服只监听环回地址、不复制生产数据，完成后主动关闭测试服。日志中的 PASS 区分“真实 Folia + 真实渲染”与“本地 HTTP 替身”，不是腾讯线上验收。

## 上线前仍需人工验证

1. 使用新生成的 AppSecret/邮箱授权码，勿提交仓库或输出到日志。
2. 在受控测试群验证帮助、PNG 状态卡显示、自定义触发词、在线人机。
3. 用独立测试账号验证白名单、管理员操作，以及 **@未验证成员 / @已验证成员 / 直接填 QQ 号** 三种写法的提示。
4. 验证 `重置密码` 邮件到达 QQ 邮箱，并用 `/changepassword` 改回。
5. 长时间 Token 刷新/自动重连、成员进退通知尚未适配，不宣称生产全功能等价。
6. 检查平台授予的图片分片上传与群聊权限及限流；若图片受限，文字降级应可见。

本轮只构建并运行 Folia 26.1.2。MySQL、其他 Minecraft 目标、腾讯图片端到端未包含在自动回归结果里。

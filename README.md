# LuoOS 客户端与 QQ 机器人插件

LuoOS 是一个 Folia 服务端综合性插件，集成了：

- **玩家认证系统**（登录/注册/修改密码，支持 AuthMe 数据迁移）
- **账号绑定系统**（星型拓扑 UUID 重映射，Netty 包层拦截）
- **内嵌 QQ 机器人**（OneBot v11 正向 WebSocket——白名单管理、服务器状态卡片、群管理）
- **统一数据库**（SQLite / MySQL 双支持，插件与 Bot 共享）

## 功能概览

### 玩家认证
- `/los login <密码>` — 登录
- `/los register <密码> <确认>` — 注册
- `/los changepassword <旧> <新>` — 修改密码
- 支持 AuthMe 密码迁移：读取 AuthMe 配置文件自动识别数据库后端
- 兼容多种密码哈希格式：SHA256 双重哈希、BCrypt、PBKDF2

### 账号绑定
- 星型拓扑：多个绑定账号 → 一个目标（不允许多级链式绑定）
- UUID 重映射在 Netty 级别实现，对下游插件透明
- 聊天 TUI / 箱子 GUI 双界面管理

### QQ 机器人（OneBot v11）
- `白名单 <游戏ID>` — 申请白名单（自动将 QQ 号绑定到 MC 账号）
- `删除 <ID>` — 删除自己的白名单
- `查询白名单` — 查看自己的白名单
- `查询白名单 @QQ` — 管理员查看指定 QQ 的白名单
- `服务器还活着吗` — 查询服务器状态卡片（图片）
- `封禁 @QQ [时长]` / `解禁 @QQ` — 管理员封禁/解封用户
- `help` / `帮助` / `菜单` — 显示命令帮助
- 支持表情回应：✅ 成功 / ❌ 失败 / 🚫 拒绝

### 数据库
- 默认使用 SQLite（即开即用）
- 支持 MySQL，可通过配置文件切换
- 数据库表在插件和 Bot 之间共享
- 支持从 AuthMe 插件迁移玩家密码

## 构建

```bash
./gradlew :folia:1.21.11:shadowJar --no-daemon
```

要求 JDK 21。

产物位置：`folia/versions/1.21.11/build/libs/luoos-folia-mc1.21.11-0.06.jar`

## 配置

参见 `folia/src/main/resources/config.yml`，主要配置项：

| 配置路径 | 说明 | 默认值 |
|---------|------|--------|
| `authentication.enabled` | 是否启用认证 | true |
| `enableAccountBinding` | 是否启用账号绑定 | true |
| `bindingStorageBackend` | 数据库后端 | sqlite |
| `bot.enabled` | 是否启用 QQ 机器人 | false |
| `bot.port` | Bot 监听端口 | 10100 |
| `bot.access_token` | OneBot 连接 Token | 空 |
| `bot.qq_groups` | 允许的 QQ 群号列表 | 空（允许所有） |
| `bot.status_trigger` | 状态查询触发词 | 服务器还活着吗 |

## 迁移 AuthMe 数据

1. 在控制台执行：`/los migrate-authme plugins/AuthMe`
2. 插件会自动读取 AuthMe 的 `config.yml`，识别数据库后端
3. 迁移完成后，玩家使用原 AuthMe 密码登录即可
4. 首次成功登录后，密码哈希会自动升级为原生 PBKDF2 格式

## 开源协议

MIT License

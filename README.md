# LuoOS v0.10 使用文档

LuoOS 是一款面向 Folia 服务器的综合管理插件，提供登录认证、账号绑定、QQ机器人、玩家统计排行榜、资源世界自动刷新等功能。项目作者：chara201x、qzgeek、CatXiaolan。

LuoOS 面向 Folia 服务器，提供认证、账号绑定、数据库白名单、QQ 机器人、玩家统计、资源世界和维护模式等功能。本文档按“安装 → 升级 → 配置 → 使用”组织，首次部署可直接从下方快速开始。

## 支持的 Minecraft 版本

| 版本范围 | Jar 文件 |
|---------|---------|
| 1.20 - 1.20.1 | `luoos-folia-mc1.20-1.20.1-0.10.jar` |
| 1.20.2 - 1.20.3 | `luoos-folia-mc1.20.2-1.20.3-0.10.jar` |
| 1.20.4 | `luoos-folia-mc1.20.4-0.10.jar` |
| 1.20.5 - 1.20.6 | `luoos-folia-mc1.20.5-1.20.6-0.10.jar` |
| 1.21 - 1.21.4 | `luoos-folia-mc1.21-1.21.4-0.10.jar` |
| 1.21.5 | `luoos-folia-mc1.21.5-0.10.jar` |
| 1.21.6 - 1.21.7 | `luoos-folia-mc1.21.6-1.21.7-0.10.jar` |
| 1.21.8 - 1.21.10 | `luoos-folia-mc1.21.8-1.21.10-0.10.jar` |
| 1.21.11 | `luoos-folia-mc1.21.11-0.10.jar` |
| 26.1 | `luoos-folia-mc26.1-0.10.jar` |
| 26.1.1 | `luoos-folia-mc26.1.1-0.10.jar` |
| 26.1.2 | `luoos-folia-mc26.1.2-0.10.jar` |

推荐启动命令（Folia 26.1+）：
```bash
java --add-modules=jdk.incubator.vector -jar lophine-server.jar --nogui
```

---

## 一、安装与升级

### 安装

1. 将对应版本的 jar 放入 `plugins/` 目录
2. 安装依赖插件：**PlaceholderAPI**（统计功能需要）、**Worlds 4.2.2**（资源世界功能需要）
3. 启动服务器，自动生成 `plugins/luoos/config.yml`

### 从旧版本升级

v0.10 保留并扩展了旧版本自动升级系统：
- 首次启动时自动检测旧版数据库（HEOS/LuoOS v0.07），自动迁移数据
- 自动为旧 `player_stats` 表添加新字段（entities_killed）
- 自动创建 `player_stats_daily` 日统计表
- 升级完成后创建 `.upgraded_v08` 标记文件，不会重复升级

无需手动操作，安装新版 jar 后直接重启即可。

### QQ 官方机器人接入

两种 QQ 通道互相独立，可同时启用，共用同一套命令、白名单与数据库：

- 私人机器人（OneBot）：`qq_bot.private-bot.enable`
- 官方机器人（QQ 开放平台）：`qq_bot.official-bot.enable`

两者都必须先打开总开关 `qq_bot.enable`。私聊机器人需要 QQ 框架用 WS 正向连接到本插件；官Q需要开放平台凭据与发件邮箱。

```yaml
qq_bot:
  enable: true
  private-bot:
    enable: true
    host: 0.0.0.0
    port: 35013
    access_token: "与框架一致"
    qq_groups: []
    card-cmd: ["服务器还活着吗", "状态", "服务器状态", "status", "state"]
  official-bot:
    enable: true
    app_id: "你的AppID"
    app_secret: "你的AppSecret"
    # 官Q状态卡片命令独立配置，可与传统机器人不同
    card-cmd: ["服务器还活着吗", "状态", "服务器状态", "status", "state"]
    code_digits: 6
    code_expire_minutes: 10
    smtp:
      host: "smtp.qq.com"
      port: 465
      username: "发件邮箱"
      password: "邮箱授权码"
      from: "发件邮箱"
      starttls: false
```

官Q不限制群聊：不再需要填写群 OpenID 列表。凭据只存服务器本地，切勿提交版本库。

在 QQ 机器人开放平台开启群聊消息事件与对应 intents。群里 @ 机器人发送 `绑定QQ <QQ号>`，验证码会发到 `<QQ号>@qq.com`；再 @ 机器人发送 `验证码 <验证码>` 完成绑定。只有验证成功后，OpenID 才会映射为命令处理器使用的 QQ 号。

验证码只保存 SHA-256 摘要，超时失效。SMTP 465 端口自动使用隐式 TLS，其他端口可按 `starttls` 强制 STARTTLS，均带网络超时。

**身份安全规则：**
- 邮件服务接受发信后，仅写 `qq_official_pending` 待验证记录；不会写正式绑定，也不会永久占用该 QQ。
- 正确且未过期的验证码经过校验后，事务内写入 `qq_official_bindings` 并消费待验证记录。验证码不可重复使用。
- 错码、过期、邮件失败、数据库失败均不授予身份；每次申请最多校验5次；同一 openid 或收件 QQ 发码冷却60秒。
- 已确认的身份不允许直接覆盖。重启不会把待验证申请变成已确认绑定。
- 旧版本错误写入正式表、且 `code_hash` 非空的未验证记录，会在事务内原样归档至 `qq_official_binding_archive` 并移出正式表，需重新申请；原已确认记录保留。迁移失败时不启用官方身份查询，不影响原 OneBot 白名单表。

**命令与回复对齐：**
- `help / 帮助 / 菜单 / 命令`：完整帮助、邮箱验证流程、管理员命令。群内每条命令都需要 @机器人。
- 状态卡片：各自读取所配置的 `card-cmd`，并支持 `服务器状态 / 服务器还活着吗`。复用传统状态卡（1500×700 PNG、服务器信息、在线玩家、自定义背景），通过官方分片上传接口发送图片，不需要图床；上传或发送失败时回复文字状态。图片分片序号兼容从 0 或 1 开始。
- `看看人机 / 在线人机 / 人机列表`：复用在线人机列表。帮助、状态、人机列表无需绑定；账号查询、白名单和管理命令必须先验证邮箱，即使是群管理员也不能绕过。
- 白名单申请、查询、删除、管理员封禁/解封/封禁列表：复用同一命令处理器与仓储。官方通道用中文文字代替 OneBot 的表情回应；数据库写失败不得回报成功。
- 每个请求独立保留 `msg_id` 和递增 `msg_seq`，不再拿群里“最后一条消息”作为其他命令的回复目标；重复投递不重复执行，群标识不使用有碰撞风险的字符串哈希。
- **官Q不支持 @目标**：官方群事件不提供被@成员的 QQ 号（无 mentions、content 中 @ 文本也被平台抹除），因此一律提示直接填写 QQ 号。管理命令缺目标时同样给出明确用法，不会只回“操作失败”。
- `重置密码 <账号名>`：新密码通过邮件发送到 `<QQ号>@qq.com`，邮件中提示使用 `/changepassword <旧密码> <新密码>`。群内回复不含密码；邮件发送失败会回滚密码，不会出现“改了却收不到”。

**配置结构说明：** `setting` / `account` / `account-binder` / `qq_bot` / `status-card` 为分组配置；旧的扁平键名（如 `bot.enabled`、`official_qq.enabled`）在内存中自动映射为对应分组键，两种写法都可用，升级不会导致功能静默失效。

**当前能力边界：**已核实官方群事件提供 `member_role`，本实现读取它区分成员、管理员和群主。成员进退群自动冻结/恢复尚未适配官方事件，不能据此承诺与 OneBot 完全等价；长时间 token 刷新/自动重连仍需完善。图片与单聊受开放平台权限、审核和消息窗口限制；本地 HTTP 协议测试不等于腾讯真实群验收。不能将当前版本视为已完成全功能生产验收。

官方机器人接入初版贡献者：CatXiaolan（猫小澜）。本轮对齐保留原贡献者署名，不改白名单/认证数据的所有权规则。

验证码仓储的隔离回归测试见 `tests/README.md`。测试只使用临时 SQLite 库，不需要 QQ 或邮件密钥。

官方接口文档：<https://bot.q.qq.com/wiki/develop/api-v2/>。当前 WebSocket 文档位于 `dev-prepare/event-emit/websocket.html`，其中 Identify 使用 `QQBot {AccessToken}` 和 `shard: [0, 1]`，不要混用旧版 Token 示例。

**v0.10 资源世界兼容升级**：旧版本的 `plugins/luoos/config.yml`、`player_data.db`、Worlds 世界目录均可直接保留。升级启动时会优先识别 `world/dimensions/luoos_resource/res_world` 等既有目录；如果旧版本未保存 `resourceWorld.currentSeed`，LuoOS 会从现有世界恢复种子，并将未导入的 Worlds 世界重新登记后加载。不会因为重启而删除或重新生成旧资源世界。

---

## 二、登录认证

### 基本配置

```yaml
enableAuthentication: true   # 启用登录认证
language: zh_cn              # 语言
loginTimeout: 60             # 登录超时(秒)
minPasswordLength: 4         # 密码最小长度
maxPasswordLength: 32        # 密码最大长度
```

### 离线玩家

```yaml
allowOfflinePlayers: true    # 允许离线玩家进入在线模式服
allowMoreOfflineUsernameCharacters: true  # 允许中文名
separateOnlineOfflineAccounts: true       # 同名正版/离线数据分离
```

### 绕过登录

某些玩家（如假人、Bot）不需要登录，配置白名单：

```yaml
loginBypassIps:
  - "127.0.0.1"
  - "192.168.1.100"

loginBypassNames:
  - "BOT_"      # 前缀匹配，所有 BOT_ 开头的玩家无需登录
```

> **Lophine 假人**：v0.08 自动检测 Lophine 内置假人（Bot 接口），无需手动配置白名单。

---

## 三、账号绑定

允许多个小号绑定到一个主账号，共享数据：

```yaml
enableAccountBinding: true   # 启用绑定系统
bindingStorage: sqlite       # 存储方式: sqlite 或 mysql
```

玩家命令：
- `/los bind <主账号名>` — 绑定到主账号
- `/los unbind` — 解绑

管理员命令：
- `/los bindinfo <玩家>` — 查看绑定关系

---

## 四、QQ机器人（OneBot）

### 配置

```yaml
bot:
  enabled: true
  host: 0.0.0.0              # WebSocket 监听地址
  port: 10100                # 监听端口
  access_token: "你的token"   # 与QQ框架一致
  qq_groups: [123456]        # 允许的群聊列表
  max_per_qq: 3              # 每个QQ最大白名单数
  allowed_id_chars: "a-zA-Z0-9_-."   # 允许的ID字符
  max_id_length: 16          # ID最大长度
  status_trigger: "服务器还活着吗"    # 触发状态卡片
  rate_limit_max: 5          # 频率限制(次)
  rate_limit_window: 60      # 时间窗口(秒)
  reply_delay_min_ms: 1000   # 回复最小延迟
  reply_delay_max_ms: 2000   # 回复最大延迟
  debug_log: false           # 调试日志
  mc_display_name: "LuoOS服务器"
  mc_description: "欢迎来到LuoOS"
  mc_display_ip: "127.0.0.1:25565"
```

QQ框架（NapCat/LLOneBot）中配置反向WebSocket地址为 `ws://服务器IP:10100`。

### 群聊命令

| 命令 | 说明 |
|------|------|
| `服务器还活着吗` / `服务器状态` | 返回状态卡片 |
| `申请白名单 <ID>` / `白名单 <ID>` / `添加白名单 <ID>` | 申请白名单 |
| `删除白名单 <ID>` / `移除白名单 <ID>` | 删除自己的白名单 |
| `查询白名单` / `查询` / `查看` [name/QQ] | 查看白名单 |
| `重置密码 <账号名>` | 重置名下账号密码（新密码通过QQ私聊发送，需开启允许陌生人私聊） |
| `看看人机` / `在线人机` / `人机列表` | 查看在线假人列表 |
| `help` / `帮助` / `菜单` | 显示帮助 |

管理员命令：

| 命令 | 说明 |
|------|------|
| `封禁/ban @QQ [时长]` | 封禁用户 |
| `解封/unban @QQ` | 解禁用户 |
| `删除 @QQ <ID>` | 删除指定用户的白名单 |
| `封禁列表` / `查看封禁列表` / `banlist` / `封神榜` | 查看封禁列表 |

### 退群/踢群白名单冻结

- 玩家主动退群（leave）或被管理员踢出群（kick）时，其名下所有白名单账号**自动冻结**：
  - 从服务器白名单移除，无法再登录
  - 若正在游戏内，会被踢出并提示
  - `查询白名单` 中该账号会显示 `(已冻结)`
- 玩家**重新进群**后，其名下冻结的白名单账号**自动恢复**。
- 冻结状态记录在 `qq_whitelist.frozen` 字段（旧库自动补列，无需手动迁移）。

> 说明：`重置密码` 仅限玩家重置自己名下（已绑定白名单）的账号，新密码为 6 位随机字母数字，通过 QQ 私聊发送，不会在群里展示；若私聊发送失败则自动回滚，不会把玩家锁在门外。同一 QQ 每 60 秒限重置一次。

---

## 五、玩家统计与排行榜

### 统计项目

- **在线时长** (`play_time_seconds`)
- **挖掘方块** (`blocks_mined`)
- **放置方块** (`blocks_placed`)
- **聊天字数** (`chat_chars`)
- **击杀实体** (`entities_killed`)

### 游戏内命令

| 命令 | 权限 | 说明 |
|------|------|------|
| `/los stats` | 玩家 | 查看自己的统计 |
| `/los stats <玩家>` | 玩家 | 查看他人统计 |
| `/los statstop` | OP | 在线时长排行榜 |
| `/los statstop <stat>` | OP | 指定统计排行 |
| `/los statstop <stat> <时间>` | OP | 时间范围排行 |
| `/los papi_test` | OP | 显示全部PAPI占位符 |

时间格式：`7d`(7天) `1w`(1周) `1m`(1月) `1q`(1季) `1y`(1年)

示例：
```
/los statstop blocks_mined       # 挖方块总排行
/los statstop entities_killed 7d # 7天击杀排行
```

### PAPI 占位符

| 占位符 | 说明 |
|--------|------|
| `%luoos_stat_<stat>%` | 指定统计数值 |
| `%luoos_stat_<stat>_<时间>%` | 时间范围统计 |
| `%luoos_stat_rank_<stat>%` | 排名 |
| `%luoos_stat_top_name_<stat>_<N>%` | 第N名名字 |
| `%luoos_stat_top_value_<stat>_<N>%` | 第N名数值 |

统计关键字：`play_time_seconds`, `blocks_mined`, `blocks_placed`, `chat_chars`, `entities_killed`

**全部 PAPI 占位符：**

| 占位符 | 说明 | 示例 |
|--------|------|------|
| `%luoos_stat_<stat>%` | 指定统计数值（总计） | `%luoos_stat_play_time_seconds%` → `4小时32分` |
| `%luoos_stat_<stat>_<时间>%` | 时间范围统计 | `%luoos_stat_blocks_mined_7d%` |
| `%luoos_stat_rank_<stat>%` | 排名（总计） | `%luoos_stat_rank_entities_killed%` |
| `%luoos_stat_rank_<stat>_<时间>%` | 时间范围排名 | `%luoos_stat_rank_blocks_mined_7d%` |
| `%luoos_stat_top_name_<stat>_<N>%` | 第N名名字（总计） | `%luoos_stat_top_name_play_time_seconds_1%` |
| `%luoos_stat_top_value_<stat>_<N>%` | 第N名数值（总计） | `%luoos_stat_top_value_play_time_seconds_1%` |
| `%luoos_stat_top_name_<stat>_<时间>_<N>%` | 时间范围第N名名字 | `%luoos_stat_top_name_blocks_mined_7d_1%` |
| `%luoos_stat_top_value_<stat>_<时间>_<N>%` | 时间范围第N名数值 | `%luoos_stat_top_value_blocks_mined_7d_2%` |
| `%luoos_resource_refresh%` | 资源世界刷新倒计时（中文） | → `22天16小时` / `即将刷新` / `未启用` |

时间后缀：`1d`(1天) `7d`(7天) `30d`(30天) `1w`(1周) `1m`(1月) `1q`(1季) `1y`(1年)

> **注意**：`<stat>` 使用下划线格式。完整统计关键字列表见下方"统计项目"。

---

## 六、资源世界

### 依赖

需要 **Worlds 4.2.2** 插件。

### 配置

```yaml
resourceWorld:
  enabled: true              # 启用资源世界
  refreshDayOfMonth: 25      # 每月刷新日（默认25号）
  refreshHour: 8             # 刷新时间（默认8点，服务器时区）
  nether: true               # 创建资源下界
  end: true                  # 创建资源终界
```

**注意**：v0.10 使用固定日期刷新策略，不再使用旧版 `refreshIntervalMinutes`。默认每月25日 08:00 刷新；每次刷新后自动计算下一个月的刷新时间，服务器重启后倒计时不会重置。升级旧配置时无需手动删除旧键，新增的 `refreshDayOfMonth` 和 `refreshHour` 缺失时会使用默认值。

### 命令

| 命令 | 权限 | 说明 |
|------|------|------|
| `/los resource` | 玩家 | 传送到资源世界 |
| `/los resourcerefresh` | OP | 立即刷新资源世界 |

### 工作原理

1. 服务器启动时自动创建资源世界（含下界/终界）
2. 世界名称为 `res_world`、`res_nether`、`res_end`，位于 `luoos_resource` 分组
3. 到达刷新间隔时，全服广播 30 秒预警 → 删除旧世界 → 随机种子生成新世界 → 全服广播完成
4. 刷新跨重启持久化（通过 config.yml 存储时间戳）

---

## 七、维护模式

```yaml
maintenance: false  # 维护模式开关
```

命令：
- `/los maintenance on` — 开启维护模式（仅OP可进入）
- `/los maintenance off` — 关闭
- `/los maintenance status` — 查看状态

---

## 八、其他功能

### 白名单

LuoOS 的登录白名单统一由数据库 `qq_whitelist` 管理，QQ 机器人、管理员命令和登录拦截都通过同一个 `FoliaWhitelistRepository` 数据访问模块操作，功能模块不再直接执行白名单 SQL。

```yaml
enableWhitelist: true  # 启用旧版 JSON 白名单兼容检查；数据库白名单始终由 qq_whitelist 管理
```

管理员命令（OP 或拥有 `luoos.admin` 权限）：

```text
/los whitelist add <玩家>
/los whitelist remove <玩家>
/los whitelist list
```

- `add` 写入 `qq_whitelist`，来源标记为“管理员”，不需要 QQ 机器人。
- `remove` 按玩家名移除数据库白名单记录。
- `list` 显示数据库白名单、来源和冻结状态。
- QQ 机器人添加的记录仍然使用 QQ 号作为来源。
- 管理员添加的记录不会因为 QQ 退群事件被冻结。

### 封禁系统

```yaml
enableCustomBan: true
```

命令：`/los ban/unban/banlist <玩家>`

### 数据迁移

用于正版/离线账号间数据转移：
```yaml
enablePlayerDataMigration: false
migrationBanSeconds: 30
```

命令：`/los migrate <源玩家> <目标玩家>`

### TPS 显示

```yaml
enableAutoLogTps: true
autoLogTpsDelayTicks: 20
```

### 配方同步

```yaml
enableRecipeViewerSync: true  # 1.21.2+
```

### 会话限制

```yaml
maxConcurrentSessionsPerIp: -1  # 同IP最大在线数, -1=不限
```

### 登录保护

```yaml
usernameLoginFailureLimit: 5          # 连续失败次数
usernameLoginFailureLockSeconds: 30   # 锁定时间(秒)
```

---

## 九、权限节点

| 权限 | 说明 |
|------|------|
| `luoos.admin` | 管理员命令权限 |

---

## 项目信息

- 作者: chara201x, qzgeek (黔中极客), CatXiaolan
- GitHub: https://github.com/qzgeek/heos-public
- 分支: main | 标签: v0.10

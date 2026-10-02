# LuoOS v0.10 使用文档

LuoOS 是一款面向 Folia 服务器的综合管理插件，提供登录认证、账号绑定、QQ 机器人、玩家统计排行榜、资源世界自动刷新等功能。

**v0.10 主要变化**

- 新增**官方 QQ 机器人**通道（QQ 开放平台），与原有 OneBot 机器人可同时运行
- 配置结构改为分组式（`setting` / `account` / `qq_bot` / `status-card`），并向下兼容旧键名
- 统一配置读取入口，新增配置自检，避免配置项静默失效
- 配置文件内置逐条中文注释（306 行）

项目作者：chara201x、qzgeek（黔中极客）、CatXiaolan。本文档按「安装 → 升级 → 配置 → 使用」组织，首次部署可直接从下方快速开始。

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

### QQ 机器人

LuoOS 提供两套互相独立的 QQ 机器人通道，均可与认证、白名单、资源世界等功能同时使用：

- **私人机器人（OneBot）**：用自己的 QQ 号搭建，配合 NapCat 等框架
- **官方机器人（QQ 开放平台）**：官方接口，更稳定，需开发者资质

两者可同时启用，共用同一套命令、白名单与数据库。完整配置与命令说明见 **第四章：QQ 机器人**。

**升级到 v0.10 注意**：配置结构由扁平键改为分组（`setting` / `account` / `qq_bot` / `status-card`），插件会自动兼容旧键名，直接替换 jar 即可。若你的配置里同时存在新旧两种写法，以分组键为准。

**资源世界数据保留**：旧版 `config.yml`、`player_data.db` 与 Worlds 世界目录均可直接沿用。启动时会优先识别 `world/dimensions/luoos_resource/` 下的既有目录；若旧配置未保存 `resourceWorld.currentSeed`，会从现有世界恢复种子，不会因重启而删除或重新生成。

---

## 二、登录认证

### 基本配置

```yaml
setting:
  language: zh_cn            # 语言

account:
  enableAuthentication: true # 启用登录认证
  loginTimeout: 120          # 登录超时(秒)
  minPasswordLength: 4       # 密码最小长度
  maxPasswordLength: 32      # 密码最大长度
```

完整配置项与逐条说明见插件生成的 `plugins/luoos/config.yml`。

### 离线玩家

```yaml
account:
  allowOfflinePlayers: true                   # 允许离线玩家进入在线模式服
  allowMoreOfflineUsernameCharacters: true    # 允许中文名
  separateOnlineOfflineAccounts: true         # 同名正版/离线数据分离
```

### 绕过登录

某些玩家（如假人、Bot）不需要登录，配置白名单：

```yaml
account:
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

## 四、QQ 机器人

两种通道互相独立，可同时启用，共用同一套命令、白名单与数据库：

| 通道 | 适用场景 | 配置开关 |
|------|---------|---------|
| 私人机器人（OneBot） | 用自己 QQ 号搭建，需配合 NapCat 等框架 | `qq_bot.private-bot.enable` |
| 官方机器人（QQ 开放平台） | 官方接口，更稳定，需开发者资质 | `qq_bot.official-bot.enable` |

两者都必须先打开总开关 `qq_bot.enable`。

### 私人机器人配置

```yaml
qq_bot:
  enable: true
  private-bot:
    enable: true
    host: 0.0.0.0
    port: 35013
    access_token: "与框架中的令牌一致"
    qq_groups: [123456, 789012]      # 留空 [] 表示允许所有群（不推荐）
    max_per_qq: 3
    card-cmd:                        # 触发状态卡片的词
      - "服务器还活着吗"
      - "状态"
      - "服务器状态"
      - "status"
      - "state"
    rate-limit-window: 60            # 频率限制窗口（秒）
    rate-limit-max: 10               # 窗口内最多处理多少条
    reply-delay-min-ms: 500          # 拟人化回复延迟（毫秒）
    reply-delay-max-ms: 2000
    debug_log: false
```

本插件是 WebSocket **服务端**，QQ 框架需用「反向 WS」主动连接：

```
NapCat 配置反向 WebSocket 地址为  ws://服务器IP:35013
并确保 access_token 与本配置完全一致，否则会被拒绝连接。
```

### 官方机器人配置

```yaml
qq_bot:
  enable: true
  official-bot:
    enable: true
    app_id: "开放平台获取的 AppID"
    app_secret: "开放平台获取的 AppSecret"
    card-cmd:                        # 官方通道独立配置，可与私人机器人不同
      - "服务器还活着吗"
      - "状态"
    groups: []                       # 留空表示不限制群聊
    code_digits: 6
    code_expire_minutes: 10
    smtp:                            # 官方通道必须配置，用于发送验证码
      host: smtp.126.com             # 必须与发件邮箱服务商一致
      port: 465                      # 465 为 SSL；587 为 STARTTLS
      username: "someone@126.com"
      password: "邮箱授权码"          # 不是登录密码，需在邮箱设置中开启 SMTP 后生成
      from: "someone@126.com"
      starttls: false                # 465 端口无需开启
```

在 QQ 开放平台开启群聊消息事件与对应 intents。绑定流程：

1. 群里 @机器人 发送 `绑定QQ <QQ号>`
2. 验证码发送到 `<QQ号>@qq.com`
3. @机器人 发送 `验证码 <验证码>` 完成绑定

**安全说明**：验证码只保存 SHA-256 摘要，10 分钟过期，每次申请最多校验 5 次；错误、过期、邮件失败、数据库失败均不授予身份；已确认的身份不可被覆盖，重启也不会把待验证申请变成正式绑定。

> `qq_bot.official-bot.groups` 留空即允许所有群；若需限制，填入官方事件中的 group_openid（**不是** QQ 群号）。

### 群聊命令

两条通道命令一致，区别仅在**个别交付方式**（见下）。

玩家命令：

| 命令 | 说明 |
|------|------|
| `申请白名单 <游戏ID>` | 申请白名单 |
| `删除白名单 <游戏ID>` | 删除自己的白名单 |
| `查询白名单` | 查看自己的白名单 |
| `查询白名单 <QQ号/ID>` | 查看指定对象 |
| `重置密码 <账号名>` | 重置名下账号密码（送达方式见下） |
| `服务器状态` | 返回状态卡片 |
| `看看人机` | 查看在线假人列表 |
| `帮助` | 显示帮助 |

管理员命令：

| 传统通道 | 官方通道 | 说明 |
|---------|---------|------|
| `封禁 <@某人\|QQ号> [时长]` | `封禁 <QQ号> [时长]` | 封禁用户（如 `1h`、`3天`） |
| `解封 <@某人\|QQ号>` | `解封 <QQ号>` | 解禁用户 |
| `删除 <@某人\|QQ号> [ID]` | `删除 <QQ号> [游戏ID]` | 删除该用户的白名单 |
| `封禁列表` | `封禁列表` | 查看封禁列表 |

> **官方通道不支持 @ 目标**：官方群事件不提供被@成员的 QQ 号（连 @ 文本也会被平台抹除），因此请直接填写 QQ 号。填了 @ 会收到明确提示而不是静默失败。

### 两通道的行为差异

| 项目 | 传统通道（OneBot） | 官方通道 |
|------|------------------|---------|
| 触发方式 | 直接发送命令 | 每条命令都需 @机器人 |
| 身份验证 | 无需绑定 | 需邮箱验证（帮助/状态/人机列表除外） |
| `重置密码` 送达 | **QQ 私聊** | **邮件到 `<QQ号>@qq.com`** |
| 管理员 @目标 | 支持 | 不支持，须填 QQ 号 |
| 回复延迟 | 可配置拟人化延迟 | 无延迟，立即回复 |
| 状态卡片触发词 | `private-bot.card-cmd` | `official-bot.card-cmd`（独立配置） |
| 表情回应 | 支持 | 改为文字提示 |

两种送达方式都会在**投递失败时自动回滚密码**，不会出现「密码已改但收不到」。`重置密码` 仅限重置自己名下（已绑定白名单）的账号，同一 QQ 每 60 秒限一次。

### 退群/踢群白名单冻结

- 玩家主动退群或被踢出群时，其名下所有白名单账号**自动冻结**：从服务器白名单移除、无法登录，游戏内则被踢出；`查询白名单` 中显示 `(已冻结)`。
- 玩家**重新进群**后自动恢复。
- 冻结状态记录在 `qq_whitelist.frozen` 字段（旧库自动补列，无需手动迁移）。

> 该功能依赖群成员进退通知，目前仅在传统通道生效。

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
maintenance: false  # 维护模式开关（顶层配置项）
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
account:
  enableWhitelist: true  # 启用旧版 JSON 白名单兼容检查；数据库白名单始终由 qq_whitelist 管理
```

注意：白名单判定为双重条件——本项开启且玩家在 JSON 白名单中，**或**数据库 `qq_whitelist` 表存在任意记录（此时自动进入白名单模式）。因此只要有玩家通过 QQ 机器人申请过白名单，未申请者就会被拒绝进入。

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
enableCustomBan: true   # 顶层配置项
```

命令：`/los ban/unban/banlist <玩家>`

### 数据迁移

用于正版/离线账号间数据转移：
```yaml
enablePlayerDataMigration: false   # 顶层配置项
migrationBanSeconds: 30
```

命令：`/los migrate <源玩家> <目标玩家>`

### TPS 显示

```yaml
enableAutoLogTps: true   # 顶层配置项
autoLogTpsDelayTicks: 20
```

### 配方同步

```yaml
enableRecipeViewerSync: true  # 1.21.2+，顶层配置项
```

### 会话限制

```yaml
account:
  maxConcurrentSessionsPerIp: -1  # 同IP最大在线数, -1=不限
```

### 登录保护

```yaml
account:
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
- GitHub: https://github.com/qzgeek/LuoOS
- 分支: main | 标签: v0.10

package heos.folia.bot;

import java.lang.reflect.Method;
import java.util.*;

/**
 * 帮助文本排版与通道差异检查。
 * 直接调用真实的 handleHelp，捕获实际发送的文本，逐行核对。
 */
public final class HelpTextCheck {
    static int passed;
    static final List<String> output = new ArrayList<>();

    static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
    }

    static void pass(String name) {
        passed++;
        System.out.println("PASS " + name);
    }

    static com.google.gson.JsonObject message(boolean official) {
        var data = new com.google.gson.JsonObject();
        var sender = new com.google.gson.JsonObject();
        data.addProperty("post_type", "message");
        data.addProperty("message_type", "group");
        data.addProperty("group_id", 100);
        data.addProperty("user_id", official ? 10001 : 10001);
        data.addProperty("raw_message", "帮助");
        sender.addProperty("role", "member");
        data.add("sender", sender);
        if (official) data.addProperty("official_verified", true);
        return data;
    }

    static String render(boolean official) throws Exception {
        output.clear();
        var handler = new BotCommandHandler(java.util.logging.Logger.getLogger("help-check"),
                null, null, null, null, 3, "a-zA-Z0-9_-", 16, new long[0], "trigger", 1000, 60, 0, 0);
        var event = new OneBotEvent(message(official), official ? "official:t" : "onebot:t",
                (action, params) -> {
                    var obj = com.google.gson.JsonParser.parseString(params).getAsJsonObject();
                    output.add(obj.getAsJsonArray("message").get(0).getAsJsonObject()
                            .getAsJsonObject("data").get("text").getAsString());
                    var ok = new com.google.gson.JsonObject();
                    ok.addProperty("status", "ok");
                    ok.addProperty("retcode", 0);
                    return java.util.concurrent.CompletableFuture.completedFuture(ok);
                });
        Method help = BotCommandHandler.class.getDeclaredMethod("handleHelp", OneBotEvent.class);
        help.setAccessible(true);
        help.invoke(handler, event);
        return String.join("\n", output);
    }

    public static void main(String[] args) throws Exception {
        String official = render(true);
        String legacy = render(false);

        // 官Q：不得出现 @目标 写法
        check(!official.contains("封禁/ban @QQ"), "official must not show @QQ admin syntax");
        check(!official.contains("@QQ"), "official must not contain @QQ");
        check(official.contains("封禁 <QQ号>"), "official shows QQ-number syntax");
        check(official.contains("不要用@"), "official warns against @");
        check(official.contains("发到QQ邮箱"), "official states email delivery");
        check(!official.contains("私聊发送"), "official must not claim private-message delivery");
        check(official.contains("绑定QQ") && official.contains("验证码"), "official shows binding steps");

        // 所有命令行必须对齐：说明列起始位置一致
        int officialCmdCol = columnOf(official, "申请白名单 <游戏ID>");
        int officialBanCol = columnOf(official, "封禁 <QQ号> [时长]");
        check(officialCmdCol == officialBanCol,
                "official columns aligned: " + officialCmdCol + " vs " + officialBanCol);

        // 帮助不得出现被截断/拼接痕迹
        check(!official.contains("查看在线人机不能"), "no concatenation artifact");
        check(!official.endsWith("查看在线人机"), "no dangling line");
        check(official.contains("看看人机"), "bot list command documented");

        // 传统通道：保留 @ 支持
        check(legacy.contains("<@某人|QQ号>"), "legacy supports @ target");
        check(legacy.contains("私聊发送"), "legacy uses private message");
        int legacyCmdCol = columnOf(legacy, "申请白名单 <游戏ID>");
        int legacyBanCol = columnOf(legacy, "封禁 <@某人|QQ号> [时长]");
        check(legacyCmdCol == legacyBanCol,
                "legacy columns aligned: " + legacyCmdCol + " vs " + legacyBanCol);

        pass("官Q帮助不含@写法、说明改为邮件，且列对齐");
        pass("传统帮助保留@支持与私聊说明，且列对齐");
        pass("无拼接/截断等排版痕迹");

        System.out.println("\n--- 官方QQ帮助实际输出 ---");
        System.out.println(official);
        System.out.println("\n--- 传统通道帮助实际输出 ---");
        System.out.println(legacy);
        System.out.println("\nHELP TEXT PASS " + passed);
    }

    /**
     * 返回「说明文字」的起始显示列号，用于校验对齐。
     * 中文等全角字符占 2 列，不能按 char 数计算。
     */
    static int columnOf(String text, String command) {
        for (String line : text.split("\n")) {
            int idx = line.indexOf(command);
            if (idx < 0) continue;
            String head = line.substring(0, idx + command.length());
            int width = 0;
            for (char c : head.toCharArray()) width += c > 0x2E80 ? 2 : 1;
            int after = idx + command.length();
            while (after < line.length() && line.charAt(after) == ' ') {
                width++;
                after++;
            }
            return width;
        }
        throw new AssertionError("未找到命令行: " + command);
    }
}

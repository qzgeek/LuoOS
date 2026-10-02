package heos.folia.bot;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.util.Properties;

public class SmtpCodeService {
    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String from;
    private final boolean startTls;

    public SmtpCodeService(String host, int port, String username, String password, String from, boolean startTls) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.from = from;
        this.startTls = startTls;
    }

    public void send(String recipient, String code) throws Exception {
        deliver(recipient, "LuoOS QQ 绑定验证码", "验证码：" + code);
    }

    /** 发送密码重置结果。密码只出现在邮件正文，不写日志。 */
    public void sendPasswordReset(String recipient, String accountName, String password) throws Exception {
        deliver(recipient, "LuoOS 密码重置",
                "账号：" + accountName + "\n新密码：" + password
                        + "\n请尽快使用 /changepassword <旧密码> <新密码> 修改");
    }

    private void deliver(String recipient, String subject, String body) throws Exception {
        Properties props = new Properties();
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", String.valueOf(port));
        props.put("mail.smtp.auth", String.valueOf(!username.isBlank()));
        // 465 使用隐式 TLS；其他端口按配置要求 STARTTLS，禁止静默降级。
        boolean implicitTls = port == 465;
        props.put("mail.smtp.ssl.enable", String.valueOf(implicitTls));
        props.put("mail.smtp.ssl.checkserveridentity", "true");
        props.put("mail.smtp.starttls.enable", String.valueOf(startTls && !implicitTls));
        props.put("mail.smtp.starttls.required", String.valueOf(startTls && !implicitTls));
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "15000");
        props.put("mail.smtp.writetimeout", "15000");
        Session session = Session.getInstance(props, new Authenticator() {
            @Override protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(username, password);
            }
        });
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(from));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipient));
        message.setSubject(subject, "UTF-8");
        message.setText(body, "UTF-8");
        Transport.send(message);
    }
}

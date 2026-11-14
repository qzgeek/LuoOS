import heos.folia.storage.OfficialBindingRepository;
import java.nio.file.*;
import java.sql.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/** 调用真实生产仓储的隔离 SQLite 回归测试；邮件回调是明确的测试替身，不联网。 */
public final class OfficialBindingRegression {
    static int passed;
    static final Logger LOG = Logger.getLogger("binding-regression");
    static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
    static void pass(String name) { passed++; System.out.println("PASS " + name); }
    static Connection open(Path path) throws Exception {
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
        sql(c, "PRAGMA journal_mode=WAL"); sql(c, "PRAGMA busy_timeout=5000"); return c;
    }
    static OfficialBindingRepository repo(Connection c) {
        var r = new OfficialBindingRepository(new Object(), () -> c, false, LOG);
        r.initialize(); return r;
    }
    static void sql(Connection c, String sql) throws Exception {
        try (var s = c.createStatement()) { s.execute(sql); }
    }
    static int count(Connection c, String table) throws Exception {
        try (var s = c.createStatement(); var rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) { rs.next(); return rs.getInt(1); }
    }
    static boolean request(OfficialBindingRepository r, String id, long qq, String hash, long now) {
        return r.requestCode(id, qq, hash, now + 600_000, now, () -> {});
    }
    static OfficialBindingRepository.RequestResult requestResult(OfficialBindingRepository r, String id, long qq, String hash, long now,
                                                                OfficialBindingRepository.MailDelivery delivery) {
        return r.requestCodeDetailed(id, qq, hash, now + 600_000, now, delivery);
    }
    public static void main(String[] args) throws Exception {
        Class.forName("org.sqlite.JDBC");
        Path dir = Files.createTempDirectory("luoos-binding-regression-");
        long now = System.currentTimeMillis();
        Path main = dir.resolve("main.db");
        try (Connection c = open(main)) {
            var r = repo(c);
            check(request(r,"alice",10001,"hashA",now),"request");
            check(count(c,"qq_official_bindings")==0 && count(c,"qq_official_pending")==1,"only pending");
            check(r.officialQq("alice").isEmpty() && !r.bindingExistsForQq(10001),"pending not identity/reservation");
            pass("unverified request never writes or exposes a formal identity");
            check(!r.confirmCode("alice","wrong",now),"wrong code");
            check(r.officialQq("alice").isEmpty() && count(c,"qq_official_bindings")==0,"wrong code not granted");
            pass("wrong code cannot grant identity");
            check(r.confirmCode("alice","hashA",now),"correct code");
            check(r.officialQq("alice").orElseThrow()==10001 && count(c,"qq_official_pending")==0,"consume+publish");
            check(!r.confirmCode("alice","hashA",now),"replay denied");
            pass("correct code publishes once and atomically consumes challenge");
            check(!request(r,"alice",10002,"replace",now+60_001),"no overwrite");
            check(!request(r,"other",10001,"takeover",now+60_001),"no takeover");
            check(r.officialQq("alice").orElseThrow()==10001,"old preserved");
            pass("verified identity and QQ cannot be overwritten");
            check(request(r,"expired",10003,"expire",now),"expired setup");
            check(!r.confirmCode("expired","expire",now+600_001),"expired denied");
            check(r.officialQq("expired").isEmpty() && !r.bindingExistsForQq(10003),"expiry no reservation");
            pass("expired challenge cannot grant or permanently reserve QQ");
            check(request(r,"attempts",10004,"right",now),"attempt setup");
            for(int i=0;i<5;i++) check(!r.confirmCode("attempts","bad",now),"bad attempt");
            check(!r.confirmCode("attempts","right",now),"attempt cap");
            pass("five failed attempts lock challenge including correct later code");
            check(!r.requestCode("mailfail",10005,"mailhash",now+600_000,now,()->{throw new Exception("simulated SMTP failure");}),"mail failure");
            check(r.officialQq("mailfail").isEmpty() && !r.confirmCode("mailfail","mailhash",now),"mail failed no code");
            pass("SMTP failure produces neither pending challenge nor identity");
            // 失败原因必须可区分：邮件失败 / 冷却 / 已绑定，不能都报同一句
            var mailFail = requestResult(r, "reason-mail", 30001, "h", now,
                    () -> { throw new Exception("simulated SMTP failure"); });
            check(mailFail == OfficialBindingRepository.RequestResult.MAIL_FAILED, "reason=mail failed, got " + mailFail);
            check(requestResult(r, "reason-ok", 30002, "h2", now, () -> {}) == OfficialBindingRepository.RequestResult.OK, "reason ok");
            var rate = requestResult(r, "reason-ok", 30002, "h3", now + 1, () -> {});
            check(rate == OfficialBindingRepository.RequestResult.RATE_LIMITED, "reason=rate limited, got " + rate);
            var invalid = requestResult(r, "reason-invalid", 0, "h4", now, () -> {});
            check(invalid == OfficialBindingRepository.RequestResult.INVALID, "reason=invalid, got " + invalid);
            pass("绑定申请按真实原因返回（邮件失败/冷却/参数无效各不同）");
            AtomicInteger deliveries = new AtomicInteger();
            check(r.requestCode("limited",10006,"one",now+600_000,now,deliveries::incrementAndGet),"limit setup");
            check(!r.requestCode("limited",10006,"two",now+600_000,now+1,deliveries::incrementAndGet),"openid cooldown");
            check(!r.requestCode("other-limited",10006,"two",now+600_000,now+1,deliveries::incrementAndGet),"qq cooldown");
            check(deliveries.get()==1,"no duplicate mail");
            pass("openid and recipient QQ cooldown prevent repeated SMTP sends");
            check(request(r,"atomic",10007,"atomic",now),"atomic setup");
            sql(c,"CREATE TRIGGER fail_consume BEFORE DELETE ON qq_official_pending WHEN OLD.openid='atomic' BEGIN SELECT RAISE(ABORT,'injected consume failure'); END");
            check(!r.confirmCode("atomic","atomic",now),"injected failure");
            check(r.officialQq("atomic").isEmpty() && c.getAutoCommit(),"rollback+autocommit");
            sql(c,"DROP TRIGGER fail_consume");
            check(r.confirmCode("atomic","atomic",now),"pending preserved after rollback");
            pass("DB failure after insert rolls back identity and preserves challenge");
            sql(c,"CREATE TRIGGER fail_save BEFORE INSERT ON qq_official_pending WHEN NEW.openid='dbfail' BEGIN SELECT RAISE(ABORT,'injected insert failure'); END");
            check(!request(r,"dbfail",10008,"dbfail",now),"save failure");
            check(r.officialQq("dbfail").isEmpty() && !r.confirmCode("dbfail","dbfail",now),"no identity after mail+db failure");
            sql(c,"DROP TRIGGER fail_save");
            pass("challenge database failure after mail cannot grant identity");
            check(request(r,"persist-pending",10009,"persist",now),"restart setup");
        }
        try(Connection c=open(main)) {
            var r=repo(c);
            check(r.officialQq("alice").orElseThrow()==10001,"verified survives restart");
            check(r.officialQq("persist-pending").isEmpty(),"pending stays untrusted on restart");
            check(r.confirmCode("persist-pending","persist",now),"pending can verify after restart");
            pass("restart preserves verified identity but does not promote pending identity");
        }
        Path race=dir.resolve("race.db");
        try(Connection a=open(race);Connection b=open(race)) {
            var ra=repo(a);var rb=repo(b);
            check(request(ra,"race-a",20001,"a",now),"race a");
            check(request(rb,"race-b",20001,"b",now+60_001),"race b pending allowed after cooldown");
            ExecutorService pool=Executors.newFixedThreadPool(2);
            CountDownLatch start=new CountDownLatch(1);
            try {
                Future<Boolean> fa=pool.submit(()->{start.await();return ra.confirmCode("race-a","a",now+60_002);});
                Future<Boolean> fb=pool.submit(()->{start.await();return rb.confirmCode("race-b","b",now+60_002);});
                start.countDown();boolean va=fa.get(15,TimeUnit.SECONDS),vb=fb.get(15,TimeUnit.SECONDS);
                check(va!=vb && count(a,"qq_official_bindings")==1,"exactly one winner");
                pass("two independent connections racing for same QQ create exactly one identity");
            } finally {pool.shutdownNow();}
        }
        Path legacy=dir.resolve("legacy.db");
        try(Connection c=open(legacy)) {
            repo(c);
            sql(c,"INSERT INTO qq_official_bindings VALUES ('legacy-pending',30001,'unsafe',1,1),('legacy-verified',30002,NULL,NULL,1)");
            var r=repo(c);
            check(r.officialQq("legacy-pending").isEmpty() && !r.bindingExistsForQq(30001),"legacy pending disabled");
            check(r.officialQq("legacy-verified").orElseThrow()==30002,"legacy verified intact");
            check(count(c,"qq_official_binding_archive")==1,"evidence archived");
            r.initialize();check(count(c,"qq_official_binding_archive")==1,"migration idempotent");
            pass("legacy migration archives unverified evidence and preserves confirmed identities");
            sql(c,"INSERT INTO qq_official_bindings VALUES ('legacy-fail',30003,'unsafe2',1,1)");
            sql(c,"CREATE TRIGGER fail_migration BEFORE DELETE ON qq_official_bindings BEGIN SELECT RAISE(ABORT,'injected migration failure'); END");
            boolean failed=false;try{r.initialize();}catch(IllegalStateException expected){failed=true;}
            check(failed && r.officialQq("legacy-verified").isEmpty(),"fail closed migration");
            check(count(c,"qq_official_bindings")==2 && count(c,"qq_official_binding_archive")==1,"migration rollback no data loss");
            pass("migration failure rolls back all data changes and disables identity reads");
        }
        try(Connection c=open(dir.resolve("unbind.db"))){
            var u=repo(c);
            // ① 没有身份就不能申请解绑（不发码、不写申请）
            AtomicInteger unboundMails=new AtomicInteger();
            check(u.requestUnbindCode("nobody",50001,"u0",now+600_000,now,unboundMails::incrementAndGet)==OfficialBindingRepository.UnbindResult.NOT_BOUND,"unbind without identity");
            check(unboundMails.get()==0 && count(c,"qq_official_unbind_pending")==0,"no mail/state without identity");
            pass("未绑定身份申请解绑被拒绝，且不发邮件不写申请");
            // ② 绑定后申请解绑：先发码，身份仍在
            check(request(u,"unbinder",50001,"bind1",now),"unbind setup bind");
            check(u.confirmCode("unbinder","bind1",now),"unbind setup confirm");
            check(u.requestUnbindCode("unbinder",50001,"u1",now+600_000,now,unboundMails::incrementAndGet)==OfficialBindingRepository.UnbindResult.OK,"unbind request");
            check(unboundMails.get()==1 && u.officialQq("unbinder").orElseThrow()==50001,"identity kept until code");
            check(count(c,"qq_official_unbind_pending")==1 && count(c,"qq_official_bindings")==1,"only challenge written");
            pass("解绑申请只发码不删除身份（验证码通过前身份保持有效）");
            // ③ 冷却：60 秒内重复申请被拒
            check(u.requestUnbindCode("unbinder",50001,"u2",now+600_000,now+1,unboundMails::incrementAndGet)==OfficialBindingRepository.UnbindResult.RATE_LIMITED,"unbind cooldown");
            check(unboundMails.get()==1,"no duplicate unbind mail");
            pass("解绑申请有 60 秒冷却，不会重复发信");
            // ④ 错误验证码不解绑
            check(!u.confirmUnbind("unbinder","wrong",now),"wrong unbind code");
            check(u.officialQq("unbinder").orElseThrow()==50001 && count(c,"qq_official_bindings")==1,"wrong code keeps identity");
            pass("错误验证码不能解绑");
            // ⑤ 正确验证码解绑：身份删除、归档留证、申请被消费
            check(u.confirmUnbind("unbinder","u1",now),"correct unbind code");
            check(u.officialQq("unbinder").isEmpty() && count(c,"qq_official_bindings")==0,"identity removed");
            check(count(c,"qq_official_unbind_pending")==0 && count(c,"qq_official_binding_archive")==1,"challenge consumed + archived");
            check(!u.bindingExistsForQq(50001),"qq released");
            pass("验证码通过后解绑：身份先归档再删除，QQ 号释放");
            // ⑥ 解绑后可以用另一个 QQ 重新绑定
            check(u.requestCodeDetailed("unbinder",50002,"bind2",now+600_000,now+60_001,()->{})==OfficialBindingRepository.RequestResult.OK,"rebind other qq");
            check(u.confirmCode("unbinder","bind2",now+60_002) && u.officialQq("unbinder").orElseThrow()==50002,"rebind confirmed");
            pass("解绑后可用新 QQ 号重新绑定");
            // ⑦ 解绑时邮件失败：不发码、不动身份
            check(u.requestUnbindCode("unbinder",50002,"u3",now+600_000,now+180_000,()->{throw new Exception("simulated SMTP failure");})==OfficialBindingRepository.UnbindResult.MAIL_FAILED,"unbind mail failure");
            check(count(c,"qq_official_unbind_pending")==0 && u.officialQq("unbinder").orElseThrow()==50002,"mail fail keeps identity");
            pass("解绑验证码邮件失败时身份保持、无残留申请");
            // ⑧ 归档失败则整体回滚：身份不丢
            check(u.requestUnbindCode("unbinder",50002,"u4",now+600_000,now+240_000,()->{})==OfficialBindingRepository.UnbindResult.OK,"unbind rollback setup");
            sql(c,"CREATE TRIGGER fail_unbind_archive BEFORE INSERT ON qq_official_binding_archive WHEN NEW.openid='unbinder' BEGIN SELECT RAISE(ABORT,'injected archive failure'); END");
            check(!u.confirmUnbind("unbinder","u4",now+240_001) && u.officialQq("unbinder").orElseThrow()==50002 && c.getAutoCommit(),"archive fail rolls back");
            sql(c,"DROP TRIGGER fail_unbind_archive");
            pass("归档失败时解绑整体回滚，身份与事务状态均不损坏");
        }
        Connection closed=open(dir.resolve("closed.db"));var r=repo(closed);closed.close();
        check(r.officialQq("x").isEmpty() && r.bindingExistsForQq(1),"db closed fail closed");
        check(!request(r,"x",40001,"h",now) && !r.confirmCode("x","h",now),"db closed writes fail");
        pass("closed database fails closed for identity and availability checks");
        System.out.println("RESULT: "+passed+" scenarios PASS; real Java repository + SQLite; SMTP failure callbacks are test doubles");
        System.out.println("ISOLATED TEST DATABASES: "+dir);
    }
}

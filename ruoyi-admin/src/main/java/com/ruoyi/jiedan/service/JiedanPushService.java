package com.ruoyi.jiedan.service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.jiedan.mapper.JiedanSettingMapper;

/**
 * 微信推送（Server酱 / 方糖）。best-effort：无 key 或异常都不影响主流程。
 */
@Service
public class JiedanPushService
{
    private static final Logger log = LoggerFactory.getLogger(JiedanPushService.class);

    @Autowired
    private JiedanSettingMapper settingMapper;

    @Autowired
    private JiedanWecomService wecom;

    @Autowired
    private JiedanWebPush webPush;

    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public void pushToMember(Long memberId, String title, String content, Long orderId)
    {
        pushToTarget(memberId, title, content, NoticeTarget.order(orderId));
    }

    public void pushToTarget(Long memberId, String title, String content, NoticeTarget target)
    {
        enqueue(memberId, title, content, target, false, null);
    }

    /** 在事务内校验并固定单个收件人，提交后再等待企业微信回执。 */
    public Runnable prepareWecomReminder(Long memberId, String title, String content, NoticeTarget target)
    {
        if (!"true".equalsIgnoreCase(value("push.enabled", "true")))
            throw new ServiceException("微信提醒已关闭，请先在通知设置中开启");
        if (!wecom.configured()) throw new ServiceException("请先配置接单提醒的企业微信应用");
        String user = wecom.userOf(memberId).trim();
        if (user.isEmpty() || user.contains("|") || "@all".equalsIgnoreCase(user))
            throw new ServiceException("请先为这条事项的负责人绑定企业微信账号");
        String url = linkFor(target);
        if (url.isEmpty()) throw new ServiceException("请先在通知设置中填写系统网址，方便负责人打开项目");
        return () -> {
            String error = wecom.send(user, title, content, url);
            if (error != null) throw new ServiceException("微信提醒发送失败：" + error);
        };
    }

    // A second WeChat identity belongs to the quote creator, not to the colleague pricing the quote.
    public void pushQuoteOwner(Long creator, Long assignee, String title, String content, NoticeTarget target)
    {
        enqueue(creator, title, content, target, true, assignee);
    }

    private void enqueue(Long memberId, String title, String content, NoticeTarget target, boolean quoteOwner, Long assignee)
    {
        if (memberId == null) return;
        webPush.publish(memberId, title, content, target.path);
        Runnable notify = () -> deliver(memberId, title, content, linkFor(target), target.label, quoteOwner, assignee);
        if (TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { notify.run(); }
            });
        else notify.run();
    }

    /**
     * 发给某个成员：企业微信配好、且这个成员填了企业微信账号 → 走企业微信；否则有 SendKey → 走 Server酱。
     * 两个都配了只走企业微信，不会重复收到。
     */
    private void deliver(Long memberId, String title, String content, String url, String linkText, boolean quoteOwner, Long assignee)
    {
        if (memberId == null) return;
        if (!"true".equalsIgnoreCase(value("push.enabled", "true"))) return;
        if (wecom.configured())
        {
            String primary = wecom.userOf(memberId);
            Set<String> recipients = new LinkedHashSet<>();
            if (!primary.isBlank()) recipients.add(primary);
            if (quoteOwner) {
                String extra = value("push.wecom.quoteCopy." + memberId, "").trim();
                if (!extra.isEmpty() && !extra.equalsIgnoreCase(primary)) recipients.add(extra);
            }
            if (!recipients.isEmpty()) {
                String exclude = assignee == null ? "" : wecom.userOf(assignee);
                for (String recipient : recipients) {
                    if (recipient.equalsIgnoreCase(exclude)) continue;
                    pool.submit(() -> {
                        String err = wecom.send(recipient, title, content, url);
                        if (err != null) log.warn("[企业微信] 推送给成员 {} 的接收账号 {} 失败：{}", memberId, recipient, err);
                    });
                }
                return;
            }
        }
        String key = value("push.sendkey." + memberId, "");
        if (key == null || key.trim().isEmpty()) return;
        String desp = url == null || url.isEmpty() ? content : content + "\n\n[" + linkText + "](" + url + ")";
        pool.submit(() -> doSend(key.trim(), title, desp));
    }

    /** 系统里某个项目（orderId 为空时是工作台）的网址；没设置系统网址时返回空串 */
    private String linkFor(NoticeTarget target)
    {
        String base = value("push.siteUrl", "");
        base = base == null ? "" : base.trim().replaceAll("/+$", "");
        if (base.isEmpty()) return "";
        return base + target.path;
    }

    /** 同步测试推送，返回结果文本（设置页"发送测试"用） */
    public String testPush(Long memberId)
    {
        String wecomUser = wecom.configured() ? wecom.userOf(memberId) : "";
        if (!wecomUser.isEmpty())
        {
            String err = wecom.send(wecomUser, "玄成接单系统·测试", "如果你在微信收到这条，说明企业微信通知已打通 🎉", linkFor(NoticeTarget.order(null)));
            return err == null ? "已通过企业微信发送，请在微信里查看（企业微信插件 / 企业微信 App）" : "企业微信发送失败：" + err;
        }
        String key = value("push.sendkey." + memberId, "");
        if (key == null || key.trim().isEmpty()) return wecom.configured() ? "这个成员还没填企业微信账号" : "未配置该成员的企业微信账号或 SendKey";
        try
        {
            int code = doSend(key.trim(), "玄成接单系统·测试", "如果你在微信收到这条，说明推送已打通 🎉");
            return code == 200 ? "已发送，请查看微信服务通知" : "发送失败，HTTP " + code;
        }
        catch (Exception e)
        {
            return "发送异常：" + e.getMessage();
        }
    }

    private int doSend(String key, String title, String content)
    {
        try
        {
            String url = "https://sctapi.ftqq.com/" + key + ".send"
                    + "?title=" + enc(title)
                    + "&desp=" + enc(content);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200)
            {
                log.warn("[推送] 失败 code={} body={}", resp.statusCode(), resp.body());
            }
            return resp.statusCode();
        }
        catch (Exception e)
        {
            log.warn("[推送] 异常: {}", e.getMessage());
            return -1;
        }
    }

    private String enc(String s)
    {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private String value(String key, String def)
    {
        try
        {
            String v = settingMapper.selectValue(key);
            return v == null ? def : v;
        }
        catch (Exception e)
        {
            return def;
        }
    }
}

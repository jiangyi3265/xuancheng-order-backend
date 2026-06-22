package com.ruoyi.jiedan.service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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

    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** 异步推送给某成员（用于建单/进度/状态/催稿等触发） */
    public void pushToMember(Long memberId, String title, String content)
    {
        if (memberId == null) return;
        if (!"true".equalsIgnoreCase(value("push.enabled", "true"))) return;
        String key = value("push.sendkey." + memberId, "");
        if (key == null || key.trim().isEmpty()) return;
        pool.submit(() -> doSend(key.trim(), title, content));
    }

    /** 同步测试推送，返回结果文本（设置页"发送测试"用） */
    public String testPush(Long memberId)
    {
        String key = value("push.sendkey." + memberId, "");
        if (key == null || key.trim().isEmpty()) return "未配置该成员的 SendKey";
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

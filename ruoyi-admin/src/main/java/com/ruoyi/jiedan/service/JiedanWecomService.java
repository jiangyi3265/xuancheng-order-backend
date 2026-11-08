package com.ruoyi.jiedan.service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.jiedan.mapper.JiedanSettingMapper;

/**
 * 企业微信通知：用企业微信「自建应用」给成员发消息，成员开启「微信插件」后在个人微信里就能收到。
 * 设置都存在 jiedan_setting：
 *   push.wecom.corpId / push.wecom.agentId / push.wecom.secret —— 企业 ID、应用 AgentId、应用 Secret
 *   push.wecom.token / push.wecom.aesKey —— 「接收消息服务器」的 Token、EncodingAESKey（只用于校验回调地址）
 *   push.wecom.user.{成员id} —— 这个成员在企业微信里的账号（UserID）
 * best-effort：任何失败只记日志、返回原因，不影响业务。
 */
@Service
public class JiedanWecomService
{
    private static final Logger log = LoggerFactory.getLogger(JiedanWecomService.class);
    private static final Pattern FROM_IP = Pattern.compile("from ip:\\s*([0-9a-fA-F:.]+)");
    /** 文本消息上限 2048 字节，留点余量给标题和链接 */
    private static final int MAX_CONTENT_BYTES = 1700;

    @Autowired
    private JiedanSettingMapper settingMapper;

    /** 企业微信接口地址；测试时可用启动参数 --jiedan.wecom.api-base 指向本地模拟服务 */
    @Value("${jiedan.wecom.api-base:https://qyapi.weixin.qq.com}")
    private String apiBase;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private String token;
    private long tokenExpireAt;
    private String tokenFor;

    /** 企业 ID、AgentId、Secret 都填了才算配好 */
    public boolean configured()
    {
        return !corpId().isEmpty() && !agentId().isEmpty() && !secret().isEmpty();
    }

    /** 某个成员的企业微信账号，没填返回空串 */
    public String userOf(Long memberId)
    {
        return memberId == null ? "" : value("push.wecom.user." + memberId);
    }

    /**
     * 发送应用消息给某个企业微信账号。成功返回 null，失败返回能看懂的原因。
     * 带链接时发送整张可点击的单图文卡片。
     */
    public String send(String userId, String title, String content, String url)
    {
        if (!configured()) return "企业微信还没配置好（企业 ID / AgentId / Secret）";
        if (userId == null || userId.trim().isEmpty()) return "这个成员没有填写企业微信账号";
        try
        {
            String body = buildMessage(userId.trim(), title, content, url);
            JSONObject r = post("/cgi-bin/message/send?access_token=" + enc(accessToken(false)), body);
            Integer code = r == null ? null : r.getInteger("errcode");
            if (code == null) return "未收到企业微信的有效回执";
            if (code == 40014 || code == 42001 || code == 40001)
            {
                // 令牌失效（比如别处重新获取过）：重新取一次再发
                r = post("/cgi-bin/message/send?access_token=" + enc(accessToken(true)), body);
                code = r == null ? null : r.getInteger("errcode");
                if (code == null) return "未收到企业微信的有效回执";
            }
            if (code != 0) return explain(code, r.getString("errmsg"));
            String invalid = r.getString("invaliduser");
            if (invalid != null && !invalid.trim().isEmpty())
            {
                return "企业微信里找不到账号「" + invalid + "」，或它不在应用的可见范围里";
            }
            return null;
        }
        catch (WecomException e)
        {
            return e.getMessage();
        }
        catch (Exception e)
        {
            log.warn("[企业微信] 发送异常: {}", e.getClass().getSimpleName());
            return "未收到企业微信回执，请检查网络或稍后查看";
        }
    }

    private String buildMessage(String userId, String title, String content, String url)
    {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("touser", userId);
        msg.put("agentid", toAgentId(agentId()));
        String heading = title == null ? "接单提醒" : title;
        String detail = content == null ? "" : content;
        if (url != null && !url.isEmpty())
        {
            Map<String, String> article = Map.of("title", cutBytes(heading, 128),
                    "description", cutBytes(detail, 512), "url", url);
            msg.put("msgtype", "news");
            msg.put("news", Map.of("articles", List.of(article)));
        }
        else
        {
            msg.put("msgtype", "text");
            msg.put("text", Map.of("content", cutBytes(heading + "\n" + detail, MAX_CONTENT_BYTES)));
        }
        return JSON.toJSONString(msg);
    }

    private synchronized String accessToken(boolean force) throws Exception
    {
        String key = corpId() + "|" + secret();
        if (!force && token != null && key.equals(tokenFor) && System.currentTimeMillis() < tokenExpireAt) return token;
        HttpRequest req = HttpRequest.newBuilder(URI.create(base() + "/cgi-bin/gettoken?corpid=" + enc(corpId()) + "&corpsecret=" + enc(secret())))
                .timeout(Duration.ofSeconds(8)).GET().build();
        JSONObject r = JSON.parseObject(client.send(req, HttpResponse.BodyHandlers.ofString()).body());
        int code = r.getIntValue("errcode");
        if (code != 0 || r.getString("access_token") == null) throw new WecomException(explain(code, r.getString("errmsg")));
        token = r.getString("access_token");
        long expiresIn = r.getLongValue("expires_in") > 0 ? r.getLongValue("expires_in") : 7200;
        // 提前 5 分钟过期，避免卡在边界上
        tokenExpireAt = System.currentTimeMillis() + Math.max(60, expiresIn - 300) * 1000L;
        tokenFor = key;
        return token;
    }

    private JSONObject post(String path, String body) throws Exception
    {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base() + path))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        return JSON.parseObject(client.send(req, HttpResponse.BodyHandlers.ofString()).body());
    }

    /** 把企业微信的错误码翻译成要去哪里改 */
    static String explain(int code, String msg)
    {
        String m = msg == null ? "" : msg;
        switch (code)
        {
            case 60020:
            {
                Matcher ip = FROM_IP.matcher(m);
                return ip.find()
                        ? "服务器 IP 不在企业微信的可信 IP 里：请在应用详情 →「企业可信IP」里加上 " + ip.group(1)
                        : "服务器 IP 不在企业微信的可信 IP 里：请在应用详情 →「企业可信IP」里加上服务器的公网 IP";
            }
            case 40013: return "企业 ID 不对（在「我的企业」页面最下面）";
            case 40001:
            case 40091:
            case 41004: return "应用 Secret 不对（在应用详情里查看，注意不要多复制空格）";
            case 40056:
            case 301002: return "AgentId 不对（在应用详情里查看）";
            case 81013: return "接收人无效：检查成员的企业微信账号，并确认他在应用的可见范围里";
            case 48002: return "这个应用没有发消息的权限，请检查应用可见范围";
            default: return "企业微信返回错误 " + code + (m.isEmpty() ? "" : "：" + m);
        }
    }

    // ================= 「接收消息服务器」回调校验 =================
    // 企业微信要求先配好接收消息服务器（或可信域名）才能填「企业可信IP」。
    // 这里只做地址校验和收到消息时的签名校验，不处理成员发来的内容。

    /** 校验 GET 回调：签名正确就返回解密后的 echostr 明文，否则返回 null */
    public String verifyUrl(String signature, String timestamp, String nonce, String echostr)
    {
        if (echostr == null) return null;
        String enc = echostr.replace(' ', '+');
        if (!signatureOk(signature, timestamp, nonce, enc)) return null;
        return decrypt(enc);
    }

    /** 校验 POST 回调的签名（内容不处理） */
    public boolean signatureOk(String signature, String timestamp, String nonce, String encrypt)
    {
        String tk = value("push.wecom.token");
        if (tk.isEmpty() || signature == null || timestamp == null || nonce == null || encrypt == null) return false;
        String[] parts = { tk, timestamp, nonce, encrypt };
        Arrays.sort(parts);
        try
        {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] d = sha1.digest(String.join("", parts).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : d) hex.append(String.format("%02x", b));
            return MessageDigest.isEqual(hex.toString().getBytes(StandardCharsets.UTF_8), signature.toLowerCase().getBytes(StandardCharsets.UTF_8));
        }
        catch (Exception e)
        {
            return false;
        }
    }

    private String decrypt(String encrypt)
    {
        try
        {
            String keyText = value("push.wecom.aesKey");
            if (keyText.length() != 43) return null;
            byte[] key = Base64.getDecoder().decode(keyText + "=");
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(Arrays.copyOfRange(key, 0, 16)));
            byte[] plain = cipher.doFinal(Base64.getDecoder().decode(encrypt));
            // 去掉 PKCS#7 补位（企业微信按 32 字节分块）
            int pad = plain[plain.length - 1] & 0xff;
            if (pad < 1 || pad > 32) pad = 0;
            byte[] content = Arrays.copyOfRange(plain, 0, plain.length - pad);
            // 16 字节随机串 + 4 字节长度（网络字节序）+ 消息 + 企业 ID
            int len = ByteBuffer.wrap(content, 16, 4).getInt();
            if (len < 0 || 20 + len > content.length) return null;
            String msg = new String(content, 20, len, StandardCharsets.UTF_8);
            String receiveId = new String(content, 20 + len, content.length - 20 - len, StandardCharsets.UTF_8);
            if (!receiveId.equals(corpId())) return null;
            return msg;
        }
        catch (Exception e)
        {
            log.warn("[企业微信] 回调解密失败: {}", e.getMessage());
            return null;
        }
    }

    // ================= 小工具 =================

    private String base()
    {
        return apiBase == null ? "https://qyapi.weixin.qq.com" : apiBase.replaceAll("/+$", "");
    }

    private String corpId() { return value("push.wecom.corpId"); }

    private String agentId() { return value("push.wecom.agentId"); }

    private String secret() { return value("push.wecom.secret"); }

    private Object toAgentId(String v)
    {
        try { return Long.valueOf(v); } catch (Exception e) { return v; }
    }

    private static String cutBytes(String s, int maxBytes)
    {
        if (s.getBytes(StandardCharsets.UTF_8).length <= maxBytes) return s;
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (int i = 0; i < s.length(); )
        {
            int cp = s.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            int n = ch.getBytes(StandardCharsets.UTF_8).length;
            if (used + n > maxBytes - 3) break;
            sb.append(ch);
            used += n;
            i += Character.charCount(cp);
        }
        return sb.append("…").toString();
    }

    private String enc(String s)
    {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private String value(String key)
    {
        try
        {
            String v = settingMapper.selectValue(key);
            return v == null ? "" : v.trim();
        }
        catch (Exception e)
        {
            return "";
        }
    }

    private static class WecomException extends Exception
    {
        WecomException(String msg) { super(msg); }
    }
}

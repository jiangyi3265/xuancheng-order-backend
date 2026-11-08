package com.ruoyi.jiedan.service;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.exception.ServiceException;
import nl.martijndwars.webpush.*;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.util.EntityUtils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

@Service
public class JiedanWebPush {
    private static final Logger log = LoggerFactory.getLogger(JiedanWebPush.class);
    private final JdbcTemplate db;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();
    private final ExecutorService queue = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(512));
    private volatile PushService sender;
    private String publicKey;

    public JiedanWebPush(JdbcTemplate db) { this.db = db; }

    public synchronized String key() {
        if (sender != null) return publicKey;
        try {
            if (Security.getProvider("BC") == null) Security.addProvider(new BouncyCastleProvider());
            if (db.queryForObject("select count(*) from jiedan_webpush_key where id=1", Integer.class) == 0) {
                KeyPairGenerator gen = KeyPairGenerator.getInstance("EC", "BC");
                gen.initialize(new ECGenParameterSpec("secp256r1"));
                KeyPair pair = gen.generateKeyPair();
                Base64.Encoder base = Base64.getUrlEncoder().withoutPadding();
                byte[] point = ((org.bouncycastle.jce.interfaces.ECPublicKey)pair.getPublic()).getQ().getEncoded(false);
                byte[] scalar = org.bouncycastle.util.BigIntegers.asUnsignedByteArray(32, ((java.security.interfaces.ECPrivateKey)pair.getPrivate()).getS());
                db.update("insert ignore into jiedan_webpush_key values(1,?,?)", base.encodeToString(point), base.encodeToString(scalar));
            }
            Map<String,Object> keys = db.queryForMap("select * from jiedan_webpush_key where id=1");
            publicKey = (String)keys.get("public_key");
            sender = new PushService(publicKey,(String)keys.get("private_key"),"https://jiedanxt.oksja.cn");
            return publicKey;
        } catch (Exception e) { throw new ServiceException("系统推送初始化失败"); }
    }

    public void subscribe(long member, Map<String,String> data) {
        try {
            String endpoint = data.get("endpoint");
            URI uri = URI.create(endpoint);
            String host = uri.getHost();
            if (!"https".equals(uri.getScheme()) || host == null || uri.getPort() != -1 || uri.getUserInfo() != null ||
                uri.getFragment() != null || endpoint.length() > 2048 || !(host.equals("fcm.googleapis.com") ||
                host.endsWith(".push.services.mozilla.com") || host.endsWith(".notify.windows.com") ||
                host.endsWith(".push.apple.com"))) throw new IllegalArgumentException();
            String binding = UUID.fromString(data.get("binding")).toString();
            String auth = data.get("auth"), point = data.get("p256dh");
            if (Base64.getUrlDecoder().decode(auth).length != 16 || Base64.getUrlDecoder().decode(point).length != 65) throw new IllegalArgumentException();
            key();
            new Notification(endpoint,point,auth,"");
            db.update("insert into jiedan_webpush_device(endpoint_hash,member_id,binding,endpoint,p256dh,auth_key) values(sha2(?,256),?,?,?,?,?) " +
                      "on duplicate key update member_id=values(member_id),binding=values(binding),p256dh=values(p256dh),auth_key=values(auth_key)",
                      endpoint,member,binding,endpoint,point,auth);
        } catch (IllegalArgumentException | NullPointerException | GeneralSecurityException e) {
            throw new ServiceException("浏览器推送订阅无效");
        }
    }

    public void remove(long member, String binding) {
        db.update("delete from jiedan_webpush_device where member_id=? and binding=?",member,binding);
    }

    public void publish(long member, String title, String body, String path) {
        Runnable dispatch = () -> {
            try {
                queue.execute(() -> {
                    try {
                        for (Map<String,Object> device : db.queryForList("select d.* from jiedan_webpush_device d join sys_user u on u.user_id=d.member_id where d.member_id=? and u.status='0' and u.del_flag='0'",member)) {
                            try { send(device,title,body,path); }
                            catch (Exception e) { log.warn("系统推送暂未送达，账号 {}，原因 {}", member, failureReason(e)); }
                        }
                    } catch (Exception e) { log.warn("系统推送无法读取订阅"); }
                });
            } catch (RejectedExecutionException e) { log.warn("系统推送队列已满"); }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { dispatch.run(); }
            });
        else dispatch.run();
    }

    public void test(long member, String binding) {
        List<Map<String,Object>> rows = db.queryForList("select * from jiedan_webpush_device where member_id=? and binding=?", member,binding);
        if (rows.size()!=1) throw new ServiceException("请先在本机开启系统通知");
        try { send(rows.get(0),"测试通知","这台设备已收到玄成接单的系统提醒。","/#/messages"); }
        catch (Exception e) {
            String reason=failureReason(e);
            log.warn("浏览器通知测试失败，账号 {}，原因 {}",member,reason);
            throw new ServiceException("后台推送未送达（"+reason+"）");
        }
    }

    private String failureReason(Exception e) {
        if(e instanceof GeneralSecurityException) return "加密失败：" + e.getClass().getSimpleName();
        if(e instanceof java.net.http.HttpTimeoutException) return "推送服务连接超时";
        if(e.getMessage()!=null && e.getMessage().matches("HTTP [0-9]{3}")) return e.getMessage();
        return e.getClass().getSimpleName();
    }

    private void send(Map<String,Object> device, String title, String body, String path) throws Exception {
        key();
        String text = body == null ? "" : body;
        String payload = JSON.toJSONString(Map.of("title",title,"body",text.substring(0,Math.min(240,text.length())),
            "binding",device.get("binding"),"path",path));
        Notification notification = new Notification((String)device.get("endpoint"),(String)device.get("p256dh"),
            (String)device.get("auth_key"),payload);
        HttpPost post = sender.preparePost(notification,Encoding.AES128GCM);
        HttpRequest.Builder req = HttpRequest.newBuilder(post.getURI()).timeout(Duration.ofSeconds(10));
        for (org.apache.http.Header h : post.getAllHeaders()) req.header(h.getName(),h.getValue());
        int code = client.send(req.POST(HttpRequest.BodyPublishers.ofByteArray(EntityUtils.toByteArray(post.getEntity()))).build(),
            HttpResponse.BodyHandlers.discarding()).statusCode();
        if (code == 410 || code == 404) remove(((Number)device.get("member_id")).longValue(),(String)device.get("binding"));
        if (code/100 != 2) throw new java.io.IOException("HTTP "+code);
    }
}

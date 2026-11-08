package com.ruoyi.jiedan.controller;

import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.jiedan.service.JiedanWecomService;

/**
 * 企业微信「接收消息服务器」回调地址：https://你的域名/jiedan/wecom/callback
 * 企业微信要求先配好它（或可信域名）才能设置「企业可信IP」，所以这里只负责通过校验；
 * 成员在企业微信里给应用发的消息不处理，只回空串。
 */
@RestController
@RequestMapping("/jiedan/wecom")
public class JiedanWecomController
{
    @Autowired
    private JiedanWecomService wecom;

    /** 企业微信保存设置时会来访问一次：签名对了就原样返回解密后的 echostr */
    @Anonymous
    @GetMapping(value = "/callback", produces = "text/plain;charset=UTF-8")
    public String verify(@RequestParam(value = "msg_signature", required = false) String signature,
                         @RequestParam(value = "timestamp", required = false) String timestamp,
                         @RequestParam(value = "nonce", required = false) String nonce,
                         @RequestParam(value = "echostr", required = false) String echostr,
                         HttpServletResponse response)
    {
        String plain = wecom.verifyUrl(signature, timestamp, nonce, echostr);
        if (plain == null)
        {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return "";
        }
        return plain;
    }

    /** 成员给应用发消息时企业微信会推过来：不处理内容，回空串表示收到 */
    @Anonymous
    @PostMapping(value = "/callback", produces = "text/plain;charset=UTF-8")
    public String receive(@RequestBody(required = false) String body)
    {
        return "";
    }
}

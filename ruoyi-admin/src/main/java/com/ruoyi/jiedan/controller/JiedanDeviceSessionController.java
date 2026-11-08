package com.ruoyi.jiedan.controller;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseCookie;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.framework.web.service.TokenService;

/** The WeChat webview can recover the same revocable server session from a first-party cookie. */
@RestController
@RequestMapping("/jiedan/session")
public class JiedanDeviceSessionController {
    private static final String COOKIE = "__Secure-xc_wechat";
    private static final Duration LIFETIME = Duration.ofDays(30);
    private final TokenService tokens;

    public JiedanDeviceSessionController(TokenService tokens) {
        this.tokens=tokens;
    }

    @PostMapping("/remember")
    @PreAuthorize("@ss.hasAnyRoles('admin,employee')")
    public AjaxResult remember(HttpServletRequest request,HttpServletResponse response) {
        if (!allowed(request,response)) return AjaxResult.error(403,"请求来源无效");
        String auth=request.getHeader("Authorization");
        if (auth==null || !auth.startsWith("Bearer ")) return AjaxResult.error(401,"请先登录");
        LoginUser user=tokens.getLoginUser(request);
        if (user==null) return AjaxResult.error(401,"请重新登录");
        long now=System.currentTimeMillis();
        if (user.getRememberedUntil()==null) user.setRememberedUntil(now+LIFETIME.toMillis());
        tokens.setLoginUser(user);
        cookie(response,auth.substring(7),Duration.ofMillis(user.getRememberedUntil()-now));
        return AjaxResult.success();
    }

    @Anonymous
    @PostMapping("/restore")
    public AjaxResult restore(HttpServletRequest request,HttpServletResponse response) {
        if (!allowed(request,response)) return AjaxResult.error(403,"请求来源无效");
        String value=rememberedToken(request);
        LoginUser user=tokens.resolveToken(value);
        if (user==null || user.getRememberedUntil()==null) {
            if(value!=null) cookie(response,"",Duration.ZERO);
            return AjaxResult.success();
        }
        return AjaxResult.success(Collections.singletonMap("token",value));
    }

    @Anonymous
    @PostMapping("/forget")
    public AjaxResult forget(HttpServletRequest request,HttpServletResponse response) {
        if (!allowed(request,response)) return AjaxResult.error(403,"请求来源无效");
        LoginUser remembered=tokens.resolveToken(rememberedToken(request));
        if (remembered!=null) tokens.delLoginUser(remembered.getToken());
        LoginUser current=tokens.getLoginUser(request);
        if (current!=null) tokens.delLoginUser(current.getToken());
        cookie(response,"",Duration.ZERO);
        return AjaxResult.success();
    }

    private boolean allowed(HttpServletRequest request,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        if (!"1".equals(request.getHeader("X-XC-Device"))) return false;
        String origin=request.getHeader("Origin");
        if (origin==null) return !"cross-site".equals(request.getHeader("Sec-Fetch-Site"));
        try {
            URI uri=URI.create(origin);
            return "https".equalsIgnoreCase(uri.getScheme()) &&
                    request.getHeader("Host").equalsIgnoreCase(uri.getRawAuthority());
        } catch(IllegalArgumentException ex) { return false; }
    }

    private String rememberedToken(HttpServletRequest request) {
        Cookie[] cookies=request.getCookies();
        if (cookies!=null) for (Cookie c:cookies) if (COOKIE.equals(c.getName())) return c.getValue();
        return null;
    }

    private void cookie(HttpServletResponse response,String value,Duration age) {
        response.addHeader("Set-Cookie",ResponseCookie.from(COOKIE,value)
                .httpOnly(true).secure(true).sameSite("Strict").path("/jiedan/session").maxAge(age).build().toString());
    }
}

package com.ruoyi.jiedan.controller;

import java.nio.charset.StandardCharsets;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.jiedan.service.AgentLinkService;
import com.ruoyi.jiedan.service.IssueSheet;

@RestController
@RequestMapping("/jiedan/agent")
public class JiedanAgentLinkController
{
    private final AgentLinkService links;
    public JiedanAgentLinkController(AgentLinkService links) { this.links = links; }

    @PreAuthorize("@ss.hasAnyRoles('admin,employee')")
    @GetMapping("/orders/{id}/link")
    public AjaxResult status(@PathVariable long id, HttpServletResponse response)
    {
        response.setHeader("Cache-Control", "no-store");
        return AjaxResult.success(links.get(id));
    }

    @PreAuthorize("@ss.hasAnyRoles('admin,employee')")
    @PostMapping("/orders/{id}/link/{action:generate|regenerate|disable}")
    public AjaxResult change(@PathVariable long id, @PathVariable String action, HttpServletRequest request, HttpServletResponse response)
    {
        response.setHeader("Cache-Control", "no-store");
        return AjaxResult.success(links.change(id, action, requestOrigin(request)));
    }

    @Anonymous
    @GetMapping("/sheet/{token}")
    public ResponseEntity<byte[]> sheet(@PathVariable String token, @RequestParam(defaultValue = "") String scope, HttpServletRequest request)
    {
        try
        {
            String text = links.read(token, "all".equals(scope), requestOrigin(request));
            return text == null ? response(404, "plain", "找不到") : response(200, "markdown", text);
        }
        catch (AgentLinkService.RateLimit limited)
        {
            return response(429, "plain", "读取太频繁，请稍后再试");
        }
    }

    // Production nginx connects over loopback and preserves Host. Forwarded headers supplied
    // by a direct remote client must not change the shared URL.
    private String requestOrigin(HttpServletRequest request)
    {
        String direct = IssueSheet.origin(request.getRequestURL().toString());
        String peer = request.getRemoteAddr();
        if ("127.0.0.1".equals(peer) || "::1".equals(peer) || "0:0:0:0:0:0:0:1".equals(peer))
        {
            String scheme = request.getHeader("X-Forwarded-Proto");
            if (!direct.isEmpty() && ("https".equals(scheme) || "http".equals(scheme)))
                return scheme + direct.substring(direct.indexOf(':'));
        }
        return direct;
    }

    private ResponseEntity<byte[]> response(int status, String subtype, String text)
    {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").header("Referrer-Policy", "no-referrer")
                .header("X-Robots-Tag", "noindex, nofollow").header("Content-Security-Policy", "default-src 'none'; sandbox")
                .contentType(new MediaType("text", subtype, StandardCharsets.UTF_8));
        if (status == 429) builder.header("Retry-After", "60");
        return builder.body(text.getBytes(StandardCharsets.UTF_8));
    }
}

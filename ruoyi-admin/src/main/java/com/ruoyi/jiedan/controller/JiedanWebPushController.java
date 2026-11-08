package com.ruoyi.jiedan.controller;

import java.util.Map;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.jiedan.service.JiedanAccess;
import com.ruoyi.jiedan.service.JiedanWebPush;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/jiedan/webpush")
@PreAuthorize("@ss.hasAnyRoles('admin,employee')")
public class JiedanWebPushController {
    private final JiedanAccess access;
    private final JiedanWebPush push;
    public JiedanWebPushController(JiedanAccess access,JiedanWebPush push) { this.access=access; this.push=push; }
    @GetMapping("/key") public AjaxResult key() { return AjaxResult.success(Map.of("key",push.key())); }
    @PostMapping("/device") public AjaxResult subscribe(@RequestBody Map<String,String> data) {
        push.subscribe(access.currentUserId(),data); return AjaxResult.success();
    }
    @DeleteMapping("/device/{binding}") public AjaxResult remove(@PathVariable String binding) {
        push.remove(access.currentUserId(),binding); return AjaxResult.success();
    }
    @PostMapping("/test/{binding}") public AjaxResult test(@PathVariable String binding) {
        push.test(access.currentUserId(),binding); return AjaxResult.success();
    }
}

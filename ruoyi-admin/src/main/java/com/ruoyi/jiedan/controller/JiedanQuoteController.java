package com.ruoyi.jiedan.controller;

import java.util.Map;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.jiedan.service.JiedanQuoteService;

@RestController
@RequestMapping("/jiedan/quote")
@PreAuthorize("@ss.hasAnyRoles('admin,employee')")
public class JiedanQuoteController {
    @Autowired private JiedanQuoteService service;
    @GetMapping("/list") public AjaxResult list(){return AjaxResult.success(service.list());}
    @GetMapping("/numbering") public AjaxResult numbering() { return AjaxResult.success(service.numbering()); }
    @GetMapping("/{id}") public AjaxResult detail(@PathVariable Long id){return AjaxResult.success(service.detail(id));}
    @PostMapping public AjaxResult create(@RequestBody Map<String,Object> body){return AjaxResult.success(service.create(body));}
    @PutMapping("/{id}") public AjaxResult edit(@PathVariable Long id,@RequestBody Map<String,Object> input) {
        return AjaxResult.success(service.edit(id,input));
    }
    @PutMapping("/read") public AjaxResult markRead(@RequestBody List<Map<String,Long>> items) {
        service.markRead(items);
        return AjaxResult.success();
    }
    @GetMapping("/{id}/conversation") public AjaxResult conversation(@PathVariable Long id, @RequestParam(required=false) Long before) {
        return AjaxResult.success(service.conversation(id,before));
    }
    @PostMapping("/{id}/conversation") public AjaxResult reply(@PathVariable Long id,@RequestBody Map<String,Object> body) {
        return AjaxResult.success(service.reply(id,body));
    }
    @PutMapping("/{id}/title")
    public AjaxResult rename(@PathVariable Long id, @RequestBody Map<String,Object> body) {
        return AjaxResult.success(service.rename(id, body));
    }
    @PutMapping("/{id}/price") public AjaxResult price(@PathVariable Long id,@RequestBody Map<String,Object> body){return AjaxResult.success(service.price(id,body));}
    @PutMapping("/{id}/assign") public AjaxResult assign(@PathVariable Long id,@RequestBody Map<String,Object> body){return AjaxResult.success(service.assign(id,body));}
    @PostMapping("/{id}/convert") public AjaxResult convert(@PathVariable Long id,@RequestBody Map<String,Object> body){return AjaxResult.success(service.convert(id,body));}
    @DeleteMapping("/{id}") public AjaxResult delete(@PathVariable Long id,@RequestBody Map<String,Object> body){service.delete(id,body);return AjaxResult.success();}
}

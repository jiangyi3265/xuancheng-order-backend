package com.ruoyi.jiedan.controller;

import java.io.IOException;
import java.net.URLEncoder;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.jiedan.service.IJiedanOrderService;
import com.ruoyi.jiedan.service.JiedanAuditService;
import com.ruoyi.jiedan.service.JiedanOrderExporter;

/**
 * 接单订单 Controller。
 * 管理接口只允许管理端角色访问，客户账号只能提交需求。
 */
@RestController
@RequestMapping("/jiedan/order")
public class JiedanOrderController extends BaseController
{
    @Autowired
    private IJiedanOrderService service;

    @Autowired
    private JiedanOrderExporter exporter;

    @Autowired
    private JiedanAuditService audit;

    /** 把导出条件整理成一句人话，写进操作日志。 */
    private String describe(Map<String, String> params)
    {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> e : params.entrySet())
        {
            if (StringUtils.isNotEmpty(e.getValue())) parts.add(e.getKey() + "=" + e.getValue());
        }
        return parts.isEmpty() ? "（无筛选条件）" : "（条件：" + String.join("，", parts) + "）";
    }

    @GetMapping("/list")
    @PreAuthorize("@ss.hasAnyRoles('admin,employee')")
    public AjaxResult list()
    {
        return AjaxResult.success(service.listVO());
    }

    /** 变更戳：后台每 10 秒问一次，戳没变就不用重新拉整个列表。 */
    @GetMapping("/stamp")
    @PreAuthorize("@ss.hasAnyRoles('admin,employee')")
    public AjaxResult stamp()
    {
        // 注意不能直接 success(String)：那个重载会把字符串当成提示语 msg，而不是 data
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("stamp", service.stamp());
        return AjaxResult.success(vo);
    }

    /** 导出 Excel：条件与列表页筛选一致；只会导出当前登录人有权看到的项目，并记入操作日志。 */
    @GetMapping("/export")
    @PreAuthorize("@ss.hasAnyRoles('admin,employee')")
    public void export(@RequestParam Map<String, String> params, HttpServletResponse response) throws IOException
    {
        List<Map<String, Object>> rows = service.listFiltered(params);
        byte[] bytes = exporter.build(rows);
        audit.record("order.export", "order", null, "订单导出", "导出 " + rows.size() + " 条" + describe(params));

        String name = URLEncoder.encode("接单列表_" + LocalDate.now() + ".xlsx", "UTF-8").replace("+", "%20");
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + name);
        response.setHeader("Access-Control-Expose-Headers", "Content-Disposition");
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
    }

    /** 回收站：总管理员看全部，下属管理员只看自己名下。 */
    @GetMapping("/trash")
    @PreAuthorize("@ss.hasAnyRoles('admin,employee')")
    public AjaxResult trash()
    {
        return AjaxResult.success(service.listTrash());
    }

    @PutMapping("/{id}/restore")
    @PreAuthorize("@ss.hasAnyRoles('admin,employee')")
    public AjaxResult restore(@PathVariable Long id)
    {
        service.restore(id);
        return AjaxResult.success();
    }

    /** 彻底删除：仅总管理员，且只能删回收站里的项目。 */
    @DeleteMapping("/{id}/purge")
    @PreAuthorize("@ss.hasRole('admin')")
    public AjaxResult purge(@PathVariable Long id)
    {
        service.purge(id);
        return AjaxResult.success();
    }

    @GetMapping("/{id}")
    @PreAuthorize("@ss.hasAnyRoles('admin,employee')")
    public AjaxResult get(@PathVariable Long id)
    {
        return AjaxResult.success(service.getVO(id));
    }

    @GetMapping("/{id}/version")
    @PreAuthorize("@ss.hasAnyRoles('admin,employee')")
    public AjaxResult version(@PathVariable Long id)
    {
        Map<String, Object> vo = service.getVersion(id);
        return vo == null ? AjaxResult.error("项目不存在") : AjaxResult.success(vo);
    }

    @PostMapping
    @PreAuthorize("@ss.hasAnyRoles('admin,employee')")
    public AjaxResult add(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(service.create(body));
    }

    @PutMapping
    @PreAuthorize("@ss.hasAnyRoles('admin,employee')")
    public AjaxResult edit(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(service.update(body));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@ss.hasAnyRoles('admin,employee')")
    public AjaxResult remove(@PathVariable Long id)
    {
        service.delete(id);
        return AjaxResult.success();
    }

    @PostMapping("/bug")
    @PreAuthorize("@ss.hasAnyRoles('admin,employee')")
    public AjaxResult bug(@RequestBody Map<String, Object> body)
    {
        Map<String, Object> vo = service.createBug(body);
        if (vo == null) return AjaxResult.error("Bug 内容不能为空或订单不存在");
        return AjaxResult.success(vo);
    }

    @DeleteMapping("/bug/{id}")
    @PreAuthorize("@ss.hasAnyRoles('admin,employee')")
    public AjaxResult removeBug(@PathVariable Long id)
    {
        Map<String, Object> vo = service.deleteBug(id);
        if (vo == null) return AjaxResult.error("Bug 不存在");
        return AjaxResult.success(vo);
    }

    @PostMapping("/bug/{id}/update")
    @PreAuthorize("@ss.hasAnyRoles('admin,employee')")
    public AjaxResult bugUpdate(@PathVariable Long id, @RequestBody Map<String, Object> body)
    {
        Map<String, Object> vo = service.addBugUpdate(id, body);
        if (vo == null) return AjaxResult.error("追加内容不能为空或 Bug 不存在");
        return AjaxResult.success(vo);
    }

    @PutMapping("/bug/{id}/status")
    @PreAuthorize("@ss.hasAnyRoles('admin,employee')")
    public AjaxResult bugStatus(@PathVariable Long id, @RequestBody Map<String, Object> body)
    {
        Map<String, Object> vo = service.changeBugStatus(id, body);
        if (vo == null) return AjaxResult.error("Bug 不存在");
        return AjaxResult.success(vo);
    }

    @GetMapping("/notes/{id}")
    @PreAuthorize("@ss.hasAnyRoles('admin,employee')")
    public AjaxResult getNotes(@PathVariable Long id)
    {
        return AjaxResult.success(service.getNotes(id));
    }

    @PutMapping("/notes")
    @PreAuthorize("@ss.hasAnyRoles('admin,employee')")
    public AjaxResult saveNotes(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(service.saveNotes(body));
    }

    @PostMapping("/progress")
    @PreAuthorize("@ss.hasAnyRoles('admin,employee')")
    public AjaxResult progress(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(service.addProgress(body));
    }

    @PutMapping("/status")
    @PreAuthorize("@ss.hasAnyRoles('admin,employee')")
    public AjaxResult status(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(service.changeStatus(body));
    }

    @PutMapping("/read")
    @PreAuthorize("@ss.hasAnyRoles('admin,employee')")
    public AjaxResult read(@RequestBody Map<String, Object> body)
    {
        Long id = body.get("id") == null ? null : Long.valueOf(body.get("id").toString());
        Long memberId = body.get("memberId") == null ? null : Long.valueOf(body.get("memberId").toString());
        return AjaxResult.success(service.markRead(id, memberId));
    }

    @PostMapping("/revision")
    @PreAuthorize("@ss.hasAnyRoles('admin,employee')")
    public AjaxResult revision(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(service.addRevision(body));
    }

    @PostMapping("/nudge")
    @PreAuthorize("@ss.hasAnyRoles('admin,employee')")
    public AjaxResult nudge(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(service.nudge(body));
    }

    /** 客户需求提交：必须先登录客户账号；负责人由该客户的归属管理员决定，不看前端传值。 */
    @PostMapping("/intake")
    @PreAuthorize("@ss.hasRole('customer')")
    public AjaxResult intake(@RequestBody Map<String, Object> body)
    {
        String username = SecurityUtils.getUsername();
        body.remove("ownerId");
        body.remove("byMemberId");
        if (StringUtils.isEmpty((String) body.get("byUserName")))
        {
            body.put("byUserName", "客户：" + username);
        }
        return AjaxResult.success(service.createFromCustomer(body, username));
    }
}

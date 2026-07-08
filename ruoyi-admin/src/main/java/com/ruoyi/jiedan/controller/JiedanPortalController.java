package com.ruoyi.jiedan.controller;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.jiedan.service.IJiedanOrderService;

/**
 * 客户门户 Controller。
 * 客户登录后：查看自己的项目、查看单个项目详情与沟通记录、发送留言（文字/图片/视频/语音）。
 * 所有接口均按当前登录账号 scope，只能访问归属自己的订单。
 */
@RestController
@RequestMapping("/jiedan/portal")
public class JiedanPortalController extends BaseController
{
    @Autowired
    private IJiedanOrderService service;

    /** 我的项目列表 */
    @GetMapping("/orders")
    public AjaxResult myOrders()
    {
        return AjaxResult.success(service.listByCustomer(SecurityUtils.getUsername()));
    }

    /** 我的项目详情（含沟通记录时间线） */
    @GetMapping("/orders/{id}")
    public AjaxResult myOrder(@PathVariable Long id)
    {
        Map<String, Object> vo = service.getForCustomer(id, SecurityUtils.getUsername());
        if (vo == null) return AjaxResult.error("项目不存在或无权查看");
        return AjaxResult.success(vo);
    }

    /** 客户删除自己提交的问题 */
    @DeleteMapping("/orders/{id}")
    public AjaxResult removeMyOrder(@PathVariable Long id)
    {
        if (!service.deleteForCustomer(id, SecurityUtils.getUsername()))
        {
            return AjaxResult.error("项目不存在或无权删除");
        }
        return AjaxResult.success();
    }

    /** 客户在项目内快速创建 Bug，独立于聊天记录。 */
    @PostMapping("/bug")
    public AjaxResult bug(@RequestBody Map<String, Object> body)
    {
        Map<String, Object> vo = service.createBugForCustomer(body, SecurityUtils.getUsername(), nickName());
        if (vo == null) return AjaxResult.error("Bug 内容不能为空或项目不存在");
        return AjaxResult.success(vo);
    }

    /** 客户删除自己项目下的 Bug。 */
    @DeleteMapping("/bug/{id}")
    public AjaxResult removeBug(@PathVariable Long id)
    {
        Map<String, Object> vo = service.deleteBugForCustomer(id, SecurityUtils.getUsername());
        if (vo == null) return AjaxResult.error("Bug 不存在或无权删除");
        return AjaxResult.success(vo);
    }

    /** 发送留言（content + attachments[图片/视频/语音]） */
    @PostMapping("/message")
    public AjaxResult message(@RequestBody Map<String, Object> body)
    {
        Long orderId = body.get("orderId") == null ? null : Long.valueOf(body.get("orderId").toString());
        if (orderId == null) return AjaxResult.error("缺少 orderId");
        String content = (String) body.get("content");
        Object attachments = body.get("attachments");
        if (StringUtils.isEmpty(content) && attachments == null)
        {
            return AjaxResult.error("消息内容不能为空");
        }
        Map<String, Object> vo = service.customerMessage(
                orderId, SecurityUtils.getUsername(), nickName(), content, attachments);
        if (vo == null) return AjaxResult.error("项目不存在或无权操作");
        return AjaxResult.success(vo);
    }

    /** 当前登录客户的显示名（昵称优先，回退登录名） */
    private String nickName()
    {
        try
        {
            LoginUser user = SecurityUtils.getLoginUser();
            if (user != null && user.getUser() != null && StringUtils.isNotEmpty(user.getUser().getNickName()))
            {
                return user.getUser().getNickName();
            }
        }
        catch (Exception ignore) {}
        return SecurityUtils.getUsername();
    }
}

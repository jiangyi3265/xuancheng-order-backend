package com.ruoyi.jiedan.controller;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.jiedan.service.JiedanAttachmentStorage;

@RestController
@RequestMapping("/jiedan/media")
public class JiedanMediaController
{
    @Autowired
    private JiedanAttachmentStorage storage;

    /**
     * 上传附件：必须是后台账号或客户账号。
     * 注意：下面的匿名读取接口会让框架放行 /jiedan/media/*，连 POST /jiedan/media/upload 也被一并放行，
     * 所以这里必须自己再校验一次登录和角色，否则任何人都能匿名往服务器上传文件。
     */
    @PostMapping("/upload")
    @PreAuthorize("@ss.hasAnyRoles('admin,employee,customer')")
    public AjaxResult upload(@RequestParam("file") MultipartFile file)
    {
        try
        {
            Map<String, Object> item = storage.store(file);
            return AjaxResult.success(item);
        }
        catch (Exception e)
        {
            return AjaxResult.error(e.getMessage());
        }
    }

    @Anonymous
    @GetMapping("/{fileName:.+}")
    public void media(@PathVariable String fileName, HttpServletResponse response) throws Exception
    {
        Path path = storage.resolve(fileName);
        if (path == null)
        {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String contentType = storage.contentType(fileName);
        response.setContentType(contentType);
        response.setContentLengthLong(Files.size(path));
        response.setHeader("Cache-Control", "public, max-age=31536000, immutable");
        response.setHeader("X-Content-Type-Options", "nosniff");
        // 附件链接是公开的，任何人都能直接在浏览器里打开。SVG 里可以夹带脚本，
        // 所以一律加上沙箱策略：放在 <img> 里正常显示，直接打开时脚本不执行、也拿不到登录凭证。
        if ("image/svg+xml".equals(contentType) || "application/octet-stream".equals(contentType))
        {
            response.setHeader("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox");
        }
        if ("application/octet-stream".equals(contentType))
        {
            // 未知类型一律当下载处理，不在浏览器里直接渲染
            response.setHeader("Content-Disposition", "attachment");
        }
        Files.copy(path, response.getOutputStream());
    }
}

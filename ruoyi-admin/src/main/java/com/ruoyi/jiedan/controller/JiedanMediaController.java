package com.ruoyi.jiedan.controller;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
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

    @PostMapping("/upload")
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
        response.setContentType(storage.contentType(fileName));
        response.setContentLengthLong(Files.size(path));
        response.setHeader("Cache-Control", "public, max-age=31536000, immutable");
        response.setHeader("X-Content-Type-Options", "nosniff");
        Files.copy(path, response.getOutputStream());
    }
}

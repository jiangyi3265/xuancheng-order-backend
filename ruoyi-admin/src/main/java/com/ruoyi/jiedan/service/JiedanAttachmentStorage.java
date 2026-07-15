package com.ruoyi.jiedan.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.config.RuoYiConfig;

@Service
public class JiedanAttachmentStorage
{
    private static final Logger log = LoggerFactory.getLogger(JiedanAttachmentStorage.class);
    private static final long MAX_FILE_SIZE = 50L * 1024 * 1024;

    public Map<String, Object> store(MultipartFile file) throws IOException
    {
        if (file == null || file.isEmpty()) throw new IOException("附件不能为空");
        if (file.getSize() > MAX_FILE_SIZE) throw new IOException("单个附件不能超过 50MB");

        String originalName = safeName(file.getOriginalFilename());
        String ext = extension(originalName, file.getContentType());
        String fileName = newFileName(ext);
        Path target = mediaRoot().resolve(fileName);
        Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", originalName);
        result.put("url", mediaUrl(fileName));
        result.put("size", file.getSize());
        result.put("ext", ext);
        return result;
    }

    /** 将历史 data URL 附件落盘，避免每个接口重复传输 Base64。 */
    public synchronized NormalizedAttachments normalize(String json)
    {
        JSONArray items = parse(json);
        boolean changed = false;
        for (int i = 0; i < items.size(); i++)
        {
            JSONObject item = items.getJSONObject(i);
            if (item == null) continue;
            String url = item.getString("url");
            if (url == null || !url.startsWith("data:")) continue;
            try
            {
                int comma = url.indexOf(',');
                if (comma < 0 || !url.substring(0, comma).contains(";base64")) continue;
                String mime = url.substring(5, url.indexOf(';', 5));
                byte[] bytes = Base64.getDecoder().decode(url.substring(comma + 1));
                if (bytes.length == 0 || bytes.length > MAX_FILE_SIZE) continue;

                String ext = extension(item.getString("name"), mime);
                String fileName = newFileName(ext);
                Files.write(mediaRoot().resolve(fileName), bytes);
                item.put("url", mediaUrl(fileName));
                item.put("size", bytes.length);
                item.put("ext", ext);
                changed = true;
            }
            catch (Exception e)
            {
                log.warn("历史附件落盘失败，保留原数据", e);
            }
        }
        return new NormalizedAttachments(items, changed ? JSON.toJSONString(items) : json, changed);
    }

    public Path resolve(String fileName) throws IOException
    {
        if (fileName == null || !fileName.matches("[0-9a-f]{32}\\.[a-z0-9]{1,10}")) return null;
        Path root = mediaRoot();
        Path path = root.resolve(fileName).normalize();
        return path.startsWith(root) && Files.isRegularFile(path) ? path : null;
    }

    public String contentType(String fileName)
    {
        String ext = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if ("png".equals(ext)) return "image/png";
        if ("jpg".equals(ext) || "jpeg".equals(ext)) return "image/jpeg";
        if ("gif".equals(ext)) return "image/gif";
        if ("webp".equals(ext)) return "image/webp";
        if ("svg".equals(ext)) return "image/svg+xml";
        if ("mp4".equals(ext)) return "video/mp4";
        if ("webm".equals(ext)) return "video/webm";
        if ("mp3".equals(ext)) return "audio/mpeg";
        if ("wav".equals(ext)) return "audio/wav";
        if ("ogg".equals(ext)) return "audio/ogg";
        if ("m4a".equals(ext)) return "audio/mp4";
        if ("pdf".equals(ext)) return "application/pdf";
        return "application/octet-stream";
    }

    private Path mediaRoot() throws IOException
    {
        Path root = Path.of(RuoYiConfig.getProfile(), "jiedan-media").toAbsolutePath().normalize();
        Files.createDirectories(root);
        return root;
    }

    private JSONArray parse(String json)
    {
        if (json == null || json.trim().isEmpty()) return new JSONArray();
        try { return JSON.parseArray(json); }
        catch (Exception e) { return new JSONArray(); }
    }

    private String newFileName(String ext)
    {
        return UUID.randomUUID().toString().replace("-", "") + "." + ext;
    }

    private String mediaUrl(String fileName)
    {
        return "/jiedan/media/" + fileName;
    }

    private String safeName(String name)
    {
        if (name == null || name.trim().isEmpty()) return "附件";
        return name.replace('\\', '_').replace('/', '_');
    }

    private String extension(String name, String mime)
    {
        if (name != null)
        {
            int dot = name.lastIndexOf('.');
            if (dot > -1 && dot < name.length() - 1)
            {
                String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
                if (!ext.isEmpty() && ext.length() <= 10) return ext;
            }
        }
        if (mime != null)
        {
            String clean = mime.toLowerCase(Locale.ROOT);
            if (clean.contains("jpeg")) return "jpg";
            if (clean.contains("png")) return "png";
            if (clean.contains("webp")) return "webp";
            if (clean.contains("gif")) return "gif";
            if (clean.contains("svg")) return "svg";
            if (clean.contains("mp4")) return clean.startsWith("audio/") ? "m4a" : "mp4";
            if (clean.contains("webm")) return "webm";
            if (clean.contains("ogg")) return "ogg";
            if (clean.contains("mpeg")) return "mp3";
            if (clean.contains("wav")) return "wav";
            if (clean.contains("pdf")) return "pdf";
            if (clean.contains("zip")) return "zip";
        }
        return "bin";
    }

    public static class NormalizedAttachments
    {
        private final JSONArray items;
        private final String json;
        private final boolean changed;

        public NormalizedAttachments(JSONArray items, String json, boolean changed)
        {
            this.items = items;
            this.json = json;
            this.changed = changed;
        }

        public JSONArray getItems() { return items; }
        public String getJson() { return json; }
        public boolean isChanged() { return changed; }
    }
}

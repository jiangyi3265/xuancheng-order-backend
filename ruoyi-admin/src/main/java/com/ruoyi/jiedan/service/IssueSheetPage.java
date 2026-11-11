package com.ruoyi.jiedan.service;

import java.util.List;
import org.springframework.web.util.HtmlUtils;

/** Human-readable view of the same project, issues and attachments served to Markdown clients. */
final class IssueSheetPage
{
    private IssueSheetPage() {}

    private static final String STYLES = String.join("\n",
            "*{box-sizing:border-box}body{margin:0;background:#f4f6fa;color:#182b47;font:16px/1.6 system-ui,sans-serif}",
            "main{max-width:880px;margin:auto;padding:32px 16px 64px;overflow-wrap:anywhere}",
            "header,article{background:white;border:1px solid #dce4ef;border-radius:14px;padding:20px;margin-bottom:14px}",
            "h1,h2,h3,p,figure{margin:0}h1{font-size:clamp(22px,4vw,30px)}h2{font-size:20px}h3{font-size:16px}",
            "header p,.meta,figcaption{font-size:14px;color:#50627b}.meta{margin:4px 0 12px}",
            "a{color:#1759b8;text-underline-offset:3px}header a{display:inline-block;margin-top:10px}",
            ".body,details p{white-space:pre-wrap}.files{display:flex;flex-wrap:wrap;gap:12px;margin-top:10px}",
            "figure{width:min(100%,290px)}img{display:block;width:100%;height:auto;max-height:360px;object-fit:contain;background:#f6f8fb;border-radius:6px}",
            "section{padding:12px 0 0;margin-top:16px;border-top:1px solid #e1e8f1}section .meta{margin-top:12px;margin-bottom:4px}",
            "details{margin-top:12px}summary{cursor:pointer;color:#465a76}details p{font-size:14px}a:focus-visible,summary:focus-visible{outline:2px solid #2465bf}");

    static String render(String heading, String summary, List<IssueSheet.Issue> issues, boolean all)
    {
        StringBuilder out = new StringBuilder("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>")
                .append(escape(heading)).append("</title><style>").append(STYLES)
                .append("</style></head><body><main><header><h1>").append(escape(heading))
                .append("</h1><p>").append(escape(summary)).append("</p><a href=\"?format=md")
                .append(all ? "&amp;scope=all" : "").append("\">查看给 Codex 的 Markdown 清单</a>")
                .append("<details><summary>给 AI 的说明</summary><p>").append(escape(IssueSheet.GUIDE))
                .append("</p></details></header>");
        if (issues.isEmpty()) out.append("<p>").append(all ? "项目里还没有问题。" : "当前没有待处理需求。").append("</p>");
        for (IssueSheet.Issue issue : issues)
        {
            IssueSheet.Entry original = issue.original;
            out.append("<article id=\"issue-").append(issue.id).append("\" data-issue-id=\"").append(issue.id)
                    .append("\"><h2>需求 #").append(issue.id).append("</h2><p class=\"meta\">")
                    .append(escape(issue.status)).append(" · ").append(escape(original.author)).append(" · ")
                    .append(escape(original.time)).append("</p><p class=\"body\">")
                    .append(escape(original.content.isEmpty() ? "（只有附件）" : original.content)).append("</p>");
            files(out, original.files, issue.id, false);
            if (!issue.replies.isEmpty())
            {
                out.append("<section><h3>补充说明</h3>");
                for (IssueSheet.Entry reply : issue.replies)
                {
                    out.append("<p class=\"meta\">").append(escape(reply.author)).append(" · ")
                            .append(escape(reply.time)).append("</p>");
                    if (!reply.content.isEmpty()) out.append("<p class=\"body\">").append(escape(reply.content)).append("</p>");
                    files(out, reply.files, issue.id, true);
                }
                out.append("</section>");
            }
            out.append("</article>");
        }
        return out.append("</main></body></html>").toString();
    }

    private static void files(StringBuilder out, List<IssueSheet.Attachment> files, Long issueId, boolean reply)
    {
        if (files.isEmpty()) return;
        out.append("<div class=\"files\">");
        int number = 0;
        for (IssueSheet.Attachment file : files)
        {
            String path = escape(file.path);
            if (file.isImage())
            {
                String label = "需求 #" + issueId + (reply ? " 补充图片 " : " 图片 ") + (++number);
                out.append("<figure><a href=\"").append(path).append("\"><img src=\"").append(path)
                        .append("\" alt=\"").append(label).append("\"></a><figcaption>")
                        .append(escape(file.name)).append(" · <a href=\"").append(path)
                        .append("\">查看原图</a></figcaption></figure>");
            }
            else out.append("<a href=\"").append(path).append("\">").append(escape(file.label))
                    .append("：").append(escape(file.name)).append("</a>");
        }
        out.append("</div>");
    }

    private static String escape(String text) { return HtmlUtils.htmlEscape(text, "UTF-8"); }
}

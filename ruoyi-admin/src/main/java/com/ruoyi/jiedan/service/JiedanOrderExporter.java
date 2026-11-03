package com.ruoyi.jiedan.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ruoyi.jiedan.mapper.JiedanAccountMapper;

/**
 * 把订单列表写成 Excel（.xlsx）。数据由调用方按权限筛好再传进来，这里只负责排版。
 */
@Component
public class JiedanOrderExporter
{
    private static final int TEXT_MAX = 2000;

    private static final Map<String, String> STATUS = new LinkedHashMap<String, String>()
    {{
        put("pending", "待确认");
        put("doing", "进行中");
        put("review", "待验收");
        put("done", "已完成");
        put("cancelled", "已取消");
    }};

    private static final Map<String, String> CHANNEL = new LinkedHashMap<String, String>()
    {{
        put("wechat", "微信");
        put("taobao", "淘宝");
        put("douyin", "抖音");
        put("xianyu", "闲鱼");
        put("form", "客户提交");
        put("other", "其他");
    }};

    private static final Map<String, String> PRIORITY = new LinkedHashMap<String, String>()
    {{
        put("high", "高");
        put("medium", "中");
        put("low", "低");
    }};

    private static final String[] HEADERS = {
        "编号", "项目名", "客户", "联系方式", "渠道", "负责人", "状态", "优先级", "金额(元)", "收款",
        "截止日期", "创建时间", "最近更新", "改稿次数", "Bug 数", "需求说明"
    };
    private static final int[] WIDTHS = { 16, 30, 16, 18, 10, 12, 10, 8, 12, 8, 12, 17, 17, 10, 8, 60 };

    @Autowired
    private JiedanAccountMapper accountMapper;

    public byte[] build(List<Map<String, Object>> rows) throws IOException
    {
        Map<Long, String> names = ownerNames();
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream())
        {
            Sheet sheet = wb.createSheet("接单列表");

            Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle head = wb.createCellStyle();
            head.setFont(bold);
            head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            CellStyle money = wb.createCellStyle();
            money.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));

            Row header = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++)
            {
                Cell c = header.createCell(i);
                c.setCellValue(HEADERS[i]);
                c.setCellStyle(head);
                sheet.setColumnWidth(i, WIDTHS[i] * 256);
            }

            int r = 1;
            for (Map<String, Object> vo : rows)
            {
                Row row = sheet.createRow(r++);
                int c = 0;
                text(row, c++, vo.get("orderNo"));
                text(row, c++, vo.get("title"));
                text(row, c++, vo.get("customer"));
                text(row, c++, vo.get("contact"));
                text(row, c++, label(CHANNEL, vo.get("channel")));
                text(row, c++, ownerLabel(names, vo.get("ownerId")));
                text(row, c++, label(STATUS, vo.get("status")));
                text(row, c++, label(PRIORITY, vo.get("priority")));
                Cell amount = row.createCell(c++);
                amount.setCellValue(decimal(vo.get("amount")).doubleValue());
                amount.setCellStyle(money);
                text(row, c++, Boolean.TRUE.equals(vo.get("paid")) ? "已收款" : "未收款");
                text(row, c++, vo.get("deadline"));
                text(row, c++, vo.get("createTime"));
                text(row, c++, vo.get("updateTime"));
                number(row, c++, vo.get("revisions"));
                number(row, c++, vo.get("bugCount"));
                text(row, c++, vo.get("requirement"));
            }
            sheet.createFreezePane(0, 1);
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, Math.max(r - 1, 0), 0, HEADERS.length - 1));
            wb.write(out);
            return out.toByteArray();
        }
    }

    private Map<Long, String> ownerNames()
    {
        Map<Long, String> map = new HashMap<>();
        for (Map<String, Object> s : accountMapper.selectStaff(null))
        {
            Object id = s.get("userId");
            if (id instanceof Number) map.put(((Number) id).longValue(), String.valueOf(s.get("nickName")));
        }
        return map;
    }

    private String ownerLabel(Map<Long, String> names, Object ownerId)
    {
        if (!(ownerId instanceof Number)) return "";
        Long id = ((Number) ownerId).longValue();
        return names.getOrDefault(id, "用户#" + id);
    }

    private String label(Map<String, String> dict, Object code)
    {
        if (code == null) return "";
        return dict.getOrDefault(code.toString(), code.toString());
    }

    private void text(Row row, int col, Object value)
    {
        String s = value == null ? "" : value.toString();
        if (s.length() > TEXT_MAX) s = s.substring(0, TEXT_MAX) + "…";
        row.createCell(col).setCellValue(s);
    }

    private void number(Row row, int col, Object value)
    {
        row.createCell(col).setCellValue(value instanceof Number ? ((Number) value).doubleValue() : 0d);
    }

    private BigDecimal decimal(Object v)
    {
        if (v instanceof BigDecimal) return (BigDecimal) v;
        if (v instanceof Number) return new BigDecimal(v.toString());
        return BigDecimal.ZERO;
    }
}

package com.ruoyi.jiedan.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import com.ruoyi.jiedan.service.IJiedanOrderService;

/**
 * 接单系统定时任务：每天 9:00 检查逾期/临近截止并推送提醒。
 */
@Configuration
@EnableScheduling
public class JiedanScheduleConfig
{
    private static final Logger log = LoggerFactory.getLogger(JiedanScheduleConfig.class);

    @Autowired
    private IJiedanOrderService orderService;

    @Scheduled(cron = "0 0 9 * * ?")
    public void deadlineReminder()
    {
        try
        {
            int n = orderService.runDeadlineReminder();
            log.info("[接单] 截止提醒已推送 {} 条", n);
        }
        catch (Exception e)
        {
            log.warn("[接单] 截止提醒任务异常: {}", e.getMessage());
        }
    }
}

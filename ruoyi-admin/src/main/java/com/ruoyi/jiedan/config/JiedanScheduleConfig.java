package com.ruoyi.jiedan.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import com.ruoyi.jiedan.service.IJiedanOrderService;
import com.ruoyi.jiedan.service.JiedanDigestService;

/**
 * 接单系统定时任务：每天 9:00 检查逾期/临近截止并推送提醒，9:05 推送每日汇总。
 */
@Configuration
@EnableScheduling
public class JiedanScheduleConfig
{
    private static final Logger log = LoggerFactory.getLogger(JiedanScheduleConfig.class);

    @Autowired
    private IJiedanOrderService orderService;

    @Autowired
    private JiedanDigestService digestService;

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

    /** 每天 9:05 把"还有什么没处理"汇总成一条消息推给每个老板和员工（错开 9:00 的截止提醒） */
    @Scheduled(cron = "0 5 9 * * ?")
    public void dailyDigest()
    {
        try
        {
            int n = digestService.run();
            log.info("[接单] 每日汇总已推送 {} 条", n);
        }
        catch (Exception e)
        {
            log.warn("[接单] 每日汇总任务异常: {}", e.getMessage());
        }
    }
}

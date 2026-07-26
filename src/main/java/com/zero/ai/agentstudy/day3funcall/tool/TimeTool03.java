package com.zero.ai.agentstudy.day3funcall.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 时间查询工具（第五章）
 *
 * 教学要点：
 * 1. LLM 训练数据有截止时间，不知道"现在几点"，必须靠 Tool 拿实时时间。
 * 2. 单一职责：只负责返回当前时间。
 * 3. 支持时区参数，非法时区兜底为系统默认。
 *
 * @author ZeroAi
 */
@Slf4j
@Component
public class TimeTool03 {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 获取当前时间。
     *
     * @param zone 时区，如 Asia/Shanghai；为空则用系统默认
     * @return 结构化 JSON
     */
    @Tool(description = "获取当前的日期和时间。当用户询问现在几点、今天几号、当前时间时使用")
    public String getCurrentTime(
            @ToolParam(description = "时区ID，如 Asia/Shanghai、America/New_York；可不填，默认系统时区") String zone) {

        log.info("[TimeTool] 被调用, zone={}", zone);

        ZoneId zoneId;
        try {
            zoneId = (zone == null || zone.isBlank())
                    ? ZoneId.systemDefault()
                    : ZoneId.of(zone.trim());
        } catch (Exception e) {
            // 时区非法：兜底为系统默认，并在返回里提示
            ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
            return String.format(
                    "{\"time\":\"%s\",\"zone\":\"%s\",\"warning\":\"无效时区'%s'，已用系统默认\"}",
                    now.format(FMT), ZoneId.systemDefault(), zone);
        }

        ZonedDateTime now = ZonedDateTime.now(zoneId);
        return String.format(
                "{\"time\":\"%s\",\"zone\":\"%s\",\"weekday\":\"%s\"}",
                now.format(FMT), zoneId, now.getDayOfWeek());
    }
}
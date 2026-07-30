package com.zero.ai.agentstudy.day13officeagent.officecore.domain.port;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 日历事件端口（CalendarPort）——出站端口。
 *
 * <p><b>为什么需要它：</b> 终极场景里，Office Agent 生成周报并发送后，往往还要\n * "把评审会/复盘会排进销售总监的日历"。领域只描述\"要排一个什么样的日程\"（{@link CalendarEvent}），\n * 至于用 iCalendar(.ics) 文件、Google Calendar API 还是 Exchange，全被隔离在适配器里。</p>
 *
 * <p><b>为什么产物是字节而非直接写日历：</b> 与渲染器一致，本端口把日程序列化为标准 .ics 字节，\n * 交由上层决定\"作为邮件附件发送\"还是\"存入 {@link FileStorage}\"。这让日历能力天然融入\n * "生成 → 存储 → 分发" 的 Pipeline，而不必与具体日历服务强耦合。</p>
 *
 * @author zero
 */
public interface CalendarPort {

    /**
     * 把一个或多个日历事件渲染为符合 RFC 5545 的 iCalendar(.ics) 字节。
     *
     * @param events 事件列表
     * @return .ics 文件字节（UTF-8）
     */
    byte[] renderIcs(List<CalendarEvent> events);

    /**
     * 便捷方法：渲染单个事件。
     *
     * @param event 单个事件
     * @return .ics 文件字节（UTF-8）
     */
    default byte[] renderIcs(CalendarEvent event) {
        return renderIcs(List.of(event));
    }

    /**
     * 日历事件值对象——领域对"一场日程"的纯描述，与任何日历实现无关。
     *
     * @param uid         事件唯一标识（跨系统去重/更新用，为空时适配器自动生成）
     * @param summary     事件标题
     * @param description 事件描述
     * @param location    地点（线上会议可填会议链接）
     * @param start       开始时间（带时区偏移）
     * @param end         结束时间（带时区偏移）
     * @param organizer   组织者邮箱
     * @param attendees   参与者邮箱列表
     * @author zero
     */
    record CalendarEvent(String uid, String summary, String description, String location,
                         OffsetDateTime start, OffsetDateTime end,
                         String organizer, List<String> attendees) {

        public CalendarEvent {
            summary = summary == null ? "" : summary;
            description = description == null ? "" : description;
            location = location == null ? "" : location;
            organizer = organizer == null ? "" : organizer;
            attendees = attendees == null ? List.of() : List.copyOf(attendees);
        }

        /**
         * 快捷工厂：一场没有参与者的简单事件。
         *
         * @param summary 标题
         * @param start   开始时间
         * @param end     结束时间
         * @return 事件值对象
         */
        public static CalendarEvent of(String summary, OffsetDateTime start, OffsetDateTime end) {
            return new CalendarEvent(null, summary, "", "", start, end, "", List.of());
        }
    }
}
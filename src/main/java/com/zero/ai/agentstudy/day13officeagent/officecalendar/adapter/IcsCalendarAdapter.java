package com.zero.ai.agentstudy.day13officeagent.officecalendar.adapter;

import com.zero.ai.agentstudy.day13officeagent.officecore.domain.port.CalendarPort;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * iCalendar(.ics) 日历适配器——{@link CalendarPort} 出站端口的实现。
 *
 * <p><b>为什么直接生成 RFC 5545 文本而不套高层库 API：</b> iCalendar 的文件格式本身是一份\n * 简单、稳定、有三十年历史的纯文本规范（RFC 5545）。相比之下，各版本日历库的对象模型 API\n * 变动频繁（例如 ical4j 在 1.x/2.x/3.x/4.x 之间构造器与属性 API 差异极大），把生成逻辑\n * 绑死在某个库版本上，反而增加了维护脆弱性。这里我们按规范手工组装文本，产物是任何日历软件\n * （Outlook / Google Calendar / Apple 日历）都能直接打开的标准 .ics——这既零版本风险，\n * 又能让读者真正看清 iCalendar 的骨架。若团队已重度使用 ical4j，可在本类内部替换为库调用，\n * 端口契约不变。</p>
 *
 * <p><b>iCalendar 骨架：</b> 一个 VCALENDAR 容器里包裹若干 VEVENT 组件，每个 VEVENT 至少要有\n * UID（全局唯一标识，用于跨端去重与更新）、DTSTAMP（生成时间戳）、DTSTART/DTEND（起止时间，\n * 统一转 UTC 并以 {@code Z} 结尾）、SUMMARY（标题）。此外可选 DESCRIPTION、LOCATION、\n * ORGANIZER、ATTENDEE 等。</p>
 *
 * @author zero
 */
@Component
public class IcsCalendarAdapter implements CalendarPort {

    /** iCalendar 换行必须是 CRLF（RFC 5545 §3.1）。 */
    private static final String CRLF = "\r\n";

    /** UTC 基本格式时间戳，例如 20260730T090000Z。 */
    private static final DateTimeFormatter ICS_UTC =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    @Override
    public byte[] renderIcs(List<CalendarEvent> events) {
        StringBuilder sb = new StringBuilder();
        // ---- VCALENDAR 头 ----
        sb.append("BEGIN:VCALENDAR").append(CRLF);
        sb.append("VERSION:2.0").append(CRLF);
        sb.append("PRODID:-//ZeroHub//Day13 Office Agent//CN").append(CRLF);
        sb.append("CALSCALE:GREGORIAN").append(CRLF);
        sb.append("METHOD:PUBLISH").append(CRLF);

        // ---- 逐个事件写入 VEVENT ----
        String dtStamp = ICS_UTC.format(java.time.OffsetDateTime.now(ZoneOffset.UTC));
        for (CalendarEvent event : events) {
            appendEvent(sb, event, dtStamp);
        }

        // ---- VCALENDAR 尾 ----
        sb.append("END:VCALENDAR").append(CRLF);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 把单个事件写为一段 VEVENT。
     *
     * @param sb      正在构建的缓冲
     * @param event   事件值对象
     * @param dtStamp 统一的生成时间戳
     */
    private void appendEvent(StringBuilder sb, CalendarEvent event, String dtStamp) {
        String uid = (event.uid() == null || event.uid().isBlank())
                ? UUID.randomUUID() + "@zerohub"
                : event.uid();

        sb.append("BEGIN:VEVENT").append(CRLF);
        sb.append("UID:").append(uid).append(CRLF);
        sb.append("DTSTAMP:").append(dtStamp).append(CRLF);

        if (event.start() != null) {
            sb.append("DTSTART:")
                    .append(ICS_UTC.format(event.start().withOffsetSameInstant(ZoneOffset.UTC)))
                    .append(CRLF);
        }
        if (event.end() != null) {
            sb.append("DTEND:")
                    .append(ICS_UTC.format(event.end().withOffsetSameInstant(ZoneOffset.UTC)))
                    .append(CRLF);
        }

        sb.append("SUMMARY:").append(escape(event.summary())).append(CRLF);

        if (!event.description().isBlank()) {
            sb.append("DESCRIPTION:").append(escape(event.description())).append(CRLF);
        }
        if (!event.location().isBlank()) {
            sb.append("LOCATION:").append(escape(event.location())).append(CRLF);
        }
        if (!event.organizer().isBlank()) {
            sb.append("ORGANIZER:mailto:").append(event.organizer()).append(CRLF);
        }
        for (String attendee : event.attendees()) {
            sb.append("ATTENDEE:mailto:").append(attendee).append(CRLF);
        }

        sb.append("END:VEVENT").append(CRLF);
    }

    /**
     * 转义 iCalendar 文本值中的特殊字符（RFC 5545 §3.3.11）。
     *
     * <p>反斜杠、分号、逗号需转义，换行统一转为字面量 {@code \\n}。这一步常被忽略，
     * 但一旦标题里出现逗号（例如"周报：华东、华南对比"），未转义就会破坏解析。</p>
     *
     * @param value 原始文本
     * @return 转义后的文本
     */
    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\r\n", "\\n")
                .replace("\n", "\\n");
    }
}
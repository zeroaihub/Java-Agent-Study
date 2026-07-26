package com.zero.ai.agentstudy.day06workflow.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * WeatherService —— 天气查询工具（Demo 用模拟数据）。
 *
 * <p>教学要点：在 Workflow 里，「工具(Tool)」是节点内部真正干活的执行体。
 * 节点(Node)负责编排与读写 Context，工具负责单一的业务能力，二者分离满足 SRP。</p>
 *
 * <p>真实项目里这里会调用和风天气/高德等 HTTP API；
 * 为保证 Demo 可独立运行、不依赖外网，这里用固定+随机的模拟数据。</p>
 *
 * @author ZeroAi
 */
@Slf4j
@Component
public class WeatherService {

    /** 预置几个城市的基础天气，其余城市走随机 */
    private static final Map<String, String> PRESET = Map.of(
            "北京", "晴，18~26℃，适合出行",
            "上海", "多云转阴，20~28℃，记得带伞",
            "广州", "阵雨，25~32℃，闷热潮湿",
            "杭州", "晴，19~27℃，非常舒适"
    );

    /**
     * 查询指定城市天气。
     *
     * @param city 城市名
     * @return 可读的天气描述
     */
    public String query(String city) {
        log.info("[WeatherService] 查询城市天气: {}", city);
        if (PRESET.containsKey(city)) {
            return PRESET.get(city);
        }
        int low = ThreadLocalRandom.current().nextInt(10, 22);
        int high = low + ThreadLocalRandom.current().nextInt(4, 10);
        String[] sky = {"晴", "多云", "小雨", "阴"};
        String s = sky[ThreadLocalRandom.current().nextInt(sky.length)];
        return String.format("%s，%d~%d℃", s, low, high);
    }
}
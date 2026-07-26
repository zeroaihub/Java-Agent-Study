package com.zero.ai.agentstudy.day06workflow.tool.hotel;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 携程数据源（策略实现之一，Demo 模拟数据）。
 *
 * <p>标注 {@code @Component}，Spring 会自动把它收集进
 * {@code List<HotelSource>}，这是「策略集合注入」的常用技巧。</p>
 *
 * @author ZeroAi
 */
@Component
public class CtripHotelSource implements HotelSource {

    @Override
    public String sourceName() {
        return "携程";
    }

    @Override
    public List<String> search(String city) {
        return List.of(
                city + "-如家酒店(¥289/晚, 4.6分)",
                city + "-全季酒店(¥399/晚, 4.7分)"
        );
    }
}
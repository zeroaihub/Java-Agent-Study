package com.zero.ai.agentstudy.day06workflow.tool.hotel;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 美团数据源（策略实现之二，Demo 模拟数据）。
 *
 * @author ZeroAi
 */
@Component
public class MeituanHotelSource implements HotelSource {

    @Override
    public String sourceName() {
        return "美团";
    }

    @Override
    public List<String> search(String city) {
        return List.of(
                city + "-汉庭酒店(¥259/晚, 4.5分)",
                city + "-亚朵酒店(¥559/晚, 4.8分)"
        );
    }
}
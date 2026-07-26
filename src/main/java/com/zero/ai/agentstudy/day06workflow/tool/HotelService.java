package com.zero.ai.agentstudy.day06workflow.tool;

import com.zero.ai.agentstudy.day06workflow.tool.hotel.HotelSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * HotelService —— 酒店查询聚合工具（策略模式的「上下文/Context」角色）。
 *
 * <p>教学要点：Spring 会把所有 {@link HotelSource} 实现自动注入成一个 List，
 * 本类只需遍历这些「策略」并聚合结果，完全不关心具体是携程还是美团。
 * 这就是策略模式带来的解耦：新增数据源零改动本类。</p>
 *
 * @author ZeroAi
 */
@Slf4j
@Component
public class HotelService {

    private final List<HotelSource> sources;

    /**
     * 构造器注入所有策略实现。
     *
     * @param sources Spring 收集到的全部 HotelSource
     */
    public HotelService(List<HotelSource> sources) {
        this.sources = sources;
        log.info("[HotelService] 已加载酒店数据源: {}",
                sources.stream().map(HotelSource::sourceName).toList());
    }

    /**
     * 聚合所有数据源查询指定城市酒店。
     *
     * @param city 城市
     * @return 「来源-酒店」列表
     */
    public List<String> searchAll(String city) {
        List<String> result = new ArrayList<>();
        for (HotelSource source : sources) {
            source.search(city).forEach(h -> result.add("[" + source.sourceName() + "] " + h));
        }
        return result;
    }
}
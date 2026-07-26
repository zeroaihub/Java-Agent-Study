package com.zero.ai.agentstudy.day06workflow.tool.hotel;

import java.util.List;

/**
 * HotelSource —— 酒店数据源「策略接口」。
 *
 * <p>教学要点：这是第三章「策略模式」的落地。查酒店可能有多个数据源
 * （携程、美团、飞猪……），它们「做同一件事、实现方式不同」。
 * 用一个统一接口抽象出来，上层 HotelService 就能在运行时挑选/聚合，
 * 新增数据源只需新增一个实现类，满足开闭原则(OCP)。</p>
 *
 * @author ZeroAi
 */
public interface HotelSource {

    /**
     * 数据源名称，用于日志与结果标注。
     *
     * @return 如 "携程"
     */
    String sourceName();

    /**
     * 按城市查询酒店列表。
     *
     * @param city 城市
     * @return 酒店描述列表
     */
    List<String> search(String city);
}
package com.zero.ai.agentstudy.day06workflow.workflow.context;

/**
 * ContextKeys —— 集中管理 Context 中的键名常量。
 *
 * <p>教学要点：避免各处硬编码字符串 "city"、"weather"，
 * 拼错一个字母就 get 不到值且不报错。集中定义常量是「消除魔法值」的最佳实践。</p>
 *
 * @author ZeroAi
 */
public final class ContextKeys {

    private ContextKeys() {
    }

    /** 用户输入的原始请求 */
    public static final String USER_INPUT = "userInput";

    /** 解析出的城市 */
    public static final String CITY = "city";

    /** 天气结果 */
    public static final String WEATHER = "weather";

    /** 酒店结果列表 */
    public static final String HOTELS = "hotels";

    /** 生成的旅行计划 */
    public static final String PLAN = "plan";

    /** 最终输出文本 */
    public static final String OUTPUT = "output";
}
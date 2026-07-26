package com.zero.ai.agentstudy.day3funcall.mytest;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class StockTool {

    /**
     * name 由方法名决定：queryStockPrice（动词+名词，清晰）
     * description 写清"何时用我"
     */
    @Tool(description = "查询指定股票的实时价格与涨跌幅。当用户询问股价、行情、某只股票多少钱时使用")
    public String queryStockPrice(
            @ToolParam(description = "股票代码，如 600519（贵州茅台）") String code) {

        // 1. 参数校验前置
        if (code == null || !code.matches("\\d{6}")) {
            // 2. 异常也返回结构化 JSON，不抛异常
            return "{\"error\":\"股票代码格式错误，应为6位数字\"}";
        }

        // 3. 真正查询（这里 mock）
        // 4. 返回结构化 JSON，字段固定
        return "{\"code\":\"" + code + "\",\"name\":\"贵州茅台\","
             + "\"price\":1680.50,\"changePct\":1.23,\"time\":\"2024-06-12 15:00\"}";

    }

    @Tool(description = "查询股票K线。当用户需要查询股票的K线的时候使用。")
    public String queryStockKLine(
            @ToolParam(description = "股票代码，如 600519（贵州茅台）") String stockCode,
            @ToolParam(description = "K线类型，如 日级别，小时级别") String kLineType
            ) {

        if (stockCode == null || kLineType == null) {
            return "{\"error\":\"参数不能为空\"}";
        }
        if (kLineType.equals("日级别")) {
            return "{\"kLine\":\"日级别数据\"}";
        }
        return "{\"error\":\"暂不支持该K线类型\"}";

    }
}
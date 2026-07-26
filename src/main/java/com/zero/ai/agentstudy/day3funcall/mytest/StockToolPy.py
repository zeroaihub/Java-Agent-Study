from langchain_core.tools import tool
import json, re

@tool
def query_stock_price(code: str) -> str:
    """查询指定股票的实时价格与涨跌幅。当用户询问股价、行情时使用。code: 6位股票代码"""
    if not re.match(r"^\d{6}$", code or ""):
        return json.dumps({"error": "股票代码格式错误"})
    return json.dumps({"code": code, "name": "贵州茅台",
                       "price": 1680.50, "changePct": 1.23})
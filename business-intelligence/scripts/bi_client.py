"""
BI API Client — Fetches data from the Business Intelligence backend REST API.

Usage:
    from bi_client import BiApiClient
    client = BiApiClient("http://localhost:8093")
    snapshot = client.get_overview()
    tab = client.get_tab("incident-analysis")
"""

import logging
import re
from typing import Any, Dict, List, Optional
from urllib.parse import urljoin

import requests

logger = logging.getLogger(__name__)


def _t(text_en: str, text_zh: str, language: str) -> str:
    """Return localized string."""
    return text_zh if language == "zh" else text_en


# ── Regex to detect Chinese characters (CJK + Extension A) ─────────────────────
_CHINESE_RE = re.compile(r'[一-鿿㐀-䶿]')


# ── Comprehensive Chinese → English localization map ──────────────────────────
# Used to translate backend Chinese strings when language="en".
_ZH_TO_EN: Dict[str, str] = {
    # ── Tab labels & descriptions ──
    "执行摘要": "Executive Summary",
    "聚合四类 ITIL 数据的核心规模与风险摘要。": "Core scale and risk summary across four ITIL domains.",
    "SLA分析": "SLA Analysis",
    "事件与请求SLA履约情况分析。": "Incident and Request SLA compliance analysis.",
    "事件分析": "Incident Analysis",
    "基于事件工单数据，分析事件管理的关键KPI、趋势变化以及类型分布。":
        "Key KPIs, trends, and type distribution for incident management.",
    "变更分析": "Change Analysis",
    "展示变更成功率趋势、类型分布、风险等级和计划满足度。":
        "Change success rate trends, type distribution, risk levels, and schedule adherence.",
    "请求分析": "Request Analysis",
    "展示请求趋势、SLA达标率、满意度和高频请求分布。":
        "Request trends, SLA compliance, satisfaction, and high-frequency request distribution.",
    "问题分析": "Problem Analysis",
    "展示问题趋势、根因分析、解决方案健康度和根因类别分布。":
        "Problem trends, root cause analysis, resolution health, and root cause distribution.",
    "跨流程关联": "Cross-Process Correlation",
    "展示变更、事件、请求和问题之间的深度关联与风险传导路径。":
        "Deep correlation and risk propagation across Change, Incident, Request, and Problem.",
    # Workforce label comes as "Workforce" from backend — no mapping needed.

    # ── Heatmap axis labels ──
    "时段": "Time Slot",
    "星期": "Weekday",
    "分类": "Category",
    "人员": "Person",

    # ── Radar dimension labels ──
    "速度": "Speed",
    "产量": "Volume",
    "质量": "Quality",
    "满意度": "Satisfaction",
    "难度": "Difficulty",

    # ── Executive Summary section titles ──
    "核心 KPI": "Core KPIs",
    "流程健康": "Process Health",

    # ── Executive Summary cards ──
    "事件 SLA 达成率": "Incident SLA Rate",
    "MTTR": "MTTR",
    "变更成功率": "Change Success Rate",
    "请求满意度": "Request Satisfaction",
    "问题关闭率": "Problem Closure Rate",

    # ── SLA cards ──
    "综合达成率": "Overall Compliance",
    "响应达成率": "Response Compliance",
    "解决达成率": "Resolution Compliance",
    "P1/P2达成率": "P1/P2 Compliance",
    "响应违约数": "Response Breaches",
    "解决违约数": "Resolution Breaches",
    "请求SLA达成率": "Request SLA Rate",
    "请求违约数": "Request Breaches",
    "平均交付时长": "Avg Delivery Time",
    "违约关联满意度": "Breach-Linked Satisfaction",

    # ── Incident cards ──
    "事件总数": "Total Incidents",
    "P1/P2 事件": "P1/P2 Incidents",
    "未解决事件": "Open Incidents",
    "SLA 达成率": "SLA Compliance",
    "P1/P2 MTTR": "P1/P2 MTTR",
    "平均 MTTR": "Avg MTTR",

    # ── Change cards ──
    "变更总数": "Total Changes",
    "成功率": "Success Rate",
    "紧急变更": "Emergency Changes",
    "引发事件的变更": "Changes Causing Incidents",

    # ── Request cards ──
    "请求总数": "Total Requests",
    "已完成请求": "Fulfilled Requests",
    "平均满意度": "Avg Satisfaction",

    # ── Problem cards ──
    "问题总数": "Total Problems",
    "已关闭问题": "Closed Problems",
    "已完成 RCA": "RCA Completed",
    "未知错误": "Known Errors",

    # ── Cross-process cards ──
    "变更致事件率": "Change-to-Incident Rate",
    "48h窗口P1/P2事件": "48h Window P1/P2",
    "请求-事件比": "Request-to-Incident Ratio",
    "系统脆弱性评分": "System Fragility Score",

    # ── Workforce cards ──
    "人均处理量": "Avg Throughput",
    "积压工单数": "Backlog Tickets",
    "平均交付耗时": "Avg Delivery Time",
    "变更实施速度": "Change Speed",
    "一次性成功率": "First-Time Success",
    "用户满意度": "User Satisfaction",
    "问题根治率": "Problem Fix Rate",

    # ── Chart titles ──
    "月度健康趋势": "Monthly Health Trend",
    "SLA达成率趋势": "SLA Compliance Trend",
    "优先级SLA达成率对比": "SLA Compliance by Priority",
    "违约优先级分布": "Breach Distribution by Priority",
    "违约事件类型分布": "Breach Distribution by Category",
    "请求SLA与满意度趋势": "Request SLA & Satisfaction Trend",
    "服务目录SLA达成率对比": "SLA by Service Catalog",
    "违约请求部门分布": "Request Breaches by Department",
    "违约服务目录分布": "Request Breaches by Catalog",
    "事件单量趋势": "Incident Volume Trend",
    "处理时长趋势": "Resolution Time Trend",
    "优先级分布": "Priority Distribution",
    "事件类型分布": "Incident Type Distribution",
    "变更成功率趋势": "Change Success Rate Trend",
    "变更等级分布": "Change Risk Distribution",
    "变更类别分布": "Change Category Distribution",
    "变更引发故障分布": "Change-Induced Failure Distribution",
    "变更计划满足分布": "Change Schedule Adherence",
    "请求单量趋势": "Request Volume Trend",
    "SLA达成率与平均耗时": "SLA Compliance vs Avg Time",
    "请求类型分布": "Request Type Distribution",
    "部门请求单数排名": "Requests by Department",
    "满意度分布": "Satisfaction Distribution",
    "高频请求目录": "Top Request Categories",
    "问题单量趋势": "Problem Volume Trend",
    "问题根因类型分布": "Problem Root Cause Distribution",
    "问题引发故障数排名": "Problems by Incident Count",
    "问题状态分布": "Problem Status Distribution",
    "已关闭问题单的解决方案健康度分析": "Resolution Health of Closed Problems",
    "系统模块薄弱点分析": "System Module Weakness Analysis",
    "变更致事件趋势": "Change-to-Incident Trend",
    "变更风险热力图": "Change Risk Heatmap",
    "系统脆弱性气泡图": "System Fragility Bubble",
    "请求与事件时间重叠": "Request-Incident Time Overlap",
    "团队工作负载分布": "Team Workload Distribution",
    "工单流转效率热力图": "Ticket Flow Efficiency Heatmap",
    "SLA达标率 vs 满意度": "SLA Compliance vs Satisfaction",
    "个人综合素质雷达图": "Individual Competency Radar",

    # ── Chart series names ──
    "事件单量": "Incidents",
    "SLA达成率(%)": "SLA Compliance (%)",
    "平均MTTR": "Avg MTTR",
    "变更数量": "Changes",
    "引发事件变更": "Change-Induced Incidents",
    "成功": "Successful",
    "失败": "Failed",
    "提前完成": "Early",
    "按时完成": "On Time",
    "延期完成": "Delayed",
    "请求单量": "Requests",
    "平均耗时(h)": "Avg Time (h)",
    "SLA达成率": "SLA Compliance",
    "问题单量": "Problems",
    "累积未解决": "Cumulative Open",
    "已永久修复": "Permanently Fixed",
    "有临时方案": "Workaround",
    "未解决": "Unresolved",
    "致事件P1/P2数": "Incident P1/P2 Count",
    "变更密度": "Change Density",
    "事件热点": "Incident Hotspots",
    "请求数": "Requests",
    "事件数": "Incidents",
    "事件": "Incidents",
    "变更": "Changes",
    "请求": "Requests",
    "问题": "Problems",

    # ── Table titles ──
    "违约样本": "Breach Samples",
    "请求违约及低满意度样本": "Request Breach & Low Satisfaction Samples",
    "处理人工作量 TOP10": "Resolver Workload TOP10",
    "事件样本": "Incident Samples",
    "失败或回退样本": "Failed / Rollback Samples",
    "低满意度样本": "Low Satisfaction Samples",
    "未关闭问题": "Open Problems",
    "变更致事件关联明细": "Change-to-Incident Details",
    "老化问题清单": "Aging Problems",
    "请求激增预警": "Request Surge Alerts",
    "救火先锋 TOP10": "Firefighter TOP10",
    "技术债务解决 TOP10": "Tech Debt Resolution TOP10",
    "高风险变更执行 TOP10": "High-Risk Change TOP10",

    # ── Table column headers ──
    "编号": "ID",
    "标题": "Title",
    "优先级": "Priority",
    "类别": "Category",
    "处理人": "Resolver",
    "响应时长": "Response Time",
    "解决时长": "Resolution Time",
    "违约类型": "Breach Type",
    "服务目录": "Service Catalog",
    "请求部门": "Department",
    "时长": "Duration",
    "SLA": "SLA",
    "状态": "Status",
    "关闭方式": "Close Method",
    "是否回退": "Rolled Back",
    "满足时间": "Fulfillment Time",
    "反馈": "Feedback",
    "关联事件": "Linked Incidents",
    "变更编号": "Change ID",
    "变更标题": "Change Title",
    "完成时间": "Completed",
    "48h内P1/P2事件": "P1/P2 within 48h",
    "风险等级": "Risk Level",
    "问题编号": "Problem ID",
    "根因类别": "Root Cause",
    "老化天数": "Aging Days",
    "关联事件数": "Linked Incidents",
    "请求类别": "Request Category",
    "本周请求数": "This Week",
    "上周请求数": "Last Week",
    "环比增长": "WoW Growth",
    "同期事件数": "Concurrent Incidents",
    "P1/P2事件数": "P1/P2 Incidents",
    "平均解决时长": "Avg Resolution Time",
    "涉及CI数": "Affected CIs",
    "SLA达标率": "SLA Compliance",
    "根治问题数": "Fixed Problems",
    "根治率": "Fix Rate",
    "实施人": "Implementer",
    "高风险变更数": "High-Risk Changes",
    "回退率": "Rollback Rate",
    "致事件率": "Incident Rate",
    "工单号": "Ticket ID",
}


def _localize(text: str, language: str) -> str:
    """Localize a backend string. Returns English if language='en' and a mapping exists.

    Missing translations are logged as warnings so developers can extend _ZH_TO_EN,
    but the original text is returned to avoid breaking the exported report.
    """
    if language == "zh" or not text:
        return text
    translated = _ZH_TO_EN.get(text)
    if translated is not None:
        return translated
    # Only log for Chinese-looking strings to avoid noisy warnings for already-English text.
    if _CHINESE_RE.search(text):
        logger.warning("Untranslated Chinese label in EN export: %r", text)
    return text


class BiApiClient:
    """Client for Business Intelligence backend REST API."""

    def __init__(self, base_url: str = "http://localhost:8093", timeout: int = 60):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.session = requests.Session()
        self.session.headers.update({"Accept": "application/json"})

    def _get(self, path: str, params: Optional[Dict] = None) -> Any:
        """Make a GET request to the BI backend."""
        url = urljoin(self.base_url + "/", path)
        try:
            resp = self.session.get(url, params=params, timeout=self.timeout)
            resp.raise_for_status()
            return resp.json()
        except requests.Timeout:
            logger.error("Timeout fetching %s after %ds", url, self.timeout)
            raise
        except requests.HTTPError as e:
            logger.error("HTTP error %s for %s: %s", e.response.status_code, url, e.response.text[:200])
            raise
        except (requests.RequestException, ValueError) as e:
            logger.error("Unexpected error fetching %s: %s", url, e)
            raise

    def get_overview(self, start_date: Optional[str] = None, end_date: Optional[str] = None) -> Dict:
        """GET /business-intelligence/overview — Returns full snapshot with all 8 tabs."""
        params = {}
        if start_date:
            params["startDate"] = start_date
        if end_date:
            params["endDate"] = end_date
        return self._get("business-intelligence/overview", params)

    def get_tab(self, tab_id: str, granularity: Optional[str] = None) -> Dict:
        """GET /business-intelligence/tabs/{tab_id} — Returns single tab content."""
        params = {}
        if granularity:
            params["granularity"] = granularity
        return self._get(f"business-intelligence/tabs/{tab_id}", params)

    def get_metrics(self, domain: str, start_date: Optional[str] = None, end_date: Optional[str] = None) -> Dict:
        """GET /business-intelligence/metrics/{domain} — Returns metrics for a domain."""
        params = {}
        if start_date:
            params["startDate"] = start_date
        if end_date:
            params["endDate"] = end_date
        return self._get(f"business-intelligence/metrics/{domain}", params)

    def get_all_tabs(self) -> Dict[str, Dict]:
        """Fetch all 8 tabs efficiently using the overview endpoint."""
        overview = self.get_overview()
        return overview.get("tabContents", {})

    def export_native(self) -> bytes:
        """GET /business-intelligence/export.xlsx — Returns BI's native Excel export (raw bytes)."""
        url = urljoin(self.base_url + "/", "business-intelligence/export.xlsx")
        resp = self.session.get(url, timeout=self.timeout)
        resp.raise_for_status()
        return resp.content


class BiDataAdapter:
    """Adapts BI API JSON responses into a flat snapshot dict for Excel rendering.

    The BI backend returns nested structures (ExecutiveSummary, SlaAnalysisSummary,
    etc.). This adapter flattens them into a uniform dict with:
        - cards: List[MetricCard]
        - charts: List[ChartSection]
        - tables: List[TableSection]
    """

    def __init__(self, snapshot: Dict, language: str = "zh"):
        self.snapshot = snapshot
        self.language = language
        self.tab_contents = snapshot.get("tabContents", {})

    def get_tab(self, tab_id: str) -> Dict:
        """Get a tab's content, normalizing special structures."""
        tab = self.tab_contents.get(tab_id)
        if tab is None:
            return {"id": tab_id, "label": tab_id, "cards": [], "charts": [], "tables": []}

        # Most tabs already have cards/charts/tables
        result = {
            "id": tab.get("id", tab_id),
            "label": tab.get("label", tab_id),
            "description": tab.get("description", ""),
            "cards": list(tab.get("cards", [])),
            "charts": list(tab.get("charts", [])),
            "tables": list(tab.get("tables", [])),
        }

        # Executive Summary has extra structured data — normalize into cards/charts/tables
        if tab_id == "executive-summary":
            self._normalize_executive(tab, result)

        # Apply localization to all backend strings
        self._localize_tab(result)

        return result

    def _localize_tab(self, tab: Dict) -> None:
        """Apply localization to all text fields in a normalized tab."""
        lang = self.language

        # Tab-level
        tab["label"] = _localize(tab["label"], lang)
        tab["description"] = _localize(tab["description"], lang)

        # Cards
        for card in tab.get("cards", []):
            card["label"] = _localize(card.get("label", ""), lang)

        # Charts — title, item labels, series names, axis labels, radar dimensions
        for chart in tab.get("charts", []):
            chart["title"] = _localize(chart.get("title", ""), lang)
            # Localize item labels for all chart types (pie/bar/line/etc.)
            for item in chart.get("items", []):
                item["label"] = _localize(item.get("label", ""), lang)
            # Radar chart config series data labels
            if chart.get("type") == "radar":
                config = chart.get("config")
                if config and config.get("seriesData"):
                    for series_list in config["seriesData"].values():
                        for datum in series_list:
                            datum["label"] = _localize(datum.get("label", ""), lang)
            config = chart.get("config")
            if config:
                if config.get("series"):
                    config["series"] = [_localize(s, lang) for s in config["series"]]
                if config.get("xAxisLabel"):
                    config["xAxisLabel"] = _localize(config["xAxisLabel"], lang)
                if config.get("yAxisLabel"):
                    config["yAxisLabel"] = _localize(config["yAxisLabel"], lang)

        # Tables — title, column headers
        for table in tab.get("tables", []):
            table["title"] = _localize(table.get("title", ""), lang)
            table["columns"] = [_localize(c, lang) for c in table.get("columns", [])]

    def _normalize_executive(self, tab: Dict, result: Dict) -> None:
        """Flatten ExecutiveSummary into cards/charts/tables."""
        exec_summary = tab.get("executiveSummary")
        if not exec_summary:
            return

        lang = self.language
        hero = exec_summary.get("hero", {})
        process_healths = exec_summary.get("processHealths", [])
        risk_summary = exec_summary.get("riskSummary", {})
        trend = exec_summary.get("trend", {})

        # Localization maps
        PROCESS_LABELS = {
            "incident": _t("Incident", "事件", lang),
            "change": _t("Change", "变更", lang),
            "request": _t("Request", "请求", lang),
            "problem": _t("Problem", "问题", lang),
        }
        PRIORITY_LABELS = {
            "Critical": _t("Critical", "严重", lang),
            "Warning": _t("Warning", "警告", lang),
            "Attention": _t("Attention", "关注", lang),
        }
        RISK_TITLES = {
            "change-failure": _t("High change failure rate", "变更失败率偏高", lang),
            "change-incident": _t("Changes causing too many incidents", "变更引发事件偏多", lang),
            "request-open": _t("Open request backlog", "未完成请求积压", lang),
            "request-csat": _t("Declining request satisfaction", "请求满意度下滑", lang),
            "incident-sla": _t("Incident SLA breaches detected", "事件 SLA 出现违约", lang),
            "problem-closure": _t("Low problem closure rate", "问题关闭率偏低", lang),
            "problem-open": _t("Too many open problems", "未关闭问题偏多", lang),
            "request-sla-critical": _t("Request SLA compliance critically low", "请求 SLA 达标率严重偏低", lang),
            "request-sla-breach": _t("Request SLA compliance insufficient", "请求 SLA 达标率不足", lang),
        }
        RISK_IMPACTS = {
            "change-failure": _t("Release stability has declined. Prioritize reviewing high-risk changes.", "发布稳定性下降，需优先排查高风险变更。", lang),
            "change-incident": _t("Release quality and change validation have weak points.", "上线质量与变更验证存在薄弱点。", lang),
            "request-open": _t("Fulfillment experience is under pressure, wait times will increase.", "履约体验承压，用户等待时间会拉长。", lang),
            "request-csat": _t("Service experience is fluctuating, consider reviewing frequent requests.", "服务体验有波动，建议复盘高频诉求。", lang),
            "incident-sla": _t("Core incident response has timed out.", "核心事件响应存在超时情况。", lang),
            "problem-closure": _t("Root cause and permanent fix backlog is growing, risk will compound.", "根因与永久修复积压加剧，风险将持续放大。", lang),
            "problem-open": _t("Problem pool is growing, which will drag down stability governance.", "问题池持续增长，将拖累稳定性治理。", lang),
            "request-sla-critical": _t("Systemic issues in fulfillment process. Investigate bottlenecks immediately.", "交付流程存在系统性问题，需立即排查瓶颈。", lang),
            "request-sla-breach": _t("Some request categories are timing out. Monitor high-frequency backlog catalogs.", "部分请求类别已超时，需关注高频积压目录。", lang),
        }

        # Hero score as the first card
        if hero:
            result["cards"].insert(0, {
                "id": "hero-score",
                "label": _t("Health Score", "健康评分", lang),
                "value": hero.get("score", "N/A"),
                "tone": self._grade_to_tone(hero.get("grade", "")),
            })

        # Process health cards
        for ph in process_healths:
            pid = ph.get("id", "")
            result["cards"].append({
                "id": f"process-{pid}",
                "label": PROCESS_LABELS.get(pid, ph.get("label", "")),
                "value": ph.get("score", "N/A"),
                "tone": ph.get("tone", "neutral"),
            })

        # Risk summary cards
        if risk_summary:
            result["cards"].append({
                "id": "risk-critical",
                "label": _t("Critical Risks", "严重风险", lang),
                "value": str(risk_summary.get("critical", 0)),
                "tone": "danger",
            })
            result["cards"].append({
                "id": "risk-warning",
                "label": _t("Warnings", "警告风险", lang),
                "value": str(risk_summary.get("warning", 0)),
                "tone": "warning",
            })
            result["cards"].append({
                "id": "risk-attention",
                "label": _t("Attention Items", "关注项", lang),
                "value": str(risk_summary.get("attention", 0)),
                "tone": "neutral",
            })

        # Trend chart — dual-line: health score (line, left Y) + high-priority signal (line, right Y)
        trend_points = trend.get("points", [])
        if trend_points:
            trend_items = []
            for p in trend_points:
                label = p.get("label", "")
                score = round(p.get("score", 0), 1)
                signal = p.get("signal", 0) if p.get("signal") is not None else 0
                trend_items.append({
                    "label": f"{label}|{score}|{signal}",
                    "value": score,
                })
            result["charts"].insert(0, {
                "id": "trend",
                "title": _t("Monthly Health Trend", "月度健康趋势", lang),
                "type": "combo-line",
                "items": trend_items,
                "config": {
                    "series": [
                        _t("Health Score", "健康评分", lang),
                        _t("High-Priority Events", "高优先级事件数", lang),
                    ],
                    "colors": ["#5b8db8", "#f59e0b"],
                },
            })

        # Risk table
        top_risks = risk_summary.get("topRisks", [])
        if top_risks:
            result["tables"].insert(0, {
                "id": "top-risks",
                "title": _t("Top Risks", "主要风险", lang),
                "columns": [
                    _t("Priority", "优先级", lang),
                    _t("Risk Item", "风险项", lang),
                    _t("Impact", "影响", lang),
                    _t("Process", "所属流程", lang),
                    _t("Current Value", "当前值", lang),
                ],
                "rows": [
                    [
                        PRIORITY_LABELS.get(r.get("priority", ""), r.get("priority", "")),
                        RISK_TITLES.get(r.get("id", ""), r.get("title", "")),
                        RISK_IMPACTS.get(r.get("id", ""), r.get("impact", "")),
                        PROCESS_LABELS.get(r.get("process", ""), r.get("process", "")),
                        r.get("value", ""),
                    ]
                    for r in top_risks
                ],
            })

    def _grade_to_tone(self, grade: str) -> str:
        """Convert grade text to tone."""
        grade_lower = grade.lower()
        if "risk" in grade_lower or "危险" in grade_lower:
            return "danger"
        if "watch" in grade_lower or "关注" in grade_lower:
            return "warning"
        if "stable" in grade_lower or "良好" in grade_lower:
            return "success"
        return "neutral"

    def get_all_tabs(self) -> Dict[str, Dict]:
        """Get all 8 tabs in normalized form."""
        tab_order = [
            "executive-summary",
            "sla-analysis",
            "incident-analysis",
            "change-analysis",
            "request-analysis",
            "problem-analysis",
            "cross-process",
            "workforce",
        ]
        return {tab_id: self.get_tab(tab_id) for tab_id in tab_order}

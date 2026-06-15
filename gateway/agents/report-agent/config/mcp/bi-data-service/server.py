#!/usr/bin/env python3
import json
import os
import sys
import traceback
import urllib.error
import urllib.parse
import urllib.request
from collections import deque
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional

JSONRPC_VERSION = "2.0"
NEGOTIATED_PROTOCOL_VERSION = "2025-03-26"
SERVER_NAME = "bi-data-service"
SERVER_VERSION = "3.0.0"
DEFAULT_TIMEOUT_SECONDS = 30
DEFAULT_BI_SERVICE_URL = "http://127.0.0.1:8093"
LOG_FILE_NAME = "bi_data_service.log"

VALID_DOMAINS = ["incidents", "changes", "requests", "problems"]

VALID_METRICS_DOMAINS = [
    "executive", "sla", "incidents", "changes",
    "requests", "problems", "cross-process", "workforce",
]

MAX_ROWS = 50
DEFAULT_ROWS = 20

VALID_METRICS = {"count", "avg", "sum", "percentage", "distribution"}

VALID_INTERVALS = {"hour", "day", "week", "month"}


# ── Helpers ──────────────────────────────────────────────────────────────────


class ToolExecutionError(Exception):
    def __init__(self, public_message: str, *, internal_message: Optional[str] = None):
        super().__init__(internal_message or public_message)
        self.public_message = public_message
        self.internal_message = internal_message or public_message


class McpLogger:
    def __init__(self) -> None:
        root = Path(os.environ.get("GOOSE_PATH_ROOT") or os.getcwd())
        self.log_dir = root / "logs" / "mcp"
        self.log_dir.mkdir(parents=True, exist_ok=True)
        self.log_file = self.log_dir / LOG_FILE_NAME

    def _write(self, level: str, message: str, **fields: Any) -> None:
        record = {"timestamp": datetime.now(timezone.utc).isoformat(), "level": level, "message": message}
        if fields:
            record["fields"] = fields
        line = json.dumps(record, ensure_ascii=True)
        with self.log_file.open("a", encoding="utf-8") as fp:
            fp.write(line + "\n")
        print(line, file=sys.stderr, flush=True)

    def info(self, message: str, **fields: Any) -> None:
        self._write("INFO", message, **fields)

    def error(self, message: str, **fields: Any) -> None:
        self._write("ERROR", message, **fields)

    def exception(self, message: str, **fields: Any) -> None:
        fields = dict(fields)
        fields["traceback"] = traceback.format_exc()
        self._write("ERROR", message, **fields)


LOGGER = McpLogger()


@dataclass(frozen=True)
class RuntimeConfig:
    bi_service_url: str
    timeout_seconds: int

    @classmethod
    def from_env(cls) -> "RuntimeConfig":
        bi_service_url = (os.environ.get("BI_SERVICE_URL") or DEFAULT_BI_SERVICE_URL).rstrip("/")
        timeout_seconds = _parse_int(os.environ.get("BI_TIMEOUT_SECONDS"), DEFAULT_TIMEOUT_SECONDS)
        return cls(bi_service_url=bi_service_url, timeout_seconds=timeout_seconds)

    def masked_dict(self) -> Dict[str, Any]:
        return {"bi_service_url": self.bi_service_url, "timeout_seconds": self.timeout_seconds}


def _parse_int(value: Optional[str], default: int) -> int:
    if value is None or not value.strip():
        return default
    try:
        return int(value)
    except ValueError:
        return default


# ── BI REST API ──────────────────────────────────────────────────────────────


def _bi_request(method: str, path: str, config: RuntimeConfig,
                params: Optional[Dict[str, str]] = None,
                body: Optional[Any] = None) -> Any:
    url = f"{config.bi_service_url}/business-intelligence{path}"
    if params:
        qs = urllib.parse.urlencode({k: v for k, v in params.items() if v is not None})
        if qs:
            url = f"{url}?{qs}"
    if method == "POST":
        data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body else b""
        req = urllib.request.Request(url, data=data, method="POST", headers={"Content-Type": "application/json"})
    else:
        req = urllib.request.Request(url)
    LOGGER.info("BI API request", method=method, url=url)
    try:
        with urllib.request.urlopen(req, timeout=config.timeout_seconds) as resp:
            resp_body = resp.read().decode("utf-8", errors="replace")
            return json.loads(resp_body)
    except urllib.error.HTTPError as exc:
        resp_body = exc.read().decode("utf-8", errors="replace")
        raise ToolExecutionError(f"BI API returned {exc.code}: {resp_body[:500]}") from exc
    except urllib.error.URLError as exc:
        raise ToolExecutionError(f"BI API request failed: {exc.reason}") from exc


# ── Shared Helpers ───────────────────────────────────────────────────────────


def _build_date_params(args: Dict[str, Any]) -> Dict[str, str]:
    params: Dict[str, str] = {}
    start_date = args.get("startDate")
    end_date = args.get("endDate")
    if start_date:
        params["startDate"] = str(start_date)
    if end_date:
        params["endDate"] = str(end_date)
    return params


DEFAULT_FIELDS_MAP: Dict[str, List[str]] = {
    "incidents": ["ticket_id", "title", "priority", "category", "assigned_to", "status",
                  "response_time_minutes", "resolution_time_minutes", "opened_at", "closed_at",
                  "close_notes"],
    "changes": ["ticket_id", "title", "change_type", "status", "category",
                "close_code", "close_notes", "incident_ids"],
    "requests": ["ticket_id", "title", "catalog_item", "status", "requester_dept",
                 "resolution_time_minutes", "satisfaction_score", "feedback"],
    "problems": ["ticket_id", "title", "status", "priority", "cause_code",
                 "root_cause", "known_error", "workaround"],
}

# Per-domain KPI fields to keep in get_all_metrics summary mode
_DOMAIN_KPI_FIELDS: Dict[str, List[str]] = {
    "executive": ["overallScore", "grade", "criticalCount", "warningCount", "attentionCount", "topRisks"],
    "sla": ["overallRate", "responseRate", "resolutionRate", "breachedCount",
            "avgResponseMinutes", "avgResolutionMinutes"],
    "incidents": ["totalCount", "p1p2Count", "openCount", "slaRate", "mttrHours", "p1p2MttrHours"],
    "changes": ["totalCount", "successRate", "emergencyCount", "incidentCausedCount"],
    "requests": ["totalCount", "fulfilledCount", "slaRate", "avgCsat", "avgFulfillmentHours"],
    "problems": ["totalCount", "closedCount", "closureRate", "rcaRate"],
    "cross-process": ["changeCausedIncidentRate", "fragilityScore", "p1p2Within48h", "requestIncidentRatio"],
    "workforce": ["avgThroughput", "backlog", "avgDeliveryHours", "overallSlaRate",
                  "avgChangeSpeedHours", "firstTimeSuccessRate", "avgSatisfaction", "problemFixRate"],
}


def _extract_kpi_summary(domain: str, data: Any) -> Any:
    """Extract only the top-level KPI fields from a domain metrics response."""
    if not isinstance(data, dict):
        return data
    kpi_fields = _DOMAIN_KPI_FIELDS.get(domain)
    if kpi_fields is None:
        return data
    result: Dict[str, Any] = {}
    for field in kpi_fields:
        if field in data:
            val = data[field]
            if field in ("riskItems", "topRisks") and isinstance(val, list):
                result[field] = val[:5]
            elif isinstance(val, (int, float, str, bool)):
                result[field] = val
    return result


def _get_trends(domain: str, metric: str, interval: str,
                config: RuntimeConfig, args: Dict[str, Any]) -> Any:
    params: Dict[str, str] = {"metric": metric, "interval": interval}
    date_params = _build_date_params(args)
    params.update(date_params)
    return _bi_request("GET", f"/data/{domain}/trends", config, params=params)


def _merge_trend_series(trend_results: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    if not trend_results:
        return []
    period_map: Dict[str, Dict[str, Any]] = {}
    for trend in trend_results:
        series_name = trend.get("name", "value")
        for point in trend.get("dataPoints", []):
            period = point.get("period", "")
            if period not in period_map:
                period_map[period] = {"period": period}
            period_map[period][series_name] = point.get("value")
    sorted_periods = sorted(period_map.keys())
    return [period_map[p] for p in sorted_periods]


# ── Tool Handlers ────────────────────────────────────────────────────────────


def _handle_get_all_metrics(args: Dict[str, Any], config: RuntimeConfig) -> Any:
    params = _build_date_params(args)
    result = {}
    exec_raw = None
    failed_domains = []
    for domain in VALID_METRICS_DOMAINS:
        domain_params = dict(params)
        if domain == "workforce":
            domain_params["personLimit"] = "10"
        try:
            raw = _bi_request("GET", f"/metrics/{domain}", config, params=domain_params or None)
            result[domain] = _extract_kpi_summary(domain, raw)
            if domain == "executive":
                exec_raw = raw
        except (ToolExecutionError, json.JSONDecodeError) as e:
            failed_domains.append(domain)
            LOGGER.error("Failed to fetch metrics", domain=domain, error=str(e))
            result[domain] = {"error": f"Failed to fetch {domain} metrics: {e}"}
    # Inject data date range from executive monthlyTrend
    if exec_raw:
        trend = exec_raw.get("monthlyTrend", [])
        if trend:
            result["dataDateRange"] = {
                "from": trend[0].get("period", ""),
                "to": trend[-1].get("period", ""),
            }
    if failed_domains:
        result["_warnings"] = [f"Metrics unavailable for: {', '.join(failed_domains)}. Try again in a moment."]
    return result


_FIELD_CONTEXT: Dict[str, str] = {
    "incidents": "Incident (故障/事件) tickets. Each row is one incident with its priority, status, assigned_to, and time metrics.",
    "changes": "Change (变更) tickets. Each row is one change request with its type, risk, assigned_to, and close_code.",
    "requests": "Service Request (服务请求) tickets. Each row is one request with its type, resolution_time_minutes, SLA compliance, and satisfaction score.",
    "problems": "Problem (问题) tickets. Each row is one problem record with its root cause, status, and resolution details.",
}

_FIELD_DESCRIPTIONS: Dict[str, Dict[str, str]] = {
    "incidents": {
        "ticket_id": "Unique incident ID (e.g. INC0001)",
        "priority": "Severity: P1 (critical) > P2 (high) > P3 (medium) > P4 (low)",
        "category": "Incident type (Database, Network, Application, etc.)",
        "assigned_to": "Person currently assigned to handle this incident",
        "status": "Current status: Closed = resolved, Open/Pending/In Progress = active",
        "response_time_minutes": "Minutes from ticket creation to first response",
        "resolution_time_minutes": "Minutes from ticket creation to resolution",
        "opened_at": "When the incident was opened",
        "closed_at": "When the incident was closed",
    },
    "changes": {
        "ticket_id": "Unique change ID (e.g. CHG0001)",
        "change_type": "Standard (pre-approved), Normal (requires approval), Emergency (urgent)",
        "status": "Current change status",
        "category": "Change area: Application, Infrastructure, Database, Network, Security",
        "close_code": "Outcome: Successful or Failed",
        "incident_ids": "Comma-separated incident IDs caused by this change, if any",
    },
    "requests": {
        "ticket_id": "Unique request ID (e.g. REQ0001)",
        "catalog_item": "Service catalog item: Access, Provisioning, Information, Standard Change",
        "status": "Current request status",
        "requester_dept": "Department that submitted the request",
        "resolution_time_minutes": "Minutes to fulfill the request",
        "satisfaction_score": "User satisfaction rating 1-5",
    },
    "problems": {
        "ticket_id": "Unique problem ID (e.g. PRB0001)",
        "status": "Current problem status",
        "priority": "Severity: P1-P4",
        "cause_code": "Root cause classification: Human Error, Process Gap, Technical Defect, Vendor Issue, Unknown",
        "known_error": "Whether root cause is identified: TRUE/FALSE",
        "workaround": "Temporary workaround description, if available",
    },
}


def _handle_query_tickets(args: Dict[str, Any], config: RuntimeConfig) -> Any:
    source = args.get("source", "")
    if source not in VALID_DOMAINS:
        raise ToolExecutionError(f"Invalid source: {source}. Valid: {', '.join(VALID_DOMAINS)}")
    body: Dict[str, Any] = {}
    if args.get("filters"):
        body["filters"] = args["filters"]
    if args.get("fields"):
        body["fields"] = args["fields"]
    else:
        default_fields = DEFAULT_FIELDS_MAP.get(source)
        if default_fields:
            body["fields"] = default_fields
    if args.get("sort_by"):
        body["sortBy"] = args["sort_by"]
    body["sortOrder"] = args.get("sort_order", "desc")
    body["limit"] = min(_parse_int(str(args.get("limit", DEFAULT_ROWS)), DEFAULT_ROWS), MAX_ROWS)
    result = _bi_request("POST", f"/data/{source}/query", config, body=body)
    # Add field context for model understanding
    if isinstance(result, dict) and "rows" in result:
        rows = result["rows"]
        if rows:
            returned_fields = list(rows[0].keys())
            result["_context"] = _FIELD_CONTEXT.get(source, f"Ticket records from {source}")
            result["_fields"] = {f: _FIELD_DESCRIPTIONS.get(source, {}).get(f, f) for f in returned_fields}
    elif isinstance(result, list) and result:
        returned_fields = list(result[0].keys())
        result = {
            "_context": _FIELD_CONTEXT.get(source, f"Ticket records from {source}"),
            "_fields": {f: _FIELD_DESCRIPTIONS.get(source, {}).get(f, f) for f in returned_fields},
            "total": len(result),
            "rows": result,
        }
    return result


def _handle_compute_metric(args: Dict[str, Any], config: RuntimeConfig) -> Any:
    source = args.get("source", "")
    if source not in VALID_DOMAINS:
        raise ToolExecutionError(f"Invalid source: {source}. Valid: {', '.join(VALID_DOMAINS)}")
    metric = args.get("metric", "")
    if metric not in VALID_METRICS:
        raise ToolExecutionError(f"Invalid metric: {metric}. Valid: {', '.join(VALID_METRICS)}")
    body: Dict[str, Any] = {}
    if args.get("filters"):
        body["filters"] = args["filters"]
    body["aggregate"] = {
        "metric": metric,
        "field": args.get("field"),
        "value": args.get("value"),
        "groupBy": args.get("group_by"),
    }
    return _bi_request("POST", f"/data/{source}/query", config, body=body)


def _handle_trace_ticket_lineage(args: Dict[str, Any], config: RuntimeConfig) -> Any:
    ticket_id = str(args.get("ticket_id", "")).strip()
    source_domain = str(args.get("source_domain", "")).strip()
    if not ticket_id:
        raise ToolExecutionError("ticket_id is required")
    if source_domain not in VALID_DOMAINS:
        raise ToolExecutionError(f"Invalid source_domain: {source_domain}. Valid: {', '.join(VALID_DOMAINS)}")
    return _bi_request("GET", f"/data/{source_domain}/lineage", config, params={"ticketId": ticket_id})


def _handle_analyze_sla_rate(args: Dict[str, Any], config: RuntimeConfig) -> Any:
    by_priority = args.get("by_priority", True)
    by_category = args.get("by_category", False)
    by_resolver = args.get("by_resolver", False)
    by_time = args.get("by_time", False)
    interval = args.get("interval", "week")
    sla_type = args.get("sla_type", "both")

    if interval not in VALID_INTERVALS:
        raise ToolExecutionError(f"Invalid interval: {interval}. Valid: {', '.join(sorted(VALID_INTERVALS))}")
    if sla_type not in ("response", "resolution", "both"):
        raise ToolExecutionError("sla_type must be 'response', 'resolution', or 'both'")

    params = _build_date_params(args)

    # 1. Overall SLA data
    sla_data = _bi_request("GET", "/metrics/sla", config, params=params or None)

    # 2. Build result filtered by sla_type
    result: Dict[str, Any] = {
        "overallRate": sla_data.get("overallRate"),
    }
    if sla_type in ("response", "both"):
        result["responseRate"] = sla_data.get("responseRate")
        result["avgResponseMinutes"] = sla_data.get("avgResponseMinutes")
        result["p90ResponseMinutes"] = sla_data.get("p90ResponseMinutes")
    if sla_type in ("resolution", "both"):
        result["resolutionRate"] = sla_data.get("resolutionRate")
        result["avgResolutionMinutes"] = sla_data.get("avgResolutionMinutes")
        result["p90ResolutionMinutes"] = sla_data.get("p90ResolutionMinutes")
        result["breachedCount"] = sla_data.get("breachedCount")

    # 3. by_priority breakdown (default true)
    if by_priority:
        result["by_priority"] = sla_data.get("priorityBreakdown", {})

    # 4. by_category — SLA risk by Category (from SLA endpoint)
    if by_category:
        result["by_category"] = sla_data.get("topCategoryRisks", [])

    # 5. by_resolver — SLA risk by assigned_to (from SLA endpoint)
    if by_resolver:
        result["by_resolver"] = sla_data.get("topResolverRisks", [])

    # 6. by_time trend
    if by_time:
        trends = []
        resp_trend = _get_trends("incidents", "response_sla_rate", interval, config, args)
        resp_trend["name"] = "response_sla_rate"
        trends.append(resp_trend)

        res_trend = _get_trends("incidents", "resolution_sla_rate", interval, config, args)
        res_trend["name"] = "resolution_sla_rate"
        trends.append(res_trend)

        p12_trend = _get_trends("incidents", "p12_sla_rate", interval, config, args)
        p12_trend["name"] = "p12_sla_rate"
        trends.append(p12_trend)

        result["by_time"] = _merge_trend_series(trends)

    return result


def _handle_analyze_request_sla_rate(args: Dict[str, Any], config: RuntimeConfig) -> Any:
    by_catalog = args.get("by_catalog", True)
    by_department = args.get("by_department", False)
    by_priority = args.get("by_priority", False)
    by_time = args.get("by_time", False)
    interval = args.get("interval", "week")

    if interval not in VALID_INTERVALS:
        raise ToolExecutionError(f"Invalid interval: {interval}. Valid: {', '.join(sorted(VALID_INTERVALS))}")

    params = _build_date_params(args)

    # 1. Overall + grouped SLA data from /metrics/requests
    request_data = _bi_request("GET", "/metrics/requests", config, params=params or None)
    result: Dict[str, Any] = {
        "total": request_data.get("totalCount", 0),
        "sla_rate": request_data.get("slaRate", 0),
        "avg_csat": request_data.get("avgCsat", 0),
        "avg_fulfillment_hours": request_data.get("avgFulfillmentHours", 0),
    }

    # 2. Grouped SLA breakdowns (response/resolution/overall rates per group)
    if by_catalog:
        result["by_catalog"] = request_data.get("slaByCatalog", [])

    if by_priority:
        result["by_priority"] = request_data.get("slaByPriority", [])

    if by_department:
        result["by_department"] = request_data.get("slaByDepartment", [])

    # 3. by_time trend
    if by_time:
        sla_trend = _get_trends("requests", "sla_rate", interval, config, args)
        sla_trend["name"] = "sla_rate"
        result["by_time"] = _merge_trend_series([sla_trend])

    return result


def _handle_analyze_incident_volume(args: Dict[str, Any], config: RuntimeConfig) -> Any:
    by_priority = args.get("by_priority", True)
    by_category = args.get("by_category", False)
    by_time = args.get("by_time", False)
    interval = args.get("interval", "week")

    if interval not in VALID_INTERVALS:
        raise ToolExecutionError(f"Invalid interval: {interval}. Valid: {', '.join(sorted(VALID_INTERVALS))}")

    params = _build_date_params(args)

    # Overall incident metrics
    incident_data = _bi_request("GET", "/metrics/incidents", config, params=params or None)
    result: Dict[str, Any] = {
        "total": incident_data.get("totalCount", incident_data.get("total", 0)),
        "p1p2_count": incident_data.get("p1p2Count", incident_data.get("p1p2_count", 0)),
        "open_count": incident_data.get("openCount", incident_data.get("open_count", 0)),
        "sla_rate": incident_data.get("slaRate", incident_data.get("sla_rate", 0)),
    }

    # by_priority distribution (default true)
    if by_priority:
        result["by_priority"] = incident_data.get("priorityDistribution", incident_data.get("by_priority", {}))

    # by_category distribution
    if by_category:
        cat_body = {
            "aggregate": {
                "metric": "distribution",
                "field": "category",
                "groupBy": "category",
            }
        }
        result["by_category"] = _bi_request("POST", "/data/incidents/query", config, body=cat_body)

    # by_time trend
    if by_time:
        trends = []
        count_trend = _get_trends("incidents", "count", interval, config, args)
        count_trend["name"] = "volume"
        trends.append(count_trend)

        sla_trend = _get_trends("incidents", "sla_rate", interval, config, args)
        sla_trend["name"] = "sla_rate"
        trends.append(sla_trend)

        result["by_time"] = _merge_trend_series(trends)

    return result


def _handle_analyze_mttr(args: Dict[str, Any], config: RuntimeConfig) -> Any:
    by_priority = args.get("by_priority", False)
    by_category = args.get("by_category", False)
    by_resolver = args.get("by_resolver", False)
    by_time = args.get("by_time", False)
    interval = args.get("interval", "week")

    if interval not in VALID_INTERVALS:
        raise ToolExecutionError(f"Invalid interval: {interval}. Valid: {', '.join(sorted(VALID_INTERVALS))}")

    params = _build_date_params(args)

    # Overall MTTR from incident metrics
    incident_data = _bi_request("GET", "/metrics/incidents", config, params=params or None)
    result: Dict[str, Any] = {
        "overall_mttr_hours": incident_data.get("mttrHours", 0),
        "p1p2_mttr_hours": incident_data.get("p1p2MttrHours", 0),
    }

    # by_priority
    if by_priority:
        prio_body = {
            "aggregate": {
                "metric": "avg",
                "field": "resolution_time_minutes",
                "groupBy": "priority",
            }
        }
        result["by_priority"] = _bi_request("POST", "/data/incidents/query", config, body=prio_body)

    # by_category
    if by_category:
        cat_body = {
            "aggregate": {
                "metric": "avg",
                "field": "resolution_time_minutes",
                "groupBy": "category",
            }
        }
        result["by_category"] = _bi_request("POST", "/data/incidents/query", config, body=cat_body)

    # by_resolver
    if by_resolver:
        res_body = {
            "aggregate": {
                "metric": "avg",
                "field": "resolution_time_minutes",
                "groupBy": "assigned_to",
            }
        }
        result["by_resolver"] = _bi_request("POST", "/data/incidents/query", config, body=res_body)

    # by_time trend
    if by_time:
        trends = []
        overall_trend = _get_trends("incidents", "avg_resolution_time", interval, config, args)
        overall_trend["name"] = "overall_mttr"
        trends.append(overall_trend)

        p12_trend = _get_trends("incidents", "p12_avg_resolution_time", interval, config, args)
        p12_trend["name"] = "p1p2_mttr"
        trends.append(p12_trend)

        result["by_time"] = _merge_trend_series(trends)

    return result


def _handle_analyze_change_success_rate(args: Dict[str, Any], config: RuntimeConfig) -> Any:
    by_type = args.get("by_type", True)
    by_category = args.get("by_category", False)
    by_risk_level = args.get("by_risk_level", False)
    by_time = args.get("by_time", False)
    interval = args.get("interval", "week")

    if interval not in VALID_INTERVALS:
        raise ToolExecutionError(f"Invalid interval: {interval}. Valid: {', '.join(sorted(VALID_INTERVALS))}")

    params = _build_date_params(args)

    # Overall change metrics
    change_data = _bi_request("GET", "/metrics/changes", config, params=params or None)
    result: Dict[str, Any] = {
        "total": change_data.get("totalCount", change_data.get("total", 0)),
        "success_rate": change_data.get("successRate", change_data.get("success_rate", 0)),
        "emergency_count": change_data.get("emergencyCount", change_data.get("emergency_count", 0)),
        "incident_caused_count": change_data.get("incidentCausedCount", change_data.get("incident_caused_count", 0)),
    }

    # by_type distribution (default true)
    if by_type:
        result["by_type"] = change_data.get("typeDistribution", change_data.get("by_type", {}))

    # by_category distribution
    if by_category:
        cat_body = {
            "aggregate": {
                "metric": "distribution",
                "field": "category",
                "groupBy": "category",
            }
        }
        result["by_category"] = _bi_request("POST", "/data/changes/query", config, body=cat_body)

    # by_risk_level distribution
    if by_risk_level:
        risk_body = {
            "aggregate": {
                "metric": "distribution",
                "field": "risk",
                "groupBy": "risk",
            }
        }
        result["by_risk_level"] = _bi_request("POST", "/data/changes/query", config, body=risk_body)

    # by_time trend
    if by_time:
        trends = []
        count_trend = _get_trends("changes", "count", interval, config, args)
        count_trend["name"] = "volume"
        trends.append(count_trend)

        success_trend = _get_trends("changes", "success_rate", interval, config, args)
        success_trend["name"] = "success_rate"
        trends.append(success_trend)

        incident_trend = _get_trends("changes", "incident_caused_count", interval, config, args)
        incident_trend["name"] = "incident_caused_count"
        trends.append(incident_trend)

        result["by_time"] = _merge_trend_series(trends)

    return result


def _handle_analyze_request_performance(args: Dict[str, Any], config: RuntimeConfig) -> Any:
    by_type = args.get("by_type", True)
    by_department = args.get("by_department", False)
    by_time = args.get("by_time", False)
    interval = args.get("interval", "week")

    if interval not in VALID_INTERVALS:
        raise ToolExecutionError(f"Invalid interval: {interval}. Valid: {', '.join(sorted(VALID_INTERVALS))}")

    params = _build_date_params(args)

    # Overall request metrics
    request_data = _bi_request("GET", "/metrics/requests", config, params=params or None)
    result: Dict[str, Any] = {
        "total": request_data.get("totalCount", request_data.get("total", 0)),
        "fulfilled_count": request_data.get("fulfilledCount", request_data.get("fulfilled_count", 0)),
        "sla_rate": request_data.get("slaRate", request_data.get("sla_rate", 0)),
        "avg_csat": request_data.get("avgCsat", request_data.get("avg_csat", 0)),
        "avg_fulfillment_hours": request_data.get("avgFulfillmentHours", request_data.get("avg_fulfillment_hours", 0)),
    }

    # by_type distribution (default true)
    if by_type:
        result["by_type"] = request_data.get("typeDistribution", request_data.get("by_type", {}))

    # by_department distribution
    if by_department:
        dept_body = {
            "aggregate": {
                "metric": "distribution",
                "field": "requester_dept",
                "groupBy": "requester_dept",
            }
        }
        result["by_department"] = _bi_request("POST", "/data/requests/query", config, body=dept_body)

    # by_time trend
    if by_time:
        trends = []
        count_trend = _get_trends("requests", "count", interval, config, args)
        count_trend["name"] = "volume"
        trends.append(count_trend)

        csat_trend = _get_trends("requests", "csat", interval, config, args)
        csat_trend["name"] = "csat"
        trends.append(csat_trend)

        sla_trend = _get_trends("requests", "sla_rate", interval, config, args)
        sla_trend["name"] = "sla_rate"
        trends.append(sla_trend)

        result["by_time"] = _merge_trend_series(trends)

    return result


def _handle_analyze_problem_metrics(args: Dict[str, Any], config: RuntimeConfig) -> Any:
    by_status = args.get("by_status", True)
    by_root_cause = args.get("by_root_cause", False)
    by_time = args.get("by_time", False)
    interval = args.get("interval", "week")

    if interval not in VALID_INTERVALS:
        raise ToolExecutionError(f"Invalid interval: {interval}. Valid: {', '.join(sorted(VALID_INTERVALS))}")

    params = _build_date_params(args)

    # Overall problem metrics
    problem_data = _bi_request("GET", "/metrics/problems", config, params=params or None)
    result: Dict[str, Any] = {
        "total": problem_data.get("totalCount", problem_data.get("total", 0)),
        "closed_count": problem_data.get("closedCount", problem_data.get("closed_count", 0)),
        "closure_rate": problem_data.get("closureRate", problem_data.get("closure_rate", 0)),
        "rca_rate": problem_data.get("rcaRate", problem_data.get("rca_rate", 0)),
    }

    # by_status distribution (default true)
    if by_status:
        result["by_status"] = problem_data.get("statusDistribution", problem_data.get("by_status", {}))

    # by_root_cause distribution
    if by_root_cause:
        rc_body = {
            "aggregate": {
                "metric": "distribution",
                "field": "cause_code",
                "groupBy": "cause_code",
            }
        }
        result["by_root_cause"] = _bi_request("POST", "/data/problems/query", config, body=rc_body)

    # by_time trend
    if by_time:
        trends = []
        count_trend = _get_trends("problems", "count", interval, config, args)
        count_trend["name"] = "volume"
        trends.append(count_trend)

        result["by_time"] = _merge_trend_series(trends)

    return result


def _handle_analyze_workforce_performance(args: Dict[str, Any], config: RuntimeConfig) -> Any:
    person_limit = args.get("personLimit", 10)
    params = _build_date_params(args)
    if person_limit is not None:
        params["personLimit"] = str(person_limit)
    return _bi_request("GET", "/metrics/workforce", config, params=params or None)


# ── Tool Definitions ─────────────────────────────────────────────────────────

FILTER_SCHEMA = {
    "type": "array",
    "items": {
        "type": "object",
        "properties": {
            "field": {"type": "string", "description": "Column name from the data"},
            "operator": {"type": "string", "description": "equals, not_equals, contains, starts_with, greater_than, less_than, in"},
            "value": {"description": "Value to compare. Use array for 'in' operator."},
        },
        "required": ["field", "operator", "value"],
    },
    "description": "Filter conditions. All filters are ANDed.",
}

TOOLS = [
    {
        "name": "get_all_metrics",
        "description": (
            "Returns ALL domain metrics (executive, SLA, incidents, changes, requests, problems, cross-process, workforce). "
            "Use for comprehensive overview or full operations summary."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "startDate": {"type": "string", "description": "Optional start date (ISO format)"},
                "endDate": {"type": "string", "description": "Optional end date (ISO format)"},
            },
        },
    },
    {
        "name": "query_tickets",
        "description": (
            "Query individual ticket records from any ITSM domain. Supports filtering, field selection, sorting, and pagination.\n"
            "Source domains: incidents, changes, requests, problems.\n"
            "Use this tool when you need:\n"
            "- Specific SLA-breached incidents, failed changes, low-CSAT requests, open problems\n"
            "- Ticket lists for a given person, category, or time range\n"
            "- Response time, resolution time, satisfaction score, and other detail fields\n"
            "Note: For aggregate metrics (totals, rates, averages, distributions), prefer get_all_metrics or analyze_* tools.\n"
            "Text fields for deeper analysis (include in 'fields' to request): "
            "description (full ticket detail), "
            "close_notes (resolution remarks), "
            "feedback (user satisfaction comment, requests only), "
            "root_cause (problem root cause, problems only), "
            "implementation_plan / test_plan / backout_plan (changes only).\n"
            "IMPORTANT: If previous tool calls used a date range (startDate/endDate), you MUST apply the SAME date range "
            "here via opened_at filters (e.g. {\"field\":\"opened_at\",\"operator\":\"greater_than\",\"value\":\"2024-11-25\"} "
            "and {\"field\":\"opened_at\",\"operator\":\"less_than\",\"value\":\"2024-12-01\"}). "
            "Never query without date filters when the analysis is time-scoped."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "source": {
                    "type": "string",
                    "description": f"One of: {', '.join(VALID_DOMAINS)}",
                    "enum": VALID_DOMAINS,
                },
                "filters": FILTER_SCHEMA,
                "fields": {"type": "array", "items": {"type": "string"}, "description": "Column names to return. Defaults to all."},
                "limit": {"type": "number", "description": f"Max rows (default {DEFAULT_ROWS}, max {MAX_ROWS})"},
                "sort_by": {"type": "string", "description": "Column to sort by"},
                "sort_order": {"type": "string", "description": "asc or desc (default: desc)"},
            },
            "required": ["source"],
        },
    },
    {
        "name": "compute_metric",
        "description": (
            "Run custom aggregation on ITSM data. Supports count/avg/sum/percentage/distribution with optional grouping by any field.\n"
            "Use this tool when you need:\n"
            "- Custom aggregations not available in pre-computed metrics (e.g. incidents grouped by week)\n"
            "- Cross-dimension breakdowns (e.g. average CSAT per category)\n"
            "- Custom percentage calculations\n"
            "Note: If the metric is already available in analyze_* tools, prefer those for richer context.\n"
            "IMPORTANT: If previous tool calls used a date range (startDate/endDate), you MUST apply the SAME date range "
            "here via opened_at filters (e.g. {\"field\":\"opened_at\",\"operator\":\"greater_than\",\"value\":\"2024-11-25\"} "
            "and {\"field\":\"opened_at\",\"operator\":\"less_than\",\"value\":\"2024-12-01\"}). "
            "Never query without date filters when the analysis is time-scoped."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "source": {"type": "string", "description": f"One of: {', '.join(VALID_DOMAINS)}"},
                "metric": {"type": "string", "description": f"One of: {', '.join(VALID_METRICS)}"},
                "field": {"type": "string", "description": "Field to aggregate (required for avg/sum/distribution/percentage)"},
                "value": {"type": "string", "description": "Target value for percentage metric (e.g. 'Yes' for SLA compliance)"},
                "filters": FILTER_SCHEMA,
                "group_by": {"type": "string", "description": "Field to group results by"},
            },
            "required": ["source", "metric"],
        },
    },
    {
        "name": "trace_ticket_lineage",
        "description": (
            "Cross-process ticket lineage tracing. Given a ticket ID, returns related tickets from other processes (e.g. change that caused an incident, incident linked to a problem).\n"
            "Use this tool when you need:\n"
            "- Trace the root-cause change for an incident\n"
            "- Check if a change triggered subsequent incidents\n"
            "- Analyze incidents linked to a problem\n"
            "- Perform cross-process causal chain analysis"
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "ticket_id": {"type": "string", "description": "The ticket ID to trace (e.g. INC000123, CHG000456)"},
                "source_domain": {"type": "string", "description": f"One of: {', '.join(VALID_DOMAINS)}"},
            },
            "required": ["ticket_id", "source_domain"],
        },
    },
    {
        "name": "analyze_sla_rate",
        "description": (
            "Analyze INCIDENT SLA compliance rate. Returns overall rate, response/resolution rates, breach count, and average times. "
            "Default includes by_priority breakdown (P1-P4). Optional: by_category (all categories), by_resolver (all resolvers), "
            "by_time (multi-series weekly trend: response + resolution + P1/P2 rates). Use sla_type to focus on response or resolution only. "
            "This tool covers Incident SLA only. For Request SLA, use analyze_request_sla_rate."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "by_priority": {"type": "boolean", "description": "Include breakdown by priority (P1-P4)", "default": True},
                "by_category": {"type": "boolean", "description": "Include SLA rate breakdown by category"},
                "by_resolver": {"type": "boolean", "description": "Include SLA rate breakdown by resolver"},
                "by_time": {"type": "boolean", "description": "Include time-series trend data"},
                "interval": {"type": "string", "description": "Time interval for trend: week, month, or day", "default": "week",
                             "enum": ["day", "week", "month"]},
                "sla_type": {"type": "string", "description": "Focus on response, resolution, or both", "default": "both",
                             "enum": ["response", "resolution", "both"]},
                "startDate": {"type": "string", "description": "Optional start date (ISO format)"},
                "endDate": {"type": "string", "description": "Optional end date (ISO format)"},
            },
        },
    },
    {
        "name": "analyze_request_sla_rate",
        "description": (
            "Analyze service request SLA compliance rate. Returns overall SLA rate (response AND resolution dual-check), "
            "avg CSAT, avg fulfillment hours. Default includes by_catalog SLA breakdown (response/resolution/overall rates per group). "
            "Optional: by_priority SLA breakdown, by_department SLA breakdown, by_time trend. "
            "For Incident SLA, use analyze_sla_rate instead."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "by_catalog": {"type": "boolean", "description": "Include SLA breakdown by service catalog", "default": True},
                "by_priority": {"type": "boolean", "description": "Include SLA breakdown by priority"},
                "by_department": {"type": "boolean", "description": "Include SLA breakdown by requester department"},
                "by_time": {"type": "boolean", "description": "Include time-series SLA rate trend"},
                "interval": {"type": "string", "description": "Time interval: day, week, or month", "default": "week",
                             "enum": ["day", "week", "month"]},
                "startDate": {"type": "string", "description": "Optional start date (ISO format)"},
                "endDate": {"type": "string", "description": "Optional end date (ISO format)"},
            },
        },
    },
    {
        "name": "analyze_incident_volume",
        "description": (
            "Analyze incident ticket volume. Returns total count, P1/P2 count, open count, SLA rate. "
            "Default includes by_priority distribution. Optional: by_category distribution, by_time trend (volume + SLA rate)."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "by_priority": {"type": "boolean", "description": "Include priority distribution", "default": True},
                "by_category": {"type": "boolean", "description": "Include category distribution"},
                "by_time": {"type": "boolean", "description": "Include time-series trend data"},
                "interval": {"type": "string", "description": "Time interval for trend: week, month, or day", "default": "week",
                             "enum": ["day", "week", "month"]},
                "startDate": {"type": "string", "description": "Optional start date (ISO format)"},
                "endDate": {"type": "string", "description": "Optional end date (ISO format)"},
            },
        },
    },
    {
        "name": "analyze_mttr",
        "description": (
            "Analyze Mean Time To Resolve (MTTR). Returns overall MTTR and P1/P2 MTTR in hours. "
            "Optional: breakdown by priority, category, resolver, and time trend (overall + P1/P2 MTTR)."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "by_priority": {"type": "boolean", "description": "Include MTTR breakdown by priority"},
                "by_category": {"type": "boolean", "description": "Include MTTR breakdown by category"},
                "by_resolver": {"type": "boolean", "description": "Include MTTR breakdown by resolver"},
                "by_time": {"type": "boolean", "description": "Include time-series trend data"},
                "interval": {"type": "string", "description": "Time interval for trend: week, month, or day", "default": "week",
                             "enum": ["day", "week", "month"]},
                "startDate": {"type": "string", "description": "Optional start date (ISO format)"},
                "endDate": {"type": "string", "description": "Optional end date (ISO format)"},
            },
        },
    },
    {
        "name": "analyze_change_success_rate",
        "description": (
            "Analyze change success rate. Returns total count, success rate, emergency count, incident-caused count. "
            "Default includes by_type distribution. Optional: by_category, by_risk_level, by_time trend (volume + success rate + incident-caused count)."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "by_type": {"type": "boolean", "description": "Include type distribution", "default": True},
                "by_category": {"type": "boolean", "description": "Include category distribution"},
                "by_risk_level": {"type": "boolean", "description": "Include risk level distribution"},
                "by_time": {"type": "boolean", "description": "Include time-series trend data"},
                "interval": {"type": "string", "description": "Time interval for trend: week, month, or day", "default": "week",
                             "enum": ["day", "week", "month"]},
                "startDate": {"type": "string", "description": "Optional start date (ISO format)"},
                "endDate": {"type": "string", "description": "Optional end date (ISO format)"},
            },
        },
    },
    {
        "name": "analyze_request_performance",
        "description": (
            "Analyze service request performance. Returns total count, fulfilled count, SLA rate, avg CSAT, avg fulfillment hours. "
            "Default includes by_type distribution. Optional: by_department, by_time trend (volume + CSAT + SLA rate)."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "by_type": {"type": "boolean", "description": "Include type distribution", "default": True},
                "by_department": {"type": "boolean", "description": "Include department distribution"},
                "by_time": {"type": "boolean", "description": "Include time-series trend data"},
                "interval": {"type": "string", "description": "Time interval for trend: week, month, or day", "default": "week",
                             "enum": ["day", "week", "month"]},
                "startDate": {"type": "string", "description": "Optional start date (ISO format)"},
                "endDate": {"type": "string", "description": "Optional end date (ISO format)"},
            },
        },
    },
    {
        "name": "analyze_problem_metrics",
        "description": (
            "Analyze problem management metrics. Returns total count, closed count, closure rate, RCA rate. "
            "Default includes by_status distribution. Optional: by_root_cause, by_time trend (volume)."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "by_status": {"type": "boolean", "description": "Include status distribution", "default": True},
                "by_root_cause": {"type": "boolean", "description": "Include root cause distribution"},
                "by_time": {"type": "boolean", "description": "Include time-series trend data"},
                "interval": {"type": "string", "description": "Time interval for trend: week, month, or day", "default": "week",
                             "enum": ["day", "week", "month"]},
                "startDate": {"type": "string", "description": "Optional start date (ISO format)"},
                "endDate": {"type": "string", "description": "Optional end date (ISO format)"},
            },
        },
    },
    {
        "name": "analyze_workforce_performance",
        "description": (
            "Analyze workforce performance. Returns team-level aggregates (throughput, backlog, delivery hours, SLA, change speed, satisfaction) "
            "and per-person metrics. Default returns top 10 persons by ticket volume; set personLimit=0 for all."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "personLimit": {"type": "number", "description": "Number of persons to return (default 10, 0 for all)", "default": 10},
                "startDate": {"type": "string", "description": "Optional start date (ISO format)"},
                "endDate": {"type": "string", "description": "Optional end date (ISO format)"},
            },
        },
    },
]


# ── Dispatch ─────────────────────────────────────────────────────────────────


def dispatch_tool(name: str, args: Dict[str, Any], config: RuntimeConfig) -> Any:
    if name == "get_all_metrics":
        return _handle_get_all_metrics(args, config)
    if name == "query_tickets":
        return _handle_query_tickets(args, config)
    if name == "compute_metric":
        return _handle_compute_metric(args, config)
    if name == "trace_ticket_lineage":
        return _handle_trace_ticket_lineage(args, config)
    if name == "analyze_sla_rate":
        return _handle_analyze_sla_rate(args, config)
    if name == "analyze_incident_volume":
        return _handle_analyze_incident_volume(args, config)
    if name == "analyze_mttr":
        return _handle_analyze_mttr(args, config)
    if name == "analyze_change_success_rate":
        return _handle_analyze_change_success_rate(args, config)
    if name == "analyze_request_performance":
        return _handle_analyze_request_performance(args, config)
    if name == "analyze_request_sla_rate":
        return _handle_analyze_request_sla_rate(args, config)
    if name == "analyze_problem_metrics":
        return _handle_analyze_problem_metrics(args, config)
    if name == "analyze_workforce_performance":
        return _handle_analyze_workforce_performance(args, config)
    raise KeyError(name)


# ── JSON-RPC ─────────────────────────────────────────────────────────────────


def make_success_response(request_id: Any, result: Dict[str, Any]) -> Dict[str, Any]:
    return {"jsonrpc": JSONRPC_VERSION, "id": request_id, "result": result}


def make_error_response(request_id: Any, code: int, message: str, data: Optional[Any] = None) -> Dict[str, Any]:
    error: Dict[str, Any] = {"code": code, "message": message}
    if data is not None:
        error["data"] = data
    response: Dict[str, Any] = {"jsonrpc": JSONRPC_VERSION, "error": error}
    if request_id is not None:
        response["id"] = request_id
    return response


MAX_RESULT_CHARS = 20000
MAX_IDENTICAL_CALLS = 2
_recent_calls: deque = deque(maxlen=10)


def _check_duplicate(tool_name: str, arguments: Dict[str, Any]) -> Optional[str]:
    """Detect repeated identical tool calls and return an error message if looping."""
    call_sig = json.dumps({"tool": tool_name, "args": arguments}, sort_keys=True, ensure_ascii=False)
    _recent_calls.append(call_sig)
    count = sum(1 for c in _recent_calls if c == call_sig)
    if count >= MAX_IDENTICAL_CALLS:
        _recent_calls.clear()
        return (f"Duplicate call detected: you have called {tool_name} with the same parameters {count} times. "
                "Use the data you already have and proceed to generate your response. Do NOT repeat this query.")
    return None


def format_tool_result(data: Any, *, is_error: bool = False) -> Dict[str, Any]:
    if isinstance(data, str):
        text = data
    else:
        text = json.dumps(data, ensure_ascii=False)
    if len(text) > MAX_RESULT_CHARS:
        text = text[:MAX_RESULT_CHARS] + f"\n\n[Result truncated, original data has {len(text)} characters. Use more specific query conditions to narrow the scope.]"
    return {"content": [{"type": "text", "text": text}], "isError": is_error}


def handle_request(message: Dict[str, Any], config: RuntimeConfig) -> Optional[Dict[str, Any]]:
    request_id = message.get("id")
    method = message.get("method")
    params = message.get("params") or {}

    if method == "initialize":
        LOGGER.info("Handling initialize request")
        return make_success_response(request_id, {
            "protocolVersion": NEGOTIATED_PROTOCOL_VERSION,
            "capabilities": {"tools": {}},
            "serverInfo": {"name": SERVER_NAME, "version": SERVER_VERSION},
            "instructions": "Use the BI data tools to query ITSM analysis tabs and raw ticket data for operations reporting.",
        })

    if method == "notifications/initialized":
        return None

    if method == "ping":
        return make_success_response(request_id, {})

    if method == "tools/list":
        return make_success_response(request_id, {"tools": TOOLS})

    if method == "tools/call":
        name = params.get("name")
        if not isinstance(name, str) or not name:
            return make_error_response(request_id, -32602, "Invalid params: tools/call requires a tool name")
        arguments = params.get("arguments") or {}
        if not isinstance(arguments, dict):
            return make_error_response(request_id, -32602, "Invalid params: tool arguments must be an object")
        LOGGER.info("Handling tool call", tool=name)
        dup_msg = _check_duplicate(name, arguments)
        if dup_msg:
            LOGGER.warning("Duplicate call blocked", tool=name, count=_recent_calls.count(json.dumps({"tool": name, "args": arguments}, sort_keys=True, ensure_ascii=False)))
            return make_success_response(request_id, format_tool_result(dup_msg, is_error=True))
        try:
            result = dispatch_tool(name, arguments, config)
            return make_success_response(request_id, format_tool_result(result))
        except KeyError:
            return make_error_response(request_id, -32601, f"Unknown tool: {name}")
        except ToolExecutionError as exc:
            LOGGER.error("Tool execution failed", tool=name, error=exc.internal_message)
            return make_success_response(request_id, format_tool_result(exc.public_message, is_error=True))
        except (TypeError, ValueError, AttributeError, IndexError) as exc:
            LOGGER.exception("Unhandled tool execution error", tool=name)
            return make_success_response(request_id, format_tool_result(f"Unhandled server error: {exc}", is_error=True))

    return make_error_response(request_id, -32601, f"Method not found: {method}")


def send_message(message: Dict[str, Any]) -> None:
    sys.stdout.write(json.dumps(message, ensure_ascii=True) + "\n")
    sys.stdout.flush()


def main() -> int:
    config = RuntimeConfig.from_env()
    LOGGER.info("BI data service MCP server starting", config=config.masked_dict())

    for raw_line in sys.stdin:
        line = raw_line.strip()
        if not line:
            continue
        try:
            message = json.loads(line)
        except json.JSONDecodeError as exc:
            LOGGER.error("Failed to parse JSON-RPC message", error=str(exc))
            send_message(make_error_response(None, -32700, "Parse error"))
            continue
        if not isinstance(message, dict):
            send_message(make_error_response(None, -32600, "Invalid Request"))
            continue
        response = handle_request(message, config)
        if response is not None:
            send_message(response)

    LOGGER.info("BI data service MCP server exiting")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

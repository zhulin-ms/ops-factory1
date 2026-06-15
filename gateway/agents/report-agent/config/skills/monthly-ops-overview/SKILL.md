---
name: "monthly-ops-overview"
description: "Generate a comprehensive monthly ITSM operations report with interactive HTML charts. Trigger when user asks for monthly report, operations overview, 运营概览, 月报, 月度汇报, or management briefing."
---

# 月度运营概览 / Monthly Operations Overview

## When to Use

Trigger this skill when the user asks for a monthly operations report, operations overview, health check, management briefing, or a comprehensive summary of ITSM operations. Typical triggers: "月度运营概览", "月报", "运维总结", "给我一份运营报告", "monthly report", "operations summary", "导出月度汇报".

## Workflow

1. Call `bi-data-service__get_all_metrics` to retrieve all 8 domain KPIs, executive health score, grade, risk radar (`topRisks`), and `dataDateRange`. Record the health score, grade, critical/warning/attention counts, and all domain KPIs.

2. From the returned `topRisks`, identify the top 3 risk items by severity (Critical > Warning > Attention). For each risk item, note the affected domain.

3. For each of the top 3 risk domains, call the corresponding `bi-data-service__analyze_*` tool with `by_time=true` and `interval=month` to get trend context:
   - SLA risk → `bi-data-service__analyze_sla_rate`
   - Incident risk → `bi-data-service__analyze_incident_volume`
   - Change risk → `bi-data-service__analyze_change_success_rate`
   - Request risk → `bi-data-service__analyze_request_performance`
   - Request SLA risk → `bi-data-service__analyze_request_sla_rate`
   - Problem risk → `bi-data-service__analyze_problem_metrics`
   - Workforce risk → `bi-data-service__analyze_workforce_performance`

4. If a risk domain involves specific ticket details (e.g. SLA breaches), call `bi-data-service__query_tickets` to retrieve up to 10 relevant sample tickets with text fields for root cause context.

5. Generate a self-contained HTML report file with embedded Chart.js charts and professional CSS styling. Use the developer `write` tool to save to `./output/monthly-ops-report-{YYYYMMDD}.html`. Do NOT use Auto Visualiser tools — embed all charts directly in the HTML.

6. Output a chat summary with key findings and reference the HTML file as `[filename](filename)`.

## HTML Report Requirements

The HTML file must be self-contained and include:

### Structure
- **Header**: Title, date range (from `dataDateRange`), health score card (score/100 + grade badge)
- **Executive Summary**: 8-domain KPI table with values and status indicators (green/yellow/red)
- **Risk Radar**: Risk items table (severity badge, title, impact) + pie chart
- **Process Health**: 4 ITIL process comparison bar chart (Incident, Change, Request, Problem scores vs target)
- **Top 3 Focus Areas**: For each — data evidence, root cause signals, sample tickets + monthly trend line chart
- **Recommendations**: 3-5 actionable recommendation cards

### Chart Types (use Chart.js 4.x via CDN)
1. **Health Score Gauge** — Doughnut chart with center text showing score and grade
2. **Risk Severity Distribution** — Pie chart (Critical=red, Warning=orange, Attention=yellow)
3. **Process Health Bar Chart** — Grouped bars: current scores vs target (85)
4. **KPI Overview Bar Chart** — 8 domains with values, color-coded by status
5. **Trend Line Charts** — One per top-3 risk domain, showing monthly metric over time

### Styling
- Professional card-based layout with shadows and rounded corners
- Color palette: primary=#2563eb, danger=#dc2626, warning=#f59e0b, success=#16a34a
- Responsive: works on desktop, tablet, and mobile
- Print-friendly: `@media print` styles included
- All text in the same language as the user's request

### Technical
- Chart.js via CDN: `https://cdn.jsdelivr.net/npm/chart.js`
- All chart data embedded as inline JavaScript variables
- No external images, fonts, or local file references
- UTF-8 charset, valid HTML5

## Rules

- Do not fabricate health scores, grades, or risk items. Use only values returned by `get_all_metrics`.
- Derive the analysis period from `dataDateRange` returned by the tool. Do NOT invent date ranges.
- Every number in the report must trace back to a tool-returned value. Do not estimate or calculate derived metrics not provided by tools.
- Risk severity ordering must follow the backend classification: Critical > Warning > Attention. Do not reclassify risks.
- Limit to 3 focus areas even if more risks exist. Mention remaining risks briefly in the Risk Radar table.
- The HTML file must be self-contained and viewable offline (except Chart.js CDN).
- Save the HTML report to `./output` via the `write` tool. Reference it as `[filename](filename)`. Never reveal full system paths.
- Use the same language as the user for ALL text: chat messages, chart titles, table headers, report headings, and recommendations.
- Do NOT silently switch time periods. If the user's requested period has no data, say so directly.
- Do NOT call any `autovisualiser__*` tools. All charts go into the HTML file only.

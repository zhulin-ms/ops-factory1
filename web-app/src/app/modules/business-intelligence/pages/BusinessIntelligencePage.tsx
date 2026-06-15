import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { RefreshCw, Download } from '../../../platform/ui/icons/AppIcons'
import { useTranslation } from 'react-i18next'
import { runtime } from '../../../../config/runtime'
import { downloadBlobResponse } from '../../../../utils/download'
import { trackedFetch } from '../../../platform/logging/requestClient'
import { useToast } from '../../../platform/providers/ToastContext'
import FilterInlineGroup from '../../../platform/ui/filters/FilterInlineGroup'
import AnalyticsTableCard from '../../../platform/ui/primitives/AnalyticsTableCard'
import Button from '../../../platform/ui/primitives/Button'
import PageHeader from '../../../platform/ui/primitives/PageHeader'
import PieDistributionCard from '../../../platform/ui/primitives/PieDistributionCard'
import SectionCard from '../../../platform/ui/primitives/SectionCard'
import StatCard from '../../../platform/ui/primitives/StatCard'
import StatusIcon, { type StatusTone } from '../../../platform/ui/primitives/StatusIcon'
import StatusCell from '../../../platform/ui/primitives/StatusCell'
import '../styles/business-intelligence.css'

interface TabMeta {
    id: string
    label: string
}

interface ExecutiveHero {
    score: string
    grade: string
    summary: string
    changeHint: string
    periodLabel: string
}

interface ProcessHealth {
    id: string
    label: string
    score: string
    tone: string
    summary: string
}

interface ExecutiveRisk {
    id: string
    priority: string
    title: string
    impact: string
    process: string
    value: string
}

interface RiskSummary {
    critical: number
    warning: number
    attention: number
    topRisks: ExecutiveRisk[]
}

interface TrendPoint {
    label: string
    score: number
    signal: number
}

interface TrendSection {
    title: string
    subtitle: string
    points: TrendPoint[]
}

interface ExecutiveSummary {
    hero: ExecutiveHero
    processHealths: ProcessHealth[]
    riskSummary: RiskSummary
    trend: TrendSection
}

interface MetricCard {
    id: string
    label: string
    value: string
    tone: string
}

interface ChartDatum {
    label: string
    value: number
}

interface ChartConfig {
    series?: string[]
    seriesData?: Record<string, ChartDatum[]>
    colors?: string[]
    xAxisLabel?: string
    yAxisLabel?: string
}

interface ChartSection {
    id: string
    title: string
    type: string
    items: ChartDatum[]
    config?: ChartConfig
}

interface TableSection {
    id: string
    title: string
    columns: string[]
    rows: string[][]
}

interface TabContent {
    id: string
    label: string
    description: string
    executiveSummary: ExecutiveSummary | null
    slaAnalysis: unknown | null
    cards: MetricCard[]
    charts: ChartSection[]
    tables: TableSection[]
}

interface OverviewResponse {
    refreshedAt: string
    tabs: TabMeta[]
    tabContents: Record<string, TabContent>
}

type TranslateFn = (key: string, options?: Record<string, unknown>) => string

const BUSINESS_INTELLIGENCE_TAB_LABEL_FALLBACK_KEYS: Record<string, string> = {
    '执行摘要': 'businessIntelligence.tabs.executiveSummary',
    'sla分析': 'businessIntelligence.tabs.slaAnalysis',
    '事件分析': 'businessIntelligence.tabs.eventAnalysis',
    '变更分析': 'businessIntelligence.tabs.changeAnalysis',
    '请求分析': 'businessIntelligence.tabs.requestAnalysis',
    '问题分析': 'businessIntelligence.tabs.problemAnalysis',
    '跨流程关联': 'businessIntelligence.tabs.crossProcessCorrelation',
    '人员与效率': 'businessIntelligence.tabs.workforce',
    'Workforce': 'businessIntelligence.tabs.workforce',
}

const INCIDENT_ANALYSIS_TAB_IDS = new Set(['event-analysis', 'incident-analysis'])
const INCIDENT_CARD_LABEL_KEYS: Record<string, string> = {
    'incident-total': 'businessIntelligence.incidents.cards.total',
    'incident-p1p2': 'businessIntelligence.incidents.cards.p1p2',
    'incident-open': 'businessIntelligence.incidents.cards.open',
    'incident-sla': 'businessIntelligence.incidents.cards.sla',
    'incident-p1p2-mttr': 'businessIntelligence.incidents.cards.p1p2Mttr',
    'incident-mttr': 'businessIntelligence.incidents.cards.mttr',
}

const INCIDENT_CHART_TITLE_KEYS: Record<string, string> = {
    'incident-volume-trend': 'businessIntelligence.incidents.charts.volumeTrend',
    'incident-mttr-trend': 'businessIntelligence.incidents.charts.mttrTrend',
    'incident-priority-pie': 'businessIntelligence.incidents.charts.priorityDistribution',
    'incident-category-pie': 'businessIntelligence.incidents.charts.typeDistribution',
}

const INCIDENT_CHART_SERIES_KEYS: Record<string, string[]> = {
    'incident-volume-trend': [
        'businessIntelligence.incidents.charts.volumeSeries',
        'businessIntelligence.incidents.charts.slaSeries',
    ],
    'incident-mttr-trend': [
        'businessIntelligence.incidents.charts.mttrSeries',
        'businessIntelligence.incidents.charts.p1p2MttrSeries',
    ],
}

const INCIDENT_TABLE_TITLE_KEYS: Record<string, string> = {
    'incident-resolver-table': 'businessIntelligence.incidents.tables.resolverTop10',
    'incident-recent-table': 'businessIntelligence.incidents.tables.samples',
}

const INCIDENT_TABLE_COLUMN_KEYS: Record<string, string> = {
    '处理人': 'businessIntelligence.incidents.columns.resolver',
    '事件数': 'businessIntelligence.incidents.columns.incidentCount',
    '编号': 'businessIntelligence.incidents.columns.id',
    '标题': 'businessIntelligence.incidents.columns.title',
    '优先级': 'businessIntelligence.incidents.columns.priority',
    '时长': 'businessIntelligence.incidents.columns.duration',
    'SLA': 'businessIntelligence.incidents.columns.sla',
}

function localizeIncidentCard(card: MetricCard, t: TranslateFn): MetricCard {
    const labelKey = INCIDENT_CARD_LABEL_KEYS[card.id]
    if (!labelKey) {
        return card
    }

    return {
        ...card,
        label: t(labelKey),
    }
}

function localizeIncidentChart(chart: ChartSection, t: TranslateFn): ChartSection {
    const titleKey = INCIDENT_CHART_TITLE_KEYS[chart.id]
    const seriesKeys = INCIDENT_CHART_SERIES_KEYS[chart.id]

    if (!titleKey && !seriesKeys) {
        return chart
    }

    return {
        ...chart,
        title: titleKey ? t(titleKey) : chart.title,
        config: {
            ...chart.config,
            series: seriesKeys ? seriesKeys.map(key => t(key)) : chart.config?.series,
        },
    }
}

function localizeIncidentTable(table: TableSection, t: TranslateFn): TableSection {
    const titleKey = INCIDENT_TABLE_TITLE_KEYS[table.id]

    return {
        ...table,
        title: titleKey ? t(titleKey) : table.title,
        columns: table.columns.map(column => {
            const key = INCIDENT_TABLE_COLUMN_KEYS[column]
            return key ? t(key) : column
        }),
    }
}

function localizeIncidentTab(tab: TabContent, t: TranslateFn): TabContent {
    return {
        ...tab,
        cards: tab.cards.map(card => localizeIncidentCard(card, t)),
        charts: tab.charts.map(chart => localizeIncidentChart(chart, t)),
        tables: tab.tables.map(table => localizeIncidentTable(table, t)),
    }
}

// ── Workforce localization ──────────────────────────────────────────

const WORKFORCE_TAB_IDS = new Set(['workforce', 'personnel-efficiency'])

const WORKFORCE_CARD_LABEL_KEYS: Record<string, string> = {
    'wf-avg-throughput': 'businessIntelligence.workforce.cards.avgThroughput',
    'wf-backlog': 'businessIntelligence.workforce.cards.backlog',
    'wf-avg-delivery-time': 'businessIntelligence.workforce.cards.avgDeliveryTime',
    'wf-sla-rate': 'businessIntelligence.workforce.cards.slaRate',
    'wf-change-speed': 'businessIntelligence.workforce.cards.changeSpeed',
    'wf-first-time-success': 'businessIntelligence.workforce.cards.firstTimeSuccess',
    'wf-avg-satisfaction': 'businessIntelligence.workforce.cards.avgSatisfaction',
    'wf-problem-fix-rate': 'businessIntelligence.workforce.cards.problemFixRate',
}

const WORKFORCE_CHART_TITLE_KEYS: Record<string, string> = {
    'wf-workload-distribution': 'businessIntelligence.workforce.charts.workloadDistribution',
    'wf-efficiency-heatmap': 'businessIntelligence.workforce.charts.efficiencyHeatmap',
    'wf-performance-matrix': 'businessIntelligence.workforce.charts.performanceMatrix',
    'wf-person-radar': 'businessIntelligence.workforce.charts.personRadar',
}

const WORKFORCE_CHART_SERIES_KEYS: Record<string, string[]> = {
    'wf-workload-distribution': [
        'businessIntelligence.workforce.charts.seriesIncidents',
        'businessIntelligence.workforce.charts.seriesChanges',
        'businessIntelligence.workforce.charts.seriesRequests',
        'businessIntelligence.workforce.charts.seriesProblems',
    ],
}

const WORKFORCE_TABLE_TITLE_KEYS: Record<string, string> = {
    'wf-firefighter-table': 'businessIntelligence.workforce.tables.firefighter',
    'wf-tech-debt-table': 'businessIntelligence.workforce.tables.techDebt',
    'wf-highrisk-change-table': 'businessIntelligence.workforce.tables.highRiskChange',
}

const WORKFORCE_TABLE_COLUMN_KEYS: Record<string, string> = {
    '处理人': 'businessIntelligence.workforce.columns.person',
    '实施人': 'businessIntelligence.workforce.columns.person',
    'P1/P2事件数': 'businessIntelligence.workforce.columns.p1p2Count',
    '平均解决时长': 'businessIntelligence.workforce.columns.avgResolutionTime',
    '涉及CI数': 'businessIntelligence.workforce.columns.ciCount',
    'SLA达标率': 'businessIntelligence.workforce.columns.slaRate',
    '根治问题数': 'businessIntelligence.workforce.columns.fixCount',
    '根治率': 'businessIntelligence.workforce.columns.fixRate',
    '关联事件数': 'businessIntelligence.workforce.columns.relatedIncidents',
    '根因类别': 'businessIntelligence.workforce.columns.rootCause',
    '高风险变更数': 'businessIntelligence.workforce.columns.changeCount',
    '成功率': 'businessIntelligence.workforce.columns.successRate',
    '回退率': 'businessIntelligence.workforce.columns.backoutRate',
    '致事件率': 'businessIntelligence.workforce.columns.causedRate',
}

const WORKFORCE_DIMENSION_KEYS: Record<string, string> = {
    '速度': 'businessIntelligence.workforce.dimensions.speed',
    '产量': 'businessIntelligence.workforce.dimensions.volume',
    '质量': 'businessIntelligence.workforce.dimensions.quality',
    '满意度': 'businessIntelligence.workforce.dimensions.satisfaction',
    '难度': 'businessIntelligence.workforce.dimensions.difficulty',
}

function localizeWorkforceTab(tab: TabContent, t: TranslateFn): TabContent {
    return {
        ...tab,
        cards: tab.cards.map(card => {
            const key = WORKFORCE_CARD_LABEL_KEYS[card.id]
            return key ? { ...card, label: t(key) } : card
        }),
        charts: tab.charts.map(chart => {
            const titleKey = WORKFORCE_CHART_TITLE_KEYS[chart.id]
            const seriesKeys = WORKFORCE_CHART_SERIES_KEYS[chart.id]
            // For radar: also localize dimension labels in items and seriesData
            if (chart.type === 'radar') {
                const localizedItems = chart.items.map(item => {
                    const dimKey = WORKFORCE_DIMENSION_KEYS[item.label]
                    return dimKey ? { ...item, label: t(dimKey) } : item
                })
                const localizedSeriesData: Record<string, ChartDatum[]> = {}
                if (chart.config?.seriesData) {
                    for (const [person, data] of Object.entries(chart.config.seriesData)) {
                        localizedSeriesData[person] = data.map(d => {
                            const dimKey = WORKFORCE_DIMENSION_KEYS[d.label]
                            return dimKey ? { ...d, label: t(dimKey) } : d
                        })
                    }
                }
                return {
                    ...chart,
                    title: titleKey ? t(titleKey) : chart.title,
                    items: localizedItems,
                    config: {
                        ...chart.config,
                        seriesData: localizedSeriesData,
                        series: seriesKeys ? seriesKeys.map(key => t(key)) : chart.config?.series,
                    },
                }
            }
            return {
                ...chart,
                title: titleKey ? t(titleKey) : chart.title,
                config: {
                    ...chart.config,
                    series: seriesKeys ? seriesKeys.map(key => t(key)) : chart.config?.series,
                },
            }
        }),
        tables: tab.tables.map(table => {
            const titleKey = WORKFORCE_TABLE_TITLE_KEYS[table.id]
            return {
                ...table,
                title: titleKey ? t(titleKey) : table.title,
                columns: table.columns.map(col => {
                    const key = WORKFORCE_TABLE_COLUMN_KEYS[col]
                    return key ? t(key) : col
                }),
            }
        }),
    }
}

function isWorkforceTab(tab: TabContent): boolean {
    return WORKFORCE_TAB_IDS.has(tab.id)
}

// ── Executive Summary localization ──────────────────────────────────

const EXECUTIVE_CARD_LABEL_KEYS: Record<string, string> = {
    'incident-sla-rate': 'businessIntelligence.executiveSummary.cards.incident-sla-rate',
    'incident-mttr': 'businessIntelligence.executiveSummary.cards.incident-mttr',
    'change-success-rate': 'businessIntelligence.executiveSummary.cards.change-success-rate',
    'change-incident-rate': 'businessIntelligence.executiveSummary.cards.change-incident-rate',
    'request-csat': 'businessIntelligence.executiveSummary.cards.request-csat',
    'problem-closure-rate': 'businessIntelligence.executiveSummary.cards.problem-closure-rate',
}

const EXECUTIVE_TABLE_TITLE_KEYS: Record<string, string> = {
    'summary-risks': 'businessIntelligence.executiveSummary.tableTitle',
}

const EXECUTIVE_TABLE_COLUMN_KEYS: Record<string, string> = {
    '指标': 'businessIntelligence.executiveSummary.tableColumns.metric',
    '当前值': 'businessIntelligence.executiveSummary.tableColumns.currentValue',
    '说明': 'businessIntelligence.executiveSummary.tableColumns.description',
}

function localizeExecutiveTab(tab: TabContent, t: TranslateFn): TabContent {
    return {
        ...tab,
        cards: tab.cards.map(card => {
            const key = EXECUTIVE_CARD_LABEL_KEYS[card.id]
            return key ? { ...card, label: t(key) } : card
        }),
        charts: tab.charts,
        tables: tab.tables.map(table => {
            const titleKey = EXECUTIVE_TABLE_TITLE_KEYS[table.id]
            return {
                ...table,
                title: titleKey ? t(titleKey) : table.title,
                columns: table.columns.map(col => {
                    const key = EXECUTIVE_TABLE_COLUMN_KEYS[col]
                    return key ? t(key) : col
                }),
            }
        }),
    }
}

function isExecutiveSummaryTab(tab: TabContent): boolean {
    return tab.id === 'executive-summary'
}

// ── Changes localization ─────────────────────────────────────────

const CHANGES_TAB_IDS = new Set(['change-analysis'])

const CHANGES_CARD_LABEL_KEYS: Record<string, string> = {
    'change-total': 'businessIntelligence.changes.cards.total',
    'change-success': 'businessIntelligence.changes.cards.success',
    'change-emergency': 'businessIntelligence.changes.cards.emergency',
    'change-incident': 'businessIntelligence.changes.cards.incident',
}

const CHANGES_CHART_TITLE_KEYS: Record<string, string> = {
    'change-success-trend': 'businessIntelligence.changes.charts.successTrend',
    'change-type-pie': 'businessIntelligence.changes.charts.typeDistribution',
    'change-category-stacked': 'businessIntelligence.changes.charts.categoryStacked',
    'change-risk-level': 'businessIntelligence.changes.charts.riskDistribution',
    'change-plan-deviation': 'businessIntelligence.changes.charts.planDeviation',
}

const CHANGES_CHART_SERIES_KEYS: Record<string, string[]> = {
    'change-success-trend': [
        'businessIntelligence.changes.charts.seriesVolume',
        'businessIntelligence.changes.charts.seriesSuccessRate',
        'businessIntelligence.changes.charts.seriesIncidentChange',
    ],
    'change-category-stacked': [
        'businessIntelligence.changes.charts.seriesSuccess',
        'businessIntelligence.changes.charts.seriesFailed',
    ],
    'change-plan-deviation': [
        'businessIntelligence.changes.charts.seriesEarly',
        'businessIntelligence.changes.charts.seriesOnTime',
        'businessIntelligence.changes.charts.seriesDelayed',
    ],
}

const CHANGES_TABLE_TITLE_KEYS: Record<string, string> = {
    'change-failed-table': 'businessIntelligence.changes.tables.failedSamples',
}

const CHANGES_TABLE_COLUMN_KEYS: Record<string, string> = {
    '编号': 'businessIntelligence.changes.columns.id',
    '标题': 'businessIntelligence.changes.columns.title',
    '状态': 'businessIntelligence.changes.columns.status',
    '是否成功': 'businessIntelligence.changes.columns.success',
    '是否回退': 'businessIntelligence.changes.columns.backout',
}

function localizeChangesTab(tab: TabContent, t: TranslateFn): TabContent {
    return {
        ...tab,
        cards: tab.cards.map(card => {
            const key = CHANGES_CARD_LABEL_KEYS[card.id]
            return key ? { ...card, label: t(key) } : card
        }),
        charts: tab.charts.map(chart => {
            const titleKey = CHANGES_CHART_TITLE_KEYS[chart.id]
            const seriesKeys = CHANGES_CHART_SERIES_KEYS[chart.id]
            return {
                ...chart,
                title: titleKey ? t(titleKey) : chart.title,
                config: {
                    ...chart.config,
                    series: seriesKeys ? seriesKeys.map(key => t(key)) : chart.config?.series,
                    xAxisLabel: chart.config?.xAxisLabel ? localizeAxis(chart.config.xAxisLabel, 'changes', t) : chart.config?.xAxisLabel,
                    yAxisLabel: chart.config?.yAxisLabel ? localizeAxis(chart.config.yAxisLabel, 'changes', t) : chart.config?.yAxisLabel,
                },
            }
        }),
        tables: tab.tables.map(table => {
            const titleKey = CHANGES_TABLE_TITLE_KEYS[table.id]
            return {
                ...table,
                title: titleKey ? t(titleKey) : table.title,
                columns: table.columns.map(col => {
                    const key = CHANGES_TABLE_COLUMN_KEYS[col]
                    return key ? t(key) : col
                }),
            }
        }),
    }
}

// ── Requests localization ─────────────────────────────────────────

const REQUESTS_TAB_IDS = new Set(['request-analysis'])

const REQUESTS_CARD_LABEL_KEYS: Record<string, string> = {
    'request-total': 'businessIntelligence.requests.cards.total',
    'request-fulfilled': 'businessIntelligence.requests.cards.fulfilled',
    'request-sla': 'businessIntelligence.requests.cards.sla',
    'request-csat': 'businessIntelligence.requests.cards.csat',
}

const REQUESTS_CHART_TITLE_KEYS: Record<string, string> = {
    'request-volume-trend': 'businessIntelligence.requests.charts.volumeTrend',
    'request-sla-time': 'businessIntelligence.requests.charts.slaTime',
    'request-type-pie': 'businessIntelligence.requests.charts.typeDistribution',
    'request-dept-ranking': 'businessIntelligence.requests.charts.deptRanking',
    'request-satisfaction-pie': 'businessIntelligence.requests.charts.satisfactionDistribution',
    'request-category-top': 'businessIntelligence.requests.charts.categoryTop',
}

const REQUESTS_CHART_SERIES_KEYS: Record<string, string[]> = {
    'request-volume-trend': [
        'businessIntelligence.requests.charts.seriesVolume',
        'businessIntelligence.requests.charts.seriesCsat',
    ],
    'request-sla-time': [
        'businessIntelligence.requests.charts.seriesAvgTime',
        'businessIntelligence.requests.charts.seriesSlaRate',
    ],
}

const REQUESTS_TABLE_TITLE_KEYS: Record<string, string> = {
    'request-low-csat-table': 'businessIntelligence.requests.tables.lowCsatSamples',
}

const REQUESTS_TABLE_COLUMN_KEYS: Record<string, string> = {
    '编号': 'businessIntelligence.requests.columns.id',
    '标题': 'businessIntelligence.requests.columns.title',
    '类别': 'businessIntelligence.requests.columns.category',
    '满足时间': 'businessIntelligence.requests.columns.fulfillTime',
    '满意度': 'businessIntelligence.requests.columns.satisfaction',
    '反馈': 'businessIntelligence.requests.columns.feedback',
}

function localizeRequestsTab(tab: TabContent, t: TranslateFn): TabContent {
    return {
        ...tab,
        cards: tab.cards.map(card => {
            const key = REQUESTS_CARD_LABEL_KEYS[card.id]
            return key ? { ...card, label: t(key) } : card
        }),
        charts: tab.charts.map(chart => {
            const titleKey = REQUESTS_CHART_TITLE_KEYS[chart.id]
            const seriesKeys = REQUESTS_CHART_SERIES_KEYS[chart.id]
            return {
                ...chart,
                title: titleKey ? t(titleKey) : chart.title,
                config: {
                    ...chart.config,
                    series: seriesKeys ? seriesKeys.map(key => t(key)) : chart.config?.series,
                    xAxisLabel: chart.config?.xAxisLabel ? localizeAxis(chart.config.xAxisLabel, 'requests', t) : chart.config?.xAxisLabel,
                    yAxisLabel: chart.config?.yAxisLabel ? localizeAxis(chart.config.yAxisLabel, 'requests', t) : chart.config?.yAxisLabel,
                },
            }
        }),
        tables: tab.tables.map(table => {
            const titleKey = REQUESTS_TABLE_TITLE_KEYS[table.id]
            return {
                ...table,
                title: titleKey ? t(titleKey) : table.title,
                columns: table.columns.map(col => {
                    const key = REQUESTS_TABLE_COLUMN_KEYS[col]
                    return key ? t(key) : col
                }),
            }
        }),
    }
}

// ── Problems localization ─────────────────────────────────────────

const PROBLEMS_TAB_IDS = new Set(['problem-analysis'])

const PROBLEMS_CARD_LABEL_KEYS: Record<string, string> = {
    'problem-total': 'businessIntelligence.problems.cards.total',
    'problem-closed': 'businessIntelligence.problems.cards.closed',
    'problem-rca': 'businessIntelligence.problems.cards.rca',
    'problem-known-error': 'businessIntelligence.problems.cards.knownError',
}

const PROBLEMS_CHART_TITLE_KEYS: Record<string, string> = {
    'problem-volume-trend': 'businessIntelligence.problems.charts.volumeTrend',
    'problem-root-cause-pie': 'businessIntelligence.problems.charts.rootCauseDistribution',
    'problem-incident-ranking': 'businessIntelligence.problems.charts.incidentRanking',
    'problem-status-pie': 'businessIntelligence.problems.charts.statusDistribution',
    'problem-resolution-health': 'businessIntelligence.problems.charts.resolutionHealth',
    'problem-tech-debt': 'businessIntelligence.problems.charts.techDebt',
}

const PROBLEMS_CHART_SERIES_KEYS: Record<string, string[]> = {
    'problem-volume-trend': [
        'businessIntelligence.problems.charts.seriesVolume',
        'businessIntelligence.problems.charts.seriesAccumulated',
    ],
    'problem-resolution-health': [
        'businessIntelligence.problems.charts.seriesPermanent',
        'businessIntelligence.problems.charts.seriesTemporary',
        'businessIntelligence.problems.charts.seriesUnresolved',
    ],
}

const PROBLEMS_TABLE_TITLE_KEYS: Record<string, string> = {
    'problem-open-table': 'businessIntelligence.problems.tables.openProblems',
}

const PROBLEMS_TABLE_COLUMN_KEYS: Record<string, string> = {
    '编号': 'businessIntelligence.problems.columns.id',
    '标题': 'businessIntelligence.problems.columns.title',
    '状态': 'businessIntelligence.problems.columns.status',
    '关联事件': 'businessIntelligence.problems.columns.relatedIncidents',
}

function localizeProblemsTab(tab: TabContent, t: TranslateFn): TabContent {
    return {
        ...tab,
        cards: tab.cards.map(card => {
            const key = PROBLEMS_CARD_LABEL_KEYS[card.id]
            return key ? { ...card, label: t(key) } : card
        }),
        charts: tab.charts.map(chart => {
            const titleKey = PROBLEMS_CHART_TITLE_KEYS[chart.id]
            const seriesKeys = PROBLEMS_CHART_SERIES_KEYS[chart.id]
            return {
                ...chart,
                title: titleKey ? t(titleKey) : chart.title,
                config: {
                    ...chart.config,
                    series: seriesKeys ? seriesKeys.map(key => t(key)) : chart.config?.series,
                    xAxisLabel: chart.config?.xAxisLabel ? localizeAxis(chart.config.xAxisLabel, 'problems', t) : chart.config?.xAxisLabel,
                    yAxisLabel: chart.config?.yAxisLabel ? localizeAxis(chart.config.yAxisLabel, 'problems', t) : chart.config?.yAxisLabel,
                },
            }
        }),
        tables: tab.tables.map(table => {
            const titleKey = PROBLEMS_TABLE_TITLE_KEYS[table.id]
            return {
                ...table,
                title: titleKey ? t(titleKey) : table.title,
                columns: table.columns.map(col => {
                    const key = PROBLEMS_TABLE_COLUMN_KEYS[col]
                    return key ? t(key) : col
                }),
            }
        }),
    }
}

// ── SLA Analysis localization ─────────────────────────────────────

const SLA_TAB_IDS = new Set(['sla-analysis'])

const SLA_CARD_LABEL_KEYS: Record<string, string> = {
    'sla-overall': 'businessIntelligence.sla.cards.overall',
    'sla-response': 'businessIntelligence.sla.cards.response',
    'sla-resolution': 'businessIntelligence.sla.cards.resolution',
    'sla-high-priority': 'businessIntelligence.sla.cards.highPriority',
    'sla-response-breached': 'businessIntelligence.sla.cards.responseBreached',
    'sla-resolution-breached': 'businessIntelligence.sla.cards.resolutionBreached',
    'req-sla-overall': 'businessIntelligence.sla.cards.requestOverall',
    'req-sla-breached': 'businessIntelligence.sla.cards.requestBreached',
    'req-sla-avg-delivery': 'businessIntelligence.sla.cards.requestAvgDelivery',
    'req-sla-breached-csat': 'businessIntelligence.sla.cards.requestBreachedCsat',
}

const SLA_CHART_TITLE_KEYS: Record<string, string> = {
    'sla-trend': 'businessIntelligence.sla.charts.trend',
    'priority-comparison': 'businessIntelligence.sla.charts.priorityComparison',
    'violation-by-priority': 'businessIntelligence.sla.charts.violationByPriority',
    'violation-by-category': 'businessIntelligence.sla.charts.violationByCategory',
    'req-sla-trend': 'businessIntelligence.sla.charts.requestTrend',
    'req-sla-catalog-comparison': 'businessIntelligence.sla.charts.requestCatalogComparison',
    'req-sla-violation-by-dept': 'businessIntelligence.sla.charts.requestViolationByDept',
    'req-sla-violation-by-catalog': 'businessIntelligence.sla.charts.requestViolationByCatalog',
}

const SLA_CHART_SERIES_KEYS: Record<string, string[]> = {
    'sla-trend': [
        'businessIntelligence.sla.charts.seriesResponse',
        'businessIntelligence.sla.charts.seriesResolution',
        'businessIntelligence.sla.charts.seriesP1p2',
    ],
    'priority-comparison': [
        'businessIntelligence.sla.charts.seriesResponse',
        'businessIntelligence.sla.charts.seriesResolution',
    ],
    'req-sla-trend': [
        'businessIntelligence.sla.charts.seriesSlaRate',
        'businessIntelligence.sla.charts.seriesAvgCsat',
    ],
    'req-sla-catalog-comparison': [
        'businessIntelligence.sla.charts.seriesResponse',
        'businessIntelligence.sla.charts.seriesResolution',
    ],
}

const SLA_TABLE_TITLE_KEYS: Record<string, string> = {
    'sla-violation-samples': 'businessIntelligence.sla.tables.violationSamples',
    'req-sla-violation-samples': 'businessIntelligence.sla.tables.requestViolationSamples',
}

const SLA_TABLE_COLUMN_KEYS: Record<string, string> = {
    '编号': 'businessIntelligence.sla.columns.id',
    '标题': 'businessIntelligence.sla.columns.title',
    '优先级': 'businessIntelligence.sla.columns.priority',
    '类别': 'businessIntelligence.sla.columns.category',
    '处理人': 'businessIntelligence.sla.columns.resolver',
    '响应时长': 'businessIntelligence.sla.columns.responseTime',
    '解决时长': 'businessIntelligence.sla.columns.resolutionTime',
    '违约类型': 'businessIntelligence.sla.columns.violationType',
    '服务目录': 'businessIntelligence.sla.columns.catalogItem',
    '请求部门': 'businessIntelligence.sla.columns.requesterDept',
    '满意度': 'businessIntelligence.sla.columns.satisfaction',
}

function localizeSlaTab(tab: TabContent, t: TranslateFn): TabContent {
    return {
        ...tab,
        cards: tab.cards.map(card => {
            const key = SLA_CARD_LABEL_KEYS[card.id]
            return key ? { ...card, label: t(key) } : card
        }),
        charts: tab.charts.map(chart => {
            const titleKey = SLA_CHART_TITLE_KEYS[chart.id]
            const seriesKeys = SLA_CHART_SERIES_KEYS[chart.id]
            return {
                ...chart,
                title: titleKey ? t(titleKey) : chart.title,
                config: {
                    ...chart.config,
                    series: seriesKeys ? seriesKeys.map(key => t(key)) : chart.config?.series,
                    xAxisLabel: chart.config?.xAxisLabel ? localizeAxis(chart.config.xAxisLabel, 'sla', t) : chart.config?.xAxisLabel,
                    yAxisLabel: chart.config?.yAxisLabel ? localizeAxis(chart.config.yAxisLabel, 'sla', t) : chart.config?.yAxisLabel,
                },
            }
        }),
        tables: tab.tables.map(table => {
            const titleKey = SLA_TABLE_TITLE_KEYS[table.id]
            return {
                ...table,
                title: titleKey ? t(titleKey) : table.title,
                columns: table.columns.map(col => {
                    const key = SLA_TABLE_COLUMN_KEYS[col]
                    return key ? t(key) : col
                }),
            }
        }),
    }
}

// ── Correlation localization ──────────────────────────────────────

const CORRELATION_TAB_IDS = new Set(['cross-process', 'cross-process-correlation', 'cross-process-analysis'])

const CORRELATION_CARD_LABEL_KEYS: Record<string, string> = {
    'cross-change-incident-rate': 'businessIntelligence.correlation.cards.changeIncidentRate',
    'cross-48h-p1p2': 'businessIntelligence.correlation.cards.48hP1p2',
    'cross-request-incident-ratio': 'businessIntelligence.correlation.cards.requestIncidentRatio',
    'cross-fragility-score': 'businessIntelligence.correlation.cards.fragilityScore',
}

const CORRELATION_CHART_TITLE_KEYS: Record<string, string> = {
    'cross-change-incident-trend': 'businessIntelligence.correlation.charts.changeIncidentTrend',
    'cross-change-heatmap': 'businessIntelligence.correlation.charts.changeHeatmap',
    'cross-tech-debt-bubble': 'businessIntelligence.correlation.charts.techDebtBubble',
    'cross-request-incident-overlap': 'businessIntelligence.correlation.charts.requestIncidentOverlap',
}

const CORRELATION_CHART_SERIES_KEYS: Record<string, string[]> = {
    'cross-change-incident-trend': [
        'businessIntelligence.correlation.charts.seriesChangeVolume',
        'businessIntelligence.correlation.charts.seriesP1p2Count',
    ],
    'cross-change-heatmap': [
        'businessIntelligence.correlation.charts.seriesChangeDensity',
        'businessIntelligence.correlation.charts.seriesIncidentHotspots',
    ],
    'cross-request-incident-overlap': [
        'businessIntelligence.correlation.charts.seriesRequests',
        'businessIntelligence.correlation.charts.seriesIncidents',
    ],
}

const CORRELATION_TABLE_TITLE_KEYS: Record<string, string> = {
    'cross-change-incident-detail': 'businessIntelligence.correlation.tables.changeIncidentDetail',
    'cross-aging-problems': 'businessIntelligence.correlation.tables.agingProblems',
    'cross-request-surge': 'businessIntelligence.correlation.tables.requestSurge',
}

const CORRELATION_TABLE_COLUMN_KEYS: Record<string, string> = {
    '变更编号': 'businessIntelligence.correlation.columns.changeId',
    '变更标题': 'businessIntelligence.correlation.columns.changeTitle',
    '完成时间': 'businessIntelligence.correlation.columns.completedAt',
    '48h内P1/P2事件': 'businessIntelligence.correlation.columns.48hP1p2',
    '风险等级': 'businessIntelligence.correlation.columns.riskLevel',
    '问题编号': 'businessIntelligence.correlation.columns.problemId',
    '根因类别': 'businessIntelligence.correlation.columns.rootCause',
    '老化天数': 'businessIntelligence.correlation.columns.agingDays',
    '关联事件数': 'businessIntelligence.correlation.columns.relatedIncidents',
    '优先级': 'businessIntelligence.correlation.columns.priority',
    '状态': 'businessIntelligence.correlation.columns.status',
    '请求类别': 'businessIntelligence.correlation.columns.requestCategory',
    '本周请求数': 'businessIntelligence.correlation.columns.thisWeek',
    '上周请求数': 'businessIntelligence.correlation.columns.lastWeek',
    '环比增长': 'businessIntelligence.correlation.columns.wowGrowth',
    '同期事件数': 'businessIntelligence.correlation.columns.concurrentIncidents',
}

function localizeCorrelationTab(tab: TabContent, t: TranslateFn): TabContent {
    return {
        ...tab,
        cards: tab.cards.map(card => {
            const key = CORRELATION_CARD_LABEL_KEYS[card.id]
            return key ? { ...card, label: t(key) } : card
        }),
        charts: tab.charts.map(chart => {
            const titleKey = CORRELATION_CHART_TITLE_KEYS[chart.id]
            const seriesKeys = CORRELATION_CHART_SERIES_KEYS[chart.id]
            return {
                ...chart,
                title: titleKey ? t(titleKey) : chart.title,
                config: {
                    ...chart.config,
                    series: seriesKeys ? seriesKeys.map(key => t(key)) : chart.config?.series,
                    xAxisLabel: chart.config?.xAxisLabel ? localizeAxis(chart.config.xAxisLabel, 'correlation', t) : chart.config?.xAxisLabel,
                    yAxisLabel: chart.config?.yAxisLabel ? localizeAxis(chart.config.yAxisLabel, 'correlation', t) : chart.config?.yAxisLabel,
                },
            }
        }),
        tables: tab.tables.map(table => {
            const titleKey = CORRELATION_TABLE_TITLE_KEYS[table.id]
            return {
                ...table,
                title: titleKey ? t(titleKey) : table.title,
                columns: table.columns.map(col => {
                    const key = CORRELATION_TABLE_COLUMN_KEYS[col]
                    return key ? t(key) : col
                }),
            }
        }),
    }
}

// ── Shared axis label localization ────────────────────────────────

const AXIS_LABEL_KEYS: Record<string, Record<string, string>> = {
    '时间': { changes: 'axisTime', requests: 'axisTime', problems: 'axisTime', sla: 'axisTime', correlation: 'axisTime' },
    '数量': { changes: 'axisCount', requests: 'axisCount', problems: 'axisCount', correlation: 'axisCount' },
    '变更类别': { changes: 'axisCategory' },
    '变更类型': { changes: 'axisType' },
    '部门': { requests: 'axisDept' },
    '请求数': { requests: 'axisRequests' },
    '类别': { requests: 'axisCategory' },
    '根因类别': { problems: 'axisCategory', correlation: 'axisCategory' },
    '故障数': { problems: 'axisFailures' },
    '优先级': { sla: 'axisPriority' },
    '达成率(%)': { sla: 'axisRate' },
    '时段': { correlation: 'axisHour' },
    '星期': { correlation: 'axisWeekday' },
    '累计关联事件数': { correlation: 'axisIncidents' },
    '平均积压天数': { correlation: 'axisAging' },
}

function localizeAxis(label: string, ns: string, t: TranslateFn): string {
    const mapping = AXIS_LABEL_KEYS[label]
    if (!mapping || !mapping[ns]) return label
    return t(`businessIntelligence.${ns}.charts.${mapping[ns]}`)
}

// ── Generic tab dispatcher ────────────────────────────────────────

const TAB_LABEL_KEYS: Record<string, string> = {
    'executive-summary': 'businessIntelligence.tabs.executiveSummary',
    'sla-analysis': 'businessIntelligence.tabs.slaAnalysis',
    'incident-analysis': 'businessIntelligence.tabs.eventAnalysis',
    'event-analysis': 'businessIntelligence.tabs.eventAnalysis',
    'change-analysis': 'businessIntelligence.tabs.changeAnalysis',
    'request-analysis': 'businessIntelligence.tabs.requestAnalysis',
    'problem-analysis': 'businessIntelligence.tabs.problemAnalysis',
    'cross-process': 'businessIntelligence.tabs.crossProcessCorrelation',
    'cross-process-correlation': 'businessIntelligence.tabs.crossProcessCorrelation',
    'cross-process-analysis': 'businessIntelligence.tabs.crossProcessCorrelation',
    'workforce': 'businessIntelligence.tabs.workforce',
    'personnel-efficiency': 'businessIntelligence.tabs.workforce',
}

const TAB_DESCRIPTION_KEYS: Record<string, string> = {
    'executive-summary': 'businessIntelligence.tabDescriptions.executiveSummary',
    'sla-analysis': 'businessIntelligence.tabDescriptions.slaAnalysis',
    'incident-analysis': 'businessIntelligence.tabDescriptions.eventAnalysis',
    'event-analysis': 'businessIntelligence.tabDescriptions.eventAnalysis',
    'change-analysis': 'businessIntelligence.tabDescriptions.changeAnalysis',
    'request-analysis': 'businessIntelligence.tabDescriptions.requestAnalysis',
    'problem-analysis': 'businessIntelligence.tabDescriptions.problemAnalysis',
    'cross-process': 'businessIntelligence.tabDescriptions.crossProcessCorrelation',
    'cross-process-correlation': 'businessIntelligence.tabDescriptions.crossProcessCorrelation',
    'cross-process-analysis': 'businessIntelligence.tabDescriptions.crossProcessCorrelation',
    'workforce': 'businessIntelligence.tabDescriptions.workforce',
    'personnel-efficiency': 'businessIntelligence.tabDescriptions.workforce',
}

function localizeTab(tab: TabContent, t: TranslateFn): TabContent {
    let localized: TabContent = tab
    if (isIncidentAnalysisTab(tab)) localized = localizeIncidentTab(tab, t)
    else if (isWorkforceTab(tab)) localized = localizeWorkforceTab(tab, t)
    else if (isExecutiveSummaryTab(tab)) localized = localizeExecutiveTab(tab, t)
    else if (CHANGES_TAB_IDS.has(tab.id)) localized = localizeChangesTab(tab, t)
    else if (REQUESTS_TAB_IDS.has(tab.id)) localized = localizeRequestsTab(tab, t)
    else if (PROBLEMS_TAB_IDS.has(tab.id)) localized = localizeProblemsTab(tab, t)
    else if (SLA_TAB_IDS.has(tab.id)) localized = localizeSlaTab(tab, t)
    else if (CORRELATION_TAB_IDS.has(tab.id)) localized = localizeCorrelationTab(tab, t)

    const labelKey = TAB_LABEL_KEYS[tab.id]
    const descKey = TAB_DESCRIPTION_KEYS[tab.id]
    return {
        ...localized,
        label: labelKey ? t(labelKey) : localized.label,
        description: descKey ? t(descKey) : localized.description,
    }
}

// Predefined period options
type PeriodPreset = 'last7days' | 'last30days' | 'last90days' | 'thisMonth' | 'lastMonth' | 'thisQuarter' | 'custom'

interface ReportingPeriod {
    preset: PeriodPreset
    startDate?: string
    endDate?: string
}

function formatLocalDate(date: Date): string {
    const year = date.getFullYear()
    const month = String(date.getMonth() + 1).padStart(2, '0')
    const day = String(date.getDate()).padStart(2, '0')
    return `${year}-${month}-${day}`
}

// Get default reporting period (last 30 days)
function getDefaultReportingPeriod(): ReportingPeriod {
    const today = new Date()
    const startDate = new Date(today.getTime() - 30 * 24 * 60 * 60 * 1000)
    return {
        preset: 'last30days',
        startDate: formatLocalDate(startDate),
        endDate: formatLocalDate(today),
    }
}

function ReportingPeriodSelector({
    value,
    onChange,
    disabled,
}: {
    value: ReportingPeriod
    onChange: (value: ReportingPeriod) => void
    disabled?: boolean
}) {
    const { t } = useTranslation()
    const presetLabels: Record<PeriodPreset, string> = {
        'last7days': t('businessIntelligence.reportingPeriods.last7days'),
        'last30days': t('businessIntelligence.reportingPeriods.last30days'),
        'last90days': t('businessIntelligence.reportingPeriods.last90days'),
        'thisMonth': t('businessIntelligence.reportingPeriods.thisMonth'),
        'lastMonth': t('businessIntelligence.reportingPeriods.lastMonth'),
        'thisQuarter': t('businessIntelligence.reportingPeriods.thisQuarter'),
        'custom': t('businessIntelligence.reportingPeriods.custom'),
    }

    // Year / month / day parts for start and end dates
    const [startParts, setStartParts] = useState({ y: '', m: '', d: '' })
    const [endParts, setEndParts] = useState({ y: '', m: '', d: '' })

    // Reset parts when switching away from custom
    useEffect(() => {
        if (value.preset !== 'custom') {
            setStartParts({ y: '', m: '', d: '' })
            setEndParts({ y: '', m: '', d: '' })
        }
    }, [value.preset])

    const today = new Date()
    const currentYear = today.getFullYear()
    const years = Array.from({ length: 5 }, (_, i) => String(currentYear - 4 + i))
    const months = Array.from({ length: 12 }, (_, i) => String(i + 1))

    const daysInMonth = (y: string, m: string) => {
        if (!y || !m) return 31
        return new Date(Number(y), Number(m), 0).getDate()
    }
    const days = (y: string, m: string) => Array.from({ length: daysInMonth(y, m) }, (_, i) => String(i + 1))

    const toDateString = (y: string, m: string, d: string) => {
        if (!y || !m || !d) return ''
        return `${y}-${m.padStart(2, '0')}-${d.padStart(2, '0')}`
    }

    const tryCommit = (start: string, end: string) => {
        if (start && end) {
            onChange({ ...value, startDate: start, endDate: end })
        }
    }

    const handleStartPartChange = (part: 'y' | 'm' | 'd', val: string) => {
        const next = { ...startParts, [part]: val }
        setStartParts(next)
        const ds = toDateString(next.y, next.m, next.d)
        if (ds) tryCommit(ds, toDateString(endParts.y, endParts.m, endParts.d) || value.endDate || '')
    }

    const handleEndPartChange = (part: 'y' | 'm' | 'd', val: string) => {
        const next = { ...endParts, [part]: val }
        setEndParts(next)
        const de = toDateString(next.y, next.m, next.d)
        if (de) tryCommit(toDateString(startParts.y, startParts.m, startParts.d) || value.startDate || '', de)
    }

    const handlePresetChange = (preset: PeriodPreset) => {
        const now = new Date()
        let startDate: Date | undefined
        let endDate: Date | undefined

        switch (preset) {
            case 'last7days':
                startDate = new Date(now.getTime() - 7 * 24 * 60 * 60 * 1000)
                endDate = now
                break
            case 'last30days':
                startDate = new Date(now.getTime() - 30 * 24 * 60 * 60 * 1000)
                endDate = now
                break
            case 'last90days':
                startDate = new Date(now.getTime() - 90 * 24 * 60 * 60 * 1000)
                endDate = now
                break
            case 'thisMonth':
                startDate = new Date(now.getFullYear(), now.getMonth(), 1)
                endDate = new Date(now.getFullYear(), now.getMonth() + 1, 0)
                break
            case 'lastMonth':
                startDate = new Date(now.getFullYear(), now.getMonth() - 1, 1)
                endDate = new Date(now.getFullYear(), now.getMonth(), 0)
                break
            case 'thisQuarter': {
                const qm = Math.floor(now.getMonth() / 3) * 3
                startDate = new Date(now.getFullYear(), qm, 1)
                endDate = new Date(now.getFullYear(), qm + 3, 0)
                break
            }
            case 'custom':
                startDate = undefined
                endDate = undefined
                break
            default:
                break
        }

        onChange({
            preset,
            startDate: startDate ? formatLocalDate(startDate) : undefined,
            endDate: endDate ? formatLocalDate(endDate) : undefined,
        })
    }

    const selectClass = 'reporting-period-date-select'

    return (
        <div className="reporting-period-selector">
            <div className="business-intelligence-period-field">
                <span className="reporting-period-selector-label">{t('businessIntelligence.reportingPeriod')}:</span>
                <select
                    className="filter-select reporting-period-select"
                    value={value.preset}
                    onChange={(e) => handlePresetChange(e.target.value as PeriodPreset)}
                    disabled={disabled}
                >
                    {Object.entries(presetLabels).map(([key, label]) => (
                        <option key={key} value={key}>{label}</option>
                    ))}
                </select>
            </div>
            {value.preset === 'custom' && (
                <div className="reporting-period-custom-dates">
                    <select className={selectClass} value={startParts.y} onChange={(e) => handleStartPartChange('y', e.target.value)} disabled={disabled}>
                        <option value="">{t('businessIntelligence.year')}</option>
                        {years.map(y => <option key={y} value={y}>{y}</option>)}
                    </select>
                    <select className={selectClass} value={startParts.m} onChange={(e) => handleStartPartChange('m', e.target.value)} disabled={disabled}>
                        <option value="">{t('businessIntelligence.month')}</option>
                        {months.map(m => <option key={m} value={m}>{m}</option>)}
                    </select>
                    <select className={selectClass} value={startParts.d} onChange={(e) => handleStartPartChange('d', e.target.value)} disabled={disabled}>
                        <option value="">{t('businessIntelligence.day')}</option>
                        {days(startParts.y, startParts.m).map(d => <option key={d} value={d}>{d}</option>)}
                    </select>
                    <span className="reporting-period-date-separator">{t('businessIntelligence.dateRangeSeparator')}</span>
                    <select className={selectClass} value={endParts.y} onChange={(e) => handleEndPartChange('y', e.target.value)} disabled={disabled}>
                        <option value="">{t('businessIntelligence.year')}</option>
                        {years.map(y => <option key={y} value={y}>{y}</option>)}
                    </select>
                    <select className={selectClass} value={endParts.m} onChange={(e) => handleEndPartChange('m', e.target.value)} disabled={disabled}>
                        <option value="">{t('businessIntelligence.month')}</option>
                        {months.map(m => <option key={m} value={m}>{m}</option>)}
                    </select>
                    <select className={selectClass} value={endParts.d} onChange={(e) => handleEndPartChange('d', e.target.value)} disabled={disabled}>
                        <option value="">{t('businessIntelligence.day')}</option>
                        {days(endParts.y, endParts.m).map(d => <option key={d} value={d}>{d}</option>)}
                    </select>
                </div>
            )}
        </div>
    )
}

function getBusinessIntelligenceTabLabel(tab: TabMeta, t: (key: string) => string): string {
    const keyById = TAB_LABEL_KEYS[tab.id]
    if (keyById) {
        return t(keyById)
    }

    const normalizedLabel = tab.label.trim().toLowerCase()
    const keyByLabel = BUSINESS_INTELLIGENCE_TAB_LABEL_FALLBACK_KEYS[normalizedLabel] ?? BUSINESS_INTELLIGENCE_TAB_LABEL_FALLBACK_KEYS[tab.label.trim()]
    if (keyByLabel) {
        return t(keyByLabel)
    }

    return tab.label
}

function getScoreTone(score: number): 'success' | 'warning' | 'danger' {
    if (score >= 80) return 'success'
    if (score >= 50) return 'warning'
    return 'danger'
}

function ExecutiveSummaryPanel({
    summary,
    cards,
    t: _t,
}: {
    summary: ExecutiveSummary
    cards: MetricCard[]
    t: (key: string, options?: Record<string, unknown>) => string
}) {
    const heroScore = parseFloat(summary.hero.score) || 0
    const heroTone = getScoreTone(heroScore)

    // ── i18n adapter: map ALL backend labels to i18n keys ──
    const ns = 'businessIntelligence.executiveSummary'
    const localizeGrade = (grade: string) => {
        const val = _t(`${ns}.grades.${grade}`)
        return val.includes('.') ? grade : val
    }
    const localizeProcessLabel = (id: string) => {
        const val = _t(`${ns}.processes.${id}`)
        return val.includes('.') ? id : val
    }
    const localizeRiskTitle = (id: string, fallback: string) => {
        const key = `${ns}.risks.${id}`
        const val = _t(key)
        // i18next returns the lookup key when translation is missing
        return val === key ? fallback : val
    }
    const localizeRiskImpact = (id: string, fallback: string) => {
        const key = `${ns}.riskImpact.${id}`
        const val = _t(key)
        // i18next returns the lookup key when translation is missing
        return val === key ? fallback : val
    }
    const localizePriority = (priority: string) => {
        const p = priority.toLowerCase()
        if (p === 'critical') return _t(`${ns}.critical`)
        if (p === 'warning') return _t(`${ns}.warning`)
        return _t(`${ns}.attention`)
    }

    // Hero summary: use grade-based i18n template
    const heroSummaryRaw = _t(`${ns}.heroSummary.${summary.hero.grade}`)
    const heroSummary = heroSummaryRaw.includes('.heroSummary.') ? _t(`${ns}.heroSummary.Risk`) : heroSummaryRaw

    // Hero changeHint: parse backend Chinese text and reconstruct with i18n template
    const heroChangeHint = (() => {
        const hint = summary.hero.changeHint
        if (hint.includes('准备中')) return _t(`${ns}.changeDirection.noData`)
        const downMatch = hint.match(/下降\s*([\d.]+)/)
        const upMatch = hint.match(/(?:提升|上升)\s*([\d.]+)/)
        if (downMatch) return _t(`${ns}.changeDirection.down`, { delta: downMatch[1] })
        if (upMatch) return _t(`${ns}.changeDirection.up`, { delta: upMatch[1] })
        return _t(`${ns}.changeDirection.stable`)
    })()

    // Hero periodLabel: replace Chinese "至" with localized separator; hide when empty
    const heroPeriodLabel = summary.hero.periodLabel
        ? summary.hero.periodLabel.replace(/\s*至\s*/, ' – ')
        : ''

    // Process summary: reconstruct from card data using i18n templates
    const cardById = Object.fromEntries(cards.map(c => [c.id, c.value]))
    const localizeProcessSummary = (ph: typeof summary.processHealths[0]) => {
        const t = (key: string, opts?: Record<string, string>) => {
            let v = _t(`${ns}.processSummary.${key}`, opts ? Object.fromEntries(Object.entries(opts).map(([k, val]) => [k, val])) as unknown as Record<string, unknown> : undefined)
            return v.includes('.processSummary.') ? '' : v
        }
        switch (ph.id) {
            case 'incident':
                return t('incident', { sla: cardById['incident-sla-rate'] || '-', mttr: cardById['incident-mttr'] || '-' })
            case 'change':
                return t('change', { successRate: cardById['change-success-rate'] || '-', incidentRate: cardById['change-incident-rate'] || '-' })
            case 'request':
                return t('request', { sla: '-', csat: cardById['request-csat'] || '-' })
            case 'problem':
                return t('problem', { closureRate: cardById['problem-closure-rate'] || '-', backlog: '-' })
            default:
                return ''
        }
    }


    const renderOverviewTrendChart = () => {
        const points = summary.trend.points
        if (!points || points.length === 0) return null

        const maxScore = Math.max(...points.map(p => p.score), 1)
        const maxSignal = Math.max(...points.map(p => p.signal), 1)

        const vbWidth = 1000
        const vbHeight = 320
        const padding = { top: 24, right: 55, bottom: 56, left: 55 }
        const innerWidth = vbWidth - padding.left - padding.right
        const innerHeight = vbHeight - padding.top - padding.bottom

        const getScoreY = (v: number) => padding.top + innerHeight - (v / maxScore) * innerHeight
        const getSignalY = (v: number) => padding.top + innerHeight - (v / maxSignal) * innerHeight
        const getX = (i: number) => {
            if (points.length <= 1) return padding.left + innerWidth / 2
            return padding.left + (i / (points.length - 1)) * innerWidth
        }

        const scoreColor = '#5b8db8'
        const signalColor = '#f59e0b'

        const yLabels = [0, 0.25, 0.5, 0.75, 1].map(r => ({
            score: Math.round(maxScore * r),
            signal: Math.round(maxSignal * r),
            y: padding.top + innerHeight - r * innerHeight
        }))

        const scoreLine = points
            .map((p, i) => `${getX(i)},${getScoreY(p.score)}`)
            .join(' ')
        const signalLine = points
            .map((p, i) => `${getX(i)},${getSignalY(p.signal)}`)
            .join(' ')

        return (
            <div style={{ width: '100%' }}>
                <svg
                    viewBox={`0 0 ${vbWidth} ${vbHeight}`}
                    preserveAspectRatio="none"
                    style={{ width: '100%', height: '320px', display: 'block' }}
                >
                    {/* Grid lines */}
                    {yLabels.map((l, idx) => (
                        <g key={idx}>
                            <line x1={padding.left} y1={l.y} x2={vbWidth - padding.right} y2={l.y}
                                stroke="var(--color-border)" strokeDasharray="4 4" />
                            <text x={padding.left - 8} y={l.y + 4} fill={scoreColor} fontSize="11" textAnchor="end">
                                {l.score}
                            </text>
                            <text x={vbWidth - padding.right + 8} y={l.y + 4} fill={signalColor} fontSize="11">
                                {l.signal}
                            </text>
                        </g>
                    ))}

                    {/* Score line */}
                    <polyline points={scoreLine} fill="none" stroke={scoreColor}
                        strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" />
                    {points.map((p, i) => (
                        <circle key={`s${i}`} cx={getX(i)} cy={getScoreY(p.score)} r="3.5" fill={scoreColor} />
                    ))}

                    {/* Signal line */}
                    <polyline points={signalLine} fill="none" stroke={signalColor}
                        strokeWidth="2" strokeDasharray="6 3" strokeLinecap="round" strokeLinejoin="round" />
                    {points.map((p, i) => (
                        <circle key={`g${i}`} cx={getX(i)} cy={getSignalY(p.signal)} r="3" fill={signalColor} />
                    ))}

                    {/* X-axis labels */}
                    {points.map((p, i) => {
                        if (!shouldRenderAxisLabel(i, points.length, 12)) return null
                        const lines = splitAxisLabel(p.label, p.label.length > 7)
                        return (
                            <text key={i} x={getX(i)} y={vbHeight - padding.bottom + 16}
                                fill="var(--color-text-secondary)" fontSize="11" textAnchor="middle">
                                {lines.map((line, li) => (
                                    <tspan key={li} x={getX(i)} dy={li === 0 ? 0 : 13}>{line}</tspan>
                                ))}
                            </text>
                        )
                    })}
                </svg>

                {/* Legend */}
                <div className="combo-chart-legend">
                    <span className="combo-chart-legend-item">
                        <span className="combo-chart-legend-line" style={{ background: scoreColor }} />
                        {_t('businessIntelligence.executiveSummary.trendScore')}
                    </span>
                    <span className="combo-chart-legend-item">
                        <span className="combo-chart-legend-line" style={{ background: signalColor, borderTop: '2px dashed ' + signalColor }} />
                        {_t('businessIntelligence.executiveSummary.trendSignal')}
                    </span>
                </div>
            </div>
        )
    }

    // ── Risk priority tone ──
    const getRiskTone = (priority: string): 'critical' | 'warning' | 'attention' => {
        const p = priority.toLowerCase()
        if (p === 'critical') return 'critical'
        if (p === 'warning') return 'warning'
        return 'attention'
    }

    const riskCols = [
        { key: 'priority', label: _t('businessIntelligence.executiveSummary.priority'), className: 'executive-risk-col-priority' },
        { key: 'title', label: _t('businessIntelligence.executiveSummary.riskTitle'), className: '' },
        { key: 'impact', label: _t('businessIntelligence.executiveSummary.impact'), className: '' },
        { key: 'process', label: _t('businessIntelligence.executiveSummary.process'), className: 'executive-risk-col-process' },
        { key: 'value', label: _t('businessIntelligence.executiveSummary.currentValue'), className: 'executive-risk-col-value' },
    ]

    return (
        <div className="executive-overview">
            <div className="executive-hero-card">
                <div className="executive-hero-score-row">
                    <span className={`executive-hero-score tone-${heroTone}`}>{summary.hero.score}</span>
                    <span className={`executive-hero-grade tone-${heroTone}`}>{localizeGrade(summary.hero.grade)}</span>
                </div>
                <p className="executive-hero-summary">{heroSummary}</p>
                <div className="executive-hero-meta">
                    <span className={`executive-hero-change tone-${heroTone}`}>{heroChangeHint}</span>
                    {heroPeriodLabel && <span>{heroPeriodLabel}</span>}
                </div>
            </div>

            {cards.length > 0 && (
                <section className="business-intelligence-section-stack">
                    <h2 className="business-intelligence-section-title">
                        {_t('businessIntelligence.executiveSummary.coreKpi')}
                    </h2>
                    <div className="business-intelligence-stat-grid ui-metric-grid"
                        style={{ gridTemplateColumns: `repeat(${cards.length % 3 === 0 ? 3 : 2}, minmax(0, 1fr))` }}>
                        {cards.map(card => {
                            const tone = getMetricTone(card.tone)
                            return (
                                <StatCard
                                    key={card.id}
                                    label={card.label}
                                    value={card.value}
                                    tone={tone}
                                    icon={tone === 'neutral' ? null : <StatusIcon tone={tone} />}
                                />
                            )
                        })}
                    </div>
                </section>
            )}

            {summary.processHealths.length > 0 && (
                <section className="business-intelligence-section-stack">
                    <h2 className="business-intelligence-section-title">
                        {_t('businessIntelligence.executiveSummary.processHealth')}
                    </h2>
                    <div className="business-intelligence-stat-grid ui-metric-grid"
                        style={{ gridTemplateColumns: `repeat(${summary.processHealths.length}, minmax(0, 1fr))` }}>
                        {summary.processHealths.map(ph => {
                            const tone = getMetricTone(ph.tone)
                            return (
                                <StatCard
                                    key={ph.id}
                                    label={localizeProcessLabel(ph.id)}
                                    value={ph.score}
                                    meta={localizeProcessSummary(ph)}
                                    tone={tone}
                                    icon={tone === 'neutral' ? null : <StatusIcon tone={tone} />}
                                />
                            )
                        })}
                    </div>
                </section>
            )}

            {summary.trend.points.length > 0 && (
                <SectionCard title={_t('businessIntelligence.executiveSummary.monthlyTrend')}>
                    <div className="business-intelligence-chart-surface business-intelligence-chart-surface-line">
                        {renderOverviewTrendChart()}
                    </div>
                </SectionCard>
            )}

            {summary.riskSummary.topRisks.length > 0 && (
                <SectionCard title={_t('businessIntelligence.executiveSummary.riskSummary')}>
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--spacing-4)' }}>
                        <div className="executive-risk-badges">
                            {summary.riskSummary.critical > 0 && (
                                <span className="executive-risk-badge tone-critical">
                                    {_t('businessIntelligence.executiveSummary.critical')} {summary.riskSummary.critical}
                                </span>
                            )}
                            {summary.riskSummary.warning > 0 && (
                                <span className="executive-risk-badge tone-warning">
                                    {_t('businessIntelligence.executiveSummary.warning')} {summary.riskSummary.warning}
                                </span>
                            )}
                            {summary.riskSummary.attention > 0 && (
                                <span className="executive-risk-badge tone-attention">
                                    {_t('businessIntelligence.executiveSummary.attention')} {summary.riskSummary.attention}
                                </span>
                            )}
                        </div>
                        <div className="business-intelligence-table-shell">
                            <table className="business-intelligence-table">
                                <thead>
                                    <tr>
                                        {riskCols.map(col => (
                                            <th key={col.key} className={col.className}>{col.label}</th>
                                        ))}
                                    </tr>
                                </thead>
                                <tbody>
                                    {summary.riskSummary.topRisks.map(risk => (
                                        <tr key={risk.id}>
                                            <td className="executive-risk-col-priority">
                                                <span className={`executive-risk-badge tone-${getRiskTone(risk.priority)}`}>
                                                    {localizePriority(risk.priority)}
                                                </span>
                                            </td>
                                            <td style={{ fontWeight: 600 }}>{localizeRiskTitle(risk.id, risk.title)}</td>
                                            <td>{localizeRiskImpact(risk.id, risk.impact)}</td>
                                            <td className="executive-risk-col-process">{localizeProcessLabel(risk.process)}</td>
                                            <td className="executive-risk-col-value" style={{ textAlign: 'right', fontVariantNumeric: 'tabular-nums' }}>
                                                {risk.value}
                                            </td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        </div>
                    </div>
                </SectionCard>
            )}
        </div>
    )
}

function getMetricTone(tone: string): 'neutral' | 'success' | 'warning' | 'danger' {
    switch (tone) {
        case 'success':
            return 'success'
        case 'warning':
            return 'warning'
        case 'danger':
            return 'danger'
        default:
            return 'neutral'
    }
}

function isIncidentAnalysisTab(tab: TabContent): boolean {
    return INCIDENT_ANALYSIS_TAB_IDS.has(tab.id)
}

function getTableColumnClassName(column: string): string {
    const normalized = column.trim().toLowerCase()

    if (normalized.includes('title') || normalized.includes('标题')) {
        return 'business-intelligence-col-order-title'
    }

    if (normalized.includes('编号') || normalized.includes('id') || normalized.includes('单号')) {
        return 'business-intelligence-col-order-number'
    }

    if (normalized.includes('priority') || normalized.includes('优先级')) {
        return 'business-intelligence-col-priority'
    }

    if (normalized.includes('category') || normalized.includes('类型')) {
        return 'business-intelligence-col-category'
    }

    if (normalized.includes('resolver') || normalized.includes('处理人') || normalized.includes('assignee')) {
        return 'business-intelligence-col-resolver'
    }

    if (normalized.includes('duration') || normalized.includes('时长') || normalized.includes('mttr')) {
        return 'business-intelligence-col-duration'
    }

    if (normalized.includes('sla')) {
        return 'business-intelligence-col-violation-type'
    }

    return ''
}

function getSlaStatus(cell: string, t: TranslateFn): { tone: StatusTone; label: string } | null {
    const normalized = cell.trim().toLowerCase()

    if (!normalized) {
        return null
    }

    if (
        normalized === '×' ||
        normalized === 'x' ||
        normalized === '✕' ||
        normalized === '✖' ||
        normalized.includes('violat') ||
        normalized.includes('breach') ||
        normalized.includes('超时') ||
        normalized.includes('未达成')
    ) {
        return { tone: 'danger', label: t('businessIntelligence.slaStatus.breached') }
    }

    if (
        normalized === '√' ||
        normalized === '✓' ||
        normalized === '✔' ||
        normalized.includes('met') ||
        normalized.includes('达成') ||
        normalized.includes('满足') ||
        normalized.includes('通过')
    ) {
        return { tone: 'success', label: t('businessIntelligence.slaStatus.met') }
    }

    if (normalized.includes('risk') || normalized.includes('warning') || normalized.includes('临界') || normalized.includes('预警')) {
        return { tone: 'warning', label: t('businessIntelligence.slaStatus.atRisk') }
    }

    if (normalized === '-' || normalized === '--' || normalized === 'n/a' || normalized.includes('unknown') || normalized.includes('未知')) {
        return { tone: 'neutral', label: t('businessIntelligence.slaStatus.unknown') }
    }

    return null
}

function shouldRenderAxisLabel(index: number, total: number, maxVisible: number): boolean {
    if (total <= maxVisible) {
        return true
    }

    const interval = Math.ceil(total / maxVisible)
    return index === total - 1 || index % interval === 0
}

function splitAxisLabel(label: string, wrap: boolean): string[] {
    if (!wrap) {
        return [label]
    }

    const dashIdx = label.indexOf('-')
    if (dashIdx > 0) {
        return [label.slice(0, dashIdx), label.slice(dashIdx)]
    }

    return [label]
}

function IncidentBubbleChart({ chart, colors, t: _t }: { chart: ChartSection; colors: string[]; t: (key: string, options?: Record<string, unknown>) => string }) {
    const [hovered, setHovered] = useState(-1)
    const xAxisLabel = chart.config?.xAxisLabel || ''
    const yAxisLabel = chart.config?.yAxisLabel || ''
    const seriesNames = chart.config?.series || []
    const fallbackLabel = _t('businessIntelligence.incidents.fallbacks.unlabeled')

    const points = chart.items.map(item => {
        const parts = item.label.split('|')
        return {
            ci: parts[0] || fallbackLabel,
            category: parts[1] || fallbackLabel,
            avgAging: parseFloat(parts[2]) || 0,
            openCount: parseFloat(parts[3]) || 0,
            totalIncidents: parseFloat(parts[4]) || 0,
        }
    }).filter(p => p.avgAging > 0 || p.openCount > 0 || p.totalIncidents > 0)

    const uniqueCategories = [...new Set(points.map(p => p.category))]
    const colorMap = new Map(uniqueCategories.map((cat, idx) => [cat, colors[idx % colors.length]]))
    const maxX = Math.max(...points.map(p => p.totalIncidents), 1)
    const maxY = Math.max(...points.map(p => p.avgAging), 1)
    const maxOpen = Math.max(...points.map(p => p.openCount), 1)
    const vbWidth = 900
    const vbHeight = 400
    const pad = { top: 20, right: 30, bottom: 60, left: 70 }
    const iw = vbWidth - pad.left - pad.right
    const ih = vbHeight - pad.top - pad.bottom
    const getX = (v: number) => pad.left + (v / maxX) * iw
    const getY = (v: number) => pad.top + ih - (v / maxY) * ih
    const getR = (v: number) => Math.max(8, Math.min(40, 8 + (v / Math.max(maxOpen, 1)) * 32))

    const sorted = [...points].sort((a, b) => b.totalIncidents - a.totalIncidents)
    const bubbles = sorted.map((p, idx) => ({
        p, idx, color: colorMap.get(p.category) || colors[0],
        cx: getX(p.totalIncidents), cy: getY(p.avgAging), r: getR(p.openCount),
    }))

    const drawBubble = (b: typeof bubbles[0], isH: boolean) => {
        const { p, idx, color, cx, cy, r } = b
        const ciParts = p.ci.split('-')
        return (
            <g key={idx} onMouseEnter={() => setHovered(idx)} onMouseLeave={() => setHovered(-1)}
                style={{ cursor: 'pointer' }}>
                <circle cx={cx} cy={cy} r={r} fill={color}
                    opacity={isH ? 0.9 : 0.45}
                    stroke={isH ? 'var(--color-text-primary)' : 'none'}
                    strokeWidth={isH ? 2 : 0}
                    style={{ transition: 'opacity 0.15s' }} />
                {isH ? (() => {
                    const lines = [...ciParts, `${Math.round(p.openCount)} ${_t('businessIntelligence.incidents.units.openProblems')}`]
                    const lineH = 12
                    const padX = 8
                    const padY = 4
                    const boxW = Math.max(...lines.map(l => l.length)) * 5.5 + padX * 2
                    const boxH = lines.length * lineH + padY * 2
                    const bx = cx - boxW / 2
                    const by = cy - boxH / 2 - 4
                    return (
                        <g>
                            <rect x={bx} y={by} width={boxW} height={boxH} rx="6"
                                fill="rgba(15,23,42,0.85)" />
                            <text x={cx} y={by + padY + 9} fill="#fff"
                                fontSize="9" textAnchor="middle" fontWeight="700">
                                {lines.map((line, i) => (
                                    <tspan key={i} x={cx} dy={i === 0 ? 0 : lineH}>{line}</tspan>
                                ))}
                            </text>
                        </g>
                    )
                })() : (
                    <text x={cx} y={cy} fill="var(--color-text-primary)"
                        fontSize="9" textAnchor="middle" fontWeight="600">
                        {ciParts.map((part, i) => (
                            <tspan key={i} x={cx} dy={i === 0 ? -((ciParts.length - 1) * 5) : 11}>{part}</tspan>
                        ))}
                    </text>
                )}
            </g>
        )
    }

    return (
        <div style={{ width: '100%' }}>
            <svg viewBox={`0 0 ${vbWidth} ${vbHeight}`} preserveAspectRatio="xMidYMid meet"
                style={{ width: '100%', height: '400px', display: 'block' }}>
                {[0, 0.25, 0.5, 0.75, 1].map(ratio => {
                    const val = Math.round(maxY * ratio)
                    const y = getY(val)
                    return (
                        <g key={`y${ratio}`}>
                            <line x1={pad.left} y1={y} x2={vbWidth - pad.right} y2={y}
                                stroke="var(--color-border)" strokeDasharray="4 4" />
                            <text x={pad.left - 10} y={y + 4}
                                fill="var(--color-text-secondary)" fontSize="12" textAnchor="end">{val}</text>
                        </g>
                    )
                })}
                {[0, 0.25, 0.5, 0.75, 1].map(ratio => {
                    const val = Math.round(maxX * ratio)
                    return (
                        <text key={`x${ratio}`} x={getX(val)} y={vbHeight - 30}
                            fill="var(--color-text-secondary)" fontSize="11" textAnchor="middle">
                            {val}
                        </text>
                    )
                })}
                <text x={pad.left + iw / 2} y={vbHeight - 8}
                    fill="var(--color-text-secondary)" fontSize="12" textAnchor="middle" fontWeight="500">
                    {xAxisLabel}
                </text>
                <text x={14} y={pad.top + ih / 2}
                    fill="var(--color-text-secondary)" fontSize="12" textAnchor="middle" fontWeight="500"
                    transform={`rotate(-90, 14, ${pad.top + ih / 2})`}>
                    {yAxisLabel}
                </text>
                {bubbles.filter(b => b.idx !== hovered).map(b => drawBubble(b, false))}
                {bubbles.filter(b => b.idx === hovered).map(b => drawBubble(b, true))}
            </svg>
            <div className="line-chart-legend">
                {(seriesNames.length > 0 ? seriesNames : uniqueCategories).slice(0, 6).map((cat, idx) => (
                    <span key={cat} className="line-chart-legend-item">
                        <span style={{ display: 'inline-block', width: '12px', height: '12px', borderRadius: '50%', background: colors[idx % colors.length] }} />
                        {cat}
                    </span>
                ))}
            </div>
        </div>
    )
}

function WorkforceBubbleChart({ chart, colors, t: _t }: { chart: ChartSection; colors: string[]; t: (key: string, options?: Record<string, unknown>) => string }) {
    const [hovered, setHovered] = useState(-1)
    const xAxisLabel = chart.config?.xAxisLabel || ''
    const yAxisLabel = chart.config?.yAxisLabel || ''
    const points = chart.items.map(item => {
        const parts = item.label.split('|')
        return { name: parts[0], slaRate: parseFloat(parts[1]) || 0, satisfaction: parseFloat(parts[2]) || 0, total: item.value }
    })
    const vbWidth = 900
    const vbHeight = 420
    const pad = { top: 30, right: 40, bottom: 60, left: 60 }
    const iw = vbWidth - pad.left - pad.right
    const ih = vbHeight - pad.top - pad.bottom
    const getX = (v: number) => pad.left + (v / 100) * iw
    const getY = (v: number) => pad.top + ih - (v / 5) * ih
    const getR = (v: number) => Math.max(8, Math.min(40, 8 + (v / Math.max(Math.max(...points.map(p => p.total)), 1)) * 32))
    const medSla = points.length > 0 ? points.reduce((s, p) => s + p.slaRate, 0) / points.length : 50
    const medSat = points.length > 0 ? points.reduce((s, p) => s + p.satisfaction, 0) / points.length : 2.5
    const sorted = [...points].sort((a, b) => b.total - a.total)
    const bubbles = sorted.map((p, idx) => ({
        p, idx, color: colors[idx % colors.length],
        cx: getX(p.slaRate), cy: getY(p.satisfaction), r: getR(p.total),
    }))

    const drawBubble = (b: typeof bubbles[0], isH: boolean) => {
        const { p, idx, color, cx, cy, r } = b
        return (
            <g key={idx} onMouseEnter={() => setHovered(idx)} onMouseLeave={() => setHovered(-1)}
                style={{ cursor: 'pointer' }}>
                <circle cx={cx} cy={cy} r={r} fill={color}
                    opacity={isH ? 0.9 : 0.45}
                    stroke={isH ? 'var(--color-text-primary)' : 'none'}
                    strokeWidth={isH ? 2 : 0}
                    style={{ transition: 'opacity 0.15s' }} />
                {isH ? (() => {
                    const lines = [p.name, _t('businessIntelligence.workforce.units.tickets', { count: Math.round(p.total) })]
                    const lineH = 12
                    const padX = 8
                    const padY = 4
                    const boxW = Math.max(...lines.map(l => l.length)) * 5.5 + padX * 2
                    const boxH = lines.length * lineH + padY * 2
                    const bx = cx - boxW / 2
                    const by = cy - boxH / 2 - 4
                    return (
                        <g>
                            <rect x={bx} y={by} width={boxW} height={boxH} rx="6"
                                fill="rgba(15,23,42,0.85)" />
                            <text x={cx} y={by + padY + 9} fill="#fff"
                                fontSize="9" textAnchor="middle" fontWeight="700">
                                {lines.map((line, i) => (
                                    <tspan key={i} x={cx} dy={i === 0 ? 0 : lineH}>{line}</tspan>
                                ))}
                            </text>
                        </g>
                    )
                })() : (
                    <text x={cx} y={cy} fill="var(--color-text-primary)"
                        fontSize="9" textAnchor="middle" fontWeight="600">
                        {p.name}
                    </text>
                )}
            </g>
        )
    }

    return (
        <div style={{ width: '100%' }}>
            <svg viewBox={`0 0 ${vbWidth} ${vbHeight}`} preserveAspectRatio="xMidYMid meet"
                style={{ width: '100%', height: '420px', display: 'block' }}>
                {/* Quadrant backgrounds */}
                <rect x={pad.left} y={pad.top} width={getX(medSla) - pad.left} height={getY(medSat) - pad.top}
                    fill="rgba(16,185,129,0.06)" />
                <rect x={getX(medSla)} y={pad.top} width={vbWidth - pad.right - getX(medSla)} height={getY(medSat) - pad.top}
                    fill="rgba(59,130,246,0.06)" />
                <rect x={pad.left} y={getY(medSat)} width={getX(medSla) - pad.left} height={pad.top + ih - getY(medSat)}
                    fill="rgba(245,158,11,0.06)" />
                <rect x={getX(medSla)} y={getY(medSat)} width={vbWidth - pad.right - getX(medSla)} height={pad.top + ih - getY(medSat)}
                    fill="rgba(239,68,68,0.06)" />
                {/* Quadrant labels */}
                <text x={pad.left + (getX(medSla) - pad.left) / 2} y={pad.top + 14}
                    fill="var(--color-text-secondary)" fontSize="10" textAnchor="middle" opacity={0.7}>
                    {_t('businessIntelligence.workforce.quadrantLabels.highSlaHighSat')}
                </text>
                <text x={getX(medSla) + (vbWidth - pad.right - getX(medSla)) / 2} y={pad.top + 14}
                    fill="var(--color-text-secondary)" fontSize="10" textAnchor="middle" opacity={0.7}>
                    {_t('businessIntelligence.workforce.quadrantLabels.lowSlaLowSat')}
                </text>
                {/* Median crosshairs */}
                <line x1={getX(medSla)} y1={pad.top} x2={getX(medSla)} y2={pad.top + ih}
                    stroke="var(--color-border)" strokeDasharray="4 4" />
                <line x1={pad.left} y1={getY(medSat)} x2={pad.left + iw} y2={getY(medSat)}
                    stroke="var(--color-border)" strokeDasharray="4 4" />
                {/* Y-axis grid lines and labels */}
                {[0, 1, 2, 3, 4, 5].map(val => {
                    const y = getY(val)
                    return (
                        <g key={`y${val}`}>
                            <line x1={pad.left} y1={y} x2={vbWidth - pad.right} y2={y}
                                stroke="var(--color-border)" strokeDasharray="2 2" opacity={0.4} />
                            <text x={pad.left - 10} y={y + 4}
                                fill="var(--color-text-secondary)" fontSize="12" textAnchor="end">{val}</text>
                        </g>
                    )
                })}
                {/* X-axis labels */}
                {[0, 25, 50, 75, 100].map(val => (
                    <text key={`x${val}`} x={getX(val)} y={vbHeight - 30}
                        fill="var(--color-text-secondary)" fontSize="11" textAnchor="middle">
                        {val}%
                    </text>
                ))}
                {/* Axis titles */}
                <text x={pad.left + iw / 2} y={vbHeight - 8}
                    fill="var(--color-text-secondary)" fontSize="12" textAnchor="middle" fontWeight="500">
                    {xAxisLabel}
                </text>
                <text x={14} y={pad.top + ih / 2}
                    fill="var(--color-text-secondary)" fontSize="12" textAnchor="middle" fontWeight="500"
                    transform={`rotate(-90, 14, ${pad.top + ih / 2})`}>
                    {yAxisLabel}
                </text>
                {/* Bubbles */}
                {bubbles.filter(b => b.idx !== hovered).map(b => drawBubble(b, false))}
                {bubbles.filter(b => b.idx === hovered).map(b => drawBubble(b, true))}
            </svg>
        </div>
    )
}

function GenericTabPanel({
    tab,
    t: _t,
}: {
    tab: TabContent
    t: (key: string, options?: Record<string, unknown>) => string
}) {
    const localizedTab = useMemo(() => localizeTab(tab, _t), [tab, _t])
    const maxValue = (items: ChartDatum[]) => Math.max(...items.map(item => item.value), 1)

    const renderLineChart = (chart: ChartSection, options?: { hideLegend?: boolean; percentage?: boolean; hoursMode?: boolean }) => {
        const colors = chart.config?.colors || ['#5b8db8', '#10b981']
        const seriesNames = chart.config?.series || [_t('businessIntelligence.incidents.charts.volumeSeries')]

        // Parse multi-series data from compound labels (format: "period|value1|value2|...")
        const dataPoints = chart.items.map(item => {
            const parts = item.label.split('|')
            return {
                period: parts[0] || item.label,
                values: parts.slice(1).map(v => {
                    const raw = parseFloat(v) || 0
                    return options?.hoursMode ? raw / 60 : raw
                })
            }
        })

        // Get all values for scaling, filter out zeros for better visualization
        const allValues = dataPoints.flatMap(dp => dp.values).filter(v => v > 0)
        const maxVal = Math.max(...allValues, 1)

        // Use a very wide viewBox for horizontal stretching
        const vbWidth = 1000
        const vbHeight = 360
        const padding = { top: 24, right: 30, bottom: 72, left: 60 }
        const innerWidth = vbWidth - padding.left - padding.right
        const innerHeight = vbHeight - padding.top - padding.bottom

        const getY = (value: number) => padding.top + innerHeight - (value / maxVal) * innerHeight
        const getX = (index: number) => {
            if (dataPoints.length <= 1) return padding.left + innerWidth / 2
            return padding.left + (index / (dataPoints.length - 1)) * innerWidth
        }

        // Generate Y-axis labels
        const yAxisLabels = [0, 0.25, 0.5, 0.75, 1].map(ratio => ({
            value: Math.round(maxVal * ratio),
            y: getY(maxVal * ratio)
        }))

        return (
            <div style={{ width: '100%' }}>
                <svg
                    viewBox={`0 0 ${vbWidth} ${vbHeight}`}
                    preserveAspectRatio="none"
                    style={{ width: '100%', height: '360px', display: 'block' }}
                >
                    {/* Y-axis grid lines and labels */}
                    {yAxisLabels.map((label, idx) => (
                        <g key={idx}>
                            <line
                                x1={padding.left}
                                y1={label.y}
                                x2={vbWidth - padding.right}
                                y2={label.y}
                                stroke="var(--color-border)"
                                strokeDasharray="4 4"
                            />
                            <text
                                x={padding.left - 10}
                                y={label.y + 4}
                                fill="var(--color-text-secondary)"
                                fontSize="12"
                                textAnchor="end"
                            >
                                {options?.percentage ? `${label.value}%` : label.value}
                            </text>
                        </g>
                    ))}

                    {/* Data lines for each series */}
                    {seriesNames.map((_, seriesIdx) => {
                        const points = dataPoints
                            .map((dp, idx) => {
                                const val = dp.values[seriesIdx]
                                if (val === undefined || val === 0) return null
                                return `${getX(idx)},${getY(val)}`
                            })
                            .filter(Boolean)
                            .join(' ')

                        return (
                            <g key={seriesIdx}>
                                <polyline
                                    points={points}
                                    fill="none"
                                    stroke={colors[seriesIdx] || '#5b8db8'}
                                    strokeWidth="3"
                                    strokeLinecap="round"
                                    strokeLinejoin="round"
                                />
                                {/* Data points */}
                                {dataPoints.map((dp, idx) => {
                                    const val = dp.values[seriesIdx]
                                    if (val === undefined || val === 0) return null
                                    return (
                                        <circle
                                            key={idx}
                                            cx={getX(idx)}
                                            cy={getY(val)}
                                            r="4"
                                            fill={colors[seriesIdx] || '#5b8db8'}
                                        />
                                    )
                                })}
                            </g>
                        )
                    })}

                    {/* X-axis labels with auto-wrapping */}
                    {(() => {
                        const colWidth = dataPoints.length > 1
                            ? innerWidth / (dataPoints.length - 1)
                            : innerWidth
                        const maxCharsPerLine = Math.max(4, Math.floor(colWidth / 7))
                        const needsWrap = dataPoints.some(dp => dp.period.length > maxCharsPerLine)
                        const splitPeriod = (label: string): string[] => {
                            return splitAxisLabel(label, needsWrap)
                        }
                        return dataPoints.map((dp, idx) => {
                            if (!shouldRenderAxisLabel(idx, dataPoints.length, 12)) {
                                return null
                            }
                            const lines = splitPeriod(dp.period)
                            return lines.map((line, lineIdx) => (
                                <text
                                    key={`${idx}-${lineIdx}`}
                                    x={getX(idx)}
                                    y={vbHeight - padding.bottom + 14 + lineIdx * 14}
                                    fill="var(--color-text-secondary)"
                                    fontSize="11"
                                    textAnchor="middle"
                                >
                                    {line}
                                </text>
                            ))
                        })
                    })()}
                </svg>
                {!options?.hideLegend ? (
                    <div className="line-chart-legend">
                        {seriesNames.map((name, idx) => (
                            <span key={name} className="line-chart-legend-item">
                                <span className="line-chart-legend-line" style={{ background: colors[idx] }} />
                                {name}
                            </span>
                        ))}
                    </div>
                ) : null}
            </div>
        )
    }

    const renderStackedBarChart = (chart: ChartSection, options?: { hideLegend?: boolean }) => {
        const colors = chart.config?.colors || ['#5b8db8', '#ef4444']
        const seriesNames = chart.config?.series?.length ? chart.config.series : [`${_t('businessIntelligence.incidents.charts.volumeSeries')} 1`, `${_t('businessIntelligence.incidents.charts.slaSeries')} 2`]

        // Parse compound labels: "category|val1|val2|..."
        const dataPoints = chart.items.map(item => {
            const parts = item.label.split('|')
            return {
                label: parts[0] || item.label,
                values: parts.slice(1).map(v => parseFloat(v) || 0),
            }
        })

        const maxVal = Math.max(...dataPoints.map(dp => dp.values.reduce((a: number, b: number) => a + b, 0)), 1)
        const vbWidth = 500
        const vbHeight = 280
        const padding = { top: 20, right: 20, bottom: 30, left: 50 }
        const innerWidth = vbWidth - padding.left - padding.right
        const innerHeight = vbHeight - padding.top - padding.bottom

        const getY = (value: number) => padding.top + innerHeight - (value / maxVal) * innerHeight
        const groupWidth = innerWidth / dataPoints.length
        const barWidth = Math.min(70, groupWidth * 0.65)

        // Y-axis labels
        const yTicks = 5
        const yAxisLabels = Array.from({ length: yTicks + 1 }, (_, i) => {
            const value = (maxVal / yTicks) * i
            return { value: Math.round(value), y: getY(value) }
        })

        return (
            <div style={{ width: '100%', minWidth: 0 }}>
                <svg
                    viewBox={`0 0 ${vbWidth} ${vbHeight}`}
                    preserveAspectRatio="xMidYMid meet"
                    style={{ width: '100%', display: 'block' }}
                >
                    {/* Y-axis grid lines and labels */}
                    {yAxisLabels.map((label, idx) => (
                        <g key={idx}>
                            <line
                                x1={padding.left} y1={label.y}
                                x2={vbWidth - padding.right} y2={label.y}
                                stroke="var(--color-border)" strokeDasharray="4 4"
                            />
                            <text
                                x={padding.left - 8} y={label.y + 5}
                                fill="var(--color-text-secondary)" fontSize="12" textAnchor="end"
                            >
                                {label.value}
                            </text>
                        </g>
                    ))}

                    {/* Stacked bars */}
                    {dataPoints.map((dp, groupIdx) => {
                        const groupX = padding.left + groupIdx * groupWidth + groupWidth / 2
                        const barX = groupX - barWidth / 2
                        let cumulative = 0

                        return (
                            <g key={groupIdx}>
                                {dp.values.map((val, seriesIdx) => {
                                    const segHeight = (val / maxVal) * innerHeight
                                    const segY = getY(cumulative + val)
                                    cumulative += val
                                    return (
                                        <g key={seriesIdx}>
                                            <rect
                                                x={barX} y={segY}
                                                width={barWidth} height={segHeight}
                                                fill={colors[seriesIdx]} rx={seriesIdx === dp.values.length - 1 ? 3 : 0}
                                                opacity="0.85"
                                            />
                                            {val > 0 && (
                                                <text
                                                    x={barX + barWidth / 2} y={segY + segHeight / 2 + 5}
                                                    fill="white" fontSize="12"
                                                    textAnchor="middle" fontWeight="600"
                                                >
                                                    {Math.round(val)}
                                                </text>
                                            )}
                                        </g>
                                    )
                                })}
                                <text
                                    x={groupX} y={vbHeight - 6}
                                    fill="var(--color-text-secondary)" fontSize="12"
                                    textAnchor="middle" fontWeight="500"
                                >
                                    {dp.label}
                                </text>
                            </g>
                        )
                    })}
                </svg>
                {!options?.hideLegend ? (
                    <div className="line-chart-legend">
                        {seriesNames.map((name, idx) => (
                            <span key={name} className="line-chart-legend-item">
                                <span
                                    className="line-chart-legend-line"
                                    style={{ background: colors[idx], borderRadius: '2px', height: '12px' }}
                                />
                                {name}
                            </span>
                        ))}
                    </div>
                ) : null}
            </div>
        )
    }

    const renderColumnChart = (chart: ChartSection) => {
        const dataPoints = chart.items
        const maxVal = Math.max(...dataPoints.map(dp => dp.value), 1)
        const vbWidth = 500
        const vbHeight = 300
        const padding = { top: 20, right: 20, bottom: 60, left: 45 }
        const innerWidth = vbWidth - padding.left - padding.right
        const innerHeight = vbHeight - padding.top - padding.bottom

        const getY = (value: number) => padding.top + innerHeight - (value / maxVal) * innerHeight
        const barWidth = Math.min(50, (innerWidth / dataPoints.length) * 0.6)

        // Split label into lines (max 2 lines, ~8 chars each)
        const splitLabel = (label: string): string[] => {
            if (label.length <= 8) return [label]
            const mid = Math.ceil(label.length / 2)
            // Try to split at space
            const spaceIdx = label.lastIndexOf(' ', mid + 2)
            if (spaceIdx > 0 && spaceIdx < label.length - 1) {
                return [label.slice(0, spaceIdx), label.slice(spaceIdx + 1)]
            }
            return [label.slice(0, mid), label.slice(mid)]
        }

        // Y-axis ticks
        const yTicks = 5
        const yAxisLabels = Array.from({ length: yTicks + 1 }, (_, i) => {
            const value = (maxVal / yTicks) * i
            return { value: Math.round(value), y: getY(value) }
        })

        return (
            <div style={{ width: '100%', minWidth: 0 }}>
                <svg
                    viewBox={`0 0 ${vbWidth} ${vbHeight}`}
                    preserveAspectRatio="xMidYMid meet"
                    style={{ width: '100%', display: 'block' }}
                >
                    {/* Y-axis grid and labels */}
                    {yAxisLabels.map((label, idx) => (
                        <g key={idx}>
                            <line
                                x1={padding.left} y1={label.y}
                                x2={vbWidth - padding.right} y2={label.y}
                                stroke="var(--color-border)" strokeDasharray="4 4"
                            />
                            <text
                                x={padding.left - 8} y={label.y + 4}
                                fill="var(--color-text-secondary)" fontSize="12" textAnchor="end"
                            >
                                {label.value}
                            </text>
                        </g>
                    ))}

                    {/* Bars */}
                    {dataPoints.map((dp, idx) => {
                        const groupWidth = innerWidth / dataPoints.length
                        const centerX = padding.left + idx * groupWidth + groupWidth / 2
                        const barX = centerX - barWidth / 2
                        const barY = getY(dp.value)
                        const barHeight = innerHeight - (barY - padding.top)
                        const lines = splitLabel(dp.label)
                        return (
                            <g key={idx}>
                                <rect
                                    x={barX} y={barY}
                                    width={barWidth} height={barHeight}
                                    fill="#5b8db8" rx="3" opacity="0.85"
                                />
                                <text
                                    x={centerX} y={barY - 5}
                                    fill="var(--color-text-primary)" fontSize="12"
                                    textAnchor="middle" fontWeight="600"
                                >
                                    {Math.round(dp.value)}
                                </text>
                                {lines.map((line, lineIdx) => (
                                    <text
                                        key={lineIdx}
                                        x={centerX} y={vbHeight - 40 + lineIdx * 14}
                                        fill="var(--color-text-secondary)" fontSize="11"
                                        textAnchor="middle" fontWeight="500"
                                    >
                                        {line}
                                    </text>
                                ))}
                            </g>
                        )
                    })}
                </svg>
            </div>
        )
    }

    const renderBarChart = (chart: ChartSection) => {
        return (
            <div className="business-intelligence-chart-list">
                {chart.items.map((item, index) => (
                    <div key={`${item.label}-${index}`} className="business-intelligence-chart-row">
                        <div className="business-intelligence-chart-meta">
                            <span className="business-intelligence-chart-label">{item.label}</span>
                            <span className="business-intelligence-chart-value">{item.value.toLocaleString()}</span>
                        </div>
                        <div className="business-intelligence-chart-track">
                            <div
                                className="business-intelligence-chart-fill"
                                style={{ width: `${(item.value / maxValue(chart.items)) * 100}%` }}
                            />
                        </div>
                    </div>
                ))}
            </div>
        )
    }

    const renderGroupedBarChart = (chart: ChartSection, options?: { hideLegend?: boolean }) => {
        const colors = chart.config?.colors || ['#5b8db8', '#10b981']
        const seriesNames = chart.config?.series?.length ? chart.config.series : []

        // Parse compound labels: "label|val1|val2|..."
        const dataPoints = chart.items.map(item => {
            const parts = item.label.split('|')
            return {
                label: parts[0] || item.label,
                values: parts.slice(1).map(v => parseFloat(v) || 0),
            }
        })

        const maxVal = Math.max(...dataPoints.flatMap(dp => dp.values), 1)
        const yAxisLabel = chart.config?.yAxisLabel || ''
        const isPercentage = !yAxisLabel ? (maxVal <= 100 && dataPoints.some(dp => dp.values.some(v => v > 5))) : yAxisLabel.includes('%') || yAxisLabel.includes('率')
        const vbWidth = 800
        const vbHeight = 280
        const padding = { top: 30, right: 30, bottom: 40, left: 60 }
        const innerWidth = vbWidth - padding.left - padding.right
        const innerHeight = vbHeight - padding.top - padding.bottom

        const getY = (value: number) => padding.top + innerHeight - (value / maxVal) * innerHeight
        const groupWidth = innerWidth / dataPoints.length
        const barWidth = Math.min(36, (groupWidth * 0.6) / seriesNames.length)
        const barGap = 4

        // Y-axis labels
        const yAxisLabels = [0, 0.25, 0.5, 0.75, 1].map(ratio => ({
            value: Math.round(maxVal * ratio),
            y: getY(maxVal * ratio),
        }))

        return (
            <div style={{ width: '100%' }}>
                <svg
                    viewBox={`0 0 ${vbWidth} ${vbHeight}`}
                    preserveAspectRatio="xMidYMid meet"
                    style={{ width: '100%', height: '280px', display: 'block' }}
                >
                    {/* Y-axis grid lines and labels */}
                    {yAxisLabels.map((label, idx) => (
                        <g key={idx}>
                            <line
                                x1={padding.left} y1={label.y}
                                x2={vbWidth - padding.right} y2={label.y}
                                stroke="var(--color-border)" strokeDasharray="4 4"
                            />
                            <text
                                x={padding.left - 10} y={label.y + 4}
                                fill="var(--color-text-secondary)" fontSize="12" textAnchor="end"
                            >
                                {isPercentage ? `${label.value}%` : label.value}
                            </text>
                        </g>
                    ))}

                    {/* Grouped bars */}
                    {dataPoints.map((dp, groupIdx) => {
                        const groupX = padding.left + groupIdx * groupWidth + groupWidth / 2
                        const totalBarsWidth = seriesNames.length * barWidth + (seriesNames.length - 1) * barGap
                        const startX = groupX - totalBarsWidth / 2

                        return (
                            <g key={groupIdx}>
                                {seriesNames.map((_, seriesIdx) => {
                                    const val = dp.values[seriesIdx] || 0
                                    const barX = startX + seriesIdx * (barWidth + barGap)
                                    const barY = getY(val)
                                    const barHeight = innerHeight - (barY - padding.top)
                                    return (
                                        <g key={seriesIdx}>
                                            <rect
                                                x={barX} y={barY}
                                                width={barWidth} height={barHeight}
                                                fill={colors[seriesIdx]} rx="3" opacity="0.85"
                                            />
                                            <text
                                                x={barX + barWidth / 2} y={barY - 5}
                                                fill={colors[seriesIdx]} fontSize="11"
                                                textAnchor="middle" fontWeight="600"
                                            >
                                                {(() => {
                                                    if (isPercentage) return `${val.toFixed(1)}%`
                                                    return val % 1 === 0 ? val : val.toFixed(1)
                                                })()}
                                            </text>
                                        </g>
                                    )
                                })}
                                <text
                                    x={groupX} y={vbHeight - 15}
                                    fill="var(--color-text-secondary)" fontSize="13"
                                    textAnchor="middle" fontWeight="500"
                                >
                                    {dp.label}
                                </text>
                            </g>
                        )
                    })}
                </svg>
                {!options?.hideLegend ? (
                    <div className="line-chart-legend">
                        {seriesNames.map((name, idx) => (
                            <span key={name} className="line-chart-legend-item">
                                <span
                                    className="line-chart-legend-line"
                                    style={{ background: colors[idx], borderRadius: '2px', height: '12px' }}
                                />
                                {name}
                            </span>
                        ))}
                    </div>
                ) : null}
            </div>
        )
    }

    const renderComboChart = (chart: ChartSection, options?: { hideLegend?: boolean; leftPercentage?: boolean; rightPercentage?: boolean }) => {
        const colors = chart.config?.colors || ['#5b8db8', '#10b981']
        const seriesNames = chart.config?.series || [
            _t('businessIntelligence.incidents.charts.volumeSeries'),
            _t('businessIntelligence.incidents.charts.slaSeries'),
        ]

        // Parse combo data: format "period|volume|completionRate" or "period|volume|completionRate|causedCount"
        const dataPoints = chart.items.map(item => {
            const parts = item.label.split('|')
            return {
                period: parts[0] || item.label,
                volume: parseFloat(parts[1]) || item.value,
                completionRate: parseFloat(parts[2]) || 0,
                causedCount: parseFloat(parts[3]) || 0,
            }
        })

        // Calculate max values for each axis
        const hasCausedLine = seriesNames.length >= 3 && dataPoints.some(dp => dp.causedCount > 0)
        const maxVolume = Math.max(...dataPoints.map(dp => dp.volume), ...dataPoints.map(dp => dp.causedCount), 1)
        const maxLineValue = Math.max(...dataPoints.map(dp => dp.completionRate), 0)
        // Auto-detect right Y-axis mode:
        // 1. Score scale: values ≤ 5 (satisfaction scores 1-5)
        // 2. Count scale: values > 5 and series name doesn't contain percentage indicators
        // 3. Percentage scale: series name contains "率" or values clearly %
        const secondSeries = seriesNames.length > 1 ? seriesNames[1] : ''
        const isScoreScale = maxLineValue <= 5 && maxLineValue > 0
        const isPercentageScale = !isScoreScale && (options?.rightPercentage || secondSeries.includes('率') || secondSeries.includes('%'))
        const isCountScale = !isScoreScale && !isPercentageScale
        const maxRate = (() => {
            if (isScoreScale) return 5
            if (isCountScale) return Math.ceil(maxLineValue / 5) * 5 || 5
            return 100
        })()

        const vbWidth = 1000
        const vbHeight = 360
        const padding = { top: 28, right: 60, bottom: 72, left: 60 }
        const innerWidth = vbWidth - padding.left - padding.right
        const innerHeight = vbHeight - padding.top - padding.bottom

        // Bar chart Y-axis (left) - volume
        const getBarY = (value: number) => padding.top + innerHeight - (value / maxVolume) * innerHeight
        // Line chart Y-axis (right) - percentage or score
        const getLineY = (value: number) => padding.top + innerHeight - (value / maxRate) * innerHeight

        const getBarX = (index: number) => {
            const barWidth = innerWidth / dataPoints.length
            return padding.left + index * barWidth + barWidth / 2
        }

        const barWidth = Math.min(60, (innerWidth / dataPoints.length) * 0.6)

        // Generate Y-axis labels for bar (left)
        const barYAxisLabels = [0, 0.25, 0.5, 0.75, 1].map(ratio => ({
            value: Math.round(maxVolume * ratio),
            y: getBarY(maxVolume * ratio),
        }))

        // Generate Y-axis labels for line (right) - score, count, or percentage
        const lineYAxisLabels = (() => {
            if (isScoreScale) return [0, 1, 2, 3, 4, 5].map(value => ({ value, y: getLineY(value) }))
            if (isPercentageScale) return [0, 25, 50, 75, 100].map(value => ({ value, y: getLineY(value) }))
            return [0, 0.25, 0.5, 0.75, 1].map(ratio => ({ value: Math.round(maxRate * ratio), y: getLineY(maxRate * ratio) }))
        })()

        // Build line path
        const linePoints = dataPoints
            .map((dp, idx) => `${getBarX(idx)},${getLineY(dp.completionRate)}`)
            .join(' ')

        return (
            <div style={{ width: '100%' }}>
                <svg
                    viewBox={`0 0 ${vbWidth} ${vbHeight}`}
                    preserveAspectRatio="xMidYMid meet"
                    style={{ width: '100%', height: '360px', display: 'block' }}
                >
                    {/* Y-axis grid lines */}
                    {barYAxisLabels.map((label, idx) => (
                        <line
                            key={idx}
                            x1={padding.left}
                            y1={label.y}
                            x2={vbWidth - padding.right}
                            y2={label.y}
                            stroke="var(--color-border)"
                            strokeDasharray="4 4"
                        />
                    ))}

                    {/* Left Y-axis labels (volume or percentage) */}
                    {barYAxisLabels.map((label, idx) => (
                        <text
                            key={idx}
                            x={padding.left - 10}
                            y={label.y + 4}
                            fill="var(--color-text-secondary)"
                            fontSize="12"
                            textAnchor="end"
                        >
                            {options?.leftPercentage ? `${label.value}%` : label.value}
                        </text>
                    ))}

                    {/* Right Y-axis labels (score or percentage) */}
                    {lineYAxisLabels.map((label, idx) => (
                        <text
                            key={idx}
                            x={vbWidth - padding.right + 10}
                            y={label.y + 4}
                            fill={colors[1]}
                            fontSize="12"
                            textAnchor="start"
                        >
                            {(() => {
                                if (isPercentageScale) return `${label.value}%`
                                return label.value
                            })()}
                        </text>
                    ))}

                    {/* Bars */}
                    {dataPoints.map((dp, idx) => (
                        <rect
                            key={idx}
                            x={getBarX(idx) - barWidth / 2}
                            y={getBarY(dp.volume)}
                            width={barWidth}
                            height={innerHeight - (getBarY(dp.volume) - padding.top)}
                            fill={colors[0]}
                            rx="4"
                            opacity="0.85"
                        />
                    ))}

                    {/* Line overlay */}
                    <polyline
                        points={linePoints}
                        fill="none"
                        stroke={colors[1]}
                        strokeWidth="3"
                        strokeLinecap="round"
                        strokeLinejoin="round"
                    />

                    {/* Line data points */}
                    {dataPoints.map((dp, idx) => (
                        <circle
                            key={idx}
                            cx={getBarX(idx)}
                            cy={getLineY(dp.completionRate)}
                            r="4"
                            fill={colors[1]}
                            stroke="white"
                            strokeWidth="2"
                        />
                    ))}

                    {/* Second line overlay (causedCount, left Y-axis scale) */}
                    {hasCausedLine && (() => {
                        const causedPoints = dataPoints
                            .map((dp, idx) => `${getBarX(idx)},${getBarY(dp.causedCount)}`)
                            .join(' ')
                        return (
                            <g>
                                <polyline
                                    points={causedPoints}
                                    fill="none"
                                    stroke={colors[2] || '#ef4444'}
                                    strokeWidth="2.5"
                                    strokeLinecap="round"
                                    strokeLinejoin="round"
                                    strokeDasharray="6 3"
                                />
                                {dataPoints.map((dp, idx) => dp.causedCount > 0 ? (
                                    <circle
                                        key={`c${idx}`}
                                        cx={getBarX(idx)}
                                        cy={getBarY(dp.causedCount)}
                                        r="3.5"
                                        fill={colors[2] || '#ef4444'}
                                        stroke="white"
                                        strokeWidth="2"
                                    />
                                ) : null)}
                            </g>
                        )
                    })()}

                    {/* X-axis labels with auto-wrapping */}
                    {(() => {
                        const colWidth = innerWidth / dataPoints.length
                        const maxCharsPerLine = Math.max(4, Math.floor(colWidth / 7))
                        const needsWrap = dataPoints.some(dp => dp.period.length > maxCharsPerLine)
                        const splitPeriod = (label: string): string[] => {
                            return splitAxisLabel(label, needsWrap)
                        }
                        return dataPoints.map((dp, idx) => {
                            if (!shouldRenderAxisLabel(idx, dataPoints.length, 12)) {
                                return null
                            }
                            const lines = splitPeriod(dp.period)
                            return lines.map((line, lineIdx) => (
                                <text
                                    key={`${idx}-${lineIdx}`}
                                    x={getBarX(idx)}
                                    y={vbHeight - padding.bottom + 14 + lineIdx * 14}
                                    fill="var(--color-text-secondary)"
                                    fontSize="12"
                                    textAnchor="middle"
                                >
                                    {line}
                                </text>
                            ))
                        })
                    })()}
                </svg>
                {!options?.hideLegend ? (
                    <div className="line-chart-legend">
                        {seriesNames.map((name, idx) => (
                            <span key={name} className="line-chart-legend-item">
                                <span
                                    className="line-chart-legend-line"
                                    style={{
                                        background: colors[idx],
                                        borderRadius: idx === 0 ? '2px' : '0',
                                        height: idx === 0 ? '12px' : '3px',
                                    }}
                                />
                                {name}
                            </span>
                        ))}
                    </div>
                ) : null}
            </div>
        )
    }

    // Generic heatmap: detects format from chart ID
    // - "change-density" style: weekday × hour grid (dow|hour|changeCount|incidentCount)
    // - "wf-efficiency-heatmap": person × category grid (person|category|avgHours), value = avgHours
    const renderHeatmapChart = (chart: ChartSection) => {
        if (chart.id === 'wf-efficiency-heatmap') {
            return renderFulfillmentHeatmap(chart)
        }
        return renderWeekdayHeatmap(chart)
    }

    const renderFulfillmentHeatmap = (chart: ChartSection) => {
        // Parse: "person|category|avgHours", value = avgHours
        const yLabelsSet: string[] = []
        const xLabelsSet: string[] = []
        const ySeen = new Set<string>()
        const xSeen = new Set<string>()
        for (const item of chart.items) {
            const parts = item.label.split('|')
            if (!ySeen.has(parts[0])) { yLabelsSet.push(parts[0]); ySeen.add(parts[0]) }
            if (!xSeen.has(parts[1])) { xLabelsSet.push(parts[1]); xSeen.add(parts[1]) }
        }
        const yLabels = yLabelsSet
        const xLabels = xLabelsSet
        if (yLabels.length === 0 || xLabels.length === 0) return null

        const cellMap = new Map<string, { avgHours: number; label: string }>()
        let maxVal = 0
        for (const item of chart.items) {
            const parts = item.label.split('|')
            cellMap.set(parts[0] + '|' + parts[1], { avgHours: item.value, label: parts[2] })
            if (item.value > maxVal) maxVal = item.value
        }

        const vbWidth = 900
        const xLabelZone = 45  // space for multi-line x-axis labels
        const padding = { top: 10, right: 20, bottom: 8, left: 130 }
        const gridHeight = yLabels.length * 48
        const vbHeight = padding.top + gridHeight + padding.bottom + xLabelZone
        const innerWidth = vbWidth - padding.left - padding.right
        const innerHeight = gridHeight
        const cellW = innerWidth / xLabels.length
        const cellH = innerHeight / yLabels.length

        const trunc = (s: string, max: number) => s.length > max ? s.slice(0, max) + '…' : s

        // Color scale: fixed thresholds with operational meaning
        // < 12h → green (fast), 12-20h → yellow-green, 20-28h → orange, > 28h → red
        const cellColor = (val: number) => {
            const clamp = (v: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, v))
            const lerp = (a: number, b: number, t: number) => Math.round(a + (b - a) * t)
            if (val <= 12) {
                // green
                const t = clamp(val / 12, 0, 1)
                return `rgba(16,185,129,${0.35 + t * 0.3})`
            } else if (val <= 20) {
                // green → yellow
                const t = clamp((val - 12) / 8, 0, 1)
                const r = lerp(16, 245, t)
                const g = lerp(185, 158, t)
                const b = lerp(129, 11, t)
                return `rgba(${r},${g},${b},0.55)`
            } else if (val <= 28) {
                // yellow → orange-red
                const t = clamp((val - 20) / 8, 0, 1)
                const r = lerp(245, 239, t)
                const g = lerp(158, 68, t)
                const b = lerp(11, 68, t)
                return `rgba(${r},${g},${b},0.55)`
            } else {
                // red (capped at 48h for opacity)
                const t = clamp((val - 28) / 20, 0, 1)
                return `rgba(239,68,68,${0.55 + t * 0.3})`
            }
        }

        return (
            <div style={{ width: '100%' }}>
                <svg viewBox={`0 0 ${vbWidth} ${vbHeight}`} preserveAspectRatio="xMidYMid meet"
                    style={{ width: '100%', height: `${vbHeight}px`, display: 'block' }}>
                    {/* Y-axis labels (persons) */}
                    {yLabels.map((label, idx) => (
                        <text key={label} x={padding.left - 8} y={padding.top + idx * cellH + cellH / 2 + 4}
                            fill="var(--color-text-secondary)" fontSize="11" textAnchor="end">
                            {trunc(label, 16)}
                        </text>
                    ))}
                    {/* X-axis labels (categories), word-wrapped */}
                    {xLabels.map((label, idx) => {
                        const cx = padding.left + idx * cellW + cellW / 2
                        const words = label.split(' ')
                        const lines: string[] = []
                        let cur = ''
                        for (const w of words) {
                            const next = cur ? cur + ' ' + w : w
                            if (next.length > 12 && cur) {
                                lines.push(cur)
                                cur = w
                            } else {
                                cur = next
                            }
                        }
                        if (cur) lines.push(cur)
                        return (
                            <text key={label} x={cx} y={padding.top + gridHeight + 12}
                                fill="var(--color-text-secondary)" fontSize="10" textAnchor="middle">
                                {lines.map((line, li) => (
                                    <tspan key={li} x={cx} dy={li === 0 ? 0 : 13}>{line}</tspan>
                                ))}
                            </text>
                        )
                    })}
                    {/* Cells */}
                    {yLabels.map((person, yi) =>
                        xLabels.map((cat, xi) => {
                            const cell = cellMap.get(person + '|' + cat)
                            if (!cell) return (
                                <rect key={`${yi}-${xi}`}
                                    x={padding.left + xi * cellW + 1} y={padding.top + yi * cellH + 1}
                                    width={cellW - 2} height={cellH - 2} rx="3"
                                    fill="var(--color-bg-secondary)" />
                            )
                            return (
                                <g key={`${yi}-${xi}`}>
                                    <rect x={padding.left + xi * cellW + 1} y={padding.top + yi * cellH + 1}
                                        width={cellW - 2} height={cellH - 2} rx="3"
                                        fill={cellColor(cell.avgHours)} />
                                    <text x={padding.left + xi * cellW + cellW / 2}
                                        y={padding.top + yi * cellH + cellH / 2 + 3}
                                        fill="var(--color-text-secondary)" fontSize="9" textAnchor="middle">
                                        {cell.label}
                                    </text>
                                </g>
                            )
                        })
                    )}
                </svg>
            </div>
        )
    }

    const renderWeekdayHeatmap = (chart: ChartSection) => {
        const colors = chart.config?.colors || ['#5b8db8', '#ef4444']
        const dayLabels = [
            _t('businessIntelligence.incidents.weekdays.monday'),
            _t('businessIntelligence.incidents.weekdays.tuesday'),
            _t('businessIntelligence.incidents.weekdays.wednesday'),
            _t('businessIntelligence.incidents.weekdays.thursday'),
            _t('businessIntelligence.incidents.weekdays.friday'),
            _t('businessIntelligence.incidents.weekdays.saturday'),
            _t('businessIntelligence.incidents.weekdays.sunday'),
        ]

        // Parse heatmap data: "dow|hour|changeCount|incidentCount"
        const cellMap = new Map<string, { changeCount: number; incidentCount: number }>()
        let maxChange = 0
        for (const item of chart.items) {
            const parts = item.label.split('|')
            const dow = parts[0]
            const hour = parts[1]
            const changeCount = parseFloat(parts[2]) || 0
            const incidentCount = parseFloat(parts[3]) || 0
            cellMap.set(dow + '-' + hour, { changeCount, incidentCount })
            if (changeCount > maxChange) maxChange = changeCount
        }

        const vbWidth = 900
        const vbHeight = 280
        const padding = { top: 10, right: 20, bottom: 30, left: 50 }
        const innerWidth = vbWidth - padding.left - padding.right
        const innerHeight = vbHeight - padding.top - padding.bottom
        const cellW = innerWidth / 24
        const cellH = innerHeight / 7

        return (
            <div style={{ width: '100%' }}>
                <svg
                    viewBox={`0 0 ${vbWidth} ${vbHeight}`}
                    preserveAspectRatio="xMidYMid meet"
                    style={{ width: '100%', height: '280px', display: 'block' }}
                >
                    {/* Y-axis labels (days) */}
                    {dayLabels.map((label, idx) => (
                        <text
                            key={label}
                            x={padding.left - 8}
                            y={padding.top + idx * cellH + cellH / 2 + 4}
                            fill="var(--color-text-secondary)"
                            fontSize="11"
                            textAnchor="end"
                        >
                            {label}
                        </text>
                    ))}
                    {/* X-axis labels (hours) */}
                    {[0, 3, 6, 9, 12, 15, 18, 21].map(hour => (
                        <text
                            key={hour}
                            x={padding.left + hour * cellW + cellW / 2}
                            y={vbHeight - 8}
                            fill="var(--color-text-secondary)"
                            fontSize="11"
                            textAnchor="middle"
                        >
                            {hour}h
                        </text>
                    ))}
                    {/* Cells */}
                    {Array.from({ length: 7 }, (_, dow) =>
                        Array.from({ length: 24 }, (_, hour) => {
                            const cell = cellMap.get((dow + 1) + '-' + hour)
                            const changeCount = cell?.changeCount || 0
                            const incidentCount = cell?.incidentCount || 0
                            const opacity = maxChange > 0 ? 0.06 + 0.84 * (changeCount / maxChange) : 0.06
                            const x = padding.left + hour * cellW
                            const y = padding.top + dow * cellH
                            return (
                                <g key={`${dow}-${hour}`}>
                                    <rect
                                        x={x + 1} y={y + 1}
                                        width={cellW - 2} height={cellH - 2}
                                        rx="3"
                                        fill={`rgba(91, 141, 184, ${opacity})`}
                                    />
                                    {changeCount > 0 && (
                                        <text
                                            x={x + cellW / 2} y={y + cellH / 2 + 3}
                                            fill={opacity > 0.5 ? 'white' : 'var(--color-text-secondary)'}
                                            fontSize="9"
                                            textAnchor="middle"
                                        >
                                            {changeCount}
                                        </text>
                                    )}
                                    {incidentCount > 0 && (
                                        <circle
                                            cx={x + cellW - 6} cy={y + 6}
                                            r="4"
                                            fill={colors[1]}
                                            opacity="0.85"
                                        />
                                    )}
                                </g>
                            )
                        })
                    )}
                </svg>
                <div className="line-chart-legend">
                    <span className="line-chart-legend-item">
                        <span className="line-chart-legend-line" style={{ background: 'rgba(91, 141, 184, 0.6)', height: '12px', width: '20px', borderRadius: '3px' }} />
                        {_t('businessIntelligence.incidents.charts.changeDensity')}
                    </span>
                    <span className="line-chart-legend-item">
                        <span style={{ display: 'inline-block', width: '8px', height: '8px', borderRadius: '50%', background: colors[1] }} />
                        {_t('businessIntelligence.incidents.charts.incidentHotspots')}
                    </span>
                </div>
            </div>
        )
    }

    const renderRadarChart = (chart: ChartSection) => {
        const colors = chart.config?.colors || ['#5b8db8', '#10b981', '#f59e0b', '#ef4444', '#8b7fc7']
        const persons = chart.config?.series || []
        const seriesData = chart.config?.seriesData || {}
        const axes = chart.items.map(item => item.label)
        const numAxes = axes.length
        if (numAxes < 3) return null
        const angleStep = (2 * Math.PI) / numAxes
        const startAngle = -Math.PI / 2
        const vbSize = 500
        const cx = vbSize / 2
        const cy = 210
        const maxR = 160
        const levels = 5
        const getAxisPoint = (axisIdx: number, ratio: number) => {
            const angle = startAngle + axisIdx * angleStep
            return { x: cx + maxR * ratio * Math.cos(angle), y: cy + maxR * ratio * Math.sin(angle) }
        }
        const gridPath = (level: number) => {
            const ratio = level / levels
            return axes.map((_, i) => {
                const p = getAxisPoint(i, ratio)
                return `${i === 0 ? 'M' : 'L'} ${p.x} ${p.y}`
            }).join(' ') + ' Z'
        }
        const personPolygon = (scores: number[]) => {
            return scores.map((score, i) => {
                const p = getAxisPoint(i, score / 100)
                return `${i === 0 ? 'M' : 'L'} ${p.x} ${p.y}`
            }).join(' ') + ' Z'
        }
        return (
            <div style={{ width: '100%' }}>
                <svg viewBox={`0 0 ${vbSize} 420`} preserveAspectRatio="xMidYMid meet" style={{ width: '100%', height: '420px', display: 'block' }}>
                    {Array.from({ length: levels }, (_, i) => (
                        <path key={`grid-${i}`} d={gridPath(i + 1)} fill="none" stroke="var(--color-border)" strokeWidth="0.8" />
                    ))}
                    {axes.map((axis, i) => {
                        const outer = getAxisPoint(i, 1)
                        const labelPt = getAxisPoint(i, 1.18)
                        return (
                            <g key={`axis-${i}`}>
                                <line x1={cx} y1={cy} x2={outer.x} y2={outer.y} stroke="var(--color-border)" strokeWidth="0.8" />
                                <text x={labelPt.x} y={labelPt.y + 4} fill="var(--color-text-primary)" fontSize="12" textAnchor="middle" fontWeight="500">{axis}</text>
                            </g>
                        )
                    })}
                    {Array.from({ length: levels }, (_, i) => {
                        const pt = getAxisPoint(0, (i + 1) / levels)
                        return <text key={`lvl-${i}`} x={pt.x + 6} y={pt.y - 2} fill="var(--color-text-secondary)" fontSize="9">{(i + 1) * 20}</text>
                    })}
                    {persons.map((person, pIdx) => {
                        const data = seriesData[person] || []
                        const scores = data.map(d => d.value)
                        return (
                            <g key={`person-${pIdx}`}>
                                <path d={personPolygon(scores)} fill={colors[pIdx % colors.length]} fillOpacity="0.12" stroke={colors[pIdx % colors.length]} strokeWidth="2" />
                                {scores.map((score, sIdx) => {
                                    const pt = getAxisPoint(sIdx, score / 100)
                                    return <circle key={`pt-${sIdx}`} cx={pt.x} cy={pt.y} r="3.5" fill={colors[pIdx % colors.length]} />
                                })}
                            </g>
                        )
                    })}
                </svg>
                <div className="line-chart-legend">
                    {persons.map((person, idx) => (
                        <span key={person} className="line-chart-legend-item">
                            <span className="line-chart-legend-line" style={{ background: colors[idx % colors.length] }} />
                            {person}
                        </span>
                    ))}
                </div>
            </div>
        )
    }

    const renderBubbleChart = (chart: ChartSection) => {
        const seriesColors = chart.config?.colors || ['#5b8db8', '#10b981', '#f59e0b', '#ef4444', '#8b7fc7', '#c97082']

        const isWorkforce = chart.items.length > 0 && chart.items[0].label.split('|').length === 3
        if (isWorkforce) {
            return <WorkforceBubbleChart key={chart.id} chart={chart} colors={seriesColors} t={_t} />
        }

        return <IncidentBubbleChart key={chart.id} chart={chart} colors={seriesColors} t={_t} />
    }
    const PERCENTAGE_CHARTS = new Set(['sla-trend', 'req-sla-trend'])
    // Combo charts where LEFT Y-axis (bar) shows percentage
    const PERCENTAGE_LEFT_AXIS_COMBOS = new Set(['req-sla-trend'])
    // Combo charts where RIGHT Y-axis (line) shows percentage (auto-detected by series name in zh, but not en)
    const PERCENTAGE_RIGHT_AXIS_COMBOS = new Set(['request-sla-time', 'change-success-trend', 'incident-volume-trend'])
    const HOURS_CHARTS = new Set(['incident-mttr-trend'])

    const renderChartContent = (chart: ChartSection, options?: { hideLegend?: boolean }) => {
        const lineOptions = {
            ...options,
            percentage: PERCENTAGE_CHARTS.has(chart.id),
            hoursMode: HOURS_CHARTS.has(chart.id),
        }
        const comboOptions = {
            ...options,
            leftPercentage: PERCENTAGE_LEFT_AXIS_COMBOS.has(chart.id),
            rightPercentage: PERCENTAGE_RIGHT_AXIS_COMBOS.has(chart.id),
        }
        return (
            <>
                {chart.type === 'pie' && (
                    <PieDistributionCard
                        embedded
                        items={chart.items}
                        colors={chart.config?.colors || ['#5b8db8', '#10b981', '#f59e0b', '#ef4444', '#8b7fc7', '#c97082']}
                        otherLabel={_t('common.other')}
                    />
                )}
                {chart.type === 'line' && renderLineChart(chart, lineOptions)}
                {chart.type === 'combo' && renderComboChart(chart, comboOptions)}
                {chart.type === 'grouped-bar' && renderGroupedBarChart(chart, options)}
                {chart.type === 'stacked-bar' && renderStackedBarChart(chart, options)}
                {chart.type === 'column' && renderColumnChart(chart)}
                {chart.type === 'heatmap' && renderHeatmapChart(chart)}
                {chart.type === 'bubble' && renderBubbleChart(chart)}
                {chart.type === 'radar' && renderRadarChart(chart)}
                {(chart.type === 'bar' || !chart.type) && renderBarChart(chart)}
            </>
        )
    }

    const renderChart = (chart: ChartSection) => {
        const isWideChart = chart.type === 'line' || chart.type === 'combo' || chart.type === 'grouped-bar' || chart.type === 'heatmap' || chart.type === 'bubble' || chart.type === 'radar'
        return (
            <section
                key={chart.id}
                className={`business-intelligence-chart-section business-intelligence-chart-${chart.type}`}
                style={isWideChart ? { gridColumn: '1 / -1', width: '100%' } : { gridColumn: 'span 1' }}
            >
                <h3 className="business-intelligence-content-card-title" style={{ fontSize: '1.125rem', marginBottom: 'var(--spacing-4)' }}>{chart.title}</h3>
                {renderChartContent(chart)}
            </section>
        )
    }

    const VIOLATION_TYPE_MAP: Record<string, string> = {
        both_breached: _t('businessIntelligence.slaStatus.bothBreached'),
        response_breached: _t('businessIntelligence.slaStatus.responseBreached'),
        resolution_breached: _t('businessIntelligence.slaStatus.resolutionBreached'),
        met: _t('businessIntelligence.slaStatus.met'),
    }

    const renderTableCell = (column: string, cell: string) => {
        if (column.trim().toLowerCase().includes('sla')) {
            const status = getSlaStatus(cell, _t)
            if (status) {
                return <StatusCell tone={status.tone} label={status.label} />
            }
        }

        if (VIOLATION_TYPE_MAP[cell]) {
            const tone = cell === 'met' ? 'success' : 'danger'
            return <StatusCell tone={tone} label={VIOLATION_TYPE_MAP[cell]} />
        }

        return cell
    }

    const renderTable = (table: TableSection) => (
        <div className="business-intelligence-table-shell">
            <table className="business-intelligence-table">
                <thead>
                    <tr>
                        {table.columns.map((col, colIndex) => (
                            <th key={colIndex} className={getTableColumnClassName(col)}>{col}</th>
                        ))}
                    </tr>
                </thead>
                <tbody>
                    {table.rows.map((row, rowIndex) => (
                        <tr key={rowIndex}>
                            {row.map((cell, cellIndex) => {
                                const column = table.columns[cellIndex] || ''
                                const cellClasses = [
                                    getTableColumnClassName(column),
                                    column.trim().toLowerCase().includes('sla') ? 'business-intelligence-table-status' : '',
                                    (column.trim().toLowerCase().includes('title') || column.trim().toLowerCase().includes('标题')) ? 'business-intelligence-table-title-cell' : '',
                                ].filter(Boolean).join(' ')

                                return (
                                    <td key={cellIndex} className={cellClasses}>
                                        {renderTableCell(column, cell)}
                                    </td>
                                )
                            })}
                        </tr>
                    ))}
                </tbody>
            </table>
        </div>
    )

    if (isIncidentAnalysisTab(localizedTab)) {
        const trendCharts = localizedTab.charts.filter(chart => chart.type !== 'pie')
        const distributionCharts = localizedTab.charts.filter(chart => chart.type === 'pie')

        return (
            <div className="business-intelligence-content-card">
                <div className="business-intelligence-content-card-header">
                    <div className="business-intelligence-content-card-copy">
                        <h2 className="business-intelligence-content-card-title">{localizedTab.label}</h2>
                        <p className="business-intelligence-content-card-description">{localizedTab.description}</p>
                    </div>
                </div>

                <div className="business-intelligence-content-card-body">
                    <div className="business-intelligence-section-stack">
                    {localizedTab.cards.length > 0 ? (
                        <section className="business-intelligence-section-stack">
                            <div
                                className="business-intelligence-stat-grid business-intelligence-stat-grid-snapshot ui-metric-grid"
                                style={{ gridTemplateColumns: `repeat(${localizedTab.cards.length % 3 === 0 ? 3 : 2}, minmax(0, 1fr))` }}
                            >
                                {localizedTab.cards.map(card => {
                                    const tone = getMetricTone(card.tone)
                                    return (
                                        <StatCard
                                            key={card.id}
                                            label={card.label}
                                            value={card.value}
                                            tone={tone}
                                            icon={tone === 'neutral' ? null : <StatusIcon tone={tone} />}
                                        />
                                    )
                                })}
                            </div>
                        </section>
                    ) : null}

                    {trendCharts.length > 0 ? (
                        <div className="business-intelligence-section-stack">
                            {trendCharts.map(chart => renderChart(chart))}
                        </div>
                    ) : null}

                    {distributionCharts.length > 0 ? (
                        <div className="business-intelligence-distribution-grid">
                            {distributionCharts.map(chart => (
                                <PieDistributionCard
                                    key={chart.id}
                                    title={chart.title}
                                    items={chart.items}
                                    colors={chart.config?.colors || ['#5b8db8', '#10b981', '#f59e0b', '#ef4444', '#8b7fc7', '#c97082']}
                                    otherLabel={_t('common.other')}
                                />
                            ))}
                        </div>
                    ) : null}

                    {localizedTab.tables.length > 0 ? (
                        <div className="business-intelligence-section-stack">
                            {localizedTab.tables.map(table => (
                                <AnalyticsTableCard key={table.id} title={table.title}>
                                    {renderTable(table)}
                                </AnalyticsTableCard>
                            ))}
                        </div>
                    ) : null}
                    </div>
                </div>
            </div>
        )
    }

    // ── SLA Analysis tab: dual content-card layout (Incident + Request) ──
    if (SLA_TAB_IDS.has(localizedTab.id)) {
        const incidentCards = localizedTab.cards.filter(c => c.id.startsWith('sla-'))
        const requestCards = localizedTab.cards.filter(c => c.id.startsWith('req-sla-'))
        const incidentCharts = localizedTab.charts.filter(c => !c.id.startsWith('req-sla-'))
        const requestCharts = localizedTab.charts.filter(c => c.id.startsWith('req-sla-'))
        const incidentTables = localizedTab.tables.filter(t => t.id === 'sla-violation-samples')
        const requestTables = localizedTab.tables.filter(t => t.id === 'req-sla-violation-samples')

        const renderCardSection = (cards: MetricCard[]) => {
            if (cards.length === 0) return null
            const gridCards = cards.filter(c => c.tone !== 'divider')
            return (
                <div
                    className="business-intelligence-stat-grid business-intelligence-stat-grid-snapshot ui-metric-grid"
                    style={{ gridTemplateColumns: `repeat(${gridCards.length % 3 === 0 ? 3 : 2}, minmax(0, 1fr))` }}
                >
                    {cards.map(card => {
                        const tone = getMetricTone(card.tone)
                        return (
                            <StatCard
                                key={card.id}
                                label={card.label}
                                value={card.value}
                                tone={tone}
                                icon={tone === 'neutral' ? null : <StatusIcon tone={tone} />}
                            />
                        )
                    })}
                </div>
            )
        }

        const renderChartSection = (charts: ChartSection[]) => {
            if (charts.length === 0) return null
            const nonPie = charts.filter(c => c.type !== 'pie')
            const pies = charts.filter(c => c.type === 'pie')
            return (
                <>
                    {nonPie.map(chart => renderChart(chart))}
                    {pies.length > 0 ? (
                        <div className="business-intelligence-distribution-grid">
                            {pies.map(chart => (
                                <PieDistributionCard
                                    key={chart.id}
                                    title={chart.title}
                                    items={chart.items}
                                    colors={chart.config?.colors || ['#5b8db8', '#10b981', '#f59e0b', '#ef4444', '#8b7fc7', '#c97082']}
                                    otherLabel={_t('common.other')}
                                />
                            ))}
                        </div>
                    ) : null}
                </>
            )
        }

        return (
            <div className="business-intelligence-section-stack">
                <div className="business-intelligence-content-card">
                    <div className="business-intelligence-content-card-header">
                        <div className="business-intelligence-content-card-copy">
                            <h2 className="business-intelligence-content-card-title">{_t('businessIntelligence.sla.sections.incident')}</h2>
                            <p className="business-intelligence-content-card-description">{_t('businessIntelligence.sla.sections.incidentDesc')}</p>
                        </div>
                    </div>
                    <div className="business-intelligence-content-card-body">
                        <div className="business-intelligence-section-stack">
                            {renderCardSection(incidentCards)}
                            {renderChartSection(incidentCharts)}
                            {incidentTables.length > 0 ? (
                                <div className="business-intelligence-section-stack">
                                    {incidentTables.map(table => (
                                        <AnalyticsTableCard key={table.id} title={table.title}>
                                            {renderTable(table)}
                                        </AnalyticsTableCard>
                                    ))}
                                </div>
                            ) : null}
                        </div>
                    </div>
                </div>

                <div className="business-intelligence-content-card">
                    <div className="business-intelligence-content-card-header">
                        <div className="business-intelligence-content-card-copy">
                            <h2 className="business-intelligence-content-card-title">{_t('businessIntelligence.sla.sections.request')}</h2>
                            <p className="business-intelligence-content-card-description">{_t('businessIntelligence.sla.sections.requestDesc')}</p>
                        </div>
                    </div>
                    <div className="business-intelligence-content-card-body">
                        <div className="business-intelligence-section-stack">
                            {renderCardSection(requestCards)}
                            {renderChartSection(requestCharts)}
                            {requestTables.length > 0 ? (
                                <div className="business-intelligence-section-stack">
                                    {requestTables.map(table => (
                                        <AnalyticsTableCard key={table.id} title={table.title}>
                                            {renderTable(table)}
                                        </AnalyticsTableCard>
                                    ))}
                                </div>
                            ) : null}
                        </div>
                    </div>
                </div>
            </div>
        )
    }

    // ── Workforce tab: uses shared primitives (StatCard, SectionCard, AnalyticsTableCard) ──
    if (isWorkforceTab(localizedTab)) {
        const nonPieCharts = localizedTab.charts.filter(c => c.type !== 'pie')
        return (
            <div className="business-intelligence-content-card">
                <div className="business-intelligence-content-card-header">
                    <div className="business-intelligence-content-card-copy">
                        <h2 className="business-intelligence-content-card-title">{localizedTab.label}</h2>
                        <p className="business-intelligence-content-card-description">{localizedTab.description}</p>
                    </div>
                </div>
                <div className="business-intelligence-content-card-body">
                    <div className="business-intelligence-section-stack">
                    {localizedTab.cards.length > 0 ? (
                        <section className="business-intelligence-section-stack">
                            <div
                                className="business-intelligence-stat-grid business-intelligence-stat-grid-snapshot ui-metric-grid"
                                style={{ gridTemplateColumns: `repeat(${localizedTab.cards.length % 3 === 0 ? 3 : 2}, minmax(0, 1fr))` }}
                            >
                                {localizedTab.cards.map(card => {
                                    const tone = getMetricTone(card.tone)
                                    return (
                                        <StatCard
                                            key={card.id}
                                            label={card.label}
                                            value={card.value}
                                            tone={tone}
                                            icon={tone === 'neutral' ? null : <StatusIcon tone={tone} />}
                                        />
                                    )
                                })}
                            </div>
                        </section>
                    ) : null}
                    {nonPieCharts.length > 0 ? (
                        <div className="business-intelligence-section-stack">
                            {nonPieCharts.map(chart => renderChart(chart))}
                        </div>
                    ) : null}
                    {localizedTab.tables.length > 0 ? (
                        <div className="business-intelligence-section-stack">
                            {localizedTab.tables.map(table => (
                                <AnalyticsTableCard key={table.id} title={table.title}>
                                    {renderTable(table)}
                                </AnalyticsTableCard>
                            ))}
                        </div>
                    ) : null}
                    </div>
                </div>
            </div>
        )
    }

    return (
        <div className="business-intelligence-content-card">
            <div className="business-intelligence-content-card-header">
                <div className="business-intelligence-content-card-copy">
                    <h2 className="business-intelligence-content-card-title">{localizedTab.label}</h2>
                    <p className="business-intelligence-content-card-description">{localizedTab.description}</p>
                </div>
            </div>

            <div className="business-intelligence-content-card-body">
                {/* Cards Section */}
                {localizedTab.cards.length > 0 && (
                    <section className="business-intelligence-section">
                        <div className="business-intelligence-stat-grid ui-metric-grid"
                            style={{ gridTemplateColumns: `repeat(${localizedTab.cards.length % 3 === 0 ? 3 : 2}, minmax(0, 1fr))` }}>
                            {localizedTab.cards.map(card => {
                                const tone = getMetricTone(card.tone)
                                return (
                                    <StatCard
                                        key={card.id}
                                        label={card.label}
                                        value={card.value}
                                        tone={tone}
                                        icon={tone === 'neutral' ? null : <StatusIcon tone={tone} />}
                                    />
                                )
                            })}
                        </div>
                    </section>
                )}

                {/* Charts Section */}
                {localizedTab.charts.length > 0 && (
                    <div className="business-intelligence-section">
                        <div className="business-intelligence-content-grid">
                            {localizedTab.charts.map(chart => renderChart(chart))}
                        </div>
                    </div>
                )}

                {/* Tables Section */}
                {localizedTab.tables.length > 0 && (
                    <div className="business-intelligence-section-stack">
                        {localizedTab.tables.map(table => (
                            <section key={table.id} className="business-intelligence-table-section">
                                <h3 className="business-intelligence-content-card-title" style={{ fontSize: '1.125rem', marginBottom: 'var(--spacing-3)' }}>{table.title}</h3>
                                {renderTable(table)}
                            </section>
                        ))}
                    </div>
                )}
            </div>
        </div>
    )
}

export default function BusinessIntelligence() {
    const { t, i18n } = useTranslation()
    const { showToast } = useToast()
    const [overview, setOverview] = useState<OverviewResponse | null>(null)
    const [activeTabId, setActiveTabId] = useState<string>('executive-summary')
    const [loading, setLoading] = useState(true)
    const [refreshing, setRefreshing] = useState(false)
    const [error, setError] = useState<string | null>(null)
    const [exporting, setExporting] = useState(false)
    const [reportingPeriod, setReportingPeriod] = useState<ReportingPeriod>(getDefaultReportingPeriod())
    const invalidRangeNotified = useRef(false)

    const isInvalidRange = reportingPeriod.preset === 'custom'
        && !!reportingPeriod.startDate
        && !!reportingPeriod.endDate
        && reportingPeriod.startDate > reportingPeriod.endDate

    const loadOverview = useCallback(async (options?: { forceRefresh?: boolean; startDate?: string; endDate?: string }) => {
        const forceRefresh = options?.forceRefresh === true
        const startDate = options?.startDate
        const endDate = options?.endDate

        if (forceRefresh) {
            setRefreshing(true)
        } else {
            setLoading(true)
        }
        setError(null)
        try {
            const params = new URLSearchParams()
            if (startDate) params.append('startDate', startDate)
            if (endDate) params.append('endDate', endDate)
            const queryString = params.toString()
            const baseUrl = forceRefresh ? `${runtime.BUSINESS_INTELLIGENCE_SERVICE_URL}/refresh` : `${runtime.BUSINESS_INTELLIGENCE_SERVICE_URL}/overview`
            const url = queryString ? `${baseUrl}?${queryString}` : baseUrl

            const response = await fetch(url, {
                method: forceRefresh ? 'POST' : 'GET',
            })

            const contentType = response.headers.get('content-type') || ''
            const isJson = contentType.includes('application/json')

            if (!response.ok) {
                if (!isJson) {
                    throw new Error(t('businessIntelligence.serviceUnavailable', {
                        status: response.status,
                        statusText: response.statusText,
                    }))
                }

                const errorPayload = await response.json().catch(() => null) as { message?: string } | null
                throw new Error(errorPayload?.message || `${response.status} ${response.statusText}`)
            }

            if (!isJson) {
                throw new Error(t('businessIntelligence.invalidJsonResponse'))
            }

            const data = await response.json() as OverviewResponse
            setOverview(data)
            setActiveTabId(current => (data.tabs.length > 0 && !data.tabs.some(tab => tab.id === current) ? data.tabs[0].id : current))
            if (forceRefresh) {
                showToast('success', t('businessIntelligence.refreshSuccess'))
            }
        } catch (requestError) {
            const message = requestError instanceof Error ? requestError.message : t('common.unknownError')
            setError(message)
            if (forceRefresh) {
                showToast('error', t('businessIntelligence.refreshFailed', { error: message }))
            }
        } finally {
            setLoading(false)
            setRefreshing(false)
        }
    }, [showToast, t])

    const handleExport = useCallback(async () => {
        if (exporting) return
        setExporting(true)
        try {
            const language = i18n.language?.startsWith('zh') ? 'zh' : 'en'
            const params = new URLSearchParams({ language })
            if (reportingPeriod.startDate) params.set('startDate', reportingPeriod.startDate)
            if (reportingPeriod.endDate) params.set('endDate', reportingPeriod.endDate)
            const url = `${runtime.BUSINESS_INTELLIGENCE_SERVICE_URL}/export-enhanced.xlsx?${params.toString()}`
            const response = await trackedFetch(url, {
                method: 'GET',
                category: 'request',
                name: 'request.send',
                credentials: 'include',
            })
            if (!response.ok) {
                const errorText = await response.text().catch(() => '')
                let errorMessage = `${response.status} ${response.statusText}`
                if (errorText) {
                    try {
                        const errorPayload = JSON.parse(errorText) as { error?: string; message?: string }
                        errorMessage = errorPayload.error || errorPayload.message || errorText
                    } catch {
                        errorMessage = errorText
                    }
                }
                throw new Error(errorMessage)
            }
            const langSuffix = language === 'zh' ? 'CN' : 'EN'
            await downloadBlobResponse(response, `BI_Dashboard_Export_${langSuffix}.xlsx`)
            showToast('success', t('businessIntelligence.exportSuccess'))
        } catch (err) {
            const message = err instanceof Error ? err.message : String(err)
            showToast('error', t('businessIntelligence.exportFailed', { error: message }))
        } finally {
            setExporting(false)
        }
    }, [exporting, i18n.language, reportingPeriod.startDate, reportingPeriod.endDate, showToast, t])

    // Load data when reporting period changes (also covers initial load)
    useEffect(() => {
        if (isInvalidRange) {
            if (!invalidRangeNotified.current) {
                showToast('error', t('businessIntelligence.invalidDateRange'))
                invalidRangeNotified.current = true
            }
            return
        }
        invalidRangeNotified.current = false

        if (reportingPeriod.preset === 'custom') {
            if (reportingPeriod.startDate && reportingPeriod.endDate) {
                void loadOverview({
                    startDate: reportingPeriod.startDate,
                    endDate: reportingPeriod.endDate,
                })
            }
            return
        }
        if (reportingPeriod.startDate && reportingPeriod.endDate) {
            void loadOverview({
                startDate: reportingPeriod.startDate,
                endDate: reportingPeriod.endDate,
            })
        } else {
            void loadOverview()
        }
    }, [reportingPeriod.preset, reportingPeriod.startDate, reportingPeriod.endDate, loadOverview, isInvalidRange, showToast, t])

    const activeTab = useMemo(() => {
        if (!overview) return null
        return overview.tabContents[activeTabId] || overview.tabContents[overview.tabs[0]?.id || ''] || null
    }, [activeTabId, overview])

    const isExportDisabled = exporting || loading || isInvalidRange ||
        (reportingPeriod.preset === 'custom' && (!reportingPeriod.startDate || !reportingPeriod.endDate))

    return (
        <div className="page-container sidebar-top-page page-shell-wide business-intelligence-page">
            <div className="business-intelligence-header">
                <PageHeader
                    title={t('businessIntelligence.title')}
                    subtitle={t('businessIntelligence.subtitle')}
                />
                <div className="business-intelligence-header-controls">
                    <FilterInlineGroup>
                        <ReportingPeriodSelector
                            value={reportingPeriod}
                            onChange={setReportingPeriod}
                            disabled={loading || refreshing}
                        />
                    </FilterInlineGroup>
                    <Button
                        variant="secondary"
                        size="sm"
                        iconOnly
                        className="business-intelligence-refresh-button"
                        onClick={() => void loadOverview({ forceRefresh: true })}
                        disabled={refreshing}
                        aria-label={refreshing ? t('businessIntelligence.refreshing') : t('businessIntelligence.refresh')}
                        title={refreshing ? t('businessIntelligence.refreshing') : t('businessIntelligence.refresh')}
                        leadingIcon={<RefreshCw size={15} className={refreshing ? 'business-intelligence-refresh-icon spinning' : 'business-intelligence-refresh-icon'} />}
                    />
                    <Button
                        variant="secondary"
                        size="sm"
                        onClick={() => void handleExport()}
                        disabled={isExportDisabled}
                        leadingIcon={<Download size={15} />}
                    >
                        {exporting ? t('businessIntelligence.exporting') : t('businessIntelligence.download')}
                    </Button>
                </div>
            </div>

            {error ? (
                <div className="conn-banner conn-banner-error">
                    {t('common.connectionError', { error })}
                </div>
            ) : null}

            {loading && (
                <div className="empty-state">
                    <div className="empty-state-title">{t('common.loading')}</div>
                    <div className="empty-state-description">{t('businessIntelligence.loadingDescription')}</div>
                </div>
            )}
            {!loading && overview && activeTab && (
                <>
                    <div className="config-tabs" role="tablist" aria-label={t('businessIntelligence.tabsLabel')}>
                        {overview.tabs.map(tab => (
                            <button
                                key={tab.id}
                                type="button"
                                role="tab"
                                aria-selected={tab.id === activeTab.id}
                                className={`config-tab ${tab.id === activeTab.id ? 'config-tab-active' : ''}`}
                                onClick={() => setActiveTabId(tab.id)}
                            >
                                {getBusinessIntelligenceTabLabel(tab, t)}
                            </button>
                        ))}
                    </div>

                    {activeTab.id === 'executive-summary' && activeTab.executiveSummary ? (
                        <ExecutiveSummaryPanel summary={activeTab.executiveSummary} cards={localizeExecutiveTab(activeTab, t).cards} t={t} />
                    ) : (
                        <GenericTabPanel tab={activeTab} t={t} />
                    )}
                </>
            )}
            {!loading && !(overview && activeTab) && (
                <div className="empty-state">
                    <h3 className="empty-state-title">{t('businessIntelligence.emptyTitle')}</h3>
                    <p className="empty-state-description">{t('businessIntelligence.emptyDescription')}</p>
                </div>
            )}
        </div>
    )
}

/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.opsfactory.businessintelligence.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Metrics Models.
 *
 * @author x00000000
 * @since 2026-05-27
 */
public final class MetricsModels {

    private MetricsModels() {}

    // ── Generic helpers ──

    public record DistributionItem(String label, long count, double percentage) {}

    public record MetricsTrendPoint(String period, double value, long sampleCount) {}

    // ── Executive ──

    public record ProcessHealthScore(String process, double score, String tone) {}

    public record RiskItem(String id, String priority, String title, String impact, String process, String value) {}

    public record ExecutiveMetrics(
        double overallScore, String grade,
        List<ProcessHealthScore> processScores,
        long criticalCount, long warningCount, long attentionCount,
        List<RiskItem> topRisks,
        List<MetricsTrendPoint> monthlyTrend
    ) {}

    // ── SLA ──

    public record SlaPriorityBreakdown(
        String priority, long total,
        double responseRate, double resolutionRate, long breached
    ) {}

    public record SlaRiskEntry(String label, long total, double rate, long breached) {}

    public record SlaMetrics(
        double overallRate, double responseRate, double resolutionRate,
        long breachedCount,
        double avgResponseMinutes, double avgResolutionMinutes,
        double p90ResponseMinutes, double p90ResolutionMinutes,
        List<SlaPriorityBreakdown> priorityBreakdown,
        List<SlaRiskEntry> topCategoryRisks, List<SlaRiskEntry> topResolverRisks
    ) {}

    // ── Incidents ──

    public record IncidentMetrics(
        long totalCount, long p1p2Count, long openCount,
        double slaRate, double mttrHours, double p1p2MttrHours,
        List<DistributionItem> priorityDistribution,
        List<DistributionItem> categoryDistribution
    ) {}

    // ── Changes ──

    public record ChangeMetrics(
        long totalCount, double successRate,
        long emergencyCount, long incidentCausedCount,
        List<DistributionItem> typeDistribution,
        List<DistributionItem> categoryDistribution,
        List<DistributionItem> riskLevelDistribution
    ) {}

    // ── Requests ──

    public record SlaGroupBreakdown(String label, long total, double responseRate,
                                     double resolutionRate, double overallRate, long breached) {}

    public record RequestMetrics(
        long totalCount, long fulfilledCount,
        double slaRate, double avgCsat, double avgFulfillmentHours,
        List<DistributionItem> typeDistribution,
        List<DistributionItem> deptDistribution,
        List<SlaGroupBreakdown> slaByCatalog,
        List<SlaGroupBreakdown> slaByPriority,
        List<SlaGroupBreakdown> slaByDepartment
    ) {}

    // ── Problems ──

    public record ProblemMetrics(
        long totalCount, long closedCount,
        double closureRate, double rcaRate, long knownErrorCount,
        List<DistributionItem> statusDistribution,
        List<DistributionItem> rootCauseCategoryDistribution
    ) {}

    // ── Cross-Process ──

    public record CrossProcessMetrics(
        double changeCausedIncidentRate, long p1p2Within48h,
        double requestIncidentRatio, double fragilityScore,
        List<MetricsTrendPoint> changeIncidentTrend,
        List<DistributionItem> techDebtByCi
    ) {}

    // ── Workforce ──

    public record PersonMetricsSummary(
        String name,
        int incidentCount, double avgResolutionHours, double incidentSlaRate,
        int changeCount, double changeSuccessRate,
        int requestCount, double avgFulfillmentHours, double requestSlaRate, double avgCsat,
        int problemCount, double permanentFixRate
    ) {}

    public record WorkforceMetrics(
        double avgThroughput, long backlog,
        double avgDeliveryHours, double overallSlaRate,
        double avgChangeSpeedHours, double firstTimeSuccessRate,
        double avgSatisfaction, double problemFixRate,
        List<PersonMetricsSummary> persons
    ) {}

    // ── Data Query ──

    public record FilterSpec(String field, String operator, Object value) {}

    public record AggregateSpec(String metric, String field, String value, String groupBy) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DataQueryRequest(
        List<FilterSpec> filters,
        List<String> fields,
        @JsonProperty("sort_by") String sortBy,
        @JsonProperty("sort_order") String sortOrder,
        Integer limit,
        AggregateSpec aggregate
    ) {}

    public record QueryResult(int totalMatched, int returned, List<Map<String, String>> rows) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ComputeResult(
        String metric,
        Object result,
        String groupBy,
        Map<String, Object> groups,
        int totalRows
    ) {}

    // ── Lineage ──

    public record TicketRef(String id, String domain) {}

    public record RelatedTicket(String id, String domain, String relationType, String confidence) {}

    public record LineageResult(TicketRef source, List<RelatedTicket> related) {}

    // ── Trends ──

    public record TrendPoint(String period, Object value, int count) {}

    public record TrendResult(String domain, String metric, String interval, List<TrendPoint> dataPoints) {}
}

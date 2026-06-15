/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.opsfactory.businessintelligence.service;

import com.huawei.opsfactory.businessintelligence.config.BusinessIntelligenceRuntimeProperties;
import com.huawei.opsfactory.businessintelligence.datasource.BiDataProvider;
import com.huawei.opsfactory.businessintelligence.datasource.BiRawData;
import com.huawei.opsfactory.businessintelligence.model.BiColumns;
import com.huawei.opsfactory.businessintelligence.model.BiModels;
import com.huawei.opsfactory.businessintelligence.model.BiModels.ChartDatum;
import com.huawei.opsfactory.businessintelligence.model.MetricsModels;
import com.huawei.opsfactory.businessintelligence.model.MetricsModels.ChangeMetrics;
import com.huawei.opsfactory.businessintelligence.model.MetricsModels.CrossProcessMetrics;
import com.huawei.opsfactory.businessintelligence.model.MetricsModels.DistributionItem;
import com.huawei.opsfactory.businessintelligence.model.MetricsModels.ExecutiveMetrics;
import com.huawei.opsfactory.businessintelligence.model.MetricsModels.IncidentMetrics;
import com.huawei.opsfactory.businessintelligence.model.MetricsModels.MetricsTrendPoint;
import com.huawei.opsfactory.businessintelligence.model.MetricsModels.PersonMetricsSummary;
import com.huawei.opsfactory.businessintelligence.model.MetricsModels.ProblemMetrics;
import com.huawei.opsfactory.businessintelligence.model.MetricsModels.ProcessHealthScore;
import com.huawei.opsfactory.businessintelligence.model.MetricsModels.RequestMetrics;
import com.huawei.opsfactory.businessintelligence.model.MetricsModels.RiskItem;
import com.huawei.opsfactory.businessintelligence.model.MetricsModels.SlaMetrics;
import com.huawei.opsfactory.businessintelligence.model.MetricsModels.SlaPriorityBreakdown;
import com.huawei.opsfactory.businessintelligence.model.MetricsModels.SlaRiskEntry;
import com.huawei.opsfactory.businessintelligence.model.MetricsModels.AggregateSpec;
import com.huawei.opsfactory.businessintelligence.model.MetricsModels.ComputeResult;
import com.huawei.opsfactory.businessintelligence.model.MetricsModels.DataQueryRequest;
import com.huawei.opsfactory.businessintelligence.model.MetricsModels.FilterSpec;
import com.huawei.opsfactory.businessintelligence.model.MetricsModels.LineageResult;
import com.huawei.opsfactory.businessintelligence.model.MetricsModels.QueryResult;
import com.huawei.opsfactory.businessintelligence.model.MetricsModels.RelatedTicket;
import com.huawei.opsfactory.businessintelligence.model.MetricsModels.TicketRef;
import com.huawei.opsfactory.businessintelligence.model.MetricsModels.TrendPoint;
import com.huawei.opsfactory.businessintelligence.model.MetricsModels.TrendResult;
import com.huawei.opsfactory.businessintelligence.model.MetricsModels.WorkforceMetrics;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Pure-computation metrics service for the 8 ITSM domains.
 * <p>
 * Extracts computation logic from {@link BusinessIntelligenceService} and returns
 * compact metric DTOs from {@link MetricsModels}. Raw data is cached with a
 * configurable TTL (default 5 minutes) to avoid re-parsing Excel on every request.
 */
@Service
/**
 * Business Intelligence Metrics Service.
 *
 * @author x00000000
 * @since 2026-05-27
 */
public class BusinessIntelligenceMetricsService {

    private static final Logger log = LoggerFactory.getLogger(BusinessIntelligenceMetricsService.class);

    private static final long CACHE_TTL_MS = TimeUnit.MINUTES.toMillis(5);

    private record CachedData(BiRawData data, long loadedAt) {
        boolean isExpired() {
            return System.currentTimeMillis() - loadedAt > CACHE_TTL_MS;
        }
    }

    // ── Constants ──────────────────────────────────────────────────────────

    private static final List<String> PRIORITY_ORDER = List.of("P1", "P2", "P3", "P4");

    private static final int MAX_ROWS = 500;
    private static final int DEFAULT_ROWS = 100;
    private static final Set<String> VALID_DOMAINS = Set.of("incidents", "changes", "requests", "problems");
    private static final Set<String> VALID_METRICS = Set.of("count", "avg", "sum", "percentage", "distribution");
    private static final Set<String> VALID_TREND_METRICS = Set.of(
        "count", "avg_resolution_time", "avg_response_time",
        "sla_rate", "p12_count", "csat", "success_rate",
        "p12_avg_resolution_time", "response_sla_rate", "resolution_sla_rate",
        "p12_sla_rate", "incident_caused_count"
    );
    private static final Set<String> VALID_INTERVALS = Set.of("hour", "day", "week", "month");
    private static final Map<String, String> DATE_FIELDS = Map.of(
        "incidents", "opened_at",
        "changes", "opened_at",
        "requests", "opened_at",
        "problems", "opened_at"
    );

    private static final List<String> RESPONSE_CRITERIA_KEYS = List.of("response_sla_min");
    private static final List<String> RESOLUTION_CRITERIA_KEYS = List.of("resolution_sla_min");

    // ── Injected dependencies ──────────────────────────────────────────────

    private final BiDataProvider dataProvider;
    private final BusinessIntelligenceRuntimeProperties runtimeProperties;
    private final AtomicReference<CachedData> rawDataCache = new AtomicReference<>();

    public BusinessIntelligenceMetricsService(BiDataProvider dataProvider,
                                               BusinessIntelligenceRuntimeProperties runtimeProperties) {
        this.dataProvider = dataProvider;
        this.runtimeProperties = runtimeProperties;
    }

    private BiRawData loadCached() {
        CachedData cached = rawDataCache.get();
        if (cached != null && !cached.isExpired() && runtimeProperties.isCacheEnabled()) {
            return cached.data();
        }
        BiRawData fresh = dataProvider.load();
        rawDataCache.set(new CachedData(fresh, System.currentTimeMillis()));
        return fresh;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Public metrics methods — one per ITSM domain
    // ════════════════════════════════════════════════════════════════════════

    // ── 1. Executive ───────────────────────────────────────────────────────

    public ExecutiveMetrics getExecutiveMetrics(String startDate, String endDate) {
        return getExecutiveMetrics(filterByDateRange(loadCached(), startDate, endDate));
    }

    public ExecutiveMetrics getExecutiveMetrics(BiRawData rawData) {

        boolean hasIncidents = !rawData.incidents().isEmpty();
        boolean hasChanges = !rawData.changes().isEmpty();
        boolean hasRequests = !rawData.requests().isEmpty();
        boolean hasProblems = !rawData.problems().isEmpty();

        long incidentSlaTotal = hasIncidents
            ? rawData.incidents().stream().filter(r -> !r.getOrDefault(BiColumns.SLA_COMPLIANT, "").isEmpty()).count()
            : 0;
        double incidentSlaRate = hasIncidents
            ? percentageValue(countByValue(rawData.incidents(), BiColumns.SLA_COMPLIANT, "Yes"),
                              incidentSlaTotal > 0 ? incidentSlaTotal : rawData.incidents().size())
            : 0;
        double incidentMttrHours = hasIncidents
            ? average(rawData.incidents(), BiColumns.RESOLUTION_TIME_M) / 60.0
            : 0;
        double changeSuccessRate = hasChanges
            ? percentageValue(countByValue(rawData.changes(), BiColumns.SUCCESS, "Successful"), rawData.changes().size())
            : 0;
        double changeIncidentRate = hasChanges
            ? percentageValue(rawData.changes().stream()
                .filter(row -> !clean(row.get(BiColumns.INCIDENT_CAUSED)).isEmpty()).count(),
                rawData.changes().size())
            : 0;
        double requestSlaRate = hasRequests
            ? computeRequestSlaRate(rawData.requests(), rawData.requestSlaCriteria())
            : 0;
        double requestCsat = hasRequests
            ? average(rawData.requests(), BiColumns.SATISFACTION_SCORE)
            : 0;
        long problemClosedCount = rawData.problems().stream()
            .filter(row -> matchesAny(clean(row.get(BiColumns.STATUS)), List.of("Resolved", "Closed")))
            .count();
        double problemClosureRate = hasProblems
            ? percentageValue(problemClosedCount, rawData.problems().size())
            : 0;

        long requestOpen = rawData.requests().stream()
            .filter(row -> {
                String status = clean(row.get(BiColumns.STATUS));
                String closeCode = clean(row.getOrDefault(BiColumns.CLOSE_CODE, ""));
                boolean completed = "Fulfilled".equalsIgnoreCase(status)
                    || "Closed".equalsIgnoreCase(status) && "Fulfilled".equalsIgnoreCase(closeCode)
                    || "Cancelled".equalsIgnoreCase(status);
                return !completed;
            })
            .count();
        long problemOpen = rawData.problems().stream()
            .filter(row -> !matchesAny(clean(row.get(BiColumns.STATUS)), List.of("Resolved", "Closed")))
            .count();
        double backlogRate = (hasProblems || hasRequests)
            ? percentageValue(problemOpen + requestOpen, rawData.problems().size() + rawData.requests().size())
            : 0;

        double incidentHealth = hasIncidents ? weightedAverage(List.of(
            scoreHigherBetter(incidentSlaRate, 0.95, 0.85),
            scoreLowerBetter(incidentMttrHours, 12, 24),
            scoreLowerBetter(percentageValue(
                rawData.incidents().stream()
                    .filter(row -> isP1P2(row.get(BiColumns.PRIORITY)))
                    .count(),
                rawData.incidents().size()), 0.12, 0.22)
        )) : 0;

        double changeHealth = hasChanges ? weightedAverage(List.of(
            scoreHigherBetter(changeSuccessRate, 0.95, 0.85),
            scoreLowerBetter(changeIncidentRate, 0.05, 0.12)
        )) : 0;

        double requestHealth = hasRequests ? weightedAverage(List.of(
            scoreHigherBetter(requestSlaRate, 0.9, 0.75),
            scoreHigherBetter(requestCsat / 5.0, 0.82, 0.7)
        )) : 0;

        double problemHealth = hasProblems ? weightedAverage(List.of(
            scoreHigherBetter(problemClosureRate, 0.75, 0.55),
            scoreLowerBetter(backlogRate, 0.25, 0.45)
        )) : 0;

        double overallScore = weightedScoreFiltered(
            Map.of(
                "incident", incidentHealth,
                "change", changeHealth,
                "request", requestHealth,
                "problem", problemHealth
            ),
            hasIncidents, hasChanges, hasRequests, hasProblems
        );

        String grade = gradeForScore(overallScore);

        List<ProcessHealthScore> processScores = List.of(
            new ProcessHealthScore("incident", incidentHealth, toneFromNormalizedScore(incidentHealth)),
            new ProcessHealthScore("change", changeHealth, toneFromNormalizedScore(changeHealth)),
            new ProcessHealthScore("request", requestHealth, toneFromNormalizedScore(requestHealth)),
            new ProcessHealthScore("problem", problemHealth, toneFromNormalizedScore(problemHealth))
        );

        long incidentSlaBreached = countByValue(rawData.incidents(), BiColumns.SLA_COMPLIANT, "No");
        long changeFailures = rawData.changes().stream()
            .filter(row -> !"Successful".equalsIgnoreCase(clean(row.get(BiColumns.SUCCESS)))).count();

        List<RiskItem> risks = buildExecutiveRiskItems(
            incidentSlaBreached, changeFailures, requestOpen, problemOpen,
            changeIncidentRate, requestCsat, problemClosureRate, requestSlaRate
        );

        long criticalCount = risks.stream().filter(r -> "Critical".equals(r.priority())).count();
        long warningCount = risks.stream().filter(r -> "Warning".equals(r.priority())).count();
        long attentionCount = risks.stream().filter(r -> "Attention".equals(r.priority())).count();

        Map<YearMonth, List<Map<String, String>>> incidentsByMonth = groupByMonth(rawData.incidents(), BiColumns.BEGIN_DATE);
        Map<YearMonth, List<Map<String, String>>> changesByMonth = groupByMonth(rawData.changes(), BiColumns.REQUESTED_DATE);
        Map<YearMonth, List<Map<String, String>>> requestsByMonth = groupByMonth(rawData.requests(), BiColumns.REQUESTED_DATE);
        Map<YearMonth, List<Map<String, String>>> problemsByMonth = groupByMonth(rawData.problems(), BiColumns.LOGGED_DATE);

        List<YearMonth> allMonths = new ArrayList<>();
        allMonths.addAll(incidentsByMonth.keySet());
        allMonths.addAll(changesByMonth.keySet());
        allMonths.addAll(requestsByMonth.keySet());
        allMonths.addAll(problemsByMonth.keySet());

        List<MetricsTrendPoint> monthlyTrend = allMonths.stream()
            .distinct()
            .sorted()
            .map(month -> {
                List<Map<String, String>> monthIncidents = incidentsByMonth.getOrDefault(month, List.of());
                List<Map<String, String>> monthChanges = changesByMonth.getOrDefault(month, List.of());
                List<Map<String, String>> monthRequests = requestsByMonth.getOrDefault(month, List.of());
                List<Map<String, String>> monthProblems = problemsByMonth.getOrDefault(month, List.of());
                long monthSlaTotal = monthIncidents.stream()
                    .filter(r -> !r.getOrDefault(BiColumns.SLA_COMPLIANT, "").isEmpty()).count();
                double monthSlaRate = percentageValue(countByValue(monthIncidents, BiColumns.SLA_COMPLIANT, "Yes"),
                    monthSlaTotal > 0 ? monthSlaTotal : monthIncidents.size());
                double score = weightedScore(Map.of(
                    "incident", weightedAverage(List.of(
                        scoreHigherBetter(monthSlaRate, 0.95, 0.85),
                        scoreLowerBetter(average(monthIncidents, BiColumns.RESOLUTION_TIME_M) / 60.0, 12, 24)
                    )),
                    "change", weightedAverage(List.of(
                        scoreHigherBetter(percentageValue(countByValue(monthChanges, BiColumns.SUCCESS, "Successful"), monthChanges.size()), 0.95, 0.85),
                        scoreLowerBetter(percentageValue(monthChanges.stream()
                            .filter(row -> !clean(row.get(BiColumns.INCIDENT_CAUSED)).isEmpty()).count(),
                            monthChanges.size()), 0.05, 0.12)
                    )),
                    "request", weightedAverage(List.of(
                        scoreHigherBetter(computeRequestSlaRate(monthRequests, rawData.requestSlaCriteria()), 0.9, 0.75),
                        scoreHigherBetter(average(monthRequests, BiColumns.SATISFACTION_SCORE) / 5.0, 0.82, 0.7)
                    )),
                    "problem", scoreHigherBetter(percentageValue(
                        monthProblems.stream()
                            .filter(row -> matchesAny(clean(row.get(BiColumns.STATUS)), List.of("Resolved", "Closed")))
                            .count(),
                        monthProblems.size()), 0.75, 0.55)
                ));
                long p1p2Count = monthIncidents.stream()
                    .filter(row -> isP1P2(row.get(BiColumns.PRIORITY))).count();
                return new MetricsTrendPoint(month.toString(), score, p1p2Count);
            })
            .toList();

        log.debug("Computed executive metrics overallScore={} grade={}", overallScore, grade);

        return new ExecutiveMetrics(
            overallScore, grade, processScores,
            criticalCount, warningCount, attentionCount,
            risks, monthlyTrend
        );
    }

    // ── 2. SLA ─────────────────────────────────────────────────────────────

    public SlaMetrics getSlaMetrics(String startDate, String endDate) {
        return getSlaMetrics(filterByDateRange(loadCached(), startDate, endDate));
    }

    public SlaMetrics getSlaMetrics(BiRawData rawData) {
        List<IncidentSlaRecord> slaRecords = buildIncidentSlaRecords(rawData);

        long totalRecords = slaRecords.size();
        long overallMetCount = slaRecords.stream().filter(IncidentSlaRecord::overallMet).count();
        long responseMetCount = slaRecords.stream().filter(IncidentSlaRecord::responseMet).count();
        long resolutionMetCount = slaRecords.stream().filter(IncidentSlaRecord::resolutionMet).count();
        long breachedCount = totalRecords - overallMetCount;

        double overallRate = percentageValue(overallMetCount, totalRecords);
        double responseRate = percentageValue(responseMetCount, totalRecords);
        double resolutionRate = percentageValue(resolutionMetCount, totalRecords);

        double avgResponseMinutes = slaRecords.stream()
            .mapToDouble(IncidentSlaRecord::responseMinutes)
            .average().orElse(0);
        double avgResolutionMinutes = slaRecords.stream()
            .mapToDouble(IncidentSlaRecord::resolutionMinutes)
            .average().orElse(0);

        double p90ResponseMinutes = percentile(
            slaRecords.stream().map(IncidentSlaRecord::responseMinutes).toList(), 0.9);
        double p90ResolutionMinutes = percentile(
            slaRecords.stream().map(IncidentSlaRecord::resolutionMinutes).toList(), 0.9);

        List<SlaPriorityBreakdown> priorityBreakdown = PRIORITY_ORDER.stream()
            .map(priority -> {
                List<IncidentSlaRecord> filtered = slaRecords.stream()
                    .filter(record -> priority.equalsIgnoreCase(record.priority()))
                    .toList();
                if (filtered.isEmpty()) return null;
                long met = filtered.stream().filter(IncidentSlaRecord::overallMet).count();
                long respMet = filtered.stream().filter(IncidentSlaRecord::responseMet).count();
                long resMet = filtered.stream().filter(IncidentSlaRecord::resolutionMet).count();
                long breached = filtered.stream().filter(IncidentSlaRecord::anyBreached).count();
                return new SlaPriorityBreakdown(
                    priority, filtered.size(),
                    percentageValue(respMet, filtered.size()),
                    percentageValue(resMet, filtered.size()),
                    breached
                );
            })
            .filter(Objects::nonNull)
            .toList();

        List<SlaRiskEntry> topCategoryRisks = rankSlaRisksAsEntries(slaRecords, IncidentSlaRecord::category);
        List<SlaRiskEntry> topResolverRisks = rankSlaRisksAsEntries(slaRecords, IncidentSlaRecord::resolver);

        log.debug("Computed SLA metrics overallRate={} breachedCount={}", overallRate, breachedCount);

        return new SlaMetrics(
            overallRate, responseRate, resolutionRate,
            breachedCount,
            avgResponseMinutes, avgResolutionMinutes,
            p90ResponseMinutes, p90ResolutionMinutes,
            priorityBreakdown,
            topCategoryRisks, topResolverRisks
        );
    }

    // ── 3. Incident ────────────────────────────────────────────────────────

    public IncidentMetrics getIncidentMetrics(String startDate, String endDate) {
        return getIncidentMetrics(filterByDateRange(loadCached(), startDate, endDate));
    }

    public IncidentMetrics getIncidentMetrics(BiRawData rawData) {
        List<Map<String, String>> incidents = rawData.incidents();

        long totalCount = incidents.size();
        long p1p2Count = incidents.stream()
            .filter(row -> isP1P2(row.get(BiColumns.PRIORITY)))
            .count();
        long openCount = incidents.stream()
            .filter(row -> !isClosedOrResolved(row.get(BiColumns.ORDER_STATUS)))
            .count();

        long slaMetCount = countByValue(incidents, BiColumns.SLA_COMPLIANT, "Yes");
        long slaTotalCount = incidents.stream()
            .filter(r -> !r.getOrDefault(BiColumns.SLA_COMPLIANT, "").isEmpty()).count();
        double slaRate = percentageValue(slaMetCount, slaTotalCount > 0 ? slaTotalCount : totalCount);

        double mttrHours = average(incidents, BiColumns.RESOLUTION_TIME_M) / 60.0;
        double p1p2MttrHours = incidents.stream()
            .filter(row -> isP1P2(row.get(BiColumns.PRIORITY)))
            .mapToDouble(row -> parseDouble(row.get(BiColumns.RESOLUTION_TIME_M)))
            .filter(v -> v > 0)
            .average().orElse(0) / 60.0;

        List<DistributionItem> priorityDistribution = toDistributionItems(topCounts(incidents, BiColumns.PRIORITY, 4), totalCount);
        List<DistributionItem> categoryDistribution = toDistributionItems(topCounts(incidents, BiColumns.CATEGORY, 8), totalCount);

        log.debug("Computed incident metrics totalCount={} slaRate={}", totalCount, slaRate);

        return new IncidentMetrics(
            totalCount, p1p2Count, openCount,
            slaRate, mttrHours, p1p2MttrHours,
            priorityDistribution, categoryDistribution
        );
    }

    // ── 4. Change ──────────────────────────────────────────────────────────

    public ChangeMetrics getChangeMetrics(String startDate, String endDate) {
        return getChangeMetrics(filterByDateRange(loadCached(), startDate, endDate));
    }

    public ChangeMetrics getChangeMetrics(BiRawData rawData) {
        List<Map<String, String>> changes = rawData.changes();

        long totalCount = changes.size();
        long successCount = countByValue(changes, BiColumns.SUCCESS, "Successful");
        double successRate = percentageValue(successCount, totalCount);
        long emergencyCount = countByValue(changes, BiColumns.CHANGE_TYPE, "Emergency");
        long incidentCausedCount = changes.stream()
            .filter(row -> !clean(row.get(BiColumns.INCIDENT_CAUSED)).isEmpty())
            .count();

        List<DistributionItem> typeDistribution = toDistributionItems(topCounts(changes, BiColumns.CHANGE_TYPE, 6), totalCount);
        List<DistributionItem> categoryDistribution = toDistributionItems(topCounts(changes, BiColumns.CATEGORY, 8), totalCount);
        List<DistributionItem> riskLevelDistribution = toDistributionItems(topCounts(changes, BiColumns.RISK, 6), totalCount);

        log.debug("Computed change metrics totalCount={} successRate={}", totalCount, successRate);

        return new ChangeMetrics(
            totalCount, successRate,
            emergencyCount, incidentCausedCount,
            typeDistribution, categoryDistribution, riskLevelDistribution
        );
    }

    // ── 5. Request ─────────────────────────────────────────────────────────

    public RequestMetrics getRequestMetrics(String startDate, String endDate) {
        return getRequestMetrics(filterByDateRange(loadCached(), startDate, endDate));
    }

    public RequestMetrics getRequestMetrics(BiRawData rawData) {
        List<Map<String, String>> requests = rawData.requests();

        long totalCount = requests.size();
        long fulfilledCount = requests.stream()
            .filter(r -> "Fulfilled".equalsIgnoreCase(clean(r.get(BiColumns.CLOSE_CODE))))
            .count();

        List<RequestSlaRecord> slaRecords = buildRequestSlaRecords(rawData);
        long slaMetCount = slaRecords.stream().filter(RequestSlaRecord::overallMet).count();
        double slaRate = percentageValue(slaMetCount, slaRecords.size() > 0 ? slaRecords.size() : totalCount);

        double avgCsat = average(requests, BiColumns.SATISFACTION_SCORE);
        double avgFulfillmentHours = average(requests, BiColumns.REQUEST_RESOLUTION_TIME_M) / 60.0;

        List<DistributionItem> typeDistribution = toDistributionItems(topCounts(requests, BiColumns.REQUEST_TYPE, 6), totalCount);
        List<DistributionItem> deptDistribution = toDistributionItems(topCounts(requests, BiColumns.REQUESTER_DEPT, 8), totalCount);

        List<MetricsModels.SlaGroupBreakdown> slaByCatalog = buildSlaGroupBreakdowns(slaRecords, RequestSlaRecord::catalogItem);
        List<MetricsModels.SlaGroupBreakdown> slaByPriority = buildSlaGroupBreakdowns(slaRecords, RequestSlaRecord::priority);
        List<MetricsModels.SlaGroupBreakdown> slaByDepartment = buildSlaGroupBreakdowns(slaRecords, RequestSlaRecord::requesterDept);

        log.debug("Computed request metrics totalCount={} fulfilledCount={} slaRate={}", totalCount, fulfilledCount, slaRate);

        return new RequestMetrics(
            totalCount, fulfilledCount,
            slaRate, avgCsat, avgFulfillmentHours,
            typeDistribution, deptDistribution,
            slaByCatalog, slaByPriority, slaByDepartment
        );
    }

    // ── 6. Problem ─────────────────────────────────────────────────────────

    public ProblemMetrics getProblemMetrics(String startDate, String endDate) {
        return getProblemMetrics(filterByDateRange(loadCached(), startDate, endDate));
    }

    public ProblemMetrics getProblemMetrics(BiRawData rawData) {
        List<Map<String, String>> problems = rawData.problems();

        long totalCount = problems.size();
        long closedCount = problems.stream()
            .filter(row -> matchesAny(clean(row.get(BiColumns.STATUS)), List.of("Resolved", "Closed")))
            .count();
        double closureRate = percentageValue(closedCount, totalCount);

        long rcaCount = problems.stream()
            .filter(row -> !clean(row.get(BiColumns.ROOT_CAUSE)).isBlank())
            .count();
        double rcaRate = percentageValue(rcaCount, totalCount);

        long knownErrorCount = problems.stream()
            .filter(row -> "true".equalsIgnoreCase(clean(row.get(BiColumns.KNOWN_ERROR))))
            .count();

        List<DistributionItem> statusDistribution = toDistributionItems(topCounts(problems, BiColumns.STATUS, 6), totalCount);
        List<DistributionItem> rootCauseCategoryDistribution = toDistributionItems(
            topCounts(problems, BiColumns.ROOT_CAUSE_CATEGORY, 6), totalCount);

        log.debug("Computed problem metrics totalCount={} closureRate={}", totalCount, closureRate);

        return new ProblemMetrics(
            totalCount, closedCount,
            closureRate, rcaRate, knownErrorCount,
            statusDistribution, rootCauseCategoryDistribution
        );
    }

    // ── 7. Cross-Process ───────────────────────────────────────────────────

    public CrossProcessMetrics getCrossProcessMetrics(String startDate, String endDate) {
        return getCrossProcessMetrics(filterByDateRange(loadCached(), startDate, endDate));
    }

    public CrossProcessMetrics getCrossProcessMetrics(BiRawData rawData) {
        List<Map<String, String>> changes = rawData.changes();
        List<Map<String, String>> incidents = rawData.incidents();
        List<Map<String, String>> problems = rawData.problems();
        List<Map<String, String>> requests = rawData.requests();

        int totalChanges = changes.size();
        int totalIncidents = incidents.size();

        long causedCount = changes.stream()
            .filter(row -> !clean(row.get(BiColumns.INCIDENT_CAUSED)).isEmpty())
            .count();
        double changeCausedIncidentRate = totalChanges > 0 ? (causedCount * 100.0 / totalChanges) : 0;

        long p1p2Within48h = changes.stream()
            .filter(ch -> !clean(ch.get(BiColumns.INCIDENT_CAUSED)).isEmpty())
            .flatMap(ch -> findIncidentsWithin48h(incidents, BiDateUtils.parseDate(ch.get(BiColumns.ACTUAL_END))).stream())
            .map(inc -> inc.get(BiColumns.ORDER_NUMBER))
            .distinct()
            .count();

        double requestIncidentRatio = totalIncidents > 0
            ? (double) requests.size() / totalIncidents
            : 0;

        double changeFailureWeight = (changeCausedIncidentRate / 100.0) * 40;
        double avgAging = problems.stream()
            .filter(row -> !matchesAny(clean(row.get(BiColumns.STATUS)), List.of("Resolved", "Closed")))
            .mapToLong(row -> {
                LocalDateTime logged = BiDateUtils.parseDate(row.get(BiColumns.LOGGED_DATE));
                return logged != null ? java.time.Duration.between(logged, LocalDateTime.now()).toDays() : 0;
            }).average().orElse(0);
        double agingWeight = Math.min(avgAging / 90.0, 1.0) * 30;
        double ratioWeight = Math.min(requestIncidentRatio / 8.0, 1.0) * 30;
        double fragilityScore = Math.round(100 - (changeFailureWeight + agingWeight + ratioWeight));

        Map<String, Long> changeByMonth = new LinkedHashMap<>();
        Map<String, Long> causedByMonth = new LinkedHashMap<>();

        for (Map<String, String> ch : changes) {
            LocalDateTime date = BiDateUtils.parseDate(ch.get(BiColumns.REQUESTED_DATE));
            if (date == null) date = BiDateUtils.parseDate(ch.get(BiColumns.ACTUAL_END));
            if (date == null) continue;
            String month = date.format(DateTimeFormatter.ofPattern("yyyy-MM"));
            changeByMonth.merge(month, 1L, Long::sum);
            LocalDateTime actualEnd = BiDateUtils.parseDate(ch.get(BiColumns.ACTUAL_END));
            long p1p2 = findIncidentsWithin48h(incidents, actualEnd).size();
            causedByMonth.merge(month, p1p2, Long::sum);
        }

        List<MetricsTrendPoint> changeIncidentTrend = new TreeSet<>(changeByMonth.keySet()).stream()
            .map(month -> {
                long total = changeByMonth.getOrDefault(month, 0L);
                long caused = causedByMonth.getOrDefault(month, 0L);
                return new MetricsTrendPoint(month, caused, total);
            })
            .toList();

        Map<String, Long> openByCi = new LinkedHashMap<>();
        for (Map<String, String> row : problems) {
            if (matchesAny(clean(row.get(BiColumns.STATUS)), List.of("Resolved", "Closed"))) continue;
            String ci = defaultLabel(row.get(BiColumns.CI_AFFECTED), "Unknown");
            openByCi.merge(ci, 1L, Long::sum);
        }
        long totalProblems = problems.size();
        List<DistributionItem> techDebtByCi = openByCi.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(10)
            .map(entry -> new DistributionItem(
                entry.getKey(),
                entry.getValue(),
                totalProblems > 0 ? entry.getValue() * 100.0 / totalProblems : 0))
            .toList();

        log.debug("Computed cross-process metrics fragilityScore={} p1p2Within48h={}", fragilityScore, p1p2Within48h);

        return new CrossProcessMetrics(
            changeCausedIncidentRate, p1p2Within48h,
            requestIncidentRatio, fragilityScore,
            changeIncidentTrend, techDebtByCi
        );
    }

    // ── 8. Workforce ───────────────────────────────────────────────────────

    public WorkforceMetrics getWorkforceMetrics(String startDate, String endDate, Integer personLimit) {
        return getWorkforceMetrics(filterByDateRange(loadCached(), startDate, endDate), personLimit);
    }

    public WorkforceMetrics getWorkforceMetrics(BiRawData rawData, Integer personLimit) {
        Map<String, PersonMetrics> allMetrics = collectPersonMetrics(rawData);

        List<PersonMetrics> activeMetrics = allMetrics.values().stream()
            .filter(m -> m.totalCount() > 0)
            .sorted(Comparator.comparingInt(PersonMetrics::totalCount).reversed())
            .toList();

        int activeCount = activeMetrics.size();
        int totalOrders = activeMetrics.stream().mapToInt(PersonMetrics::totalCount).sum();
        double avgThroughput = activeCount > 0 ? (double) totalOrders / activeCount : 0;

        long backlog = rawData.incidents().stream()
            .filter(r -> !matchesAny(clean(r.get(BiColumns.ORDER_STATUS)), List.of("Closed", "Resolved")))
            .count()
            + rawData.requests().stream()
            .filter(r -> !matchesAny(clean(r.get(BiColumns.STATUS)), List.of("Fulfilled", "Closed", "Cancelled")))
            .count();

        double avgDeliveryHours = activeMetrics.stream()
            .filter(m -> m.avgResolutionTime() > 0 || m.avgFulfillmentTime() > 0)
            .mapToDouble(m -> m.avgResolutionTime() > 0 ? m.avgResolutionTime() / 60.0 : m.avgFulfillmentTime() / 60.0)
            .average().orElse(0);

        int incidentSlaYes = activeMetrics.stream().mapToInt(PersonMetrics::incidentSlaYes).sum();
        int incidentSlaTotal = activeMetrics.stream().mapToInt(PersonMetrics::incidentSlaTotal).sum();
        int requestSlaYes = activeMetrics.stream().mapToInt(PersonMetrics::requestSlaYes).sum();
        int requestSlaTotal = activeMetrics.stream().mapToInt(PersonMetrics::requestSlaTotal).sum();
        int totalSlaBase = incidentSlaTotal + requestSlaTotal;
        double overallSlaRate = totalSlaBase > 0
            ? (double) (incidentSlaYes + requestSlaYes) / totalSlaBase
            : 0;

        double avgChangeSpeedHours = activeMetrics.stream()
            .filter(m -> m.changeDurationCount() > 0)
            .mapToDouble(m -> m.totalChangeDuration() / m.changeDurationCount() / 60.0)
            .average().orElse(0);

        int totalChanges = activeMetrics.stream().mapToInt(PersonMetrics::changeCount).sum();
        int totalChangeSuccess = activeMetrics.stream().mapToInt(PersonMetrics::changeSuccessCount).sum();
        int totalBackout = activeMetrics.stream().mapToInt(PersonMetrics::changeBackoutCount).sum();
        double firstTimeSuccessRate = totalChanges > 0
            ? (double) (totalChangeSuccess - totalBackout) / totalChanges
            : 0;

        double avgSatisfaction = activeMetrics.stream()
            .filter(m -> m.satisfactionCount() > 0)
            .mapToDouble(PersonMetrics::avgSatisfaction)
            .average().orElse(0);

        int totalProblems = activeMetrics.stream().mapToInt(PersonMetrics::problemCount).sum();
        int totalFixes = activeMetrics.stream().mapToInt(PersonMetrics::permanentFixCount).sum();
        double problemFixRate = totalProblems > 0 ? (double) totalFixes / totalProblems : 0;

        List<PersonMetricsSummary> persons = activeMetrics.stream()
            .map(m -> new PersonMetricsSummary(
                m.name(),
                m.incidentCount(),
                m.avgResolutionTime() / 60.0,
                m.incidentSlaTotal() > 0 ? (double) m.incidentSlaYes() / m.incidentSlaTotal() : 0,
                m.changeCount(),
                m.changeSuccessRate(),
                m.requestCount(),
                m.avgFulfillmentTime() / 60.0,
                m.requestSlaTotal() > 0 ? (double) m.requestSlaYes() / m.requestSlaTotal() : 0,
                m.avgSatisfaction(),
                m.problemCount(),
                m.permanentFixRate()
            ))
            .toList();

        if (personLimit != null && personLimit > 0 && persons.size() > personLimit) {
            persons = persons.subList(0, personLimit);
        }

        return new WorkforceMetrics(
            avgThroughput, backlog,
            avgDeliveryHours, overallSlaRate,
            avgChangeSpeedHours, firstTimeSuccessRate,
            avgSatisfaction, problemFixRate,
            persons
        );
    }

    // ── Data Query ─────────────────────────────────────────────────────────

    public Object query(String domain, DataQueryRequest request) {
        List<Map<String, String>> rows = getDomainData(domain);

        List<FilterSpec> filters = request.filters() != null ? request.filters() : List.of();
        List<Map<String, String>> filtered = applyFilters(rows, filters);

        AggregateSpec aggregate = request.aggregate();
        if (aggregate != null) {
            return compute(aggregate, filtered);
        }

        List<Map<String, String>> sorted = sortRows(filtered, request.sortBy(), request.sortOrder());
        int limit = request.limit() != null ? Math.min(request.limit(), MAX_ROWS) : DEFAULT_ROWS;
        List<Map<String, String>> paged = sorted.subList(0, Math.min(limit, sorted.size()));
        List<Map<String, String>> selected = selectFields(paged, request.fields());

        return new QueryResult(filtered.size(), selected.size(), selected);
    }

    private ComputeResult compute(AggregateSpec spec, List<Map<String, String>> rows) {
        String metric = spec.metric();
        String field = spec.field() != null ? spec.field() : "";
        String groupBy = spec.groupBy();

        if (groupBy != null && !groupBy.isBlank()) {
            Map<String, List<Map<String, String>>> groups = rows.stream()
                .collect(Collectors.groupingBy(
                    r -> {
                        String v = r.getOrDefault(groupBy, "").trim();
                        return v.isEmpty() ? "(empty)" : v;
                    },
                    LinkedHashMap::new,
                    Collectors.toList()
                ));
            Map<String, Object> groupResults = new LinkedHashMap<>();
            for (var entry : groups.entrySet()) {
                groupResults.put(entry.getKey(), computeSingle(metric, field, spec.value(), entry.getValue()));
            }
            return new ComputeResult(metric, null, groupBy, groupResults, rows.size());
        }

        Object result = computeSingle(metric, field, spec.value(), rows);
        return new ComputeResult(metric, result, null, null, rows.size());
    }

    private Object computeSingle(String metric, String field, String targetValue, List<Map<String, String>> rows) {
        if (rows.isEmpty()) return 0;

        return switch (metric) {
            case "count" -> rows.size();
            case "avg" -> {
                List<Double> vals = rows.stream()
                    .map(r -> parseDouble(r.getOrDefault(field, "")))
                    .filter(v -> v > 0)
                    .toList();
                yield vals.isEmpty() ? 0 : Math.round(vals.stream().mapToDouble(d -> d).average().orElse(0) * 100.0) / 100.0;
            }
            case "sum" -> Math.round(rows.stream()
                .mapToDouble(r -> parseDouble(r.getOrDefault(field, "")))
                .sum() * 100.0) / 100.0;
            case "percentage" -> {
                if (targetValue == null || targetValue.isBlank()) {
                    throw new IllegalArgumentException("'value' is required for percentage metric");
                }
                long matched = rows.stream()
                    .filter(r -> r.getOrDefault(field, "").trim().equalsIgnoreCase(targetValue.trim()))
                    .count();
                yield Math.round((double) matched / rows.size() * 1000.0) / 10.0;
            }
            case "distribution" -> {
                Map<String, Long> counter = rows.stream()
                    .collect(Collectors.groupingBy(
                        r -> {
                            String v = r.getOrDefault(field, "").trim();
                            return v.isEmpty() ? "(empty)" : v;
                        },
                        LinkedHashMap::new,
                        Collectors.counting()
                    ));
                int total = rows.size();
                yield counter.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .map(e -> Map.of("value", e.getKey(), "count", e.getValue(),
                        "percentage", Math.round((double) e.getValue() / total * 1000.0) / 10.0))
                    .toList();
            }
            default -> 0;
        };
    }

    // ── Lineage ────────────────────────────────────────────────────────────

    public LineageResult traceLineage(String domain, String ticketId) {
        BiRawData raw = loadCached();
        String normalizedId = ticketId.trim().toLowerCase(Locale.ROOT);

        List<Map<String, String>> sourceData = getDomainData(raw, domain);
        Map<String, String> source = findById(sourceData, normalizedId, idField(domain));

        List<RelatedTicket> related = new ArrayList<>();
        if (source == null) {
            return new LineageResult(new TicketRef(ticketId, domain), related);
        }

        switch (domain) {
            case "incidents" -> traceFromIncident(source, normalizedId, raw, related);
            case "changes" -> traceFromChange(source, normalizedId, raw, related);
            case "problems" -> traceFromProblem(source, raw, related);
            case "requests" -> traceFromRequest(source, raw, related);
        }

        Set<String> seen = new LinkedHashSet<>();
        List<RelatedTicket> unique = new ArrayList<>();
        for (RelatedTicket r : related) {
            String key = r.domain() + ":" + r.id() + ":" + r.relationType();
            if (seen.add(key)) {
                unique.add(r);
            }
        }

        return new LineageResult(new TicketRef(ticketId, domain), unique);
    }

    private void traceFromIncident(Map<String, String> src, String srcId, BiRawData raw, List<RelatedTicket> related) {
        String ci = src.getOrDefault(BiColumns.CI_AFFECTED, "").trim().toLowerCase(Locale.ROOT);
        String category = src.getOrDefault(BiColumns.CATEGORY, "").trim().toLowerCase(Locale.ROOT);
        LocalDateTime beginDate = BiDateUtils.parseDate(src.get(BiColumns.BEGIN_DATE));

        for (var c : raw.changes()) {
            String changeCi = c.getOrDefault(BiColumns.CI_AFFECTED, "").trim().toLowerCase(Locale.ROOT);
            if (!ci.isEmpty() && ci.equals(changeCi)) {
                related.add(new RelatedTicket(c.get(BiColumns.CHANGE_NUMBER), "changes", "same_ci", "high"));
            }
            if (beginDate != null) {
                LocalDateTime actualEnd = BiDateUtils.parseDate(c.get(BiColumns.ACTUAL_END));
                if (actualEnd != null && Math.abs(ChronoUnit.SECONDS.between(beginDate, actualEnd)) < 48 * 3600) {
                    related.add(new RelatedTicket(c.get(BiColumns.CHANGE_NUMBER), "changes", "time_window_48h", "medium"));
                }
            }
        }

        for (var p : raw.problems()) {
            String problemCi = p.getOrDefault(BiColumns.CI_AFFECTED, "").trim().toLowerCase(Locale.ROOT);
            if (!ci.isEmpty() && ci.equals(problemCi)) {
                related.add(new RelatedTicket(p.get(BiColumns.PROBLEM_NUMBER), "problems", "same_ci", "high"));
            }
            String problemCat = p.getOrDefault(BiColumns.CATEGORY, "").trim().toLowerCase(Locale.ROOT);
            if (!category.isEmpty() && category.equals(problemCat)) {
                related.add(new RelatedTicket(p.get(BiColumns.PROBLEM_NUMBER), "problems", "same_category", "medium"));
            }
        }
    }

    private void traceFromChange(Map<String, String> src, String srcId, BiRawData raw, List<RelatedTicket> related) {
        String ci = src.getOrDefault(BiColumns.CI_AFFECTED, "").trim().toLowerCase(Locale.ROOT);
        LocalDateTime actualEnd = BiDateUtils.parseDate(src.get(BiColumns.ACTUAL_END));
        String incidentIdsValue = src.getOrDefault(BiColumns.INCIDENT_CAUSED, "").trim();
        boolean caused = !incidentIdsValue.isEmpty();

        for (var inc : raw.incidents()) {
            String incOrderNum = inc.getOrDefault(BiColumns.ORDER_NUMBER, "").trim();
            if (!incidentIdsValue.isEmpty() && incidentIdsValue.contains(incOrderNum)) {
                related.add(new RelatedTicket(incOrderNum, "incidents", "referenced_in_change", "high"));
            }
            if (caused && actualEnd != null) {
                LocalDateTime incBegin = BiDateUtils.parseDate(inc.get(BiColumns.BEGIN_DATE));
                if (incBegin != null) {
                    long diff = ChronoUnit.SECONDS.between(actualEnd, incBegin);
                    if (diff > 0 && diff < 48 * 3600) {
                        related.add(new RelatedTicket(incOrderNum, "incidents", "caused_incident_48h", "high"));
                    }
                }
            }
            String incCi = inc.getOrDefault(BiColumns.CI_AFFECTED, "").trim().toLowerCase(Locale.ROOT);
            if (!ci.isEmpty() && ci.equals(incCi)) {
                related.add(new RelatedTicket(incOrderNum, "incidents", "same_ci", "medium"));
            }
        }

        for (var p : raw.problems()) {
            String problemCi = p.getOrDefault(BiColumns.CI_AFFECTED, "").trim().toLowerCase(Locale.ROOT);
            if (!ci.isEmpty() && ci.equals(problemCi)) {
                related.add(new RelatedTicket(p.get(BiColumns.PROBLEM_NUMBER), "problems", "same_ci", "medium"));
            }
        }
    }

    private void traceFromProblem(Map<String, String> src, BiRawData raw, List<RelatedTicket> related) {
        String ci = src.getOrDefault(BiColumns.CI_AFFECTED, "").trim().toLowerCase(Locale.ROOT);
        String category = src.getOrDefault(BiColumns.CATEGORY, "").trim().toLowerCase(Locale.ROOT);

        for (var inc : raw.incidents()) {
            String incCi = inc.getOrDefault(BiColumns.CI_AFFECTED, "").trim().toLowerCase(Locale.ROOT);
            if (!ci.isEmpty() && ci.equals(incCi)) {
                related.add(new RelatedTicket(inc.get(BiColumns.ORDER_NUMBER), "incidents", "same_ci", "high"));
            }
            String incCat = inc.getOrDefault(BiColumns.CATEGORY, "").trim().toLowerCase(Locale.ROOT);
            if (!category.isEmpty() && category.equals(incCat)) {
                related.add(new RelatedTicket(inc.get(BiColumns.ORDER_NUMBER), "incidents", "same_category", "medium"));
            }
        }

        for (var c : raw.changes()) {
            String changeCi = c.getOrDefault(BiColumns.CI_AFFECTED, "").trim().toLowerCase(Locale.ROOT);
            if (!ci.isEmpty() && ci.equals(changeCi)) {
                related.add(new RelatedTicket(c.get(BiColumns.CHANGE_NUMBER), "changes", "same_ci", "medium"));
            }
        }
    }

    private void traceFromRequest(Map<String, String> src, BiRawData raw, List<RelatedTicket> related) {
        String ci = src.getOrDefault(BiColumns.CI_AFFECTED, "").trim().toLowerCase(Locale.ROOT);
        if (ci.isEmpty()) return;

        for (var inc : raw.incidents()) {
            String incCi = inc.getOrDefault(BiColumns.CI_AFFECTED, "").trim().toLowerCase(Locale.ROOT);
            if (ci.equals(incCi)) {
                related.add(new RelatedTicket(inc.get(BiColumns.ORDER_NUMBER), "incidents", "same_ci", "medium"));
            }
        }
    }

    // ── Trends ─────────────────────────────────────────────────────────────

    public TrendResult getTrends(String domain, String metric, String interval, String timeRange,
                                  String startDate, String endDate) {
        return getTrends(loadCached(), domain, metric, interval, timeRange, startDate, endDate);
    }

    public TrendResult getTrends(BiRawData rawData, String domain, String metric, String interval,
                                  String timeRange, String startDate, String endDate) {
        if (!VALID_DOMAINS.contains(domain)) {
            throw new IllegalArgumentException("Invalid domain: " + domain);
        }
        if (!VALID_TREND_METRICS.contains(metric)) {
            throw new IllegalArgumentException("Invalid metric: " + metric);
        }
        if (!VALID_INTERVALS.contains(interval)) {
            throw new IllegalArgumentException("Invalid interval: " + interval);
        }

        List<Map<String, String>> rows = getDomainData(rawData, domain);
        String dateField = DATE_FIELDS.getOrDefault(domain, "");

        Map<String, Double> responseCriteria = null;
        Map<String, Double> resolutionCriteria = null;
        if ("incidents".equals(domain) && Set.of("response_sla_rate", "resolution_sla_rate").contains(metric)) {
            var slaCriteria = rawData.incidentSlaCriteria();
            responseCriteria = buildIncidentCriteriaMap(slaCriteria,
                RESPONSE_CRITERIA_KEYS);
            resolutionCriteria = buildIncidentCriteriaMap(slaCriteria,
                RESOLUTION_CRITERIA_KEYS);
        }

        LocalDateTime rangeStart = null;
        LocalDateTime rangeEnd = null;
        if (timeRange != null) {
            LocalDateTime now = LocalDateTime.now();
            rangeEnd = now;
            rangeStart = switch (timeRange) {
                case "last_7d" -> now.minusDays(7);
                case "last_30d" -> now.minusDays(30);
                case "last_90d" -> now.minusDays(90);
                default -> null;
            };
        }
        if (startDate != null) rangeStart = BiDateUtils.parseDate(startDate);
        if (endDate != null) rangeEnd = BiDateUtils.parseDate(endDate);

        Map<String, List<Map<String, String>>> buckets = new LinkedHashMap<>();
        for (var row : rows) {
            LocalDateTime d = BiDateUtils.parseDate(row.get(dateField));
            if (d == null) continue;
            if (rangeStart != null && d.isBefore(rangeStart)) continue;
            if (rangeEnd != null && d.isAfter(rangeEnd)) continue;
            String key = bucketKey(d, interval);
            buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }

        List<TrendPoint> dataPoints = new ArrayList<>();
        for (var entry : buckets.entrySet()) {
            Object value = computeTrendValue(metric, domain, entry.getValue(), responseCriteria, resolutionCriteria, rawData.requestSlaCriteria());
            dataPoints.add(new TrendPoint(entry.getKey(), value, entry.getValue().size()));
        }

        return new TrendResult(domain, metric, interval, dataPoints);
    }

    private Object computeTrendValue(String metric, String domain, List<Map<String, String>> rows,
                                       Map<String, Double> responseCriteria, Map<String, Double> resolutionCriteria,
                                       List<Map<String, String>> requestSlaCriteria) {
        if (rows.isEmpty()) return 0;

        return switch (metric) {
            case "count" -> rows.size();
            case "avg_resolution_time" -> avgPositive(rows, BiColumns.RESOLUTION_TIME_M);
            case "avg_response_time" -> avgPositive(rows, BiColumns.RESPONSE_TIME_M);
            case "sla_rate" -> {
                if ("requests".equals(domain)) {
                    yield computeRequestSlaRate(rows, requestSlaCriteria);
                }
                long met = rows.stream()
                    .filter(r -> "yes".equalsIgnoreCase(r.getOrDefault(BiColumns.SLA_COMPLIANT, "").trim()))
                    .count();
                yield Math.round((double) met / rows.size() * 100.0) / 1.0;
            }
            case "p12_count" -> rows.stream()
                .filter(r -> isP1P2(r.get(BiColumns.PRIORITY)))
                .count();
            case "csat" -> avgPositive(rows, BiColumns.SATISFACTION_SCORE);
            case "success_rate" -> {
                long met = rows.stream()
                    .filter(r -> "Successful".equalsIgnoreCase(r.getOrDefault(BiColumns.SUCCESS, "").trim()))
                    .count();
                yield Math.round((double) met / rows.size() * 100.0) / 1.0;
            }
            case "p12_avg_resolution_time" -> {
                List<Map<String, String>> p12 = rows.stream()
                    .filter(r -> isP1P2(r.get(BiColumns.PRIORITY)))
                    .toList();
                yield p12.isEmpty() ? 0 : avgPositive(p12, BiColumns.RESOLUTION_TIME_M);
            }
            case "response_sla_rate" -> {
                if (responseCriteria == null) yield 0;
                long met = rows.stream()
                    .filter(r -> {
                        String priority = clean(r.get(BiColumns.PRIORITY));
                        Double target = responseCriteria.get(priority);
                        return target != null && parseDouble(r.get(BiColumns.RESPONSE_TIME_M)) <= target;
                    }).count();
                yield Math.round((double) met / rows.size() * 100.0) / 1.0;
            }
            case "resolution_sla_rate" -> {
                if (resolutionCriteria == null) yield 0;
                long met = rows.stream()
                    .filter(r -> {
                        String priority = clean(r.get(BiColumns.PRIORITY));
                        Double target = resolutionCriteria.get(priority);
                        return target != null && parseDouble(r.get(BiColumns.RESOLUTION_TIME_M)) <= target;
                    }).count();
                yield Math.round((double) met / rows.size() * 100.0) / 1.0;
            }
            case "p12_sla_rate" -> {
                List<Map<String, String>> p12 = rows.stream()
                    .filter(r -> isP1P2(r.get(BiColumns.PRIORITY)))
                    .toList();
                if (p12.isEmpty()) yield 0;
                if ("requests".equals(domain)) {
                    yield computeRequestSlaRate(p12, requestSlaCriteria);
                }
                long met = p12.stream()
                    .filter(r -> "yes".equalsIgnoreCase(r.getOrDefault(BiColumns.SLA_COMPLIANT, "").trim()))
                    .count();
                yield Math.round((double) met / p12.size() * 100.0) / 1.0;
            }
            case "incident_caused_count" -> rows.stream()
                .filter(r -> !clean(r.getOrDefault(BiColumns.INCIDENT_CAUSED, "")).isEmpty())
                .count();
            default -> 0;
        };
    }

    private double avgPositive(List<Map<String, String>> rows, String field) {
        List<Double> vals = rows.stream()
            .map(r -> parseDouble(r.getOrDefault(field, "")))
            .filter(v -> v > 0)
            .toList();
        return vals.isEmpty() ? 0 : Math.round(vals.stream().mapToDouble(d -> d).average().orElse(0) * 10.0) / 10.0;
    }

    private String bucketKey(LocalDateTime d, String interval) {
        return switch (interval) {
            case "hour" -> d.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"));
            case "day" -> d.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            case "week" -> {
                LocalDate startOfWeek = d.toLocalDate().minusDays(d.getDayOfWeek().getValue() - 1);
                yield startOfWeek.format(DateTimeFormatter.ISO_LOCAL_DATE);
            }
            case "month" -> d.format(DateTimeFormatter.ofPattern("yyyy-MM"));
            default -> d.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        };
    }

    // ── Data access helpers ────────────────────────────────────────────────

    private List<Map<String, String>> getDomainData(String domain) {
        return getDomainData(loadCached(), domain);
    }

    private List<Map<String, String>> getDomainData(BiRawData raw, String domain) {
        return switch (domain) {
            case "incidents" -> raw.incidents();
            case "changes" -> raw.changes();
            case "requests" -> raw.requests();
            case "problems" -> raw.problems();
            default -> throw new IllegalArgumentException("Invalid domain: " + domain);
        };
    }

    private String idField(String domain) {
        return switch (domain) {
            case "incidents" -> BiColumns.ORDER_NUMBER;
            case "changes" -> BiColumns.CHANGE_NUMBER;
            case "requests" -> BiColumns.REQUEST_NUMBER;
            case "problems" -> BiColumns.PROBLEM_NUMBER;
            default -> BiColumns.ORDER_NUMBER;
        };
    }

    private Map<String, String> findById(List<Map<String, String>> rows, String id, String idField) {
        for (var r : rows) {
            String val = r.getOrDefault(idField, "").trim().toLowerCase(Locale.ROOT);
            if (val.equals(id)) return r;
        }
        return null;
    }

    // ── Filter / sort / select ─────────────────────────────────────────────

    private List<Map<String, String>> applyFilters(List<Map<String, String>> rows, List<FilterSpec> filters) {
        if (filters == null || filters.isEmpty()) return rows;
        return rows.stream()
            .filter(row -> filters.stream().allMatch(f -> applyFilter(row, f)))
            .toList();
    }

    private boolean applyFilter(Map<String, String> row, FilterSpec f) {
        String cell = row.getOrDefault(f.field(), "").trim().toLowerCase(Locale.ROOT);
        String operator = f.operator() != null ? f.operator() : "equals";
        Object value = f.value();

        return switch (operator) {
            case "equals" -> cell.equals(str(value).toLowerCase(Locale.ROOT));
            case "not_equals" -> !cell.equals(str(value).toLowerCase(Locale.ROOT));
            case "contains" -> cell.contains(str(value).toLowerCase(Locale.ROOT));
            case "starts_with" -> cell.startsWith(str(value).toLowerCase(Locale.ROOT));
            case "greater_than" -> {
                if (isNumeric(cell) && isNumeric(str(value))) {
                    yield parseDouble(cell) > parseDouble(str(value));
                }
                LocalDateTime cellDate = BiDateUtils.parseDate(cell);
                LocalDateTime valueDate = BiDateUtils.parseDate(str(value));
                if (cellDate != null && valueDate != null) {
                    yield cellDate.isAfter(valueDate);
                }
                yield cell.compareTo(str(value)) > 0;
            }
            case "less_than" -> {
                if (isNumeric(cell) && isNumeric(str(value))) {
                    yield parseDouble(cell) < parseDouble(str(value));
                }
                LocalDateTime cellDate = BiDateUtils.parseDate(cell);
                LocalDateTime valueDate = BiDateUtils.parseDate(str(value));
                if (cellDate != null && valueDate != null) {
                    yield cellDate.isBefore(valueDate);
                }
                yield cell.compareTo(str(value)) < 0;
            }
            case "in" -> {
                List<String> vals = value instanceof List<?> list
                    ? list.stream().map(Object::toString).map(String::trim).map(s -> s.toLowerCase(Locale.ROOT)).toList()
                    : List.of(str(value).toLowerCase(Locale.ROOT));
                yield vals.contains(cell);
            }
            default -> true;
        };
    }

    private List<Map<String, String>> sortRows(List<Map<String, String>> rows, String sortBy, String sortOrder) {
        if (sortBy == null || sortBy.isBlank()) return rows;
        boolean desc = !"asc".equalsIgnoreCase(sortOrder);
        Comparator<String> cmp = (a, b) -> {
            boolean aNum = isNumeric(a), bNum = isNumeric(b);
            if (aNum && bNum) return Double.compare(parseDouble(a), parseDouble(b));
            return a.compareTo(b);
        };
        Comparator<Map<String, String>> rowCmp = Comparator.comparing(r -> r.getOrDefault(sortBy, ""), cmp);
        return rows.stream().sorted(desc ? rowCmp.reversed() : rowCmp).toList();
    }

    private List<Map<String, String>> selectFields(List<Map<String, String>> rows, List<String> fields) {
        if (fields == null || fields.isEmpty()) return rows;
        return rows.stream()
            .map(row -> {
                Map<String, String> selected = new LinkedHashMap<>();
                for (String f : fields) {
                    if (row.containsKey(f)) selected.put(f, row.get(f));
                }
                return selected;
            })
            .toList();
    }

    private String str(Object value) {
        return value != null ? value.toString().trim() : "";
    }

    // ════════════════════════════════════════════════════════════════════════
    // Utility methods
    // ════════════════════════════════════════════════════════════════════════

    // ── String / number parsing ────────────────────────────────────────────

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean isP1P2(String priority) {
        String p = clean(priority).toUpperCase();
        return "P1".equals(p) || "P2".equals(p);
    }

    private boolean isClosedOrResolved(String status) {
        return matchesAny(clean(status), List.of("Resolved", "Closed"));
    }

    private String defaultLabel(String value, String fallback) {
        String normalized = clean(value);
        return normalized.isBlank() ? fallback : normalized;
    }

    private boolean isYes(String value) {
        return clean(value).equalsIgnoreCase("Yes");
    }

    private boolean matchesAny(String value, List<String> candidates) {
        return candidates.stream().anyMatch(candidate -> candidate.equalsIgnoreCase(value));
    }

    private double parseDouble(String value) {
        try {
            return Double.parseDouble(clean(value));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private boolean isNumeric(String value) {
        String c = clean(value);
        if (c.isEmpty()) return false;
        try {
            Double.parseDouble(c);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private double parseDurationMinutes(String startStr, String endStr) {
        LocalDateTime start = BiDateUtils.parseDate(startStr);
        LocalDateTime end = BiDateUtils.parseDate(endStr);
        if (start == null || end == null || !end.isAfter(start)) return 0;
        return java.time.Duration.between(start, end).toMinutes();
    }

    private String findFirstValue(Map<String, String> row, List<String> candidateKeys) {
        for (String key : candidateKeys) {
            String value = clean(row.get(key));
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    // ── Formatting ─────────────────────────────────────────────────────────

    private String percentage(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value * 100.0);
    }

    private double percentageValue(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0;
        }
        return numerator * 1.0 / denominator;
    }

    private String formatNumber(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private String formatPeriodLabel(LocalDateTime dateTime, String granularity) {
        if ("monthly".equals(granularity)) {
            return dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM"));
        } else {
            int month = dateTime.getMonthValue();
            int dayOfMonth = dateTime.getDayOfMonth();
            int weekInMonth = (dayOfMonth - 1) / 7 + 1;
            return String.format("%02d月-%d", month, weekInMonth);
        }
    }

    // ── Aggregation ────────────────────────────────────────────────────────

    private double average(List<Map<String, String>> rows, String key) {
        return rows.stream()
            .map(row -> parseDouble(row.get(key)))
            .filter(value -> value > 0)
            .mapToDouble(Double::doubleValue)
            .average()
            .orElse(0);
    }

    private long countByValue(List<Map<String, String>> rows, String key, String value) {
        return rows.stream().filter(row -> clean(row.get(key)).equalsIgnoreCase(value)).count();
    }

    private List<ChartDatum> topCounts(List<Map<String, String>> rows, String key, int limit) {
        return rows.stream()
            .map(row -> defaultLabel(row.get(key), "未标注"))
            .collect(Collectors.groupingBy(Function.identity(), LinkedHashMap::new, Collectors.counting()))
            .entrySet()
            .stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(limit)
            .map(entry -> new ChartDatum(entry.getKey(), entry.getValue()))
            .toList();
    }

    private double percentile(List<Double> values, double p) {
        List<Double> filtered = values.stream().filter(value -> value >= 0).sorted().toList();
        if (filtered.isEmpty()) {
            return 0;
        }
        int index = Math.min(filtered.size() - 1, (int) Math.floor((filtered.size() - 1) * p));
        return filtered.get(index);
    }

    // ── Scoring ────────────────────────────────────────────────────────────

    private double scoreHigherBetter(double value, double goodThreshold, double warningThreshold) {
        if (value >= goodThreshold) {
            return 90 + Math.min((value - goodThreshold) / Math.max(1 - goodThreshold, 0.0001) * 10, 10);
        }
        if (value >= warningThreshold) {
            return 65 + (value - warningThreshold) / Math.max(goodThreshold - warningThreshold, 0.0001) * 25;
        }
        return Math.max(value / Math.max(warningThreshold, 0.0001) * 65, 10);
    }

    private double scoreLowerBetter(double value, double goodThreshold, double warningThreshold) {
        if (value <= goodThreshold) {
            return 95;
        }
        if (value <= warningThreshold) {
            return 65 + (warningThreshold - value) / Math.max(warningThreshold - goodThreshold, 0.0001) * 25;
        }
        return Math.max(20, 65 - Math.min((value - warningThreshold) / Math.max(warningThreshold, 0.0001) * 40, 45));
    }

    private double weightedAverage(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private double weightedScore(Map<String, Double> scores) {
        return scores.getOrDefault("incident", 0.0) * 0.35
            + scores.getOrDefault("change", 0.0) * 0.25
            + scores.getOrDefault("request", 0.0) * 0.2
            + scores.getOrDefault("problem", 0.0) * 0.2;
    }

    private double weightedScoreFiltered(Map<String, Double> scores,
                                          boolean hasIncidents, boolean hasChanges,
                                          boolean hasRequests, boolean hasProblems) {
        double totalWeight = 0;
        double totalScore = 0;
        if (hasIncidents) { totalScore += scores.getOrDefault("incident", 0.0) * 0.35; totalWeight += 0.35; }
        if (hasChanges)   { totalScore += scores.getOrDefault("change", 0.0) * 0.25; totalWeight += 0.25; }
        if (hasRequests)  { totalScore += scores.getOrDefault("request", 0.0) * 0.2; totalWeight += 0.2; }
        if (hasProblems)  { totalScore += scores.getOrDefault("problem", 0.0) * 0.2; totalWeight += 0.2; }
        return totalWeight > 0 ? totalScore / totalWeight * (0.35 + 0.25 + 0.2 + 0.2) : 0;
    }

    private String gradeForScore(double score) {
        if (score >= 85) {
            return "Stable";
        }
        if (score >= 70) {
            return "Watch";
        }
        return "Risk";
    }

    private String toneFromNormalizedScore(double score) {
        if (score >= 85) {
            return "success";
        }
        if (score >= 70) {
            return "warning";
        }
        return "danger";
    }

    // ── Date handling ──────────────────────────────────────────────────────

    private BiRawData filterByDateRange(BiRawData rawData, String startDate, String endDate) {
        if (startDate == null && endDate == null) {
            return rawData;
        }

        LocalDate start = startDate != null ? parseLocalDate(startDate) : null;
        LocalDate end = endDate != null ? parseLocalDate(endDate) : null;

        List<Map<String, String>> filteredIncidents = rawData.incidents().stream()
            .filter(row -> isWithinDateRange(row.get(BiColumns.BEGIN_DATE), start, end))
            .collect(Collectors.toList());

        List<Map<String, String>> filteredIncidentSlaCriteria = rawData.incidentSlaCriteria();

        List<Map<String, String>> filteredChanges = rawData.changes().stream()
            .filter(row -> isWithinDateRange(row.get(BiColumns.REQUESTED_DATE), start, end))
            .collect(Collectors.toList());

        List<Map<String, String>> filteredRequests = rawData.requests().stream()
            .filter(row -> isWithinDateRange(row.get(BiColumns.REQUESTED_DATE), start, end))
            .collect(Collectors.toList());

        List<Map<String, String>> filteredProblems = rawData.problems().stream()
            .filter(row -> isWithinDateRange(row.get(BiColumns.LOGGED_DATE), start, end))
            .collect(Collectors.toList());

        return new BiRawData(filteredIncidents, filteredIncidentSlaCriteria,
            filteredChanges, filteredRequests, filteredProblems, rawData.requestSlaCriteria());
    }

    private boolean isWithinDateRange(String dateStr, LocalDate start, LocalDate end) {
        if (dateStr == null || dateStr.isBlank()) {
            return true;
        }
        LocalDateTime dateTime = BiDateUtils.parseDate(dateStr);
        if (dateTime == null) {
            return true;
        }
        LocalDate date = dateTime.toLocalDate();
        if (start != null && date.isBefore(start)) {
            return false;
        }
        if (end != null && date.isAfter(end)) {
            return false;
        }
        return true;
    }

    private LocalDate parseLocalDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (java.time.format.DateTimeParseException e) {
            log.debug("Failed to parse as LocalDate: {}", value);
        }
        try {
            return LocalDateTime.parse(value).toLocalDate();
        } catch (java.time.format.DateTimeParseException e) {
            log.debug("Failed to parse as LocalDateTime: {}", value);
        }
        try {
            return Instant.parse(value).atZone(ZoneId.systemDefault()).toLocalDate();
        } catch (java.time.format.DateTimeParseException e) {
            log.debug("Failed to parse as Instant: {}", value);
        }
        throw new IllegalArgumentException("Cannot parse date: " + value);
    }

    private Map<YearMonth, List<Map<String, String>>> groupByMonth(List<Map<String, String>> rows, String key) {
        return rows.stream()
            .map(row -> {
                LocalDateTime parsedDate = BiDateUtils.parseDate(row.get(key));
                if (parsedDate == null) {
                    return null;
                }
                return Map.entry(parsedDate, row);
            })
            .filter(Objects::nonNull)
            .collect(Collectors.groupingBy(
                entry -> YearMonth.from(entry.getKey()),
                LinkedHashMap::new,
                Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    }

    // ── SLA computation ────────────────────────────────────────────────────

    public record IncidentSlaRecord(
        String orderNumber,
        String orderName,
        String priority,
        String category,
        String resolver,
        LocalDateTime beginDate,
        double responseMinutes,
        double resolutionMinutes,
        boolean responseMet,
        boolean resolutionMet
    ) {
        public boolean overallMet() {
            return responseMet && resolutionMet;
        }

        public boolean anyBreached() {
            return !overallMet();
        }

        public String violationType() {
            if (!responseMet && !resolutionMet) return "both_breached";
            if (!responseMet) return "response_breached";
            if (!resolutionMet) return "resolution_breached";
            return "met";
        }
    }

    public List<IncidentSlaRecord> buildIncidentSlaRecords(BiRawData rawData) {
        Map<String, Double> responseCriteria = buildIncidentCriteriaMap(
            rawData.incidentSlaCriteria(),
            RESPONSE_CRITERIA_KEYS);
        Map<String, Double> resolutionCriteria = buildIncidentCriteriaMap(
            rawData.incidentSlaCriteria(),
            RESOLUTION_CRITERIA_KEYS);
        return rawData.incidents().stream()
            .map(row -> {
                String priority = clean(row.get(BiColumns.PRIORITY));
                Double responseTarget = responseCriteria.get(priority);
                Double resolutionTarget = resolutionCriteria.get(priority);
                if (priority.isBlank() || responseTarget == null || resolutionTarget == null) {
                    return null;
                }
                double responseMinutes = parseDouble(row.get(BiColumns.RESPONSE_TIME_M));
                double resolutionMinutes = parseDouble(row.get(BiColumns.RESOLUTION_TIME_M));
                return new IncidentSlaRecord(
                    row.get(BiColumns.ORDER_NUMBER),
                    row.get(BiColumns.TITLE),
                    priority,
                    row.get(BiColumns.CATEGORY),
                    row.get(BiColumns.RESOLVER),
                    BiDateUtils.parseDate(row.get(BiColumns.BEGIN_DATE)),
                    responseMinutes,
                    resolutionMinutes,
                    responseMinutes <= responseTarget,
                    resolutionMinutes <= resolutionTarget
                );
            })
            .filter(Objects::nonNull)
            .toList();
    }

    private Map<String, Double> buildIncidentCriteriaMap(List<Map<String, String>> rows,
                                                          List<String> candidateKeys) {
        return rows.stream()
            .filter(row -> !clean(row.get(BiColumns.PRIORITY)).isBlank())
            .collect(Collectors.toMap(
                row -> clean(row.get(BiColumns.PRIORITY)),
                row -> parseDouble(findFirstValue(row, candidateKeys)),
                (left, right) -> right,
                LinkedHashMap::new
            ));
    }

    // ── Request SLA records ────────────────────────────────────────────────

    record RequestSlaRecord(
        String orderNumber,
        String title,
        String priority,
        String category,
        String catalogItem,
        String requesterDept,
        String assignee,
        LocalDateTime openedAt,
        double responseMinutes,
        double resolutionMinutes,
        boolean responseMet,
        boolean resolutionMet,
        double satisfactionScore
    ) {
        boolean overallMet() { return responseMet && resolutionMet; }
        boolean anyBreached() { return !overallMet(); }
        String violationType() {
            if (!responseMet && !resolutionMet) return "both_breached";
            if (!responseMet) return "response_breached";
            if (!resolutionMet) return "resolution_breached";
            return "met";
        }
        boolean isLowSatisfaction() { return satisfactionScore > 0 && satisfactionScore < 3.5; }
    }

    List<RequestSlaRecord> buildRequestSlaRecords(BiRawData rawData) {
        Map<String, Double> responseTargets = buildCriteriaMap(
            rawData.requestSlaCriteria().isEmpty() ? rawData.incidentSlaCriteria() : rawData.requestSlaCriteria(),
            List.of("response_sla_min"));
        Map<String, Double> resolutionTargets = buildCriteriaMap(
            rawData.requestSlaCriteria().isEmpty() ? rawData.incidentSlaCriteria() : rawData.requestSlaCriteria(),
            List.of("resolution_sla_min"));
        return rawData.requests().stream()
            .map(row -> {
                String priority = clean(row.get(BiColumns.PRIORITY));
                Double respTarget = responseTargets.get(priority);
                Double resoTarget = resolutionTargets.get(priority);
                if (priority.isBlank() || respTarget == null || resoTarget == null) return null;
                double responseMinutes = parseDouble(row.get(BiColumns.RESPONSE_TIME_M));
                double resolutionMinutes = parseDouble(row.get(BiColumns.REQUEST_RESOLUTION_TIME_M));
                return new RequestSlaRecord(
                    row.get(BiColumns.REQUEST_NUMBER),
                    row.get(BiColumns.TITLE),
                    priority,
                    row.get(BiColumns.CATEGORY),
                    row.get(BiColumns.REQUEST_TYPE),
                    row.get(BiColumns.REQUESTER_DEPT),
                    row.get(BiColumns.ASSIGNED_TO),
                    BiDateUtils.parseDate(row.get(BiColumns.REQUESTED_DATE)),
                    responseMinutes, resolutionMinutes,
                    responseMinutes <= respTarget,
                    resolutionMinutes <= resoTarget,
                    parseDouble(row.get(BiColumns.SATISFACTION_SCORE))
                );
            })
            .filter(Objects::nonNull)
            .toList();
    }

    private List<MetricsModels.SlaGroupBreakdown> buildSlaGroupBreakdowns(
            List<RequestSlaRecord> records, Function<RequestSlaRecord, String> classifier) {
        return records.stream()
            .collect(Collectors.groupingBy(
                r -> defaultLabel(classifier.apply(r), "未标注"),
                LinkedHashMap::new, Collectors.toList()))
            .entrySet().stream()
            .map(entry -> {
                List<RequestSlaRecord> group = entry.getValue();
                long total = group.size();
                double respRate = percentageValue(group.stream().filter(RequestSlaRecord::responseMet).count(), total);
                double resoRate = percentageValue(group.stream().filter(RequestSlaRecord::resolutionMet).count(), total);
                double overallRate = percentageValue(group.stream().filter(RequestSlaRecord::overallMet).count(), total);
                long breached = group.stream().filter(RequestSlaRecord::anyBreached).count();
                return new MetricsModels.SlaGroupBreakdown(entry.getKey(), total, respRate, resoRate, overallRate, breached);
            })
            .sorted((a, b) -> Double.compare(b.overallRate(), a.overallRate()))
            .toList();
    }

    // ── Person metrics ─────────────────────────────────────────────────────

    record PersonMetrics(
        String name,
        int incidentCount, double totalResolutionTime, int incidentSlaYes, int incidentSlaTotal, int p1p2Count,
        int changeCount, int changeSuccessCount, int changeBackoutCount, int emergencyCount,
        double totalChangeDuration, int changeDurationCount, int changeCausedCount, int highRiskCount,
        int requestCount, double totalFulfillmentTime, int requestSlaYes, int requestSlaTotal,
        double totalSatisfaction, int satisfactionCount,
        int problemCount, int permanentFixCount, int relatedIncidents
    ) {
        double avgResolutionTime() { return incidentCount > 0 ? totalResolutionTime / incidentCount : 0; }
        double avgFulfillmentTime() { return requestCount > 0 ? totalFulfillmentTime / requestCount : 0; }
        double avgSatisfaction() { return satisfactionCount > 0 ? totalSatisfaction / satisfactionCount : 0; }
        int totalCount() { return incidentCount + changeCount + requestCount + problemCount; }
        double slaRate() {
            int t = incidentSlaTotal + requestSlaTotal;
            return t > 0 ? (double)(incidentSlaYes + requestSlaYes) / t : 0;
        }
        double changeSuccessRate() { return changeCount > 0 ? (double)changeSuccessCount / changeCount : 0; }
        double permanentFixRate() { return problemCount > 0 ? (double)permanentFixCount / problemCount : 0; }
    }

/**
     * Person Metrics Builder.
     *
     * @author x00000000
     * @since 2026-05-27
     */
    private class PersonMetricsBuilder {
        int incidentCount; double totalResolutionTime; int incidentSlaYes, incidentSlaTotal, p1p2Count;
        int changeCount, changeSuccessCount, changeBackoutCount, emergencyCount;
        double totalChangeDuration; int changeDurationCount, changeCausedCount, highRiskCount;
        int requestCount; double totalFulfillmentTime; int requestSlaYes, requestSlaTotal;
        double totalSatisfaction; int satisfactionCount;
        int problemCount, permanentFixCount, relatedIncidents;

        void addIncident(double resolutionTime, boolean sla, boolean hasSla, boolean isP1P2) {
            incidentCount++;
            totalResolutionTime += resolutionTime;
            if (hasSla) {
                incidentSlaTotal++;
                if (sla) incidentSlaYes++;
            }
            if (isP1P2) p1p2Count++;
        }

        void addChange(boolean success, boolean backout, boolean emergency, double duration, boolean caused, boolean highRisk) {
            changeCount++;
            if (success) changeSuccessCount++;
            if (backout) changeBackoutCount++;
            if (emergency) emergencyCount++;
            if (duration > 0) { totalChangeDuration += duration; changeDurationCount++; }
            if (caused) changeCausedCount++;
            if (highRisk) highRiskCount++;
        }

        void addRequest(double fulfillmentTime, boolean sla, double satisfaction) {
            requestCount++;
            totalFulfillmentTime += fulfillmentTime;
            requestSlaTotal++;
            if (sla) requestSlaYes++;
            if (satisfaction > 0) { totalSatisfaction += satisfaction; satisfactionCount++; }
        }

        void addProblem(boolean permanentFix, int related) {
            problemCount++;
            if (permanentFix) permanentFixCount++;
            relatedIncidents += related;
        }

        PersonMetrics build(String name) {
            return new PersonMetrics(name,
                incidentCount, totalResolutionTime, incidentSlaYes, incidentSlaTotal, p1p2Count,
                changeCount, changeSuccessCount, changeBackoutCount, emergencyCount,
                totalChangeDuration, changeDurationCount, changeCausedCount, highRiskCount,
                requestCount, totalFulfillmentTime, requestSlaYes, requestSlaTotal,
                totalSatisfaction, satisfactionCount,
                problemCount, permanentFixCount, relatedIncidents);
        }
    }

    private Map<String, PersonMetrics> collectPersonMetrics(BiRawData rawData) {
        Map<String, PersonMetricsBuilder> builders = new LinkedHashMap<>();
        for (var row : rawData.incidents()) {
            String resolver = defaultLabel(row.get(BiColumns.RESOLVER), "未标注");
            var b = builders.computeIfAbsent(resolver, k -> new PersonMetricsBuilder());
            double rt = parseDouble(row.get(BiColumns.RESOLUTION_TIME_M));
            String slaValue = row.getOrDefault(BiColumns.SLA_COMPLIANT, "").trim();
            boolean hasSla = !slaValue.isEmpty();
            boolean sla = "yes".equalsIgnoreCase(slaValue);
            boolean p1p2 = isP1P2(row.get(BiColumns.PRIORITY));
            b.addIncident(rt, sla, hasSla, p1p2);
        }
        for (var row : rawData.changes()) {
            String impl = defaultLabel(row.get(BiColumns.ASSIGNED_TO), "未标注");
            var b = builders.computeIfAbsent(impl, k -> new PersonMetricsBuilder());
            boolean success = "Successful".equalsIgnoreCase(clean(row.get(BiColumns.SUCCESS)));
            String closeCode = clean(row.get(BiColumns.SUCCESS));
            boolean backout = "Backed_out".equalsIgnoreCase(closeCode);
            boolean emergency = "Emergency".equalsIgnoreCase(clean(row.get(BiColumns.CHANGE_TYPE)));
            double dur = parseDurationMinutes(row.get(BiColumns.ACTUAL_START), row.get(BiColumns.ACTUAL_END));
            boolean caused = !clean(row.get(BiColumns.INCIDENT_CAUSED)).isEmpty();
            boolean highRisk = "High".equalsIgnoreCase(clean(row.get(BiColumns.RISK)));
            b.addChange(success, backout, emergency, dur, caused, highRisk);
        }
        var reqSlaSource = rawData.requestSlaCriteria().isEmpty()
            ? rawData.incidentSlaCriteria() : rawData.requestSlaCriteria();
        Map<String, Double> reqRespTargets = buildCriteriaMap(reqSlaSource, List.of("response_sla_min"));
        Map<String, Double> reqResoTargets = buildCriteriaMap(reqSlaSource, List.of("resolution_sla_min"));
        for (var row : rawData.requests()) {
            String assignee = defaultLabel(row.get(BiColumns.ASSIGNED_TO), "未标注");
            var b = builders.computeIfAbsent(assignee, k -> new PersonMetricsBuilder());
            double ft = parseDouble(row.get(BiColumns.REQUEST_RESOLUTION_TIME_M));
            String prio = clean(row.get(BiColumns.PRIORITY));
            String rt = clean(row.get(BiColumns.REQUEST_RESOLUTION_TIME_M));
            String resp = clean(row.get(BiColumns.RESPONSE_TIME_M));
            boolean sla = !rt.isEmpty() && !resp.isEmpty()
                && reqRespTargets.containsKey(prio) && reqResoTargets.containsKey(prio)
                && parseDouble(resp) <= reqRespTargets.get(prio)
                && parseDouble(rt) <= reqResoTargets.get(prio);
            double sat = parseDouble(row.get(BiColumns.SATISFACTION_SCORE));
            b.addRequest(ft, sla, sat);
        }
        for (var row : rawData.problems()) {
            String resolver = defaultLabel(row.get(BiColumns.RESOLVER), "未标注");
            var b = builders.computeIfAbsent(resolver, k -> new PersonMetricsBuilder());
            boolean fix = !clean(row.get(BiColumns.PERMANENT_FIX)).isEmpty();
            int rel = (int) parseDouble(row.get(BiColumns.RELATED_INCIDENT_COUNT));
            b.addProblem(fix, rel);
        }
        Map<String, PersonMetrics> result = new LinkedHashMap<>();
        builders.forEach((name, b) -> result.put(name, b.build(name)));
        return result;
    }

    // ── Cross-process ──────────────────────────────────────────────────────

    private List<Map<String, String>> findIncidentsWithin48h(List<Map<String, String>> incidents,
                                                              LocalDateTime changeEnd) {
        if (changeEnd == null) return List.of();
        return incidents.stream()
            .filter(inc -> {
                LocalDateTime incBegin = BiDateUtils.parseDate(inc.get(BiColumns.BEGIN_DATE));
                if (incBegin == null) return false;
                String priority = clean(inc.get(BiColumns.PRIORITY));
                if (!"P1".equalsIgnoreCase(priority) && !"P2".equalsIgnoreCase(priority)) return false;
                long hours = java.time.Duration.between(changeEnd, incBegin).toHours();
                return hours > 0 && hours <= 48;
            })
            .collect(Collectors.toList());
    }

    private int riskPriorityOrder(BiModels.ExecutiveRisk risk) {
        return switch (risk.priority()) {
            case "Critical" -> 0;
            case "Warning" -> 1;
            default -> 2;
        };
    }

    // ════════════════════════════════════════════════════════════════════════
    // Internal helpers specific to this service
    // ════════════════════════════════════════════════════════════════════════

    private List<RiskItem> buildExecutiveRiskItems(long incidentSlaBreached, long changeFailures,
                                                    long requestOpen, long problemOpen,
                                                    double changeIncidentRate, double requestCsat,
                                                    double problemClosureRate, double requestSlaRate) {
        List<BiModels.ExecutiveRisk> risks = new ArrayList<>();
        if (changeFailures >= 5) {
            risks.add(new BiModels.ExecutiveRisk("change-failure", "Critical",
                "变更失败率偏高", "发布稳定性下降，需优先排查高风险变更。", "change",
                String.valueOf(changeFailures)));
        }
        if (problemClosureRate < 0.55) {
            risks.add(new BiModels.ExecutiveRisk("problem-closure", "Warning",
                "问题关闭率不足", "根因与永久修复积压，风险会持续放大。", "problem",
                percentage(problemClosureRate)));
        }
        if (requestOpen >= 15) {
            risks.add(new BiModels.ExecutiveRisk("request-open", "Warning",
                "未完成请求积压", "履约体验承压，用户等待时间会拉长。", "request",
                String.valueOf(requestOpen)));
        }
        if (changeIncidentRate >= 0.1) {
            risks.add(new BiModels.ExecutiveRisk("change-incident", "Warning",
                "变更引发事件偏多", "上线质量与变更验证存在薄弱点。", "change",
                percentage(changeIncidentRate)));
        }
        if (requestCsat > 0 && requestCsat < 3.8) {
            risks.add(new BiModels.ExecutiveRisk("request-csat", "Attention",
                "请求满意度下滑", "服务体验有波动，建议复盘高频诉求。", "request",
                formatNumber(requestCsat)));
        }
        if (incidentSlaBreached > 0) {
            risks.add(new BiModels.ExecutiveRisk("incident-sla", "Attention",
                "事件 SLA 出现违约", "核心事件响应存在超时情况。", "incident",
                String.valueOf(incidentSlaBreached)));
        }
        if (problemOpen >= 20) {
            risks.add(new BiModels.ExecutiveRisk("problem-open", "Attention",
                "未关闭问题偏多", "问题池持续扩大，会拖累稳定性治理。", "problem",
                String.valueOf(problemOpen)));
        }
        if (requestSlaRate > 0 && requestSlaRate < 0.60) {
            risks.add(new BiModels.ExecutiveRisk("request-sla-critical", "Critical",
                "服务请求 SLA 达成率严重偏低", "履约流程存在系统性问题，需立即排查瓶颈环节。", "request",
                percentage(requestSlaRate)));
        } else if (requestSlaRate > 0 && requestSlaRate < 0.75) {
            risks.add(new BiModels.ExecutiveRisk("request-sla-breach", "Warning",
                "服务请求 SLA 达成率不足", "部分请求类型履约超时，建议关注高频积压目录。", "request",
                percentage(requestSlaRate)));
        }
        return risks.stream()
            .sorted(Comparator.comparingInt(this::riskPriorityOrder))
            .map(risk -> new RiskItem(risk.id(), risk.priority(), risk.title(), risk.impact(), risk.process(), risk.value()))
            .toList();
    }

    private List<SlaRiskEntry> rankSlaRisksAsEntries(List<IncidentSlaRecord> incidents,
                                                      Function<IncidentSlaRecord, String> classifier) {
        return incidents.stream()
            .collect(Collectors.groupingBy(
                record -> defaultLabel(classifier.apply(record), "未标注"),
                LinkedHashMap::new,
                Collectors.toList()))
            .entrySet()
            .stream()
            .map(entry -> {
                List<IncidentSlaRecord> rows = entry.getValue();
                long breachedCount = rows.stream().filter(IncidentSlaRecord::anyBreached).count();
                double rate = percentageValue(rows.stream().filter(IncidentSlaRecord::overallMet).count(), rows.size());
                return Map.entry(
                    breachedCount * 1000 + Math.round((1 - rate) * 100) + rows.size(),
                    new SlaRiskEntry(entry.getKey(), rows.size(), rate, breachedCount)
                );
            })
            .sorted((left, right) -> Long.compare(right.getKey(), left.getKey()))
            .limit(5)
            .map(Map.Entry::getValue)
            .toList();
    }

    private List<DistributionItem> toDistributionItems(List<ChartDatum> chartData, long totalCount) {
        return chartData.stream()
            .map(datum -> new DistributionItem(
                datum.label(),
                (long) datum.value(),
                totalCount > 0 ? datum.value() / totalCount * 100.0 : 0))
            .toList();
    }

    private Map<String, Double> buildCriteriaMap(List<Map<String, String>> criteria,
                                                  List<String> candidateKeys) {
        Map<String, Double> map = new LinkedHashMap<>();
        for (Map<String, String> row : criteria) {
            String priority = row.getOrDefault(BiColumns.PRIORITY, "").trim();
            if (priority.isEmpty()) continue;
            for (String key : candidateKeys) {
                String val = row.getOrDefault(key, "").trim();
                if (!val.isEmpty()) {
                    map.put(priority, parseDouble(val));
                    break;
                }
            }
        }
        return map;
    }

    private double computeRequestSlaRate(List<Map<String, String>> requests,
                                          List<Map<String, String>> requestSlaCriteria) {
        if (requests.isEmpty()) return 0;
        Map<String, Double> responseTargets = buildCriteriaMap(requestSlaCriteria, List.of("response_sla_min"));
        Map<String, Double> resolutionTargets = buildCriteriaMap(requestSlaCriteria, List.of("resolution_sla_min"));
        long met = 0;
        long total = 0;
        for (Map<String, String> r : requests) {
            String priority = clean(r.get(BiColumns.PRIORITY));
            Double respTarget = responseTargets.get(priority);
            Double resoTarget = resolutionTargets.get(priority);
            if (priority.isBlank() || respTarget == null || resoTarget == null) continue;
            total++;
            double respMinutes = parseDouble(r.get(BiColumns.RESPONSE_TIME_M));
            double resoMinutes = parseDouble(r.get(BiColumns.REQUEST_RESOLUTION_TIME_M));
            if (respMinutes <= respTarget && resoMinutes <= resoTarget) {
                met++;
            }
        }
        return Math.round(percentageValue(met, total > 0 ? total : requests.size()) * 100.0) / 1.0;
    }
}

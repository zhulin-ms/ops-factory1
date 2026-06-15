/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.opsfactory.businessintelligence.service;

import com.huawei.opsfactory.businessintelligence.config.BusinessIntelligenceRuntimeProperties;
import com.huawei.opsfactory.businessintelligence.datasource.BiDataProvider;
import com.huawei.opsfactory.businessintelligence.datasource.BiRawData;
import com.huawei.opsfactory.businessintelligence.model.BiColumns;
import com.huawei.opsfactory.businessintelligence.model.BiModels;
import com.huawei.opsfactory.businessintelligence.model.BiModels.ChartConfig;
import com.huawei.opsfactory.businessintelligence.model.BiModels.ChartDatum;
import com.huawei.opsfactory.businessintelligence.model.BiModels.ChartSection;
import com.huawei.opsfactory.businessintelligence.model.BiModels.MetricCard;
import com.huawei.opsfactory.businessintelligence.model.BiModels.Snapshot;
import com.huawei.opsfactory.businessintelligence.model.BiModels.TabContent;
import com.huawei.opsfactory.businessintelligence.model.BiModels.TabMeta;
import com.huawei.opsfactory.businessintelligence.model.MetricsModels;
import com.huawei.opsfactory.businessintelligence.model.MetricsModels.TrendResult;
import com.huawei.opsfactory.businessintelligence.model.MetricsModels.TrendPoint;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
/**
 * Business Intelligence Service.
 *
 * @author x00000000
 * @since 2026-05-27
 */
public class BusinessIntelligenceService {

    private static final Logger log = LoggerFactory.getLogger(BusinessIntelligenceService.class);

    private static final List<TabMeta> TABS = List.of(
        new TabMeta("executive-summary", "执行摘要"),
        new TabMeta("sla-analysis", "SLA分析"),
        new TabMeta("incident-analysis", "事件分析"),
        new TabMeta("change-analysis", "变更分析"),
        new TabMeta("request-analysis", "请求分析"),
        new TabMeta("problem-analysis", "问题分析"),
        new TabMeta("cross-process", "跨流程关联"),
        new TabMeta("workforce", "Workforce")
    );
    private static final List<String> PRIORITY_ORDER = List.of("P1", "P2", "P3", "P4");

    private final BiDataProvider dataProvider;
    private final BusinessIntelligenceRuntimeProperties runtimeProperties;
    private final BusinessIntelligenceMetricsService metricsService;
    private final AtomicReference<Snapshot> cache = new AtomicReference<>();

    private record IncidentBegin(Map<String, String> incident, LocalDateTime beginDate) {
    }

    public BusinessIntelligenceService(BiDataProvider dataProvider,
                                        BusinessIntelligenceRuntimeProperties runtimeProperties,
                                        BusinessIntelligenceMetricsService metricsService) {
        this.dataProvider = dataProvider;
        this.runtimeProperties = runtimeProperties;
        this.metricsService = metricsService;
    }

    public Snapshot getOverview(String startDate, String endDate) {
        // Always refresh when date range is specified
        if (startDate != null || endDate != null) {
            return refresh(startDate, endDate);
        }
        Snapshot snapshot = cache.get();
        if (snapshot != null && runtimeProperties.isCacheEnabled()) {
            log.debug(
                "Returning cached business intelligence snapshot refreshedAt={} tabCount={}",
                snapshot.refreshedAt(),
                snapshot.tabs().size()
            );
            return snapshot;
        }
        return refresh(null, null);
    }

    public synchronized Snapshot refresh(String startDate, String endDate) {
        long startedAt = System.currentTimeMillis();
        try {
            BiRawData rawData = dataProvider.load();
            // Filter data by date range if specified
            BiRawData filteredData = filterByDateRange(rawData, startDate, endDate);
            Snapshot snapshot = buildSnapshot(filteredData, startDate, endDate);
            // Only cache if no date filter is applied
            if (startDate == null && endDate == null) {
                cache.set(snapshot);
            }
            log.info(
                "Refreshed business intelligence snapshot incidents={} incidentSlaCriteria={} changes={} requests={} problems={} tabCount={} startDate={} endDate={} durationMs={}",
                filteredData.incidents().size(),
                filteredData.incidentSlaCriteria().size(),
                filteredData.changes().size(),
                filteredData.requests().size(),
                filteredData.problems().size(),
                snapshot.tabs().size(),
                startDate,
                endDate,
                System.currentTimeMillis() - startedAt
            );
            return snapshot;
        } catch (RuntimeException ex) {
            log.error(
                "Failed to refresh business intelligence snapshot startDate={} endDate={} durationMs={}",
                startDate,
                endDate,
                System.currentTimeMillis() - startedAt,
                ex
            );
            throw ex;
        }
    }

    private LocalDate parseDateInput(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        LocalDateTime dateTime = BiDateUtils.parseDate(value);
        if (dateTime == null) {
            throw new IllegalArgumentException("Invalid date value: " + value);
        }
        return dateTime.toLocalDate();
    }

    private BiRawData filterByDateRange(BiRawData rawData, String startDate, String endDate) {
        if (startDate == null && endDate == null) {
            return rawData;
        }

        LocalDate start = parseDateInput(startDate);
        LocalDate end = parseDateInput(endDate);

        // Filter incidents
        List<Map<String, String>> filteredIncidents = rawData.incidents().stream()
            .filter(row -> isWithinDateRange(row.get(BiColumns.BEGIN_DATE), start, end))
            .collect(Collectors.toList());

        // Filter incident SLA criteria (keep all, as they are reference data)
        List<Map<String, String>> filteredIncidentSlaCriteria = rawData.incidentSlaCriteria();

        // Filter changes
        List<Map<String, String>> filteredChanges = rawData.changes().stream()
            .filter(row -> isWithinDateRange(row.get(BiColumns.REQUESTED_DATE), start, end))
            .collect(Collectors.toList());

        // Filter requests
        List<Map<String, String>> filteredRequests = rawData.requests().stream()
            .filter(row -> isWithinDateRange(row.get(BiColumns.REQUESTED_DATE), start, end))
            .collect(Collectors.toList());

        // Filter problems
        List<Map<String, String>> filteredProblems = rawData.problems().stream()
            .filter(row -> isWithinDateRange(row.get(BiColumns.LOGGED_DATE), start, end))
            .collect(Collectors.toList());

        return new BiRawData(filteredIncidents, filteredIncidentSlaCriteria, filteredChanges, filteredRequests, filteredProblems, rawData.requestSlaCriteria());
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

    public TabContent getTab(String tabId, String granularity) {
        if ("incident-analysis".equals(tabId) && granularity != null) {
            BiRawData rawData = dataProvider.load();
            return buildIncidentAnalysis(rawData, null, null, granularity);
        }
        // For other tabs or default granularity, use cached snapshot
        Snapshot snapshot = getOverview(null, null);
        TabContent content = snapshot.tabContents().get(tabId);
        if (content == null) {
            throw new IllegalArgumentException("Unknown tab: " + tabId);
        }
        log.debug("Resolved business intelligence tab tabId={} label={} granularity={}", tabId, content.label(), granularity);
        return content;
    }

    public TabContent getTab(String tabId) {
        return getTab(tabId, null);
    }

    public byte[] exportCurrentWorkbook() {
        Snapshot snapshot = getOverview(null, null);
        long startedAt = System.currentTimeMillis();
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            for (TabMeta tab : snapshot.tabs()) {
                TabContent content = snapshot.tabContents().get(tab.id());
                if (content == null) {
                    continue;
                }
                XSSFSheet sheet = workbook.createSheet(safeSheetName(content.label()));
                int rowIndex = 0;
                rowIndex = writeTitle(sheet, rowIndex, content.label(), content.description());
                rowIndex = writeCards(sheet, rowIndex, content.cards());
                rowIndex = writeCharts(sheet, rowIndex, content.charts());
                writeTables(sheet, rowIndex, content.tables());
                for (int columnIndex = 0; columnIndex < 8; columnIndex++) {
                    sheet.autoSizeColumn(columnIndex);
                }
            }
            workbook.write(outputStream);
            byte[] bytes = outputStream.toByteArray();
            log.info(
                "Exported business intelligence workbook refreshedAt={} tabCount={} byteSize={} durationMs={}",
                snapshot.refreshedAt(),
                snapshot.tabs().size(),
                bytes.length,
                System.currentTimeMillis() - startedAt
            );
            return bytes;
        } catch (IOException exception) {
            log.error(
                "Failed to export business intelligence workbook refreshedAt={} tabCount={} durationMs={}",
                snapshot.refreshedAt(),
                snapshot.tabs().size(),
                System.currentTimeMillis() - startedAt,
                exception
            );
            throw new IllegalStateException("Failed to export business intelligence workbook", exception);
        }
    }

    /**
     * Export enhanced Excel workbook by invoking the Python generate_report.py script.
     * The script calls BI API to fetch data and renders native charts via openpyxl.
     */
    public byte[] exportEnhancedWorkbook(String language, String startDate, String endDate) {
        long startedAt = System.currentTimeMillis();
        Path tempDir = null;
        Path outputLog = null;
        try {
            String validLanguage = "en".equalsIgnoreCase(language) ? "en" : "zh";

            // Validate dates before invoking Python (matches non-export paths).
            if (startDate != null && !startDate.isBlank()) {
                parseDateInput(startDate);
            }
            if (endDate != null && !endDate.isBlank()) {
                parseDateInput(endDate);
            }

            tempDir = Files.createTempDirectory("bi-export-");

            // Resolve the project directory: baseDir is "./data" relative to CWD,
            // which is typically business-intelligence/scripts/ or business-intelligence/.
            // We need to find the directory containing scripts/generate_report.py.
            File baseDir = new File(runtimeProperties.getBaseDir()).getAbsoluteFile();
            File projectDir = baseDir.getParentFile(); // should be business-intelligence/
            File scriptFile = new File(projectDir, "scripts/generate_report.py");

            // Fallback: if not found, try one level up (CWD might be business-intelligence/ itself)
            if (!scriptFile.exists()) {
                File altDir = projectDir.getParentFile();
                File altScript = new File(altDir, "business-intelligence/scripts/generate_report.py");
                if (altScript.exists()) {
                    projectDir = new File(altDir, "business-intelligence");
                    scriptFile = altScript;
                }
            }

            if (!scriptFile.exists()) {
                throw new IllegalStateException("Export script not found. Tried: " + scriptFile.getAbsolutePath());
            }

            preflightPythonEnvironment(projectDir);

            // Build BI URL: prefer configured URL, fallback to localhost for local dev.
            String configuredUrl = runtimeProperties.getExportBiUrl();
            String biUrl;
            if (configuredUrl != null && !configuredUrl.isBlank()) {
                biUrl = configuredUrl;
            } else {
                biUrl = "http://localhost:" + System.getProperty("server.port", "8093");
            }

            java.util.List<String> command = new java.util.ArrayList<>(java.util.List.of(
                "python3", scriptFile.getAbsolutePath(),
                "--language", validLanguage,
                "--bi-url", biUrl,
                "--output-dir", tempDir.toString()
            ));
            if (startDate != null && !startDate.isBlank()) {
                command.addAll(java.util.List.of("--start-date", startDate));
            }
            if (endDate != null && !endDate.isBlank()) {
                command.addAll(java.util.List.of("--end-date", endDate));
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            pb.directory(projectDir);

            outputLog = Files.createTempFile("bi-export-", ".log");
            pb.redirectOutput(outputLog.toFile());

            Process process = pb.start();
            boolean finished = process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
            String output = readOutputLog(outputLog);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                throw new IllegalStateException("Python export script timed out after 120s. Output: " + output);
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.error("Python export script failed exitCode={} output={}", exitCode, output);
                throw new IllegalStateException("Python export script failed (exit " + exitCode + "): " + output);
            }

            // Find the generated .xlsx file in tempDir
            Path xlsxFile = null;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(tempDir, "*.xlsx")) {
                for (Path entry : stream) {
                    if (!entry.getFileName().toString().startsWith("~$")) {
                        xlsxFile = entry;
                        break;
                    }
                }
            }

            if (xlsxFile == null) {
                throw new IllegalStateException("No .xlsx file generated in " + tempDir + ". Script output: " + output);
            }

            byte[] bytes = Files.readAllBytes(xlsxFile);
            log.info("Enhanced export completed language={} byteSize={} durationMs={}", validLanguage, bytes.length, System.currentTimeMillis() - startedAt);
            return bytes;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Failed to run enhanced export: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to run enhanced export: " + e.getMessage(), e);
        } finally {
            if (outputLog != null) {
                try {
                    Files.deleteIfExists(outputLog);
                } catch (IOException ignored) {
                }
            }
            if (tempDir != null) {
                deleteTempDir(tempDir);
            }
        }
    }

    private void deleteTempDir(Path tempDir) {
        try {
            Files.walkFileTree(tempDir, new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException ex) {
                        log.warn("Failed to delete temp file {}: {}", file, ex.getMessage());
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    try {
                        Files.deleteIfExists(dir);
                    } catch (IOException ex) {
                        log.warn("Failed to delete temp directory {}: {}", dir, ex.getMessage());
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Failed to walk temp directory {}: {}", tempDir, e.getMessage());
        }
    }

    private static String readOutputLog(Path logFile) {
        try {
            return Files.readString(logFile);
        } catch (IOException e) {
            return "";
        } finally {
            try {
                Files.deleteIfExists(logFile);
            } catch (IOException ignored) {
            }
        }
    }

    private void preflightPythonEnvironment(File projectDir) {
        Path outputLog = null;
        try {
            outputLog = Files.createTempFile("bi-preflight-", ".log");
            ProcessBuilder pb = new ProcessBuilder(
                "python3", "-c",
                "import openpyxl, lxml, requests; print('ok')"
            );
            pb.directory(projectDir);
            pb.redirectErrorStream(true);
            pb.redirectOutput(outputLog.toFile());
            Process process = pb.start();
            boolean finished = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            String output = readOutputLog(outputLog);
            outputLog = null; // already deleted by readOutputLog
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("Python dependency check timed out. Output: " + output);
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException(
                    "Python export dependencies missing. Ensure python3 and openpyxl, lxml, requests are installed. Output: "
                        + output.trim()
                );
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Failed to verify Python export environment: " + e.getMessage(), e);
        } finally {
            if (outputLog != null) {
                try {
                    Files.deleteIfExists(outputLog);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private Snapshot buildSnapshot(BiRawData rawData, String startDate, String endDate) {
        Map<String, TabContent> contents = new LinkedHashMap<>();
        contents.put("executive-summary", buildExecutiveSummary(rawData));
        contents.put("sla-analysis", buildSlaAnalysis(rawData, startDate, endDate));
        contents.put("incident-analysis", buildIncidentAnalysis(rawData, startDate, endDate, "weekly"));
        contents.put("change-analysis", buildChangeAnalysis(rawData, startDate, endDate));
        contents.put("request-analysis", buildRequestAnalysis(rawData, startDate, endDate));
        contents.put("problem-analysis", buildProblemAnalysis(rawData));
        contents.put("cross-process", buildCrossProcess(rawData));
        contents.put("workforce", buildWorkforce(rawData));
        return new Snapshot(Instant.now(), TABS, contents);
    }

    private TabContent buildExecutiveSummary(BiRawData rawData) {
        var exec = metricsService.getExecutiveMetrics(rawData);
        var incident = metricsService.getIncidentMetrics(rawData);
        var change = metricsService.getChangeMetrics(rawData);
        var request = metricsService.getRequestMetrics(rawData);
        var problem = metricsService.getProblemMetrics(rawData);

        return new TabContent(
            "executive-summary",
            "执行摘要",
            "聚合四类 ITIL 数据的核心规模与风险摘要。",
            buildExecutiveSummaryContent(exec, incident, change, request, problem, rawData),
            null,
            List.of(
                card("incident-sla-rate", "事件 SLA 达成率", percentage(incident.slaRate()),
                    rawData.incidents().isEmpty() ? "neutral" : toneFromScore(incident.slaRate(), 0.9, 0.75)),
                card("incident-mttr", "MTTR", formatHours(incident.mttrHours()),
                    rawData.incidents().isEmpty() ? "neutral" : toneFromInverse(incident.mttrHours(), 12, 24)),
                card("change-success-rate", "变更成功率", percentage(change.successRate()),
                    rawData.changes().isEmpty() ? "neutral" : toneFromScore(change.successRate(), 0.9, 0.8)),
                card("change-incident-rate", "变更致事件率",
                    percentage(change.totalCount() > 0 ? (double) change.incidentCausedCount() / change.totalCount() : 0),
                    rawData.changes().isEmpty() ? "neutral" : toneFromInverse(
                        change.totalCount() > 0 ? (double) change.incidentCausedCount() / change.totalCount() : 0, 0.05, 0.1)),
                card("request-csat", "请求满意度", formatNumber(request.avgCsat()),
                    rawData.requests().isEmpty() ? "neutral" : toneFromScore(request.avgCsat() / 5.0, 0.8, 0.7)),
                card("problem-closure-rate", "问题关闭率", percentage(problem.closureRate()),
                    rawData.problems().isEmpty() ? "neutral" : toneFromScore(problem.closureRate(), 0.75, 0.55))
            ),
            List.of(),
            List.of()
        );
    }

    private TabContent buildSlaAnalysis(BiRawData rawData, String startDate, String endDate) {
        // ── Incident SLA ──
        List<BusinessIntelligenceMetricsService.IncidentSlaRecord> slaRecords = metricsService.buildIncidentSlaRecords(rawData);
        BiModels.SlaAnalysisSummary summary = buildSlaAnalysisSummary(slaRecords);

        List<MetricCard> incidentCards = List.of(
            card("sla-overall", "综合达成率", summary.hero().overallComplianceRate(), toneFromRate(summary.hero().overallComplianceRate())),
            card("sla-response", "响应达成率", summary.hero().responseComplianceRate(), summary.response().tone()),
            card("sla-resolution", "解决达成率", summary.hero().resolutionComplianceRate(), summary.resolution().tone()),
            card("sla-high-priority", "P1/P2达成率", summary.hero().highPriorityComplianceRate(), toneFromRate(summary.hero().highPriorityComplianceRate())),
            card("sla-response-breached", "响应违约数", summary.violationBreakdown().responseBreached(), summary.violationBreakdown().responseBreached() > 0 ? "warning" : "success"),
            card("sla-resolution-breached", "解决违约数", summary.violationBreakdown().resolutionBreached(), summary.violationBreakdown().resolutionBreached() > 0 ? "warning" : "success")
        );

        List<ChartSection> incidentCharts = List.of(
            lineChart("sla-trend", "SLA达成率趋势", buildSlaWeeklyTrendData(rawData, startDate, endDate),
                List.of("响应达成率", "解决达成率", "P1/P2达成率"),
                List.of("#10b981", "#5b8db8", "#f59e0b")),
            new ChartSection("priority-comparison", "优先级SLA达成率对比", "grouped-bar",
                summary.priorityRows().stream()
                    .map(row -> new ChartDatum(
                        row.priority() + "|" + String.format("%.1f", parsePercentage(row.responseComplianceRate())) + "|" + String.format("%.1f", parsePercentage(row.resolutionComplianceRate())),
                        parsePercentage(row.responseComplianceRate())))
                    .toList(),
                new ChartConfig(List.of("响应达成率", "解决达成率"), null, List.of("#10b981", "#5b8db8"), "优先级", "达成率(%)")),
            pieChart("violation-by-priority", "违约优先级分布",
                slaRecords.stream()
                    .filter(BusinessIntelligenceMetricsService.IncidentSlaRecord::anyBreached)
                    .collect(Collectors.groupingBy(BusinessIntelligenceMetricsService.IncidentSlaRecord::priority, LinkedHashMap::new, Collectors.counting()))
                    .entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .map(e -> new ChartDatum(e.getKey(), e.getValue()))
                    .toList(),
                List.of("#ef4444", "#f59e0b", "#eab308", "#10b981")),
            pieChart("violation-by-category", "违约事件类型分布",
                slaRecords.stream()
                    .filter(BusinessIntelligenceMetricsService.IncidentSlaRecord::anyBreached)
                    .collect(Collectors.groupingBy(r -> defaultLabel(r.category(), "未标注"), LinkedHashMap::new, Collectors.counting()))
                    .entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(8)
                        .map(e -> new ChartDatum(e.getKey(), e.getValue()))
                        .toList(),
                    List.of("#5b8db8", "#10b981", "#f59e0b", "#ef4444", "#8b7fc7", "#c97082", "#5e9bb5", "#7ca65a"))
        );

        List<BiModels.TableSection> incidentTables = List.of(
            table("sla-violation-samples", "违约样本", List.of("编号", "标题", "优先级", "类别", "处理人", "响应时长", "解决时长", "违约类型"), summary.violationSamples().stream()
                .map(sample -> List.of(
                    sample.orderNumber(),
                    sample.orderName(),
                    sample.priority(),
                    sample.category(),
                    sample.resolver(),
                    sample.responseDuration(),
                    sample.resolutionDuration(),
                    sample.violationType()
                )).toList())
        );

        // ── Request SLA ──
        List<BusinessIntelligenceMetricsService.RequestSlaRecord> reqRecords = metricsService.buildRequestSlaRecords(rawData);
        List<MetricCard> requestCards = buildRequestSlaCards(reqRecords);
        List<ChartSection> requestCharts = buildRequestSlaCharts(reqRecords, rawData, startDate, endDate);
        List<BiModels.TableSection> requestTables = buildRequestSlaTables(reqRecords);

        // ── Merge ──
        List<MetricCard> allCards = new ArrayList<>(incidentCards);
        allCards.addAll(requestCards);

        List<ChartSection> allCharts = new ArrayList<>(incidentCharts);
        allCharts.addAll(requestCharts);

        List<BiModels.TableSection> allTables = new ArrayList<>(incidentTables);
        allTables.addAll(requestTables);

        return new TabContent(
            "sla-analysis",
            "SLA分析",
            "事件与请求SLA履约情况分析。",
            null,
            summary,
            allCards,
            allCharts,
            allTables
        );
    }

    private TabContent buildIncidentAnalysis(BiRawData rawData, String startDate, String endDate) {
        return buildIncidentAnalysis(rawData, startDate, endDate, "weekly");
    }

    private TabContent buildIncidentAnalysis(BiRawData rawData, String startDate, String endDate, String granularity) {
        var m = metricsService.getIncidentMetrics(rawData);
        List<Map<String, String>> incidents = rawData.incidents();

        List<ChartDatum> volumeTrend = buildVolumeTrend(rawData, startDate, endDate, granularity);
        List<ChartDatum> mttrTrend = buildMttrTrend(rawData, startDate, endDate, granularity);

        return new TabContent(
            "incident-analysis",
            "事件分析",
            "基于事件工单数据，分析事件管理的关键KPI、趋势变化以及类型分布。",
            null,
            null,
            List.of(
                card("incident-total", "事件总数", formatNumber(m.totalCount()), "neutral"),
                card("incident-p1p2", "P1/P2 事件", formatNumber(m.p1p2Count()), m.p1p2Count() > m.totalCount() * 0.15 ? "warning" : "success"),
                card("incident-open", "未解决事件", formatNumber(m.openCount()), m.openCount() > m.totalCount() * 0.3 ? "warning" : "success"),
                card("incident-sla", "SLA 达成率", percentage(m.slaRate()), toneFromScore(m.slaRate(), 0.9, 0.75)),
                card("incident-p1p2-mttr", "P1/P2 MTTR", formatHours(m.p1p2MttrHours()), toneFromInverse(m.p1p2MttrHours(), 8, 24)),
                card("incident-mttr", "平均 MTTR", formatHours(m.mttrHours()), toneFromInverse(m.mttrHours(), 24, 48))
            ),
            List.of(
                comboChart("incident-volume-trend", "事件单量趋势", volumeTrend,
                    List.of("事件单量", "SLA达成率(%)"), List.of("#5b8db8", "#10b981")),
                lineChart("incident-mttr-trend", "处理时长趋势", mttrTrend,
                    List.of("平均MTTR", "P1/P2 MTTR"), List.of("#5b8db8", "#ef4444")),
                pieChart("incident-priority-pie", "优先级分布",
                    m.priorityDistribution().stream().map(d -> new ChartDatum(d.label(), d.count())).toList(),
                    List.of("#ef4444", "#f59e0b", "#eab308", "#10b981")),
                pieChart("incident-category-pie", "事件类型分布",
                    m.categoryDistribution().stream().map(d -> new ChartDatum(d.label(), d.count())).toList(),
                    List.of("#5b8db8", "#10b981", "#f59e0b", "#ef4444", "#8b7fc7", "#c97082", "#5e9bb5", "#7ca65a"))
            ),
            List.of(
                table("incident-resolver-table", "处理人工作量 TOP10", List.of("处理人", "事件数"), rowsFromChart(topCounts(incidents, BiColumns.RESOLVER, 10))),
                table("incident-recent-table", "事件样本", List.of("编号", "标题", "优先级", "处理人", "时长", "SLA"), incidents.stream()
                    .sorted((a, b) -> {
                        int priorityCompare = priorityIndex(clean(a.get(BiColumns.PRIORITY))) - priorityIndex(clean(b.get(BiColumns.PRIORITY)));
                        if (priorityCompare != 0) return priorityCompare;
                        return Double.compare(parseDouble(b.get(BiColumns.RESOLUTION_TIME_M)), parseDouble(a.get(BiColumns.RESOLUTION_TIME_M)));
                    })
                    .limit(15)
                    .<List<String>>map(row -> List.of(
                        defaultLabel(row.get(BiColumns.ORDER_NUMBER), "—"),
                        defaultLabel(row.get(BiColumns.TITLE), "—"),
                        defaultLabel(row.get(BiColumns.PRIORITY), "—"),
                        defaultLabel(row.get(BiColumns.RESOLVER), "—"),
                        formatMinutes(parseDouble(row.get(BiColumns.RESOLUTION_TIME_M))),
                        isYes(row.get(BiColumns.SLA_COMPLIANT)) ? "✓" : "✗"
                    )).toList())
            )
        );
    }

    private int priorityIndex(String priority) {
        return switch (priority.toUpperCase(Locale.ROOT)) {
            case "P1" -> 1;
            case "P2" -> 2;
            case "P3" -> 3;
            case "P4" -> 4;
            default -> 5;
        };
    }

    /**
     * Merges multiple TrendResult series into pipe-delimited ChartDatum items.
     * Each TrendResult contributes one value column per period. The resulting
     * ChartDatum label is "period|val1|val2|..." and the value is taken from
     * the first series.
     */
    private List<ChartDatum> mergeTrendSeries(List<TrendResult> results) {
        if (results.isEmpty()) {
            return List.of();
        }

        // Collect all periods across all series
        Set<String> allPeriods = new TreeSet<>();
        for (TrendResult result : results) {
            for (TrendPoint point : result.dataPoints()) {
                allPeriods.add(point.period());
            }
        }

        // Build period -> value map for each series
        List<Map<String, Object>> seriesValues = results.stream()
            .map(result -> result.dataPoints().stream()
                .collect(Collectors.<TrendPoint, String, Object, LinkedHashMap<String, Object>>toMap(
                    TrendPoint::period, TrendPoint::value, (a, b) -> b, LinkedHashMap::new)))
            .map(m -> (Map<String, Object>) m)
            .toList();

        List<ChartDatum> merged = new ArrayList<>();
        for (String period : allPeriods) {
            StringBuilder label = new StringBuilder(formatTrendLabel(period));
            double primaryValue = 0;
            for (int i = 0; i < seriesValues.size(); i++) {
                Object raw = seriesValues.get(i).get(period);
                double val = raw instanceof Number n ? n.doubleValue() : 0;
                label.append("|").append(String.format("%.1f", val));
                if (i == 0) {
                    primaryValue = val;
                }
            }
            merged.add(new ChartDatum(label.toString(), primaryValue));
        }
        return merged;
    }

    private String formatTrendLabel(String period) {
        // "2024-01-01" (week start date) → "01月-1", "2025-01" (month) → "2025-01"
        try {
            if (period.length() == 10 && period.charAt(4) == '-') {
                LocalDate date = LocalDate.parse(period);
                int month = date.getMonthValue();
                int dayOfMonth = date.getDayOfMonth();
                int weekInMonth = (dayOfMonth - 1) / 7 + 1;
                return String.format("%02d月-%d", month, weekInMonth);
            }
        } catch (DateTimeException ignored) {
        }
        return period;
    }

    private List<ChartDatum> buildVolumeTrend(BiRawData rawData, String startDate, String endDate, String granularity) {
        String interval = "monthly".equals(granularity) ? "month" : "week";
        TrendResult countTrend = metricsService.getTrends(rawData, "incidents", "count", interval, null, startDate, endDate);
        TrendResult slaTrend = metricsService.getTrends(rawData, "incidents", "sla_rate", interval, null, startDate, endDate);
        return mergeTrendSeries(List.of(countTrend, slaTrend));
    }

    private List<ChartDatum> buildMttrTrend(BiRawData rawData, String startDate, String endDate, String granularity) {
        String interval = "monthly".equals(granularity) ? "month" : "week";
        TrendResult mttrTrend = metricsService.getTrends(rawData, "incidents", "avg_resolution_time", interval, null, startDate, endDate);
        TrendResult p12Trend = metricsService.getTrends(rawData, "incidents", "p12_avg_resolution_time", interval, null, startDate, endDate);
        return mergeTrendSeries(List.of(mttrTrend, p12Trend));
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

    private ChartSection lineChart(String id, String title, List<ChartDatum> items, List<String> seriesNames, List<String> colors) {
        return new ChartSection(id, title, "line", items, new ChartConfig(seriesNames, null, colors, "时间", "数量"));
    }

    private ChartSection comboChart(String id, String title, List<ChartDatum> items, List<String> seriesNames, List<String> colors) {
        return new ChartSection(id, title, "combo", items, new ChartConfig(seriesNames, null, colors, "时间", "数量"));
    }

    private ChartSection pieChart(String id, String title, List<ChartDatum> items, List<String> colors) {
        return new ChartSection(id, title, "pie", items, new ChartConfig(null, null, colors, null, null));
    }

    private TabContent buildChangeAnalysis(BiRawData rawData, String startDate, String endDate) {
        var m = metricsService.getChangeMetrics(rawData);
        List<Map<String, String>> changes = rawData.changes();

        return new TabContent(
            "change-analysis",
            "变更分析",
            "展示变更成功率趋势、类型分布、风险等级和计划满足度。",
            null,
            null,
            List.of(
                card("change-total", "变更总数", m.totalCount(), "neutral"),
                card("change-success", "成功率", percentage(m.successRate()), "success"),
                card("change-emergency", "紧急变更", m.emergencyCount(), m.emergencyCount() > 0 ? "warning" : "success"),
                card("change-incident", "引发事件的变更", m.incidentCausedCount(), m.incidentCausedCount() > 0 ? "warning" : "success")
            ),
            List.of(
                comboChart("change-success-trend", "变更成功率趋势", buildChangeWeeklyTrendData(rawData, startDate, endDate),
                    List.of("变更数量", "成功率", "引发事件变更"), List.of("#5b8db8", "#10b981", "#ef4444")),
                pieChart("change-type-pie", "变更等级分布",
                    m.typeDistribution().stream().map(d -> new ChartDatum(d.label(), d.count())).toList(),
                    List.of("#5b8db8", "#10b981", "#f59e0b", "#ef4444", "#8b7fc7", "#c97082")),
                // Row 2: stacked bar (category success/failure distribution)
                new ChartSection("change-category-stacked", "变更类别分布", "stacked-bar",
                    buildChangeCategoryDistribution(changes),
                    new ChartConfig(List.of("成功", "失败"), null, List.of("#5b8db8", "#ef4444"), "变更类别", "数量")),
                // Row 3: bar chart (risk level distribution for incident-causing changes)
                pieChart("change-risk-level", "变更引发故障分布", buildRiskLevelDistribution(changes),
                    List.of("#5b8db8", "#10b981", "#f59e0b", "#ef4444", "#8b7fc7", "#c97082")),
                // Row 3: stacked bar (plan deviation by change type)
                new ChartSection("change-plan-deviation", "变更计划满足分布", "stacked-bar",
                    buildPlanDeviation(changes),
                    new ChartConfig(List.of("提前完成", "按时完成", "延期完成"), null, List.of("#10b981", "#5b8db8", "#ef4444"), "变更类型", "数量"))
            ),
            List.of(
                table("change-failed-table", "失败或回退样本", List.of("编号", "标题", "状态", "关闭方式", "是否回退"), changes.stream()
                    .filter(row -> {
                        String cc = clean(row.get(BiColumns.SUCCESS));
                        return !"Successful".equalsIgnoreCase(cc) && !"Cancelled".equalsIgnoreCase(cc);
                    })
                    .limit(10)
                    .map(row -> List.of(
                        defaultLabel(row.get(BiColumns.CHANGE_NUMBER), "—"),
                        defaultLabel(row.get(BiColumns.TITLE), "—"),
                        defaultLabel(row.get(BiColumns.STATUS), "—"),
                        defaultLabel(row.get(BiColumns.SUCCESS), "—"),
                        "Backed_out".equalsIgnoreCase(clean(row.get(BiColumns.SUCCESS))) ? "是" : "否"
                    )).toList())
            )
        );
    }

    private List<ChartDatum> buildChangeWeeklyTrendData(BiRawData rawData, String startDate, String endDate) {
        TrendResult countTrend = metricsService.getTrends(rawData, "changes", "count", "week", null, startDate, endDate);
        TrendResult successTrend = metricsService.getTrends(rawData, "changes", "success_rate", "week", null, startDate, endDate);
        TrendResult causedTrend = metricsService.getTrends(rawData, "changes", "incident_caused_count", "week", null, startDate, endDate);
        return mergeTrendSeries(List.of(countTrend, successTrend, causedTrend));
    }

    private List<ChartDatum> buildChangeCategoryDistribution(List<Map<String, String>> changes) {
        return changes.stream()
            .collect(Collectors.groupingBy(
                row -> defaultLabel(row.get(BiColumns.CATEGORY), "未标注"),
                LinkedHashMap::new, Collectors.toList()))
            .entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
            .limit(8)
            .map(entry -> {
                long success = entry.getValue().stream().filter(row -> isSuccessful(row.get(BiColumns.SUCCESS))).count();
                long failure = entry.getValue().size() - success;
                return new ChartDatum(entry.getKey() + "|" + success + "|" + failure, success);
            })
            .toList();
    }

    private List<ChartDatum> buildRiskLevelDistribution(List<Map<String, String>> changes) {
        return changes.stream()
            .filter(row -> isNonEmpty(row.get(BiColumns.INCIDENT_CAUSED)))
            .collect(Collectors.groupingBy(
                row -> defaultLabel(row.get(BiColumns.RISK), "未标注"),
                LinkedHashMap::new, Collectors.counting()))
            .entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(6)
            .map(entry -> new ChartDatum(entry.getKey(), entry.getValue()))
            .toList();
    }

    private List<ChartDatum> buildPlanDeviation(List<Map<String, String>> changes) {
        return changes.stream()
            .filter(row -> {
                String plannedEnd = row.get(BiColumns.PLANNED_END);
                String actualEnd = row.get(BiColumns.ACTUAL_END);
                return plannedEnd != null && !plannedEnd.isBlank() && actualEnd != null && !actualEnd.isBlank();
            })
            .collect(Collectors.groupingBy(
                row -> defaultLabel(row.get(BiColumns.CHANGE_TYPE), "未标注"),
                LinkedHashMap::new, Collectors.toList()))
            .entrySet().stream()
            .limit(6)
            .map(entry -> {
                long early = 0, onTime = 0, late = 0;
                for (Map<String, String> row : entry.getValue()) {
                    LocalDateTime plannedEnd = BiDateUtils.parseDate(row.get(BiColumns.PLANNED_END));
                    LocalDateTime actualEnd = BiDateUtils.parseDate(row.get(BiColumns.ACTUAL_END));
                    if (plannedEnd == null || actualEnd == null) continue;
                    long diffHours = java.time.Duration.between(plannedEnd, actualEnd).toHours();
                    if (diffHours < -1) early++;
                    else if (diffHours <= 1) onTime++;
                    else late++;
                }
                return new ChartDatum(entry.getKey() + "|" + early + "|" + onTime + "|" + late, early + onTime + late);
            })
            .toList();
    }

    private TabContent buildRequestAnalysis(BiRawData rawData, String startDate, String endDate) {
        var m = metricsService.getRequestMetrics(rawData);
        List<Map<String, String>> requests = rawData.requests();

        return new TabContent(
            "request-analysis",
            "请求分析",
            "展示请求趋势、SLA达标率、满意度和高频请求分布。",
            null,
            null,
            List.of(
                card("request-total", "请求总数", m.totalCount(), "neutral"),
                card("request-fulfilled", "已完成请求", m.fulfilledCount(), "success"),
                card("request-sla", "SLA 达成率", percentage(m.slaRate()), "success"),
                card("request-csat", "平均满意度", formatNumber(m.avgCsat()), m.avgCsat() >= 4 ? "success" : "warning")
            ),
            List.of(
                comboChart("request-volume-trend", "请求单量趋势", buildRequestWeeklyTrendData(rawData, startDate, endDate),
                    List.of("请求单量", "平均满意度"), List.of("#5b8db8", "#10b981")),
                // Row 2: combo chart - SLA rate & avg fulfillment time by category
                comboChart("request-sla-time", "SLA达成率与平均耗时", buildRequestSlaByCategoryData(requests, rawData.requestSlaCriteria().isEmpty() ? rawData.incidentSlaCriteria() : rawData.requestSlaCriteria()),
                    List.of("平均耗时(h)", "SLA达成率"), List.of("#5b8db8", "#10b981")),
                // Row 3 left: pie chart - request type distribution
                pieChart("request-type-pie", "请求类型分布", topCounts(requests, BiColumns.REQUEST_TYPE, 6),
                    List.of("#5b8db8", "#10b981", "#f59e0b", "#ef4444", "#8b7fc7", "#c97082")),
                // Row 3 right: bar chart - department consumption ranking
                new ChartSection("request-dept-ranking", "部门请求单数排名", "column", topCounts(requests, BiColumns.REQUESTER_DEPT, 8), new ChartConfig(null, null, List.of("#5b8db8"), "部门", "请求数")),
                // Row 4 left: pie chart - satisfaction distribution
                pieChart("request-satisfaction-pie", "满意度分布", buildSatisfactionDistribution(requests),
                    List.of("#ef4444", "#f59e0b", "#10b981", "#5b8db8")),
                // Row 4 right: bar chart - high-frequency request category TOP8
                new ChartSection("request-category-top", "高频请求目录", "column", topCounts(requests, BiColumns.CATEGORY, 8), new ChartConfig(null, null, List.of("#5b8db8"), "类别", "数量"))
            ),
            List.of(
                table("request-low-csat-table", "低满意度样本", List.of("编号", "标题", "类别", "满足时间", "满意度", "反馈"), requests.stream()
                    .filter(row -> {
                        double score = parseDouble(row.get(BiColumns.SATISFACTION_SCORE));
                        return score > 0 && score <= 3;
                    })
                    .sorted((a, b) -> Double.compare(parseDouble(a.get(BiColumns.SATISFACTION_SCORE)), parseDouble(b.get(BiColumns.SATISFACTION_SCORE))))
                    .limit(15)
                    .map(row -> List.of(
                        defaultLabel(row.get(BiColumns.REQUEST_NUMBER), "—"),
                        defaultLabel(row.get(BiColumns.TITLE), "—"),
                        defaultLabel(row.get(BiColumns.CATEGORY), "—"),
                        formatHours(parseDouble(row.get(BiColumns.REQUEST_RESOLUTION_TIME_M)) / 60.0),
                        defaultLabel(row.get(BiColumns.SATISFACTION_SCORE), "—"),
                        defaultLabel(row.get("feedback"), "—")
                    )).toList())
            )
        );
    }

    private List<ChartDatum> buildRequestWeeklyTrendData(BiRawData rawData, String startDate, String endDate) {
        TrendResult countTrend = metricsService.getTrends(rawData, "requests", "count", "week", null, startDate, endDate);
        TrendResult csatTrend = metricsService.getTrends(rawData, "requests", "csat", "week", null, startDate, endDate);
        return mergeTrendSeries(List.of(countTrend, csatTrend));
    }

    private List<ChartDatum> buildRequestSlaByCategoryData(List<Map<String, String>> requests, List<Map<String, String>> slaCriteria) {
        Map<String, Double> resolutionTargets = buildCriteriaMap(slaCriteria, List.of("resolution_sla_min"));
        return requests.stream()
            .collect(Collectors.groupingBy(
                row -> defaultLabel(row.get(BiColumns.CATEGORY), "未标注"),
                LinkedHashMap::new, Collectors.toList()))
            .entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
            .limit(8)
            .map(entry -> {
                List<Map<String, String>> rows = entry.getValue();
                long slaCount = rows.stream().filter(row -> {
                    String rt = clean(row.get(BiColumns.REQUEST_RESOLUTION_TIME_M));
                    if (rt.isEmpty()) return false;
                    double minutes = parseDouble(rt);
                    String priority = clean(row.get(BiColumns.PRIORITY));
                    Double target = resolutionTargets.get(priority);
                    return target != null && minutes <= target;
                }).count();
                long withTime = rows.stream().filter(row -> !clean(row.get(BiColumns.REQUEST_RESOLUTION_TIME_M)).isEmpty()).count();
                double slaRate = percentageValue(slaCount, withTime > 0 ? withTime : rows.size()) * 100.0;
                double avgTime = rows.stream()
                    .mapToDouble(row -> parseDouble(row.get(BiColumns.REQUEST_RESOLUTION_TIME_M)) / 60.0)
                    .filter(v -> v > 0)
                    .average().orElse(0);
                return new ChartDatum(
                    entry.getKey() + "|" + String.format("%.1f", avgTime) + "|" + String.format("%.1f", slaRate),
                    avgTime);
            })
            .toList();
    }

    private List<ChartDatum> buildSatisfactionDistribution(List<Map<String, String>> requests) {
        Map<String, Long> dist = new LinkedHashMap<>();
        dist.put("非常不满意(1-2)", 0L);
        dist.put("不满意(3)", 0L);
        dist.put("满意(4)", 0L);
        dist.put("非常满意(5)", 0L);

        for (Map<String, String> row : requests) {
            double score = parseDouble(row.get(BiColumns.SATISFACTION_SCORE));
            if (score <= 2) dist.merge("非常不满意(1-2)", 1L, Long::sum);
            else if (score <= 3) dist.merge("不满意(3)", 1L, Long::sum);
            else if (score <= 4) dist.merge("满意(4)", 1L, Long::sum);
            else dist.merge("非常满意(5)", 1L, Long::sum);
        }

        return dist.entrySet().stream()
            .filter(e -> e.getValue() > 0)
            .map(e -> new ChartDatum(e.getKey(), e.getValue()))
            .toList();
    }

    private TabContent buildProblemAnalysis(BiRawData rawData) {
        var m = metricsService.getProblemMetrics(rawData);
        List<Map<String, String>> problems = rawData.problems();

        return new TabContent(
            "problem-analysis",
            "问题分析",
            "展示问题趋势、根因分析、解决方案健康度和根因类别分布。",
            null,
            null,
            List.of(
                card("problem-total", "问题总数", m.totalCount(), "neutral"),
                card("problem-closed", "已关闭问题", m.closedCount(), "success"),
                card("problem-rca", "已完成 RCA", (long) (m.rcaRate() * m.totalCount()), "success"),
                card("problem-known-error", "未知错误", m.knownErrorCount(), "warning")
            ),
            List.of(
                // Row 1: combo chart - weekly problem volume + cumulative unresolved
                comboChart("problem-volume-trend", "问题单量趋势", buildProblemWeeklyTrendData(problems),
                    List.of("问题单量", "累积未解决"), List.of("#5b8db8", "#ef4444")),
                // Row 2 left: pie chart - root cause category distribution
                pieChart("problem-root-cause-pie", "问题根因类型分布", topCounts(problems, BiColumns.ROOT_CAUSE_CATEGORY, 6),
                    List.of("#5b8db8", "#10b981", "#f59e0b", "#ef4444", "#8b7fc7", "#c97082")),
                // Row 2 right: column chart - incident count by root cause category
                new ChartSection("problem-incident-ranking", "问题引发故障数排名", "column",
                    buildProblemIncidentRanking(problems),
                    new ChartConfig(null, null, List.of("#5b8db8"), "根因类别", "故障数")),
                // Row 3 left: pie chart - status distribution
                pieChart("problem-status-pie", "问题状态分布", topCounts(problems, BiColumns.STATUS, 6),
                    List.of("#10b981", "#5b8db8", "#f59e0b", "#ef4444", "#8b7fc7", "#c97082")),
                // Row 3 right: stacked bar - resolution health
                new ChartSection("problem-resolution-health", "已关闭问题单的解决方案健康度分析", "stacked-bar",
                    buildResolutionHealth(problems),
                    new ChartConfig(List.of("已永久修复", "有临时方案", "未解决"), null, List.of("#10b981", "#5b8db8", "#ef4444"), "根因类别", "数量")),
                // Row 4: grouped bar - tech debt distribution
                new ChartSection("problem-tech-debt", "系统模块薄弱点分析", "grouped-bar",
                    buildTechDebtDistribution(problems),
                    new ChartConfig(topRootCauseCategories(problems, 4), null, List.of("#5b8db8", "#10b981", "#f59e0b", "#ef4444"), "Category", "数量"))
            ),
            List.of(
                table("problem-open-table", "未关闭问题", List.of("编号", "标题", "状态", "关联事件"), problems.stream()
                    .filter(row -> !matchesAny(clean(row.get(BiColumns.STATUS)), List.of("Resolved", "Closed")))
                    .limit(10)
                    .map(row -> List.of(
                        defaultLabel(row.get(BiColumns.PROBLEM_NUMBER), "—"),
                        defaultLabel(row.get(BiColumns.TITLE), "—"),
                        defaultLabel(row.get(BiColumns.STATUS), "—"),
                        defaultLabel(row.get(BiColumns.RELATED_INCIDENT_COUNT), "0")
                    )).toList())
            )
        );
    }

    private List<ChartDatum> buildProblemWeeklyTrendData(List<Map<String, String>> problems) {
        // Group by week: count total and track cumulative unresolved
        List<Map<String, String>> sorted = new ArrayList<>(problems);
        sorted.sort((a, b) -> {
            LocalDateTime da = BiDateUtils.parseDate(a.get(BiColumns.LOGGED_DATE));
            LocalDateTime db = BiDateUtils.parseDate(b.get(BiColumns.LOGGED_DATE));
            if (da == null && db == null) return 0;
            if (da == null) return 1;
            if (db == null) return -1;
            return da.compareTo(db);
        });

        Map<String, Long> totalByPeriod = new LinkedHashMap<>();
        Map<String, Long> resolvedByPeriod = new LinkedHashMap<>();

        for (Map<String, String> row : sorted) {
            LocalDateTime date = BiDateUtils.parseDate(row.get(BiColumns.LOGGED_DATE));
            if (date == null) continue;
            String period = formatPeriodLabel(date, "weekly");
            totalByPeriod.merge(period, 1L, Long::sum);
            if (matchesAny(clean(row.get(BiColumns.STATUS)), List.of("Resolved", "Closed"))) {
                resolvedByPeriod.merge(period, 1L, Long::sum);
            }
        }

        Set<String> allPeriods = new TreeSet<>(totalByPeriod.keySet());
        long cumulativeUnresolved = 0;
        List<ChartDatum> result = new ArrayList<>();
        for (String period : allPeriods) {
            long total = totalByPeriod.getOrDefault(period, 0L);
            long resolved = resolvedByPeriod.getOrDefault(period, 0L);
            cumulativeUnresolved += (total - resolved);
            result.add(new ChartDatum(period + "|" + total + "|" + cumulativeUnresolved, total));
        }
        return result;
    }

    private List<ChartDatum> buildProblemIncidentRanking(List<Map<String, String>> problems) {
        return problems.stream()
            .filter(row -> !clean(row.get(BiColumns.RELATED_INCIDENT_COUNT)).isBlank())
            .collect(Collectors.groupingBy(
                row -> defaultLabel(row.get(BiColumns.ROOT_CAUSE_CATEGORY), "未标注"),
                LinkedHashMap::new, Collectors.toList()))
            .entrySet().stream()
            .map(entry -> {
                long incidentCount = entry.getValue().stream()
                    .mapToLong(row -> parseLong(row.get(BiColumns.RELATED_INCIDENT_COUNT)))
                    .sum();
                return new ChartDatum(entry.getKey(), incidentCount);
            })
            .sorted((a, b) -> Double.compare(b.value(), a.value()))
            .limit(8)
            .toList();
    }

    private List<ChartDatum> buildResolutionHealth(List<Map<String, String>> problems) {
        return problems.stream()
            .filter(row -> matchesAny(clean(row.get(BiColumns.STATUS)), List.of("Resolved", "Closed")))
            .collect(Collectors.groupingBy(
                row -> defaultLabel(row.get(BiColumns.ROOT_CAUSE_CATEGORY), "未标注"),
                LinkedHashMap::new, Collectors.toList()))
            .entrySet().stream()
            .limit(6)
            .map(entry -> {
                long green = 0, blue = 0, red = 0;
                for (Map<String, String> row : entry.getValue()) {
                    String workaroundVal = row.get(BiColumns.WORKAROUND_AVAILABLE);
                    boolean workaround = workaroundVal != null && !workaroundVal.isBlank();
                    String fixVal = row.get(BiColumns.PERMANENT_FIX);
                    boolean permanentFix = fixVal != null && !fixVal.isBlank();
                    if (workaround && permanentFix) green++;
                    else if (workaround && !permanentFix) blue++;
                    else red++;
                }
                return new ChartDatum(entry.getKey() + "|" + green + "|" + blue + "|" + red, green + blue + red);
            })
            .toList();
    }

    private List<String> topCategories(List<Map<String, String>> problems, int limit) {
        return problems.stream()
            .map(row -> defaultLabel(row.get(BiColumns.CATEGORY), "未标注"))
            .collect(Collectors.groupingBy(Function.identity(), LinkedHashMap::new, Collectors.counting()))
            .entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(limit)
            .map(Map.Entry::getKey)
            .toList();
    }

    private List<String> topRootCauseCategories(List<Map<String, String>> problems, int limit) {
        return problems.stream()
            .map(row -> defaultLabel(row.get(BiColumns.ROOT_CAUSE_CATEGORY), "未标注"))
            .collect(Collectors.groupingBy(Function.identity(), LinkedHashMap::new, Collectors.counting()))
            .entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(limit)
            .map(Map.Entry::getKey)
            .toList();
    }

    private List<ChartDatum> buildTechDebtDistribution(List<Map<String, String>> problems) {
        List<String> topCats = topCategories(problems, 4);
        List<String> rccSeries = topRootCauseCategories(problems, 4);

        return topCats.stream()
            .map(cat -> {
                long[] counts = new long[rccSeries.size()];
                List<Map<String, String>> matching = problems.stream()
                    .filter(row -> defaultLabel(row.get(BiColumns.CATEGORY), "未标注").equals(cat))
                    .toList();
                for (Map<String, String> row : matching) {
                    String rcc = defaultLabel(row.get(BiColumns.ROOT_CAUSE_CATEGORY), "未标注");
                    int idx = rccSeries.indexOf(rcc);
                    if (idx >= 0) counts[idx]++;
                }
                StringBuilder label = new StringBuilder(cat);
                for (long count : counts) {
                    label.append("|").append(count);
                }
                return new ChartDatum(label.toString(), matching.size());
            })
            .toList();
    }

    private TabContent buildCrossProcess(BiRawData rawData) {
        var cm = metricsService.getCrossProcessMetrics(rawData);
        List<Map<String, String>> changes = rawData.changes();
        List<Map<String, String>> incidents = rawData.incidents();
        List<Map<String, String>> problems = rawData.problems();

        boolean hasData = !changes.isEmpty() || !incidents.isEmpty() || !problems.isEmpty() || !rawData.requests().isEmpty();

        String causedTone = hasData && cm.changeCausedIncidentRate() < 5 ? "success" : hasData && cm.changeCausedIncidentRate() < 10 ? "warning" : hasData ? "danger" : "neutral";
        String p1p2Tone = hasData && cm.p1p2Within48h() == 0 ? "success" : hasData ? "warning" : "neutral";
        String ratioTone = hasData && cm.requestIncidentRatio() < 3 ? "success" : hasData && cm.requestIncidentRatio() < 5 ? "warning" : hasData ? "danger" : "neutral";
        String fragTone = hasData && cm.fragilityScore() > 75 ? "success" : hasData && cm.fragilityScore() > 50 ? "warning" : hasData ? "danger" : "neutral";

        return new TabContent(
            "cross-process",
            "跨流程关联",
            "展示变更、事件、请求和问题之间的深度关联与风险传导路径。",
            null,
            null,
            List.of(
                card("cross-change-incident-rate", "变更致事件率", formatNumber(cm.changeCausedIncidentRate()) + "%", causedTone),
                card("cross-48h-p1p2", "48h窗口P1/P2事件", String.valueOf(cm.p1p2Within48h()), p1p2Tone),
                card("cross-request-incident-ratio", "请求-事件比", formatNumber(cm.requestIncidentRatio()), ratioTone),
                card("cross-fragility-score", "系统脆弱性评分", String.valueOf((int) cm.fragilityScore()), fragTone)
            ),
            List.of(
                comboChart("cross-change-incident-trend", "变更致事件趋势",
                    buildChangeIncidentTrendData(rawData),
                    List.of("变更数量", "致事件P1/P2数"), List.of("#5b8db8", "#ef4444")),
                new ChartSection("cross-change-heatmap", "变更风险热力图", "heatmap",
                    buildChangeHeatmapData(rawData),
                    new ChartConfig(List.of("变更密度", "事件热点"), null, List.of("#5b8db8", "#ef4444"), "时段", "星期")),
                new ChartSection("cross-tech-debt-bubble", "系统脆弱性气泡图", "bubble",
                    buildTechDebtBubbleData(rawData),
                    new ChartConfig(topRootCauseCategories(problems, 6), null, List.of("#5b8db8", "#10b981", "#f59e0b", "#ef4444", "#8b7fc7", "#c97082"), "累计关联事件数", "平均积压天数")),
                comboChart("cross-request-incident-overlap", "请求与事件时间重叠",
                    buildRequestIncidentOverlap(rawData),
                    List.of("请求数", "事件数"), List.of("#5b8db8", "#ef4444"))
            ),
            List.of(
                table("cross-change-incident-detail", "变更致事件关联明细",
                    List.of("变更编号", "变更标题", "完成时间", "48h内P1/P2事件", "风险等级"),
                    buildCrossChangeIncidentDetailRows(changes, incidents)),
                table("cross-aging-problems", "老化问题清单",
                    List.of("问题编号", "根因类别", "老化天数", "关联事件数", "优先级", "状态"),
                    problems.stream()
                        .map(row -> {
                            LocalDateTime logged = BiDateUtils.parseDate(row.get(BiColumns.LOGGED_DATE));
                            long agingDays = 0;
                            if (logged != null) {
                                LocalDateTime resolved = BiDateUtils.parseDate(row.get(BiColumns.RESOLVED_AT));
                                LocalDateTime end = resolved != null ? resolved : LocalDateTime.now();
                                agingDays = java.time.Duration.between(logged, end).toDays();
                            }
                            return new Object[]{row, agingDays};
                        })
                        .sorted((a, b) -> Long.compare((long) b[1], (long) a[1]))
                        .limit(15)
                        .map(obj -> {
                            if (!(obj instanceof Object[])) {
                                throw new IllegalStateException("Expected Object[] array");
                            }
                            Object[] array = obj;
                            if (array.length != 2) {
                                throw new IllegalStateException("Expected array of length 2");
                            }
                            if (!(array[0] instanceof Map)) {
                                throw new IllegalStateException("Expected Map at index 0, got: " + array[0].getClass());
                            }
                            if (!(array[1] instanceof Long)) {
                                throw new IllegalStateException("Expected Long at index 1, got: " + array[1].getClass());
                            }
                            @SuppressWarnings("unchecked")
                            Map<String, String> row = (Map<String, String>) array[0];
                            long agingDays = (long) array[1];
                            return List.of(
                                defaultLabel(row.get(BiColumns.PROBLEM_NUMBER), "—"),
                                defaultLabel(row.get(BiColumns.ROOT_CAUSE_CATEGORY), "未标注"),
                                String.valueOf(agingDays),
                                defaultLabel(row.get(BiColumns.RELATED_INCIDENT_COUNT), "0"),
                                defaultLabel(row.get(BiColumns.PRIORITY), "—"),
                                defaultLabel(row.get(BiColumns.STATUS), "—")
                            );
                        }).toList()),
                table("cross-request-surge", "请求激增预警",
                    List.of("请求类别", "本周请求数", "上周请求数", "环比增长", "同期事件数"),
                    buildRequestSurgeData(rawData))
            )
        );
    }

    private List<IncidentBegin> filterP1P2IncidentBegins(List<Map<String, String>> incidents) {
        return incidents.stream()
            .map(inc -> new IncidentBegin(inc, BiDateUtils.parseDate(inc.get(BiColumns.BEGIN_DATE))))
            .filter(ib -> ib.beginDate() != null)
            .filter(ib -> {
                String priority = clean(ib.incident().get(BiColumns.PRIORITY));
                return "P1".equalsIgnoreCase(priority) || "P2".equalsIgnoreCase(priority);
            })
            .collect(Collectors.toList());
    }

    private List<Map<String, String>> findIncidentsWithin48h(List<IncidentBegin> incidentBegins, LocalDateTime changeEnd) {
        if (changeEnd == null) return List.of();
        return incidentBegins.stream()
            .filter(ib -> {
                long hours = java.time.Duration.between(changeEnd, ib.beginDate()).toHours();
                return hours > 0 && hours <= 48;
            })
            .map(IncidentBegin::incident)
            .collect(Collectors.toList());
    }

    private List<List<String>> buildCrossChangeIncidentDetailRows(List<Map<String, String>> changes, List<Map<String, String>> incidents) {
        List<IncidentBegin> p1p2Begins = filterP1P2IncidentBegins(incidents);
        return changes.stream()
            .filter(ch -> isNonEmpty(ch.get(BiColumns.INCIDENT_CAUSED)))
            .limit(15)
            .map(ch -> {
                LocalDateTime actualEnd = BiDateUtils.parseDate(ch.get(BiColumns.ACTUAL_END));
                String linkedIncidents = findIncidentsWithin48h(p1p2Begins, actualEnd).stream()
                    .map(inc -> defaultLabel(inc.get(BiColumns.ORDER_NUMBER), ""))
                    .filter(s -> !s.isBlank())
                    .reduce((a, b) -> a + "," + b)
                    .orElse("—");
                return List.of(
                    defaultLabel(ch.get(BiColumns.CHANGE_NUMBER), "—"),
                    defaultLabel(ch.get(BiColumns.TITLE), "—"),
                    actualEnd != null ? actualEnd.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) : "—",
                    linkedIncidents,
                    defaultLabel(ch.get(BiColumns.RISK), "—")
                );
            }).toList();
    }

    private List<ChartDatum> buildChangeIncidentTrendData(BiRawData rawData) {
        List<Map<String, String>> changes = rawData.changes();
        List<IncidentBegin> p1p2Begins = filterP1P2IncidentBegins(rawData.incidents());

        Map<String, Long> changeByWeek = new LinkedHashMap<>();
        Map<String, Long> causedByWeek = new LinkedHashMap<>();

        for (Map<String, String> ch : changes) {
            LocalDateTime date = BiDateUtils.parseDate(ch.get(BiColumns.REQUESTED_DATE));
            if (date == null) date = BiDateUtils.parseDate(ch.get(BiColumns.ACTUAL_END));
            if (date == null) continue;
            String week = formatPeriodLabel(date, "weekly");
            changeByWeek.merge(week, 1L, Long::sum);
            LocalDateTime actualEnd = BiDateUtils.parseDate(ch.get(BiColumns.ACTUAL_END));
            long p1p2 = findIncidentsWithin48h(p1p2Begins, actualEnd).size();
            causedByWeek.merge(week, p1p2, Long::sum);
        }

        Set<String> allWeeks = new TreeSet<>(changeByWeek.keySet());
        List<ChartDatum> result = new ArrayList<>();
        for (String week : allWeeks) {
            long total = changeByWeek.getOrDefault(week, 0L);
            long caused = causedByWeek.getOrDefault(week, 0L);
            result.add(new ChartDatum(week + "|" + total + "|" + caused, total));
        }
        return result;
    }

    private List<ChartDatum> buildChangeHeatmapData(BiRawData rawData) {
        List<Map<String, String>> changes = rawData.changes();
        List<IncidentBegin> p1p2Begins = filterP1P2IncidentBegins(rawData.incidents());

        // Build 7x24 grid: key = "dow|hour", value = [changeCount, incidentCount]
        Map<String, long[]> grid = new LinkedHashMap<>();
        for (int dow = 1; dow <= 7; dow++) {
            for (int hour = 0; hour < 24; hour++) {
                grid.put(dow + "|" + hour, new long[]{0, 0});
            }
        }

        for (Map<String, String> ch : changes) {
            LocalDateTime date = BiDateUtils.parseDate(ch.get(BiColumns.ACTUAL_START));
            if (date == null) continue;
            int dow = date.getDayOfWeek().getValue(); // 1=Mon, 7=Sun
            int hour = date.getHour();
            String key = dow + "|" + hour;
            grid.get(key)[0]++;
            if (isNonEmpty(ch.get(BiColumns.INCIDENT_CAUSED))) {
                LocalDateTime actualEnd = BiDateUtils.parseDate(ch.get(BiColumns.ACTUAL_END));
                List<Map<String, String>> linked = findIncidentsWithin48h(p1p2Begins, actualEnd);
                for (Map<String, String> inc : linked) {
                    LocalDateTime incBegin = BiDateUtils.parseDate(inc.get(BiColumns.BEGIN_DATE));
                    if (incBegin == null) continue;
                    int incDow = incBegin.getDayOfWeek().getValue();
                    int incHour = incBegin.getHour();
                    grid.get(incDow + "|" + incHour)[1]++;
                }
            }
        }

        return grid.entrySet().stream()
            .map(e -> new ChartDatum(
                e.getKey() + "|" + e.getValue()[0] + "|" + e.getValue()[1],
                e.getValue()[0]))
            .toList();
    }

    private List<ChartDatum> buildTechDebtBubbleData(BiRawData rawData) {
        List<Map<String, String>> problems = rawData.problems();

        // Group problems by CI Affected
        Map<String, List<Map<String, String>>> byCi = problems.stream()
            .collect(Collectors.groupingBy(
                row -> defaultLabel(row.get(BiColumns.CI_AFFECTED), "未标注"),
                LinkedHashMap::new, Collectors.toList()));

        List<ChartDatum> result = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, String>>> entry : byCi.entrySet()) {
            String ci = entry.getKey();
            List<Map<String, String>> probs = entry.getValue();

            // Count open problems and compute average aging for open ones
            long openCount = probs.stream()
                .filter(row -> !matchesAny(clean(row.get(BiColumns.STATUS)), List.of("Resolved", "Closed")))
                .count();
            double avgAging = probs.stream()
                .filter(row -> !matchesAny(clean(row.get(BiColumns.STATUS)), List.of("Resolved", "Closed")))
                .mapToLong(row -> {
                    LocalDateTime logged = BiDateUtils.parseDate(row.get(BiColumns.LOGGED_DATE));
                    if (logged == null) return 0;
                    LocalDateTime resolved = BiDateUtils.parseDate(row.get(BiColumns.RESOLVED_AT));
                    LocalDateTime end = resolved != null ? resolved : LocalDateTime.now();
                    return java.time.Duration.between(logged, end).toDays();
                }).average().orElse(0);

            // Sum of all related incidents
            long totalIncidents = probs.stream()
                .mapToLong(row -> parseLong(row.get(BiColumns.RELATED_INCIDENT_COUNT)))
                .sum();

            // Dominant root cause category
            String dominantRcc = probs.stream()
                .collect(Collectors.groupingBy(r -> defaultLabel(r.get(BiColumns.ROOT_CAUSE_CATEGORY), "未标注"), Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse("未标注");

            // Label: "ciName|dominantRcc|avgAging|openCount|totalIncidents"
            String label = ci + "|" + dominantRcc + "|" + String.format("%.1f", avgAging) + "|" + openCount + "|" + totalIncidents;
            result.add(new ChartDatum(label, totalIncidents));
        }

        // Sort by total incidents descending, take top 20
        result.sort((a, b) -> Double.compare(b.value(), a.value()));
        return result.stream().limit(20).toList();
    }

    private List<ChartDatum> buildRequestIncidentOverlap(BiRawData rawData) {
        Map<String, Long> requestByWeek = new LinkedHashMap<>();
        for (Map<String, String> req : rawData.requests()) {
            LocalDateTime date = BiDateUtils.parseDate(req.get(BiColumns.REQUESTED_DATE));
            if (date == null) continue;
            String week = formatPeriodLabel(date, "weekly");
            requestByWeek.merge(week, 1L, Long::sum);
        }

        Map<String, Long> incidentByWeek = new LinkedHashMap<>();
        for (Map<String, String> inc : rawData.incidents()) {
            LocalDateTime date = BiDateUtils.parseDate(inc.get(BiColumns.BEGIN_DATE));
            if (date == null) continue;
            String week = formatPeriodLabel(date, "weekly");
            incidentByWeek.merge(week, 1L, Long::sum);
        }

        Set<String> allWeeks = new TreeSet<>();
        allWeeks.addAll(requestByWeek.keySet());
        allWeeks.addAll(incidentByWeek.keySet());

        List<ChartDatum> result = new ArrayList<>();
        for (String week : allWeeks) {
            long reqCount = requestByWeek.getOrDefault(week, 0L);
            long incCount = incidentByWeek.getOrDefault(week, 0L);
            result.add(new ChartDatum(week + "|" + reqCount + "|" + incCount, reqCount));
        }
        return result;
    }

    private List<List<String>> buildRequestSurgeData(BiRawData rawData) {
        LocalDateTime now = LocalDateTime.now();
        String thisWeek = formatPeriodLabel(now, "weekly");
        String lastWeek = formatPeriodLabel(now.minusDays(7), "weekly");

        Map<String, Long> thisWeekReqs = rawData.requests().stream()
            .filter(r -> BiDateUtils.parseDate(r.get(BiColumns.REQUESTED_DATE)) != null && formatPeriodLabel(BiDateUtils.parseDate(r.get(BiColumns.REQUESTED_DATE)), "weekly").equals(thisWeek))
            .collect(Collectors.groupingBy(r -> defaultLabel(r.get(BiColumns.CATEGORY), "未标注"), Collectors.counting()));

        Map<String, Long> lastWeekReqs = rawData.requests().stream()
            .filter(r -> BiDateUtils.parseDate(r.get(BiColumns.REQUESTED_DATE)) != null && formatPeriodLabel(BiDateUtils.parseDate(r.get(BiColumns.REQUESTED_DATE)), "weekly").equals(lastWeek))
            .collect(Collectors.groupingBy(r -> defaultLabel(r.get(BiColumns.CATEGORY), "未标注"), Collectors.counting()));

        long thisWeekIncidents = rawData.incidents().stream()
            .filter(inc -> BiDateUtils.parseDate(inc.get(BiColumns.BEGIN_DATE)) != null && formatPeriodLabel(BiDateUtils.parseDate(inc.get(BiColumns.BEGIN_DATE)), "weekly").equals(thisWeek))
            .count();

        Set<String> allCats = new TreeSet<>(thisWeekReqs.keySet());
        allCats.addAll(lastWeekReqs.keySet());

        return allCats.stream()
            .map(cat -> {
                long tw = thisWeekReqs.getOrDefault(cat, 0L);
                long lw = lastWeekReqs.getOrDefault(cat, 0L);
                String growth = lw > 0 ? String.format("%.0f%%", (tw - lw) * 100.0 / lw) : (tw > 0 ? "+∞" : "0%");
                return List.of(cat, String.valueOf(tw), String.valueOf(lw), growth, String.valueOf(thisWeekIncidents));
            })
            .sorted((a, b) -> Long.compare(Long.parseLong(b.get(1)), Long.parseLong(a.get(1))))
            .limit(10)
            .toList();
    }

    private TabContent buildWorkforce(BiRawData rawData) {
        var wm = metricsService.getWorkforceMetrics(rawData, 0);

        String slaTone = toneFromScore(wm.overallSlaRate(), 0.8);
        String deliveryTone = wm.avgDeliveryHours() > 0 ? toneFromScore(1.0 - Math.min(wm.avgDeliveryHours() / 48.0, 1.0), 0.5) : "neutral";
        String changeSpeedTone = wm.avgChangeSpeedHours() > 0 ? toneFromScore(1.0 - Math.min(wm.avgChangeSpeedHours() / 8.0, 1.0), 0.5) : "neutral";
        String ftTone = toneFromScore(wm.firstTimeSuccessRate(), 0.8);
        String satTone = toneFromScore(wm.avgSatisfaction() / 5.0, 0.7);
        String prTone = toneFromScore(wm.problemFixRate(), 0.5);
        String backlogTone = wm.backlog() > 50 ? "danger" : wm.backlog() > 20 ? "warning" : "success";

        List<MetricCard> cards = List.of(
            card("wf-avg-throughput", "人均处理量", String.format("%.1f", wm.avgThroughput()), "neutral"),
            card("wf-backlog", "积压工单数", String.valueOf(wm.backlog()), backlogTone),
            card("wf-avg-delivery-time", "平均交付耗时", formatHours(wm.avgDeliveryHours()), deliveryTone),
            card("wf-sla-rate", "SLA达标率", percentage(wm.overallSlaRate()), slaTone),
            card("wf-change-speed", "变更实施速度", formatHours(wm.avgChangeSpeedHours()), changeSpeedTone),
            card("wf-first-time-success", "一次性成功率", percentage(wm.firstTimeSuccessRate()), ftTone),
            card("wf-avg-satisfaction", "用户满意度", String.format("%.1f / 5", wm.avgSatisfaction()), satTone),
            card("wf-problem-fix-rate", "问题根治率", percentage(wm.problemFixRate()), prTone)
        );

        List<BiModels.ChartSection> charts = List.of(
            buildWorkloadDistribution(wm.persons()),
            buildEfficiencyHeatmap(rawData, wm.persons()),
            buildPerformanceMatrix(wm.persons()),
            buildPersonRadar(wm.persons())
        );

        List<BiModels.TableSection> tables = List.of(
            buildFirefighterTable(rawData),
            buildTechDebtTable(rawData),
            buildHighRiskChangeTable(rawData)
        );

        return new TabContent("workforce", "Workforce",
            "团队人员效能分析：产量、效率与质量三维评估。", null, null, cards, charts, tables);
    }

    private String formatHours(double hours) {
        if (hours < 1) return String.format("%.0fm", hours * 60);
        return String.format("%.1fh", hours);
    }

    private String toneFromScore(double score, double threshold) {
        if (score >= threshold) return "success";
        if (score >= threshold * 0.7) return "warning";
        return "danger";
    }

    // ── Workforce chart builders ───────────────────────────────────────

    private BiModels.ChartSection buildWorkloadDistribution(List<MetricsModels.PersonMetricsSummary> persons) {
        var top = persons.stream().limit(10).toList();
        List<ChartDatum> items = top.stream()
            .map(m -> new ChartDatum(
                m.name() + "|" + m.incidentCount() + "|" + m.changeCount() + "|" + m.requestCount() + "|" + m.problemCount(),
                m.incidentCount() + m.changeCount() + m.requestCount() + m.problemCount()))
            .toList();
        return new BiModels.ChartSection("wf-workload-distribution", "团队工作负载分布", "grouped-bar", items,
            new BiModels.ChartConfig(List.of("事件", "变更", "请求", "问题"), null,
                List.of("#5b8db8", "#10b981", "#f59e0b", "#8b7fc7"), "人员", "工单数"));
    }

    private BiModels.ChartSection buildEfficiencyHeatmap(BiRawData rawData, List<MetricsModels.PersonMetricsSummary> persons) {
        var topPersons = persons.stream().limit(8).map(MetricsModels.PersonMetricsSummary::name).toList();
        var topPersonSet = new HashSet<>(topPersons);

        // Collect raw time data: person → category → [cumulativeHours, count]
        Map<String, Map<String, double[]>> cellMap = new LinkedHashMap<>();
        // Incidents: Resolution Time(m) -> hours, cap at 72h to suppress outliers
        for (var row : rawData.incidents()) {
            String person = defaultLabel(row.get(BiColumns.RESOLVER), "未标注");
            if (!topPersonSet.contains(person)) continue;
            String cat = clean(row.get(BiColumns.CATEGORY));
            if (cat.isBlank()) continue;
            double hours = Math.min(parseDouble(row.get(BiColumns.RESOLUTION_TIME_M)) / 60.0, 72.0);
            if (hours <= 0) continue;
            cellMap.computeIfAbsent(person, k -> new LinkedHashMap<>())
                .computeIfAbsent(cat, k -> new double[2]);
            cellMap.get(person).get(cat)[0] += hours;
            cellMap.get(person).get(cat)[1]++;
        }
        // Requests: Fulfillment Time(h), cap at 72h
        for (var row : rawData.requests()) {
            String person = defaultLabel(row.get(BiColumns.ASSIGNED_TO), "未标注");
            if (!topPersonSet.contains(person)) continue;
            String cat = clean(row.get(BiColumns.CATEGORY));
            if (cat.isBlank()) continue;
            double hours = Math.min(parseDouble(row.get(BiColumns.REQUEST_RESOLUTION_TIME_M)) / 60.0, 72.0);
            if (hours <= 0) continue;
            cellMap.computeIfAbsent(person, k -> new LinkedHashMap<>())
                .computeIfAbsent(cat, k -> new double[2]);
            cellMap.get(person).get(cat)[0] += hours;
            cellMap.get(person).get(cat)[1]++;
        }

        // Count category frequencies to pick TOP 8
        Map<String, Integer> catCounts = new LinkedHashMap<>();
        for (var personCats : cellMap.values()) {
            for (var entry : personCats.entrySet()) {
                catCounts.merge(entry.getKey(), (int) entry.getValue()[1], Integer::sum);
            }
        }
        List<String> topCats = catCounts.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(8)
            .map(Map.Entry::getKey)
            .toList();

        // Sort categories by team-average fulfillment time descending (slowest first → skill gap)
        Map<String, Double> catTeamAvg = new LinkedHashMap<>();
        for (String cat : topCats) {
            double sum = 0;
            int count = 0;
            for (var personCats : cellMap.values()) {
                double[] arr = personCats.get(cat);
                if (arr != null && arr[1] >= 2) { sum += arr[0] / arr[1]; count++; }
            }
            catTeamAvg.put(cat, count > 0 ? sum / count : 0);
        }
        List<String> sortedCats = topCats.stream()
            .sorted((a, b) -> Double.compare(catTeamAvg.getOrDefault(b, 0.0), catTeamAvg.getOrDefault(a, 0.0)))
            .toList();

        // Build chart items — only cells with ≥ 2 samples for reliability
        List<ChartDatum> items = new ArrayList<>();
        for (String person : topPersons) {
            Map<String, double[]> personCats = cellMap.getOrDefault(person, Map.of());
            for (String cat : sortedCats) {
                double[] arr = personCats.get(cat);
                if (arr == null || arr[1] < 2) continue;
                double avgHours = arr[0] / arr[1];
                items.add(new ChartDatum(person + "|" + cat + "|" + String.format("%.1fh", avgHours), avgHours));
            }
        }
        return new BiModels.ChartSection("wf-efficiency-heatmap", "工单流转效率热力图", "heatmap", items,
            new BiModels.ChartConfig(null, null, null, "分类", "人员"));
    }

    private BiModels.ChartSection buildPerformanceMatrix(List<MetricsModels.PersonMetricsSummary> persons) {
        List<ChartDatum> items = persons.stream()
            .filter(m -> m.requestSlaRate() > 0 || m.avgCsat() > 0)
            .limit(15)
            .map(m -> new ChartDatum(
                m.name() + "|" + String.format("%.1f", m.requestSlaRate() * 100) + "|" + String.format("%.1f", m.avgCsat()),
                m.requestCount()))
            .toList();
        return new BiModels.ChartSection("wf-performance-matrix", "SLA达标率 vs 满意度", "bubble", items,
            new BiModels.ChartConfig(null, null, List.of("#5b8db8"), "SLA达标率(%)", "满意度"));
    }

    private BiModels.ChartSection buildPersonRadar(List<MetricsModels.PersonMetricsSummary> persons) {
        var top = persons.stream().limit(5).toList();
        if (top.isEmpty()) {
            return new BiModels.ChartSection("wf-person-radar", "个人综合素质雷达图", "radar", List.of(),
                new BiModels.ChartConfig(List.of(), Map.of(), List.of("#5b8db8"), null, null));
        }
        double maxCount = top.stream().mapToInt(m -> m.incidentCount() + m.changeCount() + m.requestCount() + m.problemCount()).max().orElse(1);
        List<String> names = top.stream().map(MetricsModels.PersonMetricsSummary::name).toList();
        Map<String, List<ChartDatum>> seriesData = new LinkedHashMap<>();
        List<String> dims = List.of("速度", "产量", "质量", "满意度", "难度");
        for (var m : top) {
            double speed = m.incidentSlaRate() * 100;
            int total = m.incidentCount() + m.changeCount() + m.requestCount() + m.problemCount();
            double volume = maxCount > 0 ? total / maxCount * 100 : 0;
            double quality = m.changeSuccessRate() * 100;
            double satisfaction = m.avgCsat() / 5.0 * 100;
            double difficulty = 50;
            List<ChartDatum> scores = new ArrayList<>();
            String[] dimArr = dims.toArray(new String[0]);
            double[] valArr = {speed, volume, quality, satisfaction, difficulty};
            for (int i = 0; i < dimArr.length; i++) {
                scores.add(new ChartDatum(dimArr[i], valArr[i]));
            }
            seriesData.put(m.name(), scores);
        }
        List<ChartDatum> items = dims.stream()
            .map(d -> new ChartDatum(d, 100))
            .toList();
        return new BiModels.ChartSection("wf-person-radar", "个人综合素质雷达图", "radar", items,
            new BiModels.ChartConfig(names, seriesData, List.of("#5b8db8", "#10b981", "#f59e0b", "#ef4444", "#8b7fc7"), null, null));
    }

    // ── Workforce table builders ───────────────────────────────────────

    private BiModels.TableSection buildFirefighterTable(BiRawData rawData) {
        Map<String, long[]> stats = new LinkedHashMap<>();
        Map<String, Set<String>> ciSets = new LinkedHashMap<>();
        for (var row : rawData.incidents()) {
            if (!matchesAny(clean(row.get(BiColumns.PRIORITY)), List.of("P1", "P2"))) continue;
            String resolver = defaultLabel(row.get(BiColumns.RESOLVER), "未标注");
            long[] s = stats.computeIfAbsent(resolver, k -> new long[4]);
            s[0]++;
            double rt = parseDouble(row.get(BiColumns.RESOLUTION_TIME_M));
            if (rt > 0) { s[1] += (long) rt; s[2]++; }
            if (isYes(row.get(BiColumns.SLA_COMPLIANT))) s[3]++;
            String ci = clean(row.get(BiColumns.CI_AFFECTED));
            if (!ci.isBlank()) ciSets.computeIfAbsent(resolver, k -> new HashSet<>()).add(ci);
        }
        List<List<String>> rows = stats.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
            .limit(10)
            .map(e -> {
                long[] s = e.getValue();
                String avgTime = s[2] > 0 ? formatMinutes((double) s[1] / s[2]) : "—";
                String slaRate = s[0] > 0 ? percentage(s[3], s[0]) : "—";
                long ciCount = ciSets.getOrDefault(e.getKey(), Set.of()).size();
                return List.of(e.getKey(), String.valueOf(s[0]), avgTime, String.valueOf(ciCount), slaRate);
            })
            .toList();
        return table("wf-firefighter-table", "救火先锋 TOP10", List.of("处理人", "P1/P2事件数", "平均解决时长", "涉及CI数", "SLA达标率"), rows);
    }

    private BiModels.TableSection buildTechDebtTable(BiRawData rawData) {
        Map<String, long[]> stats = new LinkedHashMap<>();
        Map<String, String> mainRootCause = new LinkedHashMap<>();
        for (var row : rawData.problems()) {
            if (!matchesAny(clean(row.get(BiColumns.STATUS)), List.of("Resolved", "Closed"))) continue;
            String resolver = defaultLabel(row.get(BiColumns.RESPONDER), "未标注");
            long[] s = stats.computeIfAbsent(resolver, k -> new long[3]);
            s[1]++;
            String fixVal = row.get(BiColumns.PERMANENT_FIX);
            if (fixVal != null && !fixVal.isBlank()) s[0]++;
            s[2] += (long) parseDouble(row.get(BiColumns.RELATED_INCIDENT_COUNT));
            String rc = clean(row.get(BiColumns.ROOT_CAUSE_CATEGORY));
            if (!rc.isBlank()) mainRootCause.merge(resolver, rc, (a, b) -> a);
        }
        List<List<String>> rows = stats.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
            .limit(10)
            .map(e -> {
                long[] s = e.getValue();
                String fixRate = s[1] > 0 ? percentage(s[0], s[1]) : "—";
                String rc = mainRootCause.getOrDefault(e.getKey(), "—");
                return List.of(e.getKey(), String.valueOf(s[0]), fixRate, String.valueOf(s[2]), rc);
            })
            .toList();
        return table("wf-tech-debt-table", "技术债务解决 TOP10", List.of("处理人", "根治问题数", "根治率", "关联事件数", "根因类别"), rows);
    }

    private BiModels.TableSection buildHighRiskChangeTable(BiRawData rawData) {
        Map<String, long[]> stats = new LinkedHashMap<>();
        for (var row : rawData.changes()) {
            String type = clean(row.get(BiColumns.CHANGE_TYPE));
            String risk = clean(row.get(BiColumns.RISK));
            if (!"Emergency".equalsIgnoreCase(type) && !"High".equalsIgnoreCase(risk)) continue;
            String impl = defaultLabel(row.get(BiColumns.ASSIGNED_TO), "未标注");
            long[] s = stats.computeIfAbsent(impl, k -> new long[4]);
            s[0]++;
            if (isSuccessful(row.get(BiColumns.SUCCESS))) s[1]++;
            if ("Backed_out".equalsIgnoreCase(clean(row.get(BiColumns.SUCCESS)))) s[2]++;
            if (isNonEmpty(row.get(BiColumns.INCIDENT_CAUSED))) s[3]++;
        }
        List<List<String>> rows = stats.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
            .limit(10)
            .map(e -> {
                long[] s = e.getValue();
                String successRate = s[0] > 0 ? percentage(s[1], s[0]) : "—";
                String backoutRate = s[0] > 0 ? percentage(s[2], s[0]) : "—";
                String causedRate = s[0] > 0 ? percentage(s[3], s[0]) : "—";
                return List.of(e.getKey(), String.valueOf(s[0]), successRate, backoutRate, causedRate);
            })
            .toList();
        return table("wf-highrisk-change-table", "高风险变更执行 TOP10", List.of("实施人", "高风险变更数", "成功率", "回退率", "致事件率"), rows);
    }

    private MetricCard card(String id, String label, Object value, String tone) {
        return new MetricCard(id, label, String.valueOf(value), tone);
    }

    private BiModels.ExecutiveSummary buildExecutiveSummaryContent(
            MetricsModels.ExecutiveMetrics exec,
            MetricsModels.IncidentMetrics incident,
            MetricsModels.ChangeMetrics change,
            MetricsModels.RequestMetrics request,
            MetricsModels.ProblemMetrics problem,
            BiRawData rawData) {

        boolean hasIncidents = !rawData.incidents().isEmpty();
        boolean hasChanges = !rawData.changes().isEmpty();
        boolean hasRequests = !rawData.requests().isEmpty();
        boolean hasProblems = !rawData.problems().isEmpty();

        List<BiModels.ExecutiveRisk> risks = exec.topRisks().stream()
            .map(r -> new BiModels.ExecutiveRisk(r.id(), r.priority(), r.title(), r.impact(), r.process(), r.value()))
            .toList();

        BiModels.RiskSummary riskSummary = new BiModels.RiskSummary(
            (int) exec.criticalCount(),
            (int) exec.warningCount(),
            (int) exec.attentionCount(),
            risks.stream().limit(5).toList()
        );

        List<BiModels.ProcessHealth> processHealths = exec.processScores().stream()
            .map(ps -> {
                String label = switch (ps.process()) {
                    case "incident" -> "事件";
                    case "change" -> "变更";
                    case "request" -> "请求";
                    case "problem" -> "问题";
                    default -> ps.process();
                };
                String summaryText = switch (ps.process()) {
                    case "incident" -> incidentHealthSummary(incident.slaRate(), incident.mttrHours());
                    case "change" -> "成功率 " + percentage(change.successRate()) + "，致事件率 " + percentage(change.totalCount() > 0 ? (double) change.incidentCausedCount() / change.totalCount() : 0);
                    case "request" -> "SLA " + percentage(request.slaRate()) + "，满意度 " + formatNumber(request.avgCsat());
                    case "problem" -> "关闭率 " + percentage(problem.closureRate());
                    default -> "";
                };
                boolean hasDomain = switch (ps.process()) {
                    case "incident" -> hasIncidents;
                    case "change" -> hasChanges;
                    case "request" -> hasRequests;
                    case "problem" -> hasProblems;
                    default -> false;
                };
                return new BiModels.ProcessHealth(ps.process(), label, formatScore(ps.score()),
                    hasDomain ? ps.tone() : "neutral", summaryText);
            })
            .toList();

        List<BiModels.TrendPoint> trendPoints = exec.monthlyTrend().stream()
            .map(tp -> new BiModels.TrendPoint(tp.period(), tp.value(), tp.sampleCount()))
            .toList();

        String summary = buildExecutiveSummarySentence(exec.grade(), riskSummary, processHealths);
        String changeHint = buildTrendHint(trendPoints);
        String periodLabel = buildPeriodLabel(rawData);

        return new BiModels.ExecutiveSummary(
            new BiModels.ExecutiveHero(formatScore(exec.overallScore()), exec.grade(), summary, changeHint, periodLabel),
            processHealths,
            riskSummary,
            new BiModels.TrendSection("月度健康趋势", "健康分与高优先级事件同步观察。", trendPoints)
        );
    }

    private BiModels.TableSection table(String id, String title, List<String> columns, List<List<String>> rows) {
        return new BiModels.TableSection(id, title, columns, rows);
    }

    private BiModels.SlaAnalysisSummary buildSlaAnalysisSummary(List<BusinessIntelligenceMetricsService.IncidentSlaRecord> incidents) {
        long responseBreached = incidents.stream().filter(record -> !record.responseMet()).count();
        long resolutionBreached = incidents.stream().filter(record -> !record.resolutionMet()).count();
        long bothBreached = incidents.stream().filter(record -> !record.responseMet() && !record.resolutionMet()).count();
        long overallBreached = incidents.stream().filter(BusinessIntelligenceMetricsService.IncidentSlaRecord::anyBreached).count();
        double overallRate = percentageValue(incidents.stream().filter(BusinessIntelligenceMetricsService.IncidentSlaRecord::overallMet).count(), incidents.size());
        double responseRate = percentageValue(incidents.stream().filter(BusinessIntelligenceMetricsService.IncidentSlaRecord::responseMet).count(), incidents.size());
        double resolutionRate = percentageValue(incidents.stream().filter(BusinessIntelligenceMetricsService.IncidentSlaRecord::resolutionMet).count(), incidents.size());
        List<BusinessIntelligenceMetricsService.IncidentSlaRecord> highPriority = incidents.stream().filter(record -> matchesAny(record.priority(), List.of("P1", "P2"))).toList();
        double highPriorityRate = percentageValue(highPriority.stream().filter(BusinessIntelligenceMetricsService.IncidentSlaRecord::overallMet).count(), highPriority.size());

        List<BiModels.SlaPriorityRow> priorityRows = PRIORITY_ORDER.stream()
            .map(priority -> buildPriorityRow(priority, incidents.stream().filter(record -> priority.equalsIgnoreCase(record.priority())).toList()))
            .filter(Objects::nonNull)
            .toList();

        List<BiModels.SlaRiskRow> categoryRisks = rankSlaRisks(incidents, BusinessIntelligenceMetricsService.IncidentSlaRecord::category);
        List<BiModels.SlaRiskRow> resolverRisks = rankSlaRisks(incidents, BusinessIntelligenceMetricsService.IncidentSlaRecord::resolver);
        List<BiModels.SlaTrendPoint> trends = buildSlaTrendPoints(incidents);

        String summary = buildSlaSummarySentence(responseRate, resolutionRate, priorityRows, categoryRisks);
        return new BiModels.SlaAnalysisSummary(
            new BiModels.SlaHero(
                summary,
                percentage(overallRate),
                percentage(responseRate),
                percentage(resolutionRate),
                overallBreached,
                percentage(highPriorityRate)
            ),
            buildSlaDimensionCard("响应 SLA", responseRate, incidents.stream().mapToDouble(BusinessIntelligenceMetricsService.IncidentSlaRecord::responseMinutes).average().orElse(0), percentile(incidents.stream().map(BusinessIntelligenceMetricsService.IncidentSlaRecord::responseMinutes).toList(), 0.9), responseBreached),
            buildSlaDimensionCard("解决 SLA", resolutionRate, incidents.stream().mapToDouble(BusinessIntelligenceMetricsService.IncidentSlaRecord::resolutionMinutes).average().orElse(0), percentile(incidents.stream().map(BusinessIntelligenceMetricsService.IncidentSlaRecord::resolutionMinutes).toList(), 0.9), resolutionBreached),
            priorityRows,
            new BiModels.SlaComparisonChart("优先级响应 vs 解决对比", priorityRows.stream()
                .map(row -> new BiModels.SlaComparisonDatum(
                    row.priority(),
                    parsePercentage(row.responseComplianceRate()),
                    parsePercentage(row.resolutionComplianceRate())
                )).toList()),
            categoryRisks,
            resolverRisks,
            trends,
            new BiModels.SlaViolationBreakdown(responseBreached, resolutionBreached, bothBreached),
            incidents.stream()
                .filter(BusinessIntelligenceMetricsService.IncidentSlaRecord::anyBreached)
                .sorted(Comparator
                    .comparing((BusinessIntelligenceMetricsService.IncidentSlaRecord record) -> priorityRank(record.priority()))
                    .thenComparing((BusinessIntelligenceMetricsService.IncidentSlaRecord record) -> violationSeverity(record.violationType()))
                    .thenComparing(BusinessIntelligenceMetricsService.IncidentSlaRecord::resolutionMinutes, Comparator.reverseOrder()))
                .limit(12)
                .map(record -> new BiModels.SlaViolationSample(
                    defaultLabel(record.orderNumber(), "—"),
                    defaultLabel(record.orderName(), "—"),
                    defaultLabel(record.priority(), "—"),
                    defaultLabel(record.category(), "未标注"),
                    defaultLabel(record.resolver(), "未分配"),
                    formatMinutes(record.responseMinutes()),
                    formatHours(record.resolutionMinutes() / 60.0),
                    record.violationType()
                )).toList()
        );
    }

    private BiModels.SlaPriorityRow buildPriorityRow(String priority, List<BusinessIntelligenceMetricsService.IncidentSlaRecord> rows) {
        if (rows.isEmpty()) {
            return null;
        }
        return new BiModels.SlaPriorityRow(
            priority,
            rows.size(),
            percentage(percentageValue(rows.stream().filter(BusinessIntelligenceMetricsService.IncidentSlaRecord::responseMet).count(), rows.size())),
            percentage(percentageValue(rows.stream().filter(BusinessIntelligenceMetricsService.IncidentSlaRecord::resolutionMet).count(), rows.size())),
            rows.stream().filter(BusinessIntelligenceMetricsService.IncidentSlaRecord::anyBreached).count(),
            formatHours(rows.stream().mapToDouble(BusinessIntelligenceMetricsService.IncidentSlaRecord::resolutionMinutes).average().orElse(0) / 60.0)
        );
    }

    private BiModels.SlaDimensionCard buildSlaDimensionCard(String title, double complianceRate, double averageMinutes, double p90Minutes, long breachedCount) {
        return new BiModels.SlaDimensionCard(
            title,
            percentage(complianceRate),
            title.contains("响应") ? formatMinutes(averageMinutes) : formatHours(averageMinutes / 60.0),
            title.contains("响应") ? formatMinutes(p90Minutes) : formatHours(p90Minutes / 60.0),
            breachedCount,
            toneFromScore(complianceRate, 0.9, 0.75),
            complianceRate >= 0.9 ? "整体稳定" : (complianceRate >= 0.75 ? "需重点关注" : "当前主要风险")
        );
    }

    private List<BiModels.SlaRiskRow> rankSlaRisks(List<BusinessIntelligenceMetricsService.IncidentSlaRecord> incidents, Function<BusinessIntelligenceMetricsService.IncidentSlaRecord, String> classifier) {
        return incidents.stream()
            .collect(Collectors.groupingBy(record -> defaultLabel(classifier.apply(record), "未标注"), LinkedHashMap::new, Collectors.toList()))
            .entrySet()
            .stream()
            .map(entry -> {
                List<BusinessIntelligenceMetricsService.IncidentSlaRecord> rows = entry.getValue();
                long breachedCount = rows.stream().filter(BusinessIntelligenceMetricsService.IncidentSlaRecord::anyBreached).count();
                double resolutionRate = percentageValue(rows.stream().filter(BusinessIntelligenceMetricsService.IncidentSlaRecord::resolutionMet).count(), rows.size());
                return Map.entry(
                    breachedCount * 1000 + Math.round((1 - resolutionRate) * 100) + rows.size(),
                    new BiModels.SlaRiskRow(
                        entry.getKey(),
                        rows.size(),
                        percentage(percentageValue(rows.stream().filter(BusinessIntelligenceMetricsService.IncidentSlaRecord::responseMet).count(), rows.size())),
                        percentage(resolutionRate),
                        breachedCount,
                        formatHours(rows.stream().mapToDouble(BusinessIntelligenceMetricsService.IncidentSlaRecord::resolutionMinutes).average().orElse(0) / 60.0)
                    )
                );
            })
            .sorted((left, right) -> Long.compare(right.getKey(), left.getKey()))
            .limit(5)
            .map(Map.Entry::getValue)
            .toList();
    }

    private List<BiModels.SlaTrendPoint> buildSlaTrendPoints(List<BusinessIntelligenceMetricsService.IncidentSlaRecord> incidents) {
        return incidents.stream()
            .filter(record -> record.beginDate() != null)
            .collect(Collectors.groupingBy(record -> YearMonth.from(record.beginDate()), LinkedHashMap::new, Collectors.toList()))
            .entrySet()
            .stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> {
                List<BusinessIntelligenceMetricsService.IncidentSlaRecord> rows = entry.getValue();
                return new BiModels.SlaTrendPoint(
                    entry.getKey().toString(),
                    parsePercentage(percentage(percentageValue(rows.stream().filter(BusinessIntelligenceMetricsService.IncidentSlaRecord::overallMet).count(), rows.size()))),
                    parsePercentage(percentage(percentageValue(rows.stream().filter(BusinessIntelligenceMetricsService.IncidentSlaRecord::responseMet).count(), rows.size()))),
                    parsePercentage(percentage(percentageValue(rows.stream().filter(BusinessIntelligenceMetricsService.IncidentSlaRecord::resolutionMet).count(), rows.size()))),
                    rows.stream().filter(BusinessIntelligenceMetricsService.IncidentSlaRecord::anyBreached).count()
                );
            })
            .toList();
    }

    private List<ChartDatum> buildSlaWeeklyTrendData(BiRawData rawData, String startDate, String endDate) {
        TrendResult responseTrend = metricsService.getTrends(rawData, "incidents", "response_sla_rate", "week", null, startDate, endDate);
        TrendResult resolutionTrend = metricsService.getTrends(rawData, "incidents", "resolution_sla_rate", "week", null, startDate, endDate);
        TrendResult p12Trend = metricsService.getTrends(rawData, "incidents", "p12_sla_rate", "week", null, startDate, endDate);
        return mergeTrendSeries(List.of(responseTrend, resolutionTrend, p12Trend));
    }

    private List<MetricCard> buildRequestSlaCards(List<BusinessIntelligenceMetricsService.RequestSlaRecord> records) {
        long total = records.size();
        long overallMet = records.stream().filter(BusinessIntelligenceMetricsService.RequestSlaRecord::overallMet).count();
        long breached = total - overallMet;
        String overallRate = percentage(overallMet, total);

        double avgDeliveryHours = records.stream()
            .mapToDouble(BusinessIntelligenceMetricsService.RequestSlaRecord::resolutionMinutes).filter(v -> v > 0).average().orElse(0) / 60.0;

        double breachedCsat = records.stream()
            .filter(BusinessIntelligenceMetricsService.RequestSlaRecord::anyBreached)
            .mapToDouble(BusinessIntelligenceMetricsService.RequestSlaRecord::satisfactionScore).filter(v -> v > 0).average().orElse(0);

        return List.of(
            card("req-sla-overall", "请求SLA达成率", overallRate, toneFromRate(overallRate)),
            card("req-sla-breached", "请求违约数", String.valueOf(breached), breached > 0 ? "warning" : "success"),
            card("req-sla-avg-delivery", "平均交付时长", String.format(Locale.ROOT, "%.1fh", avgDeliveryHours), "neutral"),
            card("req-sla-breached-csat", "违约关联满意度", formatNumber(breachedCsat), breachedCsat > 0 && breachedCsat < 3.5 ? "danger" : "success")
        );
    }

    private List<ChartSection> buildRequestSlaCharts(List<BusinessIntelligenceMetricsService.RequestSlaRecord> records, BiRawData rawData, String startDate, String endDate) {
        // SLA + Satisfaction trend (combo)
        TrendResult slaTrend = metricsService.getTrends(rawData, "requests", "sla_rate", "week", null, startDate, endDate);
        TrendResult csatTrend = metricsService.getTrends(rawData, "requests", "csat", "week", null, startDate, endDate);
        List<ChartDatum> trendData = mergeTrendSeries(List.of(slaTrend, csatTrend));

        // Catalog comparison (grouped-bar): response vs resolution compliance per catalog_item
        List<ChartDatum> catalogComparison = records.stream()
            .collect(Collectors.groupingBy(r -> defaultLabel(r.catalogItem(), "未标注"), LinkedHashMap::new, Collectors.toList()))
            .entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
            .limit(8)
            .map(entry -> {
                List<BusinessIntelligenceMetricsService.RequestSlaRecord> group = entry.getValue();
                long respMet = group.stream().filter(BusinessIntelligenceMetricsService.RequestSlaRecord::responseMet).count();
                long resoMet = group.stream().filter(BusinessIntelligenceMetricsService.RequestSlaRecord::resolutionMet).count();
                double respRate = percentageValue(respMet, group.size()) * 100.0;
                double resoRate = percentageValue(resoMet, group.size()) * 100.0;
                return new ChartDatum(
                    entry.getKey() + "|" + String.format(Locale.ROOT, "%.1f", respRate) + "|" + String.format(Locale.ROOT, "%.1f", resoRate),
                    respRate);
            }).toList();

        // Violation by department (pie)
        List<ChartDatum> violationByDept = records.stream()
            .filter(BusinessIntelligenceMetricsService.RequestSlaRecord::anyBreached)
            .collect(Collectors.groupingBy(r -> defaultLabel(r.requesterDept(), "未标注"), LinkedHashMap::new, Collectors.counting()))
            .entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(8)
            .map(e -> new ChartDatum(e.getKey(), e.getValue()))
            .toList();

        // Violation by catalog (pie)
        List<ChartDatum> violationByCatalog = records.stream()
            .filter(BusinessIntelligenceMetricsService.RequestSlaRecord::anyBreached)
            .collect(Collectors.groupingBy(r -> defaultLabel(r.catalogItem(), "未标注"), LinkedHashMap::new, Collectors.counting()))
            .entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(8)
            .map(e -> new ChartDatum(e.getKey(), e.getValue()))
            .toList();

        return List.of(
            comboChart("req-sla-trend", "请求SLA与满意度趋势", trendData,
                List.of("SLA达成率", "平均满意度"),
                List.of("#8b5cf6", "#10b981")),
            new ChartSection("req-sla-catalog-comparison", "服务目录SLA达成率对比", "grouped-bar",
                catalogComparison,
                new ChartConfig(List.of("响应达成率", "解决达成率"), null, List.of("#10b981", "#5b8db8"), "服务目录", "达成率(%)")),
            pieChart("req-sla-violation-by-dept", "违约请求部门分布", violationByDept,
                List.of("#8b7fc7", "#10b981", "#f59e0b", "#ef4444", "#5b8db8", "#c97082", "#5e9bb5", "#7ca65a")),
            pieChart("req-sla-violation-by-catalog", "违约服务目录分布", violationByCatalog,
                List.of("#5b8db8", "#10b981", "#f59e0b", "#ef4444", "#8b7fc7", "#c97082", "#5e9bb5", "#7ca65a"))
        );
    }

    private List<BiModels.TableSection> buildRequestSlaTables(List<BusinessIntelligenceMetricsService.RequestSlaRecord> records) {
        return List.of(
            table("req-sla-violation-samples", "请求违约及低满意度样本",
                List.of("编号", "标题", "服务目录", "请求部门", "处理人", "响应时长", "解决时长", "违约类型", "满意度"),
                records.stream()
                    .filter(BusinessIntelligenceMetricsService.RequestSlaRecord::anyBreached)
                    .sorted(Comparator
                        .comparing((BusinessIntelligenceMetricsService.RequestSlaRecord r) -> priorityRank(r.priority()))
                        .thenComparing(BusinessIntelligenceMetricsService.RequestSlaRecord::resolutionMinutes, Comparator.reverseOrder()))
                    .limit(12)
                    .map(r -> List.of(
                        defaultLabel(r.orderNumber(), "—"),
                        defaultLabel(r.title(), "—"),
                        defaultLabel(r.catalogItem(), "未标注"),
                        defaultLabel(r.requesterDept(), "未标注"),
                        defaultLabel(r.assignee(), "未分配"),
                        formatHours(r.responseMinutes() / 60.0),
                        formatHours(r.resolutionMinutes() / 60.0),
                        r.violationType(),
                        r.satisfactionScore() > 0 ? String.format(Locale.ROOT, "%.1f", r.satisfactionScore()) : "—"
                    )).toList())
        );
    }

    private String buildSlaSummarySentence(double responseRate, double resolutionRate, List<BiModels.SlaPriorityRow> priorityRows, List<BiModels.SlaRiskRow> categoryRisks) {
        BiModels.SlaPriorityRow weakestPriority = priorityRows.stream()
            .min(Comparator.comparingDouble(row -> parsePercentage(row.resolutionComplianceRate())))
            .orElse(null);
        String priorityLabel = weakestPriority == null ? "高优先级" : weakestPriority.priority();
        String categoryLabel = categoryRisks.isEmpty() ? "重点类别" : categoryRisks.getFirst().label();
        if (resolutionRate < 0.75 && responseRate >= 0.9) {
            return "响应履约整体稳定，但解决履约在" + priorityLabel + "与" + categoryLabel + "上持续承压。";
        }
        if (responseRate < 0.9 && resolutionRate < 0.75) {
            return "响应与解决双环节均存在压力，当前需优先收敛" + priorityLabel + "工单的履约风险。";
        }
        return "整体履约可控，建议持续盯防" + priorityLabel + "和" + categoryLabel + "的波动。";
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

    private List<List<String>> rowsFromChart(List<ChartDatum> items) {
        return items.stream()
            .map(item -> List.of(item.label(), formatNumber(item.value())))
            .toList();
    }

    private long countByValue(List<Map<String, String>> rows, String key, String value) {
        return rows.stream().filter(row -> clean(row.get(key)).equalsIgnoreCase(value)).count();
    }

    private boolean isYes(String value) {
        return clean(value).equalsIgnoreCase("Yes");
    }

    private boolean isSuccessful(String value) {
        return clean(value).equalsIgnoreCase("Successful");
    }

    private boolean isNonEmpty(String value) {
        return value != null && !value.isBlank();
    }

    private boolean matchesAny(String value, List<String> candidates) {
        return candidates.stream().anyMatch(candidate -> candidate.equalsIgnoreCase(value));
    }

    private String percentage(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value * 100.0);
    }

    private String percentage(long numerator, long denominator) {
        if (denominator <= 0) {
            return "0.0%";
        }
        return String.format(Locale.ROOT, "%.1f%%", numerator * 100.0 / denominator);
    }

    private double percentageValue(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0;
        }
        return numerator * 1.0 / denominator;
    }

    private double average(List<Map<String, String>> rows, String key) {
        return rows.stream()
            .map(row -> parseDouble(row.get(key)))
            .filter(value -> value > 0)
            .mapToDouble(Double::doubleValue)
            .average()
            .orElse(0);
    }

    private String formatNumber(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private String formatScore(double value) {
        return String.format(Locale.ROOT, "%.0f", value);
    }

    private String formatMinutes(double value) {
        return String.format(Locale.ROOT, "%.1fm", value);
    }

    private double percentile(List<Double> values, double percentile) {
        List<Double> filtered = values.stream().filter(value -> value >= 0).sorted().toList();
        if (filtered.isEmpty()) {
            return 0;
        }
        int index = Math.min(filtered.size() - 1, (int) Math.floor((filtered.size() - 1) * percentile));
        return filtered.get(index);
    }

    private double parsePercentage(String percentageText) {
        return parseDouble(percentageText.replace("%", ""));
    }

    private long parseLong(String value) {
        try {
            return Math.round(Double.parseDouble(clean(value)));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private double parseDouble(String value) {
        try {
            return Double.parseDouble(clean(value));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String defaultLabel(String value, String fallback) {
        String normalized = clean(value);
        return normalized.isBlank() ? fallback : normalized;
    }

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

    private String toneFromScore(double score, double goodThreshold, double warningThreshold) {
        if (score >= goodThreshold) {
            return "success";
        }
        if (score >= warningThreshold) {
            return "warning";
        }
        return "danger";
    }

    private String toneFromRate(String percentageText) {
        return toneFromScore(parsePercentage(percentageText) / 100.0, 0.9, 0.75);
    }

    private int priorityRank(String priority) {
        int index = PRIORITY_ORDER.indexOf(priority.toUpperCase(Locale.ROOT));
        return index >= 0 ? index : PRIORITY_ORDER.size();
    }

    private int violationSeverity(String violationType) {
        return switch (violationType) {
        case "both_breached" -> 0;
        case "resolution_breached" -> 1;
        case "response_breached" -> 2;
        default -> 3;
        };
    }

    private String toneFromInverse(double value, double goodThreshold, double warningThreshold) {
        if (value <= goodThreshold) {
            return "success";
        }
        if (value <= warningThreshold) {
            return "warning";
        }
        return "danger";
    }

    private String incidentHealthSummary(double slaRate, double mttrHours) {
        return "SLA " + percentage(slaRate) + "，MTTR " + formatHours(mttrHours);
    }

    private String buildExecutiveSummarySentence(String grade, BiModels.RiskSummary riskSummary, List<BiModels.ProcessHealth> processHealths) {
        BiModels.ProcessHealth weakest = processHealths.stream().min(Comparator.comparingDouble(process -> parseDouble(process.score()))).orElse(null);
        if ("Stable".equals(grade)) {
            return "整体运行稳定，但仍需持续关注" + (weakest != null ? weakest.label() : "重点流程") + "的波动。";
        }
        if ("Watch".equals(grade)) {
            return "整体运行可控，但" + (weakest != null ? weakest.label() : "部分流程") + "已进入重点关注区，建议优先处理前列风险。";
        }
        return "整体健康存在风险，当前需优先收敛" + (riskSummary.topRisks().isEmpty() ? "关键问题" : riskSummary.topRisks().getFirst().title()) + "。";
    }

    private String buildTrendHint(List<BiModels.TrendPoint> trendPoints) {
        if (trendPoints.size() < 2) {
            return "趋势数据正在准备中。";
        }
        BiModels.TrendPoint previous = trendPoints.get(trendPoints.size() - 2);
        BiModels.TrendPoint current = trendPoints.getLast();
        double delta = current.score() - previous.score();
        if (delta > 2) {
            return "较上期提升 " + String.format(Locale.ROOT, "%.1f", delta) + " 分。";
        }
        if (delta < -2) {
            return "较上期下降 " + String.format(Locale.ROOT, "%.1f", Math.abs(delta)) + " 分。";
        }
        return "整体趋势基本持平。";
    }

    private String buildPeriodLabel(BiRawData rawData) {
        List<LocalDateTime> dates = collectDates(rawData);
        if (dates.isEmpty()) {
            return "";
        }
        LocalDate min = dates.stream().min(LocalDateTime::compareTo).orElseThrow().toLocalDate();
        LocalDate max = dates.stream().max(LocalDateTime::compareTo).orElseThrow().toLocalDate();
        return min + " 至 " + max;
    }

    private List<LocalDateTime> collectDates(BiRawData rawData) {
        List<LocalDateTime> dates = new ArrayList<>();
        addDates(dates, rawData.incidents(), "opened_at");
        addDates(dates, rawData.changes(), "opened_at");
        addDates(dates, rawData.requests(), "opened_at");
        addDates(dates, rawData.problems(), "opened_at");
        return dates;
    }

    private void addDates(List<LocalDateTime> target, List<Map<String, String>> rows, String key) {
        rows.stream().map(row -> BiDateUtils.parseDate(row.get(key))).filter(Objects::nonNull).forEach(target::add);
    }

    private String safeSheetName(String value) {
        return value.replace("/", "-");
    }

    private int writeTitle(XSSFSheet sheet, int rowIndex, String title, String description) {
        Row titleRow = sheet.createRow(rowIndex++);
        titleRow.createCell(0).setCellValue(title);
        Row descriptionRow = sheet.createRow(rowIndex++);
        descriptionRow.createCell(0).setCellValue(description);
        return rowIndex + 1;
    }

    private int writeCards(XSSFSheet sheet, int rowIndex, List<MetricCard> cards) {
        if (cards.isEmpty()) {
            return rowIndex;
        }
        Row header = sheet.createRow(rowIndex++);
        header.createCell(0).setCellValue("指标");
        header.createCell(1).setCellValue("值");
        for (MetricCard card : cards) {
            Row row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(card.label());
            row.createCell(1).setCellValue(card.value());
        }
        return rowIndex + 1;
    }

    private int writeCharts(XSSFSheet sheet, int rowIndex, List<ChartSection> charts) {
        for (ChartSection chart : charts) {
            Row titleRow = sheet.createRow(rowIndex++);
            titleRow.createCell(0).setCellValue(chart.title());
            Row headerRow = sheet.createRow(rowIndex++);
            headerRow.createCell(0).setCellValue("标签");
            headerRow.createCell(1).setCellValue("值");
            for (ChartDatum item : chart.items()) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(item.label());
                Cell valueCell = row.createCell(1);
                valueCell.setCellValue(item.value());
            }
            rowIndex++;
        }
        return rowIndex;
    }

    private int writeTables(XSSFSheet sheet, int rowIndex, List<BiModels.TableSection> tables) {
        for (BiModels.TableSection table : tables) {
            Row titleRow = sheet.createRow(rowIndex++);
            titleRow.createCell(0).setCellValue(table.title());
            Row headerRow = sheet.createRow(rowIndex++);
            for (int index = 0; index < table.columns().size(); index++) {
                headerRow.createCell(index).setCellValue(table.columns().get(index));
            }
            for (List<String> values : table.rows()) {
                Row row = sheet.createRow(rowIndex++);
                for (int index = 0; index < values.size(); index++) {
                    row.createCell(index).setCellValue(values.get(index));
                }
            }
            rowIndex++;
        }
        return rowIndex;
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
}

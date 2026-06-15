/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.opsfactory.businessintelligence.api;

import com.huawei.opsfactory.businessintelligence.model.BiModels.Snapshot;
import com.huawei.opsfactory.businessintelligence.model.BiModels.TabContent;
import com.huawei.opsfactory.businessintelligence.model.MetricsModels.DataQueryRequest;
import com.huawei.opsfactory.businessintelligence.service.BusinessIntelligenceMetricsService;
import com.huawei.opsfactory.businessintelligence.service.BusinessIntelligenceService;
import java.time.Instant;
import java.util.Map;
import java.time.format.DateTimeFormatter;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.servicecomb.provider.rest.common.RestSchema;

/**
 * REST controller for business intelligence operations.
 *
 * @author x00000000
 * @since 2026-05-27
 */
@RestController
@RestSchema(schemaId = "businessIntelligenceController")
@RequestMapping("/api/business-intelligence")
public class BusinessIntelligenceController {

    private static final Logger log = LoggerFactory.getLogger(BusinessIntelligenceController.class);

    private final BusinessIntelligenceService businessIntelligenceService;
    private final BusinessIntelligenceMetricsService metricsService;

    /**
     * Creates the business intelligence controller instance.
     *
     * @param businessIntelligenceService the business intelligence service
     * @param metricsService the metrics service
     */
    public BusinessIntelligenceController(BusinessIntelligenceService businessIntelligenceService,
                                           BusinessIntelligenceMetricsService metricsService) {
        this.businessIntelligenceService = businessIntelligenceService;
        this.metricsService = metricsService;
    }

    @GetMapping("/overview")
    public Snapshot getOverview(
        @RequestParam(value = "startDate", required = false) String startDate,
        @RequestParam(value = "endDate", required = false) String endDate
    ) {
        return businessIntelligenceService.getOverview(startDate, endDate);
    }

    @PostMapping("/refresh")
    public Snapshot refresh(
        @RequestParam(value = "startDate", required = false) String startDate,
        @RequestParam(value = "endDate", required = false) String endDate
    ) {
        return businessIntelligenceService.refresh(startDate, endDate);
    }

    @GetMapping("/tabs/{tabId}")
    public TabContent getTab(
        @PathVariable("tabId") String tabId,
        @RequestParam(value = "granularity", required = false) String granularity
    ) {
        return businessIntelligenceService.getTab(tabId, granularity);
    }

    @GetMapping("/export.xlsx")
    public ResponseEntity<ByteArrayResource> exportWorkbook() {
        byte[] bytes = businessIntelligenceService.exportCurrentWorkbook();
        String filename = "business-intelligence-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(Instant.now().atZone(java.time.ZoneId.systemDefault())) + ".xlsx";
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .body(new ByteArrayResource(bytes));
    }

    @GetMapping("/export-enhanced.xlsx")
    public ResponseEntity<?> exportEnhancedWorkbook(
        @RequestParam(value = "language", defaultValue = "zh") String language,
        @RequestParam(value = "startDate", required = false) String startDate,
        @RequestParam(value = "endDate", required = false) String endDate
    ) {
        try {
            String normalizedLang = "en".equalsIgnoreCase(language) ? "en" : "zh";
            byte[] bytes = businessIntelligenceService.exportEnhancedWorkbook(normalizedLang, startDate, endDate);
            String langSuffix = "zh".equals(normalizedLang) ? "CN" : "EN";
            String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(Instant.now().atZone(java.time.ZoneId.systemDefault()));
            String filename = "BI_Dashboard_Export_" + timestamp + "_" + langSuffix + ".xlsx";
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(new ByteArrayResource(bytes));
        } catch (IllegalArgumentException e) {
            log.warn("Invalid export request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid export request"));
        } catch (Exception e) {
            log.error("Enhanced export failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Export failed"));
        }
    }

    @GetMapping("/metrics/{domain}")
    public ResponseEntity<?> getMetrics(
        @PathVariable("domain") String domain,
        @RequestParam(value = "startDate", required = false) String startDate,
        @RequestParam(value = "endDate", required = false) String endDate,
        @RequestParam(value = "personLimit", required = false) Integer personLimit
    ) {
        return switch (domain) {
            case "executive" -> ResponseEntity.ok(metricsService.getExecutiveMetrics(startDate, endDate));
            case "sla" -> ResponseEntity.ok(metricsService.getSlaMetrics(startDate, endDate));
            case "incidents" -> ResponseEntity.ok(metricsService.getIncidentMetrics(startDate, endDate));
            case "changes" -> ResponseEntity.ok(metricsService.getChangeMetrics(startDate, endDate));
            case "requests" -> ResponseEntity.ok(metricsService.getRequestMetrics(startDate, endDate));
            case "problems" -> ResponseEntity.ok(metricsService.getProblemMetrics(startDate, endDate));
            case "cross-process" -> ResponseEntity.ok(metricsService.getCrossProcessMetrics(startDate, endDate));
            case "workforce" -> ResponseEntity.ok(metricsService.getWorkforceMetrics(startDate, endDate, personLimit != null ? personLimit : 10));
            default -> ResponseEntity.badRequest().body(Map.of("error", "Unknown metrics domain"));
        };
    }

    @PostMapping("/data/{domain}/query")
    public ResponseEntity<?> queryData(
        @PathVariable("domain") String domain,
        @RequestBody DataQueryRequest request
    ) {
        return ResponseEntity.ok(metricsService.query(domain, request));
    }

    @GetMapping("/data/{domain}/lineage")
    public ResponseEntity<?> traceLineage(
        @PathVariable("domain") String domain,
        @RequestParam("ticketId") String ticketId
    ) {
        return ResponseEntity.ok(metricsService.traceLineage(domain, ticketId));
    }

    @GetMapping("/data/{domain}/trends")
    public ResponseEntity<?> getTrends(
        @PathVariable("domain") String domain,
        @RequestParam("metric") String metric,
        @RequestParam("interval") String interval,
        @RequestParam(value = "timeRange", required = false) String timeRange,
        @RequestParam(value = "startDate", required = false) String startDate,
        @RequestParam(value = "endDate", required = false) String endDate
    ) {
        return ResponseEntity.ok(metricsService.getTrends(domain, metric, interval, timeRange, startDate, endDate));
    }
}

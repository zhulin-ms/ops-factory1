/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.opsfactory.gateway.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.huawei.opsfactory.gateway.config.GatewayProperties;
import com.huawei.opsfactory.gateway.exception.ConflictException;
import com.huawei.opsfactory.gateway.exception.NotFoundException;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test coverage for Business Service Service.
 *
 * @author x00000000
 * @since 2026-05-09
 */
public class BusinessServiceServiceTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private BusinessServiceService businessServiceService;

    private GatewayProperties properties;

    private Path businessServicesDir;

    private BusinessTypeService businessTypeService;

    /**
     * Sets the up.
     *
     * @throws IOException if the operation fails
     */
    @Before
    public void setUp() throws IOException {
        properties = new GatewayProperties();
        GatewayProperties.Paths paths = new GatewayProperties.Paths();
        paths.setProjectRoot(tempFolder.getRoot().getAbsolutePath());
        properties.setPaths(paths);

        businessTypeService = new BusinessTypeService(properties);
        businessTypeService.init();

        businessServiceService = new BusinessServiceService(properties);
        businessServiceService.init();
        businessServiceService.setBusinessTypeService(businessTypeService);

        businessServicesDir = Path.of(tempFolder.getRoot().getAbsolutePath())
            .toAbsolutePath()
            .normalize()
            .resolve("gateway")
            .resolve("data")
            .resolve("business-services");

        // Create a test business type
        Path businessTypesDir = Path.of(tempFolder.getRoot().getAbsolutePath())
            .toAbsolutePath()
            .normalize()
            .resolve("gateway")
            .resolve("data")
            .resolve("business-types");
        Files.createDirectories(businessTypesDir);

        Map<String, Object> businessType = new LinkedHashMap<>();
        businessType.put("id", "bt-1");
        businessType.put("name", "Test Business Type");
        businessType.put("code", "test-bt");
        businessType.put("description", "Test business type");
        businessType.put("color", "#6366f1");
        businessType.put("knowledge", "test");
        businessType.put("createdAt", "2024-01-01T00:00:00Z");
        businessType.put("updatedAt", "2024-01-01T00:00:00Z");

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(businessType);
        Files.writeString(businessTypesDir.resolve("bt-1.json"), json, StandardCharsets.UTF_8);
    }

    // ── createBusinessService ──────────────────────────────────────

    /**
     * Tests create business service.
     */
    @Test
    public void testCreateBusinessService() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "OrderService");
        body.put("code", "ORDER");
        body.put("groupId", "group-1");
        body.put("description", "Order management service");
        body.put("hostIds", List.of("cluster-1", "cluster-2"));
        body.put("tags", List.of("core", "production"));
        body.put("priority", "high");
        body.put("contactInfo", "team-order@example.com");
        body.put("businessTypeId", "bt-1");

        Map<String, Object> result = businessServiceService.createBusinessService(body);

        assertNotNull(result.get("id"));
        assertEquals("OrderService", result.get("name"));
        assertEquals("ORDER", result.get("code"));
        assertEquals("group-1", result.get("groupId"));
        assertEquals("Order management service", result.get("description"));
        assertEquals(List.of("cluster-1", "cluster-2"), result.get("hostIds"));
        assertEquals(List.of("core", "production"), result.get("tags"));
        assertEquals("high", result.get("priority"));
        assertEquals("team-order@example.com", result.get("contactInfo"));
        assertNotNull(result.get("createdAt"));
        assertNotNull(result.get("updatedAt"));
    }

    /**
     * Tests create business service defaults.
     */
    @Test
    public void testCreateBusinessService_defaults() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "MinimalService");
        body.put("groupId", "group-1");
        body.put("businessTypeId", "bt-1");

        Map<String, Object> result = businessServiceService.createBusinessService(body);

        assertNotNull(result.get("id"));
        assertEquals("MinimalService", result.get("name"));
        assertEquals("", result.get("code"));
        assertEquals("group-1", result.get("groupId"));
        assertEquals("bt-1", result.get("businessTypeId"));
        assertEquals("", result.get("description"));
        assertEquals(Collections.emptyList(), result.get("hostIds"));
        assertEquals(Collections.emptyList(), result.get("tags"));
        assertEquals("", result.get("priority"));
        assertEquals("", result.get("contactInfo"));
    }

    // ── getBusinessService ─────────────────────────────────────────

    /**
     * Tests get business service.
     */
    @Test
    public void testGetBusinessService() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "GetTest");
        body.put("code", "GT");
        body.put("groupId", "group-1");
        body.put("businessTypeId", "bt-1");

        Map<String, Object> created = businessServiceService.createBusinessService(body);
        String id = (String) created.get("id");

        Map<String, Object> result = businessServiceService.getBusinessService(id);
        assertEquals("GetTest", result.get("name"));
        assertEquals("GT", result.get("code"));
    }

    /**
     * Tests get business service not found.
     */
    @Test(expected = NotFoundException.class)
    public void testGetBusinessService_notFound() throws Exception {
        businessServiceService.getBusinessService("nonexistent");
    }

    // ── listBusinessServices ───────────────────────────────────────

    /**
     * Tests list business services empty.
     */
    @Test
    public void testListBusinessServices_empty() throws Exception {
        List<Map<String, Object>> services = businessServiceService.listBusinessServices(null, null);
        assertTrue(services.isEmpty());
    }

    /**
     * Tests list business services returns all.
     */
    @Test
    public void testListBusinessServices_returnsAll() throws Exception {
        createBs("bs-1", "Svc1", "S1", "group-1", List.of());
        createBs("bs-2", "Svc2", "S2", "group-1", List.of());
        createBs("bs-3", "Svc3", "S3", "group-2", List.of());

        List<Map<String, Object>> services = businessServiceService.listBusinessServices(null, null);
        assertEquals(3, services.size());
    }

    /**
     * Tests list business services filter by group id.
     */
    @Test
    public void testListBusinessServices_filterByGroupId() throws Exception {
        createBs("bs-1", "Svc1", "S1", "group-1", List.of());
        createBs("bs-2", "Svc2", "S2", "group-2", List.of());

        List<Map<String, Object>> services = businessServiceService.listBusinessServices("group-1", null);
        assertEquals(1, services.size());
        assertEquals("Svc1", services.get(0).get("name"));
    }

    /**
     * Tests list business services filter by host id.
     */
    @Test
    public void testListBusinessServices_filterByHostId() throws Exception {
        createBs("bs-1", "Svc1", "S1", "group-1", List.of("host-1", "host-2"));
        createBs("bs-2", "Svc2", "S2", "group-1", List.of("host-3"));

        List<Map<String, Object>> services = businessServiceService.listBusinessServices(null, "host-2");
        assertEquals(1, services.size());
        assertEquals("Svc1", services.get(0).get("name"));
    }

    // ── updateBusinessService ──────────────────────────────────────

    /**
     * Tests update business service.
     */
    @Test
    public void testUpdateBusinessService() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Original");
        body.put("code", "ORIG");
        body.put("tags", List.of("v1"));
        body.put("groupId", "group-1");
        body.put("businessTypeId", "bt-1");

        Map<String, Object> created = businessServiceService.createBusinessService(body);
        String id = (String) created.get("id");

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("name", "Updated");
        updates.put("code", "UPD");
        updates.put("tags", List.of("v2"));

        Map<String, Object> result = businessServiceService.updateBusinessService(id, updates);
        assertEquals("Updated", result.get("name"));
        assertEquals("UPD", result.get("code"));
        assertEquals(List.of("v2"), result.get("tags"));
    }

    /**
     * Tests update business service partial update.
     */
    @Test
    public void testUpdateBusinessService_partialUpdate() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Original");
        body.put("code", "ORIG");
        body.put("description", "original desc");
        body.put("groupId", "group-1");
        body.put("businessTypeId", "bt-1");

        Map<String, Object> created = businessServiceService.createBusinessService(body);
        String id = (String) created.get("id");

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("description", "new desc");

        Map<String, Object> result = businessServiceService.updateBusinessService(id, updates);
        assertEquals("Original", result.get("name"));
        assertEquals("ORIG", result.get("code"));
        assertEquals("new desc", result.get("description"));
    }

    /**
     * Tests update business service not found.
     */
    @Test(expected = NotFoundException.class)
    public void testUpdateBusinessService_notFound() throws Exception {
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("name", "NewName");
        businessServiceService.updateBusinessService("nonexistent", updates);
    }

    // ── deleteBusinessService ──────────────────────────────────────

    /**
     * Tests delete business service.
     */
    @Test
    public void testDeleteBusinessService() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "ToDelete");
        body.put("groupId", "group-1");
        body.put("businessTypeId", "bt-1");
        Map<String, Object> created = businessServiceService.createBusinessService(body);
        String id = (String) created.get("id");

        assertTrue(businessServiceService.deleteBusinessService(id));
        assertFalse(Files.exists(businessServicesDir.resolve(id + ".json")));
    }

    /**
     * Tests delete business service not found.
     */
    @Test
    public void testDeleteBusinessService_notFound() throws Exception {
        assertFalse(businessServiceService.deleteBusinessService("nonexistent"));
    }

    // ── searchByKeyword ────────────────────────────────────────────

    /**
     * Tests search by keyword.
     */
    @Test
    public void testSearchByKeyword() throws Exception {
        createBs("bs-1", "OrderService", "ORDER", null, List.of(), List.of("core"));
        createBs("bs-2", "PaymentService", "PAY", null, List.of(), List.of("billing"));
        createBs("bs-3", "ShippingService", "SHIP", null, List.of(), List.of("order"));

        List<Map<String, Object>> byName = businessServiceService.searchByKeyword("order");
        // OrderService (name) + ShippingService (tag "order")
        assertEquals(2, byName.size());

        List<Map<String, Object>> byCode = businessServiceService.searchByKeyword("pay");
        assertEquals(1, byCode.size());
        assertEquals("PaymentService", byCode.get(0).get("name"));

        List<Map<String, Object>> byTag = businessServiceService.searchByKeyword("billing");
        assertEquals(1, byTag.size());
    }

    /**
     * Tests search by keyword empty keyword.
     */
    @Test
    public void testSearchByKeyword_emptyKeyword() throws Exception {
        createBs("bs-1", "Svc1", "S1", null, List.of());

        List<Map<String, Object>> all = businessServiceService.searchByKeyword("");
        assertEquals(1, all.size());

        List<Map<String, Object>> nullKw = businessServiceService.searchByKeyword(null);
        assertEquals(1, nullKw.size());
    }

    // ── Code Uniqueness (create) ───────────────────────────────────────

    /**
     * Tests create business service with duplicate code throws exception.
     */
    @Test
    public void testCreateBusinessService_duplicateCode_throwsException() throws Exception {
        Map<String, Object> body1 = new LinkedHashMap<>();
        body1.put("name", "Service1");
        body1.put("code", "ORDER");
        body1.put("groupId", "group-1");
        body1.put("businessTypeId", "bt-1");

        businessServiceService.createBusinessService(body1);

        Map<String, Object> body2 = new LinkedHashMap<>();
        body2.put("name", "Service2");
        body2.put("code", "ORDER");
        body2.put("groupId", "group-2");
        body2.put("businessTypeId", "bt-1");

        try {
            businessServiceService.createBusinessService(body2);
            fail("Expected ConflictException for duplicate business service code");
        } catch (ConflictException e) {
            assertTrue(e.getMessage().contains("Business service code already exists"));
        }
    }

    /**
     * Tests create business service with case-insensitive duplicate code throws exception.
     */
    @Test
    public void testCreateBusinessService_caseInsensitiveDuplicateCode_throwsException() throws Exception {
        Map<String, Object> body1 = new LinkedHashMap<>();
        body1.put("name", "Service1");
        body1.put("code", "ORDER");
        body1.put("groupId", "group-1");
        body1.put("businessTypeId", "bt-1");

        businessServiceService.createBusinessService(body1);

        Map<String, Object> body2 = new LinkedHashMap<>();
        body2.put("name", "Service2");
        body2.put("code", "order");
        body2.put("groupId", "group-2");
        body2.put("businessTypeId", "bt-1");

        try {
            businessServiceService.createBusinessService(body2);
            fail("Expected ConflictException for case-insensitive duplicate code");
        } catch (ConflictException e) {
            assertTrue(e.getMessage().contains("Business service code already exists"));
        }
    }

    /**
     * Tests create business service with empty code succeeds.
     */
    @Test
    public void testCreateBusinessService_emptyCode_succeeds() throws Exception {
        Map<String, Object> body1 = new LinkedHashMap<>();
        body1.put("name", "Service1");
        body1.put("code", "");
        body1.put("groupId", "group-1");
        body1.put("businessTypeId", "bt-1");

        businessServiceService.createBusinessService(body1);

        Map<String, Object> body2 = new LinkedHashMap<>();
        body2.put("name", "Service2");
        body2.put("code", "");
        body2.put("groupId", "group-2");
        body2.put("businessTypeId", "bt-1");

        // Both empty codes should be allowed
        Map<String, Object> result = businessServiceService.createBusinessService(body2);
        assertEquals("Service2", result.get("name"));
        assertEquals("", result.get("code"));
    }

    // ── Code Uniqueness (update) ───────────────────────────────────────

    /**
     * Tests update business service with duplicate code throws exception.
     */
    @Test
    public void testUpdateBusinessService_duplicateCode_throwsException() throws Exception {
        Map<String, Object> body1 = new LinkedHashMap<>();
        body1.put("name", "Service1");
        body1.put("code", "ORDER");
        body1.put("groupId", "group-1");
        body1.put("businessTypeId", "bt-1");
        businessServiceService.createBusinessService(body1);

        Map<String, Object> body2 = new LinkedHashMap<>();
        body2.put("name", "Service2");
        body2.put("code", "PAY");
        body2.put("groupId", "group-2");
        body2.put("businessTypeId", "bt-1");
        Map<String, Object> created = businessServiceService.createBusinessService(body2);
        String id = (String) created.get("id");

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("code", "ORDER");

        try {
            businessServiceService.updateBusinessService(id, updates);
            fail("Expected ConflictException for duplicate code");
        } catch (ConflictException e) {
            assertTrue(e.getMessage().contains("Business service code already exists"));
        }
    }

    /**
     * Tests update business service with same code succeeds.
     */
    @Test
    public void testUpdateBusinessService_sameCode_succeeds() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Service1");
        body.put("code", "ORDER");
        body.put("groupId", "group-1");
        body.put("businessTypeId", "bt-1");
        Map<String, Object> created = businessServiceService.createBusinessService(body);
        String id = (String) created.get("id");

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("code", "ORDER");

        Map<String, Object> result = businessServiceService.updateBusinessService(id, updates);
        assertEquals("ORDER", result.get("code"));
    }

    /**
     * Tests update business service with empty code succeeds.
     */
    @Test
    public void testUpdateBusinessService_emptyCode_succeeds() throws Exception {
        Map<String, Object> body1 = new LinkedHashMap<>();
        body1.put("name", "Service1");
        body1.put("code", "ORDER");
        body1.put("groupId", "group-1");
        body1.put("businessTypeId", "bt-1");
        businessServiceService.createBusinessService(body1);

        Map<String, Object> body2 = new LinkedHashMap<>();
        body2.put("name", "Service2");
        body2.put("code", "PAY");
        body2.put("groupId", "group-2");
        body2.put("businessTypeId", "bt-1");
        Map<String, Object> created = businessServiceService.createBusinessService(body2);
        String id = (String) created.get("id");

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("code", "");

        Map<String, Object> result = businessServiceService.updateBusinessService(id, updates);
        assertEquals("", result.get("code"));
    }

    // ── Helpers ────────────────────────────────────────────────────

    private void createBs(String id, String name, String code, String groupId, List<String> hostIds) {
        createBs(id, name, code, groupId, hostIds, List.of());
    }

    private void createBs(String id, String name, String code, String groupId, List<String> hostIds,
        List<String> tags) {
        Map<String, Object> bs = new LinkedHashMap<>();
        bs.put("id", id);
        bs.put("name", name);
        bs.put("code", code);
        bs.put("groupId", groupId);
        bs.put("description", "");
        bs.put("hostIds", hostIds);
        bs.put("tags", tags);
        bs.put("priority", "");
        bs.put("contactInfo", "");
        bs.put("createdAt", "2026-01-01T00:00:00Z");
        bs.put("updatedAt", "2026-01-01T00:00:00Z");

        try {
            Path file = businessServicesDir.resolve(id + ".json");
            String json = new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(bs);
            Files.writeString(file, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}

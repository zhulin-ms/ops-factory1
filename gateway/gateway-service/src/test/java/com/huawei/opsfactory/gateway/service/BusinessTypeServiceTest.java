/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.opsfactory.gateway.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertFalse;

import com.huawei.opsfactory.gateway.config.GatewayProperties;
import com.huawei.opsfactory.gateway.exception.ConflictException;

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
 * Test coverage for Business Type Service.
 *
 * @author x00000000
 * @since 2026-06-10
 */
public class BusinessTypeServiceTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private BusinessTypeService businessTypeService;

    private GatewayProperties properties;

    private Path businessTypesDir;

    private BusinessServiceService businessServiceService;

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

        // Create and inject BusinessServiceService for deletion tests
        businessServiceService = new BusinessServiceService(properties);
        businessServiceService.init();
        businessTypeService.setBusinessServiceService(businessServiceService);

        businessTypesDir = Path.of(tempFolder.getRoot().getAbsolutePath())
            .toAbsolutePath()
            .normalize()
            .resolve("gateway")
            .resolve("data")
            .resolve("business-types");
    }

    // ── createBusinessType ───────────────────────────────────────────

    /**
     * Tests create business type with basic fields succeeds.
     */
    @Test
    public void testCreateBusinessType_basicFields_succeeds() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "WebApp");
        body.put("code", "WEB");

        Map<String, Object> result = businessTypeService.createBusinessType(body);

        assertNotNull(result.get("id"));
        assertEquals("WebApp", result.get("name"));
        assertEquals("WEB", result.get("code"));
        assertEquals("", result.get("description"));
        assertEquals("", result.get("knowledge"));
    }

    /**
     * Tests create business type with description and knowledge succeeds.
     */
    @Test
    public void testCreateBusinessType_withDescriptionAndKnowledge_succeeds() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "WebApp");
        body.put("code", "WEB");
        body.put("description", "Web application service");
        body.put("knowledge", "Some knowledge content");

        Map<String, Object> result = businessTypeService.createBusinessType(body);

        assertEquals("Web application service", result.get("description"));
        assertEquals("Some knowledge content", result.get("knowledge"));
    }

    // ── validateLengthOnly (create) ──────────────────────────────────

    /**
     * Tests create business type with description exceeding max length throws exception.
     */
    @Test
    public void testCreateBusinessType_descriptionTooLong_throwsException() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "WebApp");
        body.put("code", "WEB");
        body.put("description", "a".repeat(501));

        try {
            businessTypeService.createBusinessType(body);
            fail("Expected IllegalArgumentException for description exceeding 500 chars");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Description exceeds maximum length of 500"));
        }
    }

    /**
     * Tests create business type with knowledge exceeding max length throws exception.
     */
    @Test
    public void testCreateBusinessType_knowledgeTooLong_throwsException() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "WebApp");
        body.put("code", "WEB");
        body.put("knowledge", "b".repeat(2001));

        try {
            businessTypeService.createBusinessType(body);
            fail("Expected IllegalArgumentException for knowledge exceeding 2000 chars");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Knowledge exceeds maximum length of 2000"));
        }
    }

    /**
     * Tests create business type with XSS characters in description succeeds.
     */
    @Test
    public void testCreateBusinessType_descriptionWithXssChars_succeeds() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "WebApp");
        body.put("code", "WEB");
        body.put("description", "<script>alert('xss')</script>");

        Map<String, Object> result = businessTypeService.createBusinessType(body);

        assertEquals("<script>alert('xss')</script>", result.get("description"));
    }

    // ── updateBusinessType ───────────────────────────────────────────

    /**
     * Tests update business type with code present is silently ignored.
     */
    @Test
    public void testUpdateBusinessType_codePresent_silentlyIgnored() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "WebApp");
        body.put("code", "WEB");
        Map<String, Object> created = businessTypeService.createBusinessType(body);
        String id = (String) created.get("id");

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("code", "MODIFIED");

        Map<String, Object> result = businessTypeService.updateBusinessType(id, updates);
        assertEquals("WEB", result.get("code"));
    }

    // ── validateLengthOnly (update) ──────────────────────────────────

    /**
     * Tests update business type with description exceeding max length throws exception.
     */
    @Test
    public void testUpdateBusinessType_descriptionTooLong_throwsException() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "WebApp");
        body.put("code", "WEB");
        Map<String, Object> created = businessTypeService.createBusinessType(body);
        String id = (String) created.get("id");

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("description", "a".repeat(501));

        try {
            businessTypeService.updateBusinessType(id, updates);
            fail("Expected IllegalArgumentException for description exceeding 500 chars");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Description exceeds maximum length of 500"));
        }
    }

    /**
     * Tests update business type with knowledge exceeding max length throws exception.
     */
    @Test
    public void testUpdateBusinessType_knowledgeTooLong_throwsException() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "WebApp");
        body.put("code", "WEB");
        Map<String, Object> created = businessTypeService.createBusinessType(body);
        String id = (String) created.get("id");

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("knowledge", "b".repeat(2001));

        try {
            businessTypeService.updateBusinessType(id, updates);
            fail("Expected IllegalArgumentException for knowledge exceeding 2000 chars");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Knowledge exceeds maximum length of 2000"));
        }
    }

    /**
     * Tests update business type with XSS characters in description succeeds.
     */
    @Test
    public void testUpdateBusinessType_descriptionWithXssChars_succeeds() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "WebApp");
        body.put("code", "WEB");
        Map<String, Object> created = businessTypeService.createBusinessType(body);
        String id = (String) created.get("id");

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("description", "<img src=x onerror=alert(1)>");

        Map<String, Object> result = businessTypeService.updateBusinessType(id, updates);
        assertEquals("<img src=x onerror=alert(1)>", result.get("description"));
    }

    // ── deleteBusinessType ───────────────────────────────────────────────

    /**
     * Tests delete business type when not in use succeeds.
     */
    @Test
    public void testDeleteBusinessType_notInUse_succeeds() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "ToDelete");
        body.put("code", "DEL");
        Map<String, Object> created = businessTypeService.createBusinessType(body);
        String id = (String) created.get("id");

        boolean deleted = businessTypeService.deleteBusinessType(id);
        assertTrue(deleted);
    }

    /**
     * Tests delete business type when in use throws exception.
     */
    @Test
    public void testDeleteBusinessType_inUse_throwsException() throws Exception {
        // Create a business type
        Map<String, Object> btBody = new LinkedHashMap<>();
        btBody.put("name", "WebApp");
        btBody.put("code", "WEB");
        Map<String, Object> businessType = businessTypeService.createBusinessType(btBody);
        String btId = (String) businessType.get("id");

        // Create a business service that uses this business type
        Map<String, Object> bsBody = new LinkedHashMap<>();
        bsBody.put("name", "OrderService");
        bsBody.put("code", "ORDER");
        bsBody.put("groupId", "group-1");
        bsBody.put("businessTypeId", btId);
        businessServiceService.createBusinessService(bsBody);

        // Try to delete the business type, should fail
        try {
            businessTypeService.deleteBusinessType(btId);
            fail("Expected ConflictException when business type is in use");
        } catch (ConflictException e) {
            assertTrue(e.getMessage().contains("it is being used by business service"));
            assertTrue(e.getMessage().contains("OrderService"));
        }
    }

    /**
     * Tests delete business type when not in use succeeds even with business services present.
     */
    @Test
    public void testDeleteBusinessType_notInUseWithOtherServices_succeeds() throws Exception {
        // Create a business type
        Map<String, Object> btBody1 = new LinkedHashMap<>();
        btBody1.put("name", "WebApp");
        btBody1.put("code", "WEB");
        Map<String, Object> businessType1 = businessTypeService.createBusinessType(btBody1);
        String btId1 = (String) businessType1.get("id");

        // Create another business type
        Map<String, Object> btBody2 = new LinkedHashMap<>();
        btBody2.put("name", "DbApp");
        btBody2.put("code", "DB");
        Map<String, Object> businessType2 = businessTypeService.createBusinessType(btBody2);
        String btId2 = (String) businessType2.get("id");

        // Create a business service that uses the first business type
        Map<String, Object> bsBody = new LinkedHashMap<>();
        bsBody.put("name", "OrderService");
        bsBody.put("code", "ORDER");
        bsBody.put("groupId", "group-1");
        bsBody.put("businessTypeId", btId1);
        businessServiceService.createBusinessService(bsBody);

        // Try to delete the second business type (not in use), should succeed
        boolean deleted = businessTypeService.deleteBusinessType(btId2);
        assertTrue(deleted);
    }
}

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Test coverage for SolutionType Service.
 *
 * @author x00000000
 * @since 2026-05-30
 */
public class SolutionTypeServiceTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private SolutionTypeService solutionTypeService;
    private GatewayProperties properties;

    private Path solutionTypesDir;

    /**
     * Initializes the service and data directory before each test.
     *
     * @throws IOException if the temporary directory cannot be created
     */
    @Before
    public void setUp() throws IOException {
        properties = new GatewayProperties();
        GatewayProperties.Paths paths = new GatewayProperties.Paths();
        paths.setProjectRoot(tempFolder.getRoot().getAbsolutePath());
        properties.setPaths(paths);

        solutionTypeService = new SolutionTypeService(properties);
        solutionTypeService.init();

        solutionTypesDir = Path.of(tempFolder.getRoot().getAbsolutePath())
            .toAbsolutePath()
            .normalize()
            .resolve("gateway")
            .resolve("data")
            .resolve("solution-types");
    }

    // ── listSolutionTypes ──────────────────────────────────────────

    /**
     * Tests list solution types empty.
     */
    @Test
    public void testListSolutionTypes_empty() throws Exception {
        List<Map<String, Object>> types = solutionTypeService.listSolutionTypes();
        assertTrue(types.isEmpty());
    }

    /**
     * Tests list solution types returns all.
     */
    @Test
    public void testListSolutionTypes_returnsAll() throws Exception {
        createSolutionType("st-1", "CRM Commerce", "CRM solution");
        createSolutionType("st-2", "CBS Billing", "CBS solution");

        List<Map<String, Object>> types = solutionTypeService.listSolutionTypes();
        assertEquals(2, types.size());
    }

    /**
     * Tests list solution types skips corrupt file.
     *
     * @throws IOException if the operation fails
     */
    @Test
    public void testListSolutionTypes_skipsCorruptFile() throws Exception {
        createSolutionType("st-1", "CRM Commerce", "CRM solution");
        Files.writeString(solutionTypesDir.resolve("bad.json"), "not valid json {}", StandardCharsets.UTF_8);

        List<Map<String, Object>> types = solutionTypeService.listSolutionTypes();
        assertEquals(1, types.size());
    }

    /**
     * Tests validate solution type reference returns universal for null.
     */
    @Test
    public void testValidateSolutionTypeReference_null_returnsUniversal() {
        assertEquals("universal", solutionTypeService.validateSolutionTypeReference(null));
    }

    /**
     * Tests validate solution type reference returns existing code.
     */
    @Test
    public void testValidateSolutionTypeReference_existing_returnsCode() throws Exception {
        createSolutionType("st-1", "CRM Commerce", "CRM billing solution");
        assertEquals("CRM_COMMERCE", solutionTypeService.validateSolutionTypeReference("CRM_COMMERCE"));
    }

    /**
     * Tests validate solution type reference keeps original cause.
     */
    @Test
    public void testValidateSolutionTypeReference_missing_preservesCause() {
        try {
            solutionTypeService.validateSolutionTypeReference("missing");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertEquals("Solution type not found: missing", e.getMessage());
            assertNotNull(e.getCause());
            assertTrue(e.getCause() instanceof NotFoundException);
        }
    }

    // ── createSolutionType ─────────────────────────────────────────

    /**
     * Tests create solution type success.
     */
    @Test
    public void testCreateSolutionType_success() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "彩铃播控");
        body.put("code", "CRBT");
        body.put("description", "彩铃播控解决方案");
        body.put("color", "#e11d48");
        body.put("knowledge", "彩铃系统架构及常见故障知识");

        Map<String, Object> result = solutionTypeService.createSolutionType(body);

        assertNotNull(result.get("id"));
        assertEquals("彩铃播控", result.get("name"));
        assertEquals("CRBT", result.get("code"));
        assertEquals("彩铃播控解决方案", result.get("description"));
        assertEquals("#e11d48", result.get("color"));
        assertEquals("彩铃系统架构及常见故障知识", result.get("knowledge"));
        assertNotNull(result.get("createdAt"));
        assertNotNull(result.get("updatedAt"));
    }

    /**
     * Tests create solution type default values.
     */
    @Test
    public void testCreateSolutionType_defaultValues() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "MinimalSolution");
        body.put("code", "MIN");

        Map<String, Object> result = solutionTypeService.createSolutionType(body);

        assertNotNull(result.get("id"));
        assertEquals("MinimalSolution", result.get("name"));
        assertEquals("MIN", result.get("code"));
        assertEquals("", result.get("description"));
        assertEquals("#8b5cf6", result.get("color"));
        assertEquals("", result.get("knowledge"));
    }

    /**
     * Tests create solution type with invalid color falls back to default.
     */
    @Test
    public void testCreateSolutionType_invalidColorFallback() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "ColorTest");
        body.put("code", "COL");
        body.put("color", "not-a-color");

        Map<String, Object> result = solutionTypeService.createSolutionType(body);
        assertEquals("#8b5cf6", result.get("color"));
    }

    // ── createSolutionType validation ───────────────────────────────

    /**
     * Tests create solution type name blank.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testCreateSolutionType_nameBlank() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "   ");
        body.put("code", "CODE");
        solutionTypeService.createSolutionType(body);
    }

    /**
     * Tests create solution type name too long.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testCreateSolutionType_nameTooLong() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "a".repeat(101));
        body.put("code", "CODE");
        solutionTypeService.createSolutionType(body);
    }

    /**
     * Tests create solution type name contains xss.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testCreateSolutionType_nameXss() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Test<script>");
        body.put("code", "CODE");
        solutionTypeService.createSolutionType(body);
    }

    /**
     * Tests create solution type code blank.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testCreateSolutionType_codeBlank() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Name");
        body.put("code", "   ");
        solutionTypeService.createSolutionType(body);
    }

    /**
     * Tests create solution type code too long.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testCreateSolutionType_codeTooLong() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Name");
        body.put("code", "a".repeat(51));
        solutionTypeService.createSolutionType(body);
    }

    /**
     * Tests create solution type code contains xss.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testCreateSolutionType_codeXss() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Name");
        body.put("code", "code<script>");
        solutionTypeService.createSolutionType(body);
    }

    /**
     * Tests create solution type duplicate code.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testCreateSolutionType_duplicateCode() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "First");
        body.put("code", "DUP");
        solutionTypeService.createSolutionType(body);

        Map<String, Object> body2 = new LinkedHashMap<>();
        body2.put("name", "Second");
        body2.put("code", "DUP");
        solutionTypeService.createSolutionType(body2);
    }

    /**
     * Tests create solution type description too long.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testCreateSolutionType_descriptionTooLong() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Name");
        body.put("code", "CODE");
        body.put("description", "a".repeat(501));
        solutionTypeService.createSolutionType(body);
    }

    /**
     * Tests create solution type description allows xss characters.
     */
    @Test
    public void testCreateSolutionType_descriptionAllowsXssCharacters() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Name");
        body.put("code", "CODE");
        body.put("description", "<script>alert(1)</script>");
        Map<String, Object> result = solutionTypeService.createSolutionType(body);
        assertEquals("<script>alert(1)</script>", result.get("description"));
    }

    /**
     * Tests create solution type knowledge allows xss characters.
     */
    @Test
    public void testCreateSolutionType_knowledgeAllowsXssCharacters() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Name");
        body.put("code", "CODE");
        body.put("knowledge", "<>'\"&`/");
        Map<String, Object> result = solutionTypeService.createSolutionType(body);
        assertEquals("<>'\"&`/", result.get("knowledge"));
    }

    // ── updateSolutionType ─────────────────────────────────────────

    /**
     * Tests update solution type success.
     */
    @Test
    public void testUpdateSolutionType_success() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Original");
        body.put("code", "ORIG");
        body.put("description", "orig desc");
        Map<String, Object> created = solutionTypeService.createSolutionType(body);
        String id = (String) created.get("id");

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("name", "Updated");
        updates.put("description", "new desc");

        Map<String, Object> result = solutionTypeService.updateSolutionType(id, updates);
        assertEquals("Updated", result.get("name"));
        assertEquals("new desc", result.get("description"));
    }

    /**
     * Tests update solution type partial update.
     */
    @Test
    public void testUpdateSolutionType_partialUpdate() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Original");
        body.put("code", "ORIG");
        body.put("description", "orig desc");
        Map<String, Object> created = solutionTypeService.createSolutionType(body);
        String id = (String) created.get("id");

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("description", "new desc only");

        Map<String, Object> result = solutionTypeService.updateSolutionType(id, updates);
        assertEquals("Original", result.get("name"));
        assertEquals("ORIG", result.get("code"));
        assertEquals("new desc only", result.get("description"));
    }

    /**
     * Tests update solution type color.
     */
    @Test
    public void testUpdateSolutionType_color() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "ST");
        body.put("code", "ST");
        body.put("color", "#ff0000");
        Map<String, Object> created = solutionTypeService.createSolutionType(body);
        String id = (String) created.get("id");

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("color", "#00ff00");

        Map<String, Object> result = solutionTypeService.updateSolutionType(id, updates);
        assertEquals("#00ff00", result.get("color"));
    }

    /**
     * Tests update solution type not found.
     */
    @Test(expected = NotFoundException.class)
    public void testUpdateSolutionType_notFound() throws Exception {
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("name", "NewName");
        solutionTypeService.updateSolutionType("nonexistent", updates);
    }

    // ── updateSolutionType validation ───────────────────────────────

    /**
     * Tests update solution type name blank.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testUpdateSolutionType_nameBlank() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Original");
        body.put("code", "ORIG");
        Map<String, Object> created = solutionTypeService.createSolutionType(body);
        String id = (String) created.get("id");

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("name", "   ");
        solutionTypeService.updateSolutionType(id, updates);
    }

    /**
     * Tests update solution type name too long.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testUpdateSolutionType_nameTooLong() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Original");
        body.put("code", "ORIG");
        Map<String, Object> created = solutionTypeService.createSolutionType(body);
        String id = (String) created.get("id");

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("name", "a".repeat(101));
        solutionTypeService.updateSolutionType(id, updates);
    }

    /**
     * Tests update solution type name contains xss.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testUpdateSolutionType_nameXss() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Original");
        body.put("code", "ORIG");
        Map<String, Object> created = solutionTypeService.createSolutionType(body);
        String id = (String) created.get("id");

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("name", "<script>");
        solutionTypeService.updateSolutionType(id, updates);
    }

    /**
     * Tests update solution type code blank.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testUpdateSolutionType_codeBlank() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Original");
        body.put("code", "ORIG");
        Map<String, Object> created = solutionTypeService.createSolutionType(body);
        String id = (String) created.get("id");

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("code", "   ");
        solutionTypeService.updateSolutionType(id, updates);
    }

    /**
     * Tests update solution type code too long.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testUpdateSolutionType_codeTooLong() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Original");
        body.put("code", "ORIG");
        Map<String, Object> created = solutionTypeService.createSolutionType(body);
        String id = (String) created.get("id");

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("code", "a".repeat(51));
        solutionTypeService.updateSolutionType(id, updates);
    }

    /**
     * Tests update solution type code contains xss.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testUpdateSolutionType_codeXss() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Original");
        body.put("code", "ORIG");
        Map<String, Object> created = solutionTypeService.createSolutionType(body);
        String id = (String) created.get("id");

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("code", "<script>");
        solutionTypeService.updateSolutionType(id, updates);
    }

    /**
     * Tests update solution type duplicate code.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testUpdateSolutionType_duplicateCode() throws Exception {
        Map<String, Object> body1 = new LinkedHashMap<>();
        body1.put("name", "First");
        body1.put("code", "FIRST");
        solutionTypeService.createSolutionType(body1);

        Map<String, Object> body2 = new LinkedHashMap<>();
        body2.put("name", "Second");
        body2.put("code", "SECOND");
        Map<String, Object> created = solutionTypeService.createSolutionType(body2);
        String id = (String) created.get("id");

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("code", "FIRST");
        solutionTypeService.updateSolutionType(id, updates);
    }

    /**
     * Tests update solution type description too long.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testUpdateSolutionType_descriptionTooLong() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Original");
        body.put("code", "ORIG");
        Map<String, Object> created = solutionTypeService.createSolutionType(body);
        String id = (String) created.get("id");

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("description", "a".repeat(501));
        solutionTypeService.updateSolutionType(id, updates);
    }

    /**
     * Tests update solution type description allows xss characters.
     */
    @Test
    public void testUpdateSolutionType_descriptionAllowsXssCharacters() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Original");
        body.put("code", "ORIG");
        Map<String, Object> created = solutionTypeService.createSolutionType(body);
        String id = (String) created.get("id");

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("description", "<img src=x onerror=alert(1)>");
        Map<String, Object> result = solutionTypeService.updateSolutionType(id, updates);
        assertEquals("<img src=x onerror=alert(1)>", result.get("description"));
    }

    /**
     * Tests update solution type knowledge allows xss characters.
     */
    @Test
    public void testUpdateSolutionType_knowledgeAllowsXssCharacters() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Original");
        body.put("code", "ORIG");
        Map<String, Object> created = solutionTypeService.createSolutionType(body);
        String id = (String) created.get("id");

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("knowledge", "<script>alert('test')</script>");
        Map<String, Object> result = solutionTypeService.updateSolutionType(id, updates);
        assertEquals("<script>alert('test')</script>", result.get("knowledge"));
    }

    /**
     * Tests update solution type invalid color falls back to default.
     */
    @Test
    public void testUpdateSolutionType_invalidColorFallback() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "ST");
        body.put("code", "ST");
        body.put("color", "#ff0000");
        Map<String, Object> created = solutionTypeService.createSolutionType(body);
        String id = (String) created.get("id");

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("color", "invalid");

        Map<String, Object> result = solutionTypeService.updateSolutionType(id, updates);
        assertEquals("#8b5cf6", result.get("color"));
    }

    // ── boundary values ────────────────────────────────────────────

    /**
     * Tests update solution type same code allowed.
     */
    @Test
    public void testUpdateSolutionType_sameCodeAllowed() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Original");
        body.put("code", "ORIG");
        Map<String, Object> created = solutionTypeService.createSolutionType(body);
        String id = (String) created.get("id");

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("code", "ORIG");

        Map<String, Object> result = solutionTypeService.updateSolutionType(id, updates);
        assertEquals("ORIG", result.get("code"));
    }

    /**
     * Tests create solution type name at max length.
     */
    @Test
    public void testCreateSolutionType_nameAtMaxLength() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "a".repeat(100));
        body.put("code", "CODE");

        Map<String, Object> result = solutionTypeService.createSolutionType(body);
        assertEquals("a".repeat(100), result.get("name"));
    }

    /**
     * Tests create solution type code at max length.
     */
    @Test
    public void testCreateSolutionType_codeAtMaxLength() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Name");
        body.put("code", "a".repeat(50));

        Map<String, Object> result = solutionTypeService.createSolutionType(body);
        assertEquals("a".repeat(50), result.get("code"));
    }

    /**
     * Tests create solution type description at max length.
     */
    @Test
    public void testCreateSolutionType_descriptionAtMaxLength() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Name");
        body.put("code", "CODE");
        body.put("description", "a".repeat(500));

        Map<String, Object> result = solutionTypeService.createSolutionType(body);
        assertEquals("a".repeat(500), result.get("description"));
    }

    // ── deleteSolutionType ─────────────────────────────────────────

    /**
     * Tests delete solution type success.
     */
    @Test
    public void testDeleteSolutionType_success() throws Exception {
        createSolutionType("st-del", "ToDelete", "desc");

        boolean deleted = solutionTypeService.deleteSolutionType("st-del");
        assertTrue(deleted);
        assertTrue(solutionTypeService.listSolutionTypes().isEmpty());
    }

    /**
     * Tests delete solution type not found.
     */
    @Test
    public void testDeleteSolutionType_notFound() throws Exception {
        boolean deleted = solutionTypeService.deleteSolutionType("nonexistent");
        assertFalse(deleted);
    }

    /**
     * Tests delete solution type file removed.
     *
     * @throws IOException if the operation fails
     */
    @Test
    public void testDeleteSolutionType_fileRemoved() throws Exception {
        createSolutionType("st-del", "ToDelete", "desc");
        assertTrue(Files.exists(solutionTypesDir.resolve("st-del.json")));

        solutionTypeService.deleteSolutionType("st-del");
        assertFalse(Files.exists(solutionTypesDir.resolve("st-del.json")));
    }

    /**
     * Tests delete solution type in use by SOP throws exception.
     */
    @Test
    public void testDeleteSolutionType_inUseBySop_throwsException() throws Exception {
        createSolutionType("st-used", "UsedSolution", "Used for testing");
        String code = "USEDSOLUTION";

        // Create an SOP that references this solution type
        Path sopsDir = properties.getGatewayRootPath().resolve("data").resolve("sops");
        Files.createDirectories(sopsDir);
        Map<String, Object> sop = new LinkedHashMap<>();
        sop.put("id", "sop-1");
        sop.put("name", "TestSOP");
        sop.put("description", "Test SOP");
        sop.put("version", "1.0");
        sop.put("triggerCondition", "");
        sop.put("enabled", true);
        sop.put("stepsDescription", "");
        sop.put("targetSolution", code);
        sop.put("requiredTools", List.of());
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter()
            .writeValueAsString(sop);
        Files.writeString(sopsDir.resolve("sop-1.json"), json, StandardCharsets.UTF_8);

        try {
            solutionTypeService.deleteSolutionType("st-used");
            fail("Expected ConflictException");
        } catch (ConflictException e) {
            assertTrue(e.getMessage().contains("is in use"));
            assertTrue(e.getMessage().contains("TestSOP"));
        }
    }

    /**
     * Tests delete solution type in use by cluster type throws exception.
     */
    @Test
    public void testDeleteSolutionType_inUseByClusterType_throwsException() throws Exception {
        createSolutionType("st-used-ct", "UsedSolutionCT", "Used for testing");
        String code = "USEDSOLUTIONCT";

        // Create a cluster type that references this solution type
        Path clusterTypesDir = properties.getGatewayRootPath().resolve("data").resolve("cluster-types");
        Files.createDirectories(clusterTypesDir);
        Map<String, Object> ct = new LinkedHashMap<>();
        ct.put("id", "ct-1");
        ct.put("name", "TestCluster");
        ct.put("code", "TEST_CLUSTER");
        ct.put("description", "Test cluster");
        ct.put("color", "#10b981");
        ct.put("knowledge", "");
        ct.put("commandPrefix", "");
        ct.put("envVariables", List.of());
        ct.put("mode", "peer");
        ct.put("solutionType", code);
        ct.put("createdAt", "2026-05-30T00:00:00Z");
        ct.put("updatedAt", "2026-05-30T00:00:00Z");
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter()
            .writeValueAsString(ct);
        Files.writeString(clusterTypesDir.resolve("ct-1.json"), json, StandardCharsets.UTF_8);

        try {
            solutionTypeService.deleteSolutionType("st-used-ct");
            fail("Expected ConflictException");
        } catch (ConflictException e) {
            assertTrue(e.getMessage().contains("is in use"));
            assertTrue(e.getMessage().contains("TestCluster"));
        }
    }

    /**
     * Tests delete solution type in use by both SOP and cluster type throws exception with both details.
     */
    @Test
    public void testDeleteSolutionType_inUseByBoth_throwsExceptionWithBothDetails() throws Exception {
        createSolutionType("st-used-both", "UsedSolutionBoth", "Used for testing");
        String code = "USEDSOLUTIONBOTH";

        // Create an SOP that references this solution type
        Path sopsDir = properties.getGatewayRootPath().resolve("data").resolve("sops");
        Files.createDirectories(sopsDir);
        Map<String, Object> sop = new LinkedHashMap<>();
        sop.put("id", "sop-1");
        sop.put("name", "TestSOP");
        sop.put("description", "Test SOP");
        sop.put("version", "1.0");
        sop.put("triggerCondition", "");
        sop.put("enabled", true);
        sop.put("stepsDescription", "");
        sop.put("targetSolution", code);
        sop.put("requiredTools", List.of());
        String sopJson = new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter()
            .writeValueAsString(sop);
        Files.writeString(sopsDir.resolve("sop-1.json"), sopJson, StandardCharsets.UTF_8);

        // Create a cluster type that references this solution type
        Path clusterTypesDir = properties.getGatewayRootPath().resolve("data").resolve("cluster-types");
        Files.createDirectories(clusterTypesDir);
        Map<String, Object> ct = new LinkedHashMap<>();
        ct.put("id", "ct-1");
        ct.put("name", "TestCluster");
        ct.put("code", "TEST_CLUSTER");
        ct.put("description", "Test cluster");
        ct.put("color", "#10b981");
        ct.put("knowledge", "");
        ct.put("commandPrefix", "");
        ct.put("envVariables", List.of());
        ct.put("mode", "peer");
        ct.put("solutionType", code);
        ct.put("createdAt", "2026-05-30T00:00:00Z");
        ct.put("updatedAt", "2026-05-30T00:00:00Z");
        String ctJson = new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter()
            .writeValueAsString(ct);
        Files.writeString(clusterTypesDir.resolve("ct-1.json"), ctJson, StandardCharsets.UTF_8);

        try {
            solutionTypeService.deleteSolutionType("st-used-both");
            fail("Expected ConflictException");
        } catch (ConflictException e) {
            assertTrue(e.getMessage().contains("is in use"));
            assertTrue(e.getMessage().contains("TestSOP"));
            assertTrue(e.getMessage().contains("TestCluster"));
        }
    }

    /**
     * Tests get solution type by code existing.
     */
    @Test
    public void testGetSolutionTypeByCode_existing() throws Exception {
        createSolutionType("st-1", "CRM Commerce", "CRM billing solution");

        Map<String, Object> st = solutionTypeService.getSolutionTypeByCode("CRM_COMMERCE");
        assertNotNull(st);
        assertEquals("CRM Commerce", st.get("name"));
        assertEquals("CRM billing solution", st.get("description"));
    }

    /**
     * Tests get solution type by code not found.
     */
    @Test(expected = NotFoundException.class)
    public void testGetSolutionTypeByCode_notFound() throws Exception {
        solutionTypeService.getSolutionTypeByCode("nonexistent");
    }

    /**
     * Tests check solution type usage with SOP.
     */
    @Test
    public void testCheckSolutionTypeUsage_withSop() throws Exception {
        createSolutionType("st-check", "CheckSolution", "For checking usage");
        String code = "CHECKSOLUTION";

        // Create an SOP that references this solution type
        Path sopsDir = properties.getGatewayRootPath().resolve("data").resolve("sops");
        Files.createDirectories(sopsDir);
        Map<String, Object> sop = new LinkedHashMap<>();
        sop.put("id", "sop-1");
        sop.put("name", "TestSOP");
        sop.put("description", "Test SOP");
        sop.put("version", "1.0");
        sop.put("triggerCondition", "");
        sop.put("enabled", true);
        sop.put("stepsDescription", "");
        sop.put("targetSolution", code);
        sop.put("requiredTools", List.of());
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter()
            .writeValueAsString(sop);
        Files.writeString(sopsDir.resolve("sop-1.json"), json, StandardCharsets.UTF_8);

        Map<String, List<String>> usage = solutionTypeService.checkSolutionTypeUsage(code);
        assertTrue(usage.containsKey("sops"));
        assertEquals(1, usage.get("sops").size());
        assertEquals("TestSOP", usage.get("sops").get(0));
    }

    /**
     * Tests check solution type usage with cluster type.
     */
    @Test
    public void testCheckSolutionTypeUsage_withClusterType() throws Exception {
        createSolutionType("st-check-ct", "CheckSolutionCT", "For checking usage");
        String code = "CHECKSOLUTIONCT";

        // Create a cluster type that references this solution type
        Path clusterTypesDir = properties.getGatewayRootPath().resolve("data").resolve("cluster-types");
        Files.createDirectories(clusterTypesDir);
        Map<String, Object> ct = new LinkedHashMap<>();
        ct.put("id", "ct-1");
        ct.put("name", "TestCluster");
        ct.put("code", "TEST_CLUSTER");
        ct.put("description", "Test cluster");
        ct.put("color", "#10b981");
        ct.put("knowledge", "");
        ct.put("commandPrefix", "");
        ct.put("envVariables", List.of());
        ct.put("mode", "peer");
        ct.put("solutionType", code);
        ct.put("createdAt", "2026-05-30T00:00:00Z");
        ct.put("updatedAt", "2026-05-30T00:00:00Z");
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter()
            .writeValueAsString(ct);
        Files.writeString(clusterTypesDir.resolve("ct-1.json"), json, StandardCharsets.UTF_8);

        Map<String, List<String>> usage = solutionTypeService.checkSolutionTypeUsage(code);
        assertTrue(usage.containsKey("clusterTypes"));
        assertEquals(1, usage.get("clusterTypes").size());
        assertEquals("TestCluster", usage.get("clusterTypes").get(0));
    }

    /**
     * Tests check solution type usage empty when not in use.
     */
    @Test
    public void testCheckSolutionTypeUsage_emptyWhenNotInUse() throws Exception {
        createSolutionType("st-unused", "UnusedSolution", "Not used by anything");

        Map<String, List<String>> usage = solutionTypeService.checkSolutionTypeUsage("UNUSEDSOLUTION");
        assertTrue(usage.isEmpty());
    }

    // ── Helpers ──────────────────────────────────────────────────

    /**
     * Creates a solution type directly in the data directory (bypassing the service).
     * Used to set up pre-existing data for list/get/delete tests.
     *
     * @param id the entity identifier used as filename
     * @param name the solution type name
     * @param description the solution type description
     */
    private void createSolutionType(String id, String name, String description) throws ConflictException {
        Map<String, Object> st = new LinkedHashMap<>();
        st.put("id", id);
        st.put("name", name);
        st.put("code", name.toUpperCase(Locale.ROOT).replaceAll(" ", "_"));
        st.put("description", description);
        st.put("color", "#8b5cf6");
        st.put("knowledge", "");
        st.put("createdAt", "2026-05-30T00:00:00Z");
        st.put("updatedAt", "2026-05-30T00:00:00Z");

        try {
            Path file = solutionTypesDir.resolve(id + ".json");
            String json = new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(st);
            Files.writeString(file, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ConflictException("Failed to create test file", e);
        }
    }
}

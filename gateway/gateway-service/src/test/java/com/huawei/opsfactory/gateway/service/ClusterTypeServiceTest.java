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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test coverage for ClusterType Service.
 *
 * @author x00000000
 * @since 2026-06-09
 */
public class ClusterTypeServiceTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private ClusterTypeService clusterTypeService;
    private SolutionTypeService solutionTypeService;
    private ClusterService clusterService;
    private GatewayProperties properties;

    private Path clusterTypesDir;

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

        clusterTypeService = new ClusterTypeService(properties, solutionTypeService);
        clusterTypeService.init();

        // Create and inject ClusterService for deletion tests
        clusterService = new ClusterService(properties);
        clusterService.init();
        clusterTypeService.setClusterService(clusterService);

        clusterTypesDir = Path.of(tempFolder.getRoot().getAbsolutePath())
            .toAbsolutePath()
            .normalize()
            .resolve("gateway")
            .resolve("data")
            .resolve("cluster-types");
    }

    // ── listClusterTypes ──────────────────────────────────────────

    /**
     * Tests list cluster types empty.
     */
    @Test
    public void testListClusterTypes_empty() throws Exception {
        List<Map<String, Object>> types = clusterTypeService.listClusterTypes();
        assertTrue(types.isEmpty());
    }

    /**
     * Tests list cluster types returns all.
     */
    @Test
    public void testListClusterTypes_returnsAll() throws Exception {
        createClusterType("ct-1", "Web Cluster", "WEB");
        createClusterType("ct-2", "DB Cluster", "DB");

        List<Map<String, Object>> types = clusterTypeService.listClusterTypes();
        assertEquals(2, types.size());
    }

    /**
     * Tests list cluster types skips corrupt file.
     *
     * @throws IOException if the operation fails
     */
    @Test
    public void testListClusterTypes_skipsCorruptFile() throws IOException {
        createClusterType("ct-1", "Web Cluster", "WEB");
        Files.writeString(clusterTypesDir.resolve("bad.json"), "not valid json {}", StandardCharsets.UTF_8);

        List<Map<String, Object>> types = clusterTypeService.listClusterTypes();
        assertEquals(1, types.size());
    }

    // ── createClusterType ─────────────────────────────────────────

    /**
     * Tests create cluster type success.
     */
    @Test
    public void testCreateClusterType_success() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Kubernetes Cluster");
        body.put("code", "K8S");
        body.put("description", "Kubernetes cluster type");
        body.put("color", "#10b981");
        body.put("knowledge", "K8S knowledge base");
        body.put("commandPrefix", "kubectl");
        body.put("mode", "peer");
        body.put("solutionType", "universal");

        Map<String, Object> result = clusterTypeService.createClusterType(body);

        assertNotNull(result.get("id"));
        assertEquals("Kubernetes Cluster", result.get("name"));
        assertEquals("K8S", result.get("code"));
        assertEquals("Kubernetes cluster type", result.get("description"));
        assertEquals("#10b981", result.get("color"));
        assertEquals("K8S knowledge base", result.get("knowledge"));
        assertEquals("kubectl", result.get("commandPrefix"));
        assertEquals("peer", result.get("mode"));
        assertEquals("universal", result.get("solutionType"));
        assertNotNull(result.get("createdAt"));
        assertNotNull(result.get("updatedAt"));
    }

    /**
     * Tests create cluster type with environment variables.
     */
    @Test
    public void testCreateClusterType_withEnvVariables() throws Exception {
        List<Map<String, String>> envVars = new ArrayList<>();
        Map<String, String> env1 = new LinkedHashMap<>();
        env1.put("key", "JAVA_HOME");
        env1.put("value", "/usr/lib/jvm/java-11");
        envVars.add(env1);
        Map<String, String> env2 = new LinkedHashMap<>();
        env2.put("key", "PATH");
        env2.put("value", "/usr/local/bin:/usr/bin");
        envVars.add(env2);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Java Cluster");
        body.put("code", "JAVA");
        body.put("envVariables", envVars);

        Map<String, Object> result = clusterTypeService.createClusterType(body);

        @SuppressWarnings("unchecked")
        List<Map<String, String>> resultEnv = (List<Map<String, String>>) result.get("envVariables");
        assertEquals(2, resultEnv.size());
        assertEquals("JAVA_HOME", resultEnv.get(0).get("key"));
        assertEquals("/usr/lib/jvm/java-11", resultEnv.get(0).get("value"));
    }

    /**
     * Tests create cluster type default values.
     */
    @Test
    public void testCreateClusterType_defaultValues() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "MinimalCluster");
        body.put("code", "MIN");

        Map<String, Object> result = clusterTypeService.createClusterType(body);

        assertNotNull(result.get("id"));
        assertEquals("MinimalCluster", result.get("name"));
        assertEquals("MIN", result.get("code"));
        assertEquals("", result.get("description"));
        assertEquals("#10b981", result.get("color"));
        assertEquals("", result.get("knowledge"));
        assertEquals("peer", result.get("mode"));
        assertEquals("universal", result.get("solutionType"));
    }

    // ── createClusterType validation ───────────────────────────────

    /**
     * Tests create cluster type name blank.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testCreateClusterType_nameBlank() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "   ");
        body.put("code", "CODE");
        clusterTypeService.createClusterType(body);
    }

    /**
     * Tests create cluster type name too long.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testCreateClusterType_nameTooLong() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "a".repeat(101));
        body.put("code", "CODE");
        clusterTypeService.createClusterType(body);
    }

    /**
     * Tests create cluster type name contains xss.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testCreateClusterType_nameXss() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Test<script>");
        body.put("code", "CODE");
        clusterTypeService.createClusterType(body);
    }

    /**
     * Tests create cluster type code blank.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testCreateClusterType_codeBlank() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Name");
        body.put("code", "   ");
        clusterTypeService.createClusterType(body);
    }

    /**
     * Tests create cluster type code too long.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testCreateClusterType_codeTooLong() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Name");
        body.put("code", "a".repeat(51));
        clusterTypeService.createClusterType(body);
    }

    /**
     * Tests create cluster type code contains xss.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testCreateClusterType_codeXss() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Name");
        body.put("code", "code<script>");
        clusterTypeService.createClusterType(body);
    }

    /**
     * Tests create cluster type duplicate code.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testCreateClusterType_duplicateCode() throws Exception {
        Map<String, Object> body1 = new LinkedHashMap<>();
        body1.put("name", "First");
        body1.put("code", "DUP");
        clusterTypeService.createClusterType(body1);

        Map<String, Object> body2 = new LinkedHashMap<>();
        body2.put("name", "Second");
        body2.put("code", "DUP");
        clusterTypeService.createClusterType(body2);
    }

    /**
     * Tests create cluster type duplicate name.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testCreateClusterType_duplicateName() throws Exception {
        Map<String, Object> body1 = new LinkedHashMap<>();
        body1.put("name", "SameName");
        body1.put("code", "FIRST");
        clusterTypeService.createClusterType(body1);

        Map<String, Object> body2 = new LinkedHashMap<>();
        body2.put("name", "SameName");
        body2.put("code", "SECOND");
        clusterTypeService.createClusterType(body2);
    }

    /**
     * Tests create cluster type description too long.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testCreateClusterType_descriptionTooLong() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Name");
        body.put("code", "CODE");
        body.put("description", "a".repeat(501));
        clusterTypeService.createClusterType(body);
    }

    /**
     * Tests create cluster type description allows xss characters.
     */
    @Test
    public void testCreateClusterType_descriptionAllowsXssCharacters() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Name");
        body.put("code", "CODE");
        body.put("description", "<script>alert(1)</script>");
        Map<String, Object> result = clusterTypeService.createClusterType(body);
        assertEquals("<script>alert(1)</script>", result.get("description"));
    }

    /**
     * Tests create cluster type knowledge too long.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testCreateClusterType_knowledgeTooLong() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Name");
        body.put("code", "CODE");
        body.put("knowledge", "a".repeat(2001));
        clusterTypeService.createClusterType(body);
    }

    /**
     * Tests create cluster type knowledge allows xss characters.
     */
    @Test
    public void testCreateClusterType_knowledgeAllowsXssCharacters() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Name");
        body.put("code", "CODE");
        body.put("knowledge", "<script>alert('xss')</script>");
        Map<String, Object> result = clusterTypeService.createClusterType(body);
        assertEquals("<script>alert('xss')</script>", result.get("knowledge"));
    }

    /**
     * Tests create cluster type command prefix contains xss.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testCreateClusterType_commandPrefixXss() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Name");
        body.put("code", "CODE");
        body.put("commandPrefix", "<script>");
        clusterTypeService.createClusterType(body);
    }

    /**
     * Tests create cluster type command prefix too long.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testCreateClusterType_commandPrefixTooLong() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Name");
        body.put("code", "CODE");
        body.put("commandPrefix", "a".repeat(101));
        clusterTypeService.createClusterType(body);
    }

    /**
     * Tests create cluster type command prefix at max length.
     */
    @Test
    public void testCreateClusterType_commandPrefixAtMaxLength() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Name");
        body.put("code", "CODE");
        body.put("commandPrefix", "a".repeat(100));

        Map<String, Object> result = clusterTypeService.createClusterType(body);
        assertEquals("a".repeat(100), result.get("commandPrefix"));
    }

    /**
     * Tests create cluster type invalid mode.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testCreateClusterType_invalidMode() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Name");
        body.put("code", "CODE");
        body.put("mode", "invalid");
        clusterTypeService.createClusterType(body);
    }

    /**
     * Tests create cluster type env variable key contains xss.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testCreateClusterType_envVarKeyXss() throws Exception {
        List<Map<String, String>> envVars = new ArrayList<>();
        Map<String, String> env1 = new LinkedHashMap<>();
        env1.put("key", "KEY<script>");
        env1.put("value", "value");
        envVars.add(env1);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Name");
        body.put("code", "CODE");
        body.put("envVariables", envVars);
        clusterTypeService.createClusterType(body);
    }

    /**
     * Tests create cluster type env variable value contains dangerous chars.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testCreateClusterType_envVarValueDangerousChars() throws Exception {
        List<Map<String, String>> envVars = new ArrayList<>();
        Map<String, String> env1 = new LinkedHashMap<>();
        env1.put("key", "KEY");
        env1.put("value", "value<script>");
        envVars.add(env1);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Name");
        body.put("code", "CODE");
        body.put("envVariables", envVars);
        clusterTypeService.createClusterType(body);
    }

    /**
     * Tests create cluster type env variable allows slash in value.
     */
    @Test
    public void testCreateClusterType_envVarValueAllowsSlash() throws Exception {
        List<Map<String, String>> envVars = new ArrayList<>();
        Map<String, String> env1 = new LinkedHashMap<>();
        env1.put("key", "PATH");
        env1.put("value", "/usr/local/bin:/usr/bin");
        envVars.add(env1);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Name");
        body.put("code", "CODE");
        body.put("envVariables", envVars);

        Map<String, Object> result = clusterTypeService.createClusterType(body);
        assertNotNull(result.get("id"));
    }

    /**
     * Tests create cluster type duplicate env var keys.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testCreateClusterType_duplicateEnvVarKeys() throws Exception {
        List<Map<String, String>> envVars = new ArrayList<>();
        Map<String, String> env1 = new LinkedHashMap<>();
        env1.put("key", "PATH");
        env1.put("value", "/usr/bin");
        envVars.add(env1);
        Map<String, String> env2 = new LinkedHashMap<>();
        env2.put("key", "PATH");
        env2.put("value", "/usr/local/bin");
        envVars.add(env2);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Name");
        body.put("code", "CODE");
        body.put("envVariables", envVars);
        clusterTypeService.createClusterType(body);
    }

    /**
     * Tests create cluster type duplicate env var keys case insensitive.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testCreateClusterType_duplicateEnvVarKeysCaseInsensitive() throws Exception {
        List<Map<String, String>> envVars = new ArrayList<>();
        Map<String, String> env1 = new LinkedHashMap<>();
        env1.put("key", "PATH");
        env1.put("value", "/usr/bin");
        envVars.add(env1);
        Map<String, String> env2 = new LinkedHashMap<>();
        env2.put("key", "path");
        env2.put("value", "/usr/local/bin");
        envVars.add(env2);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Name");
        body.put("code", "CODE");
        body.put("envVariables", envVars);
        clusterTypeService.createClusterType(body);
    }

    /**
     * Tests create cluster type env var key too long.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testCreateClusterType_envVarKeyTooLong() throws Exception {
        List<Map<String, String>> envVars = new ArrayList<>();
        Map<String, String> env1 = new LinkedHashMap<>();
        env1.put("key", "a".repeat(101));
        env1.put("value", "value");
        envVars.add(env1);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Name");
        body.put("code", "CODE");
        body.put("envVariables", envVars);
        clusterTypeService.createClusterType(body);
    }

    /**
     * Tests create cluster type env var value too long.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testCreateClusterType_envVarValueTooLong() throws Exception {
        List<Map<String, String>> envVars = new ArrayList<>();
        Map<String, String> env1 = new LinkedHashMap<>();
        env1.put("key", "KEY");
        env1.put("value", "a".repeat(501));
        envVars.add(env1);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Name");
        body.put("code", "CODE");
        body.put("envVariables", envVars);
        clusterTypeService.createClusterType(body);
    }

    /**
     * Tests create cluster type env var key at max length.
     */
    @Test
    public void testCreateClusterType_envVarKeyAtMaxLength() throws Exception {
        List<Map<String, String>> envVars = new ArrayList<>();
        Map<String, String> env1 = new LinkedHashMap<>();
        env1.put("key", "a".repeat(100));
        env1.put("value", "value");
        envVars.add(env1);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Name");
        body.put("code", "CODE");
        body.put("envVariables", envVars);

        Map<String, Object> result = clusterTypeService.createClusterType(body);
        @SuppressWarnings("unchecked")
        List<Map<String, String>> resultEnv = (List<Map<String, String>>) result.get("envVariables");
        assertEquals(1, resultEnv.size());
        assertEquals("a".repeat(100), resultEnv.get(0).get("key"));
    }

    /**
     * Tests create cluster type env var value at max length.
     */
    @Test
    public void testCreateClusterType_envVarValueAtMaxLength() throws Exception {
        List<Map<String, String>> envVars = new ArrayList<>();
        Map<String, String> env1 = new LinkedHashMap<>();
        env1.put("key", "KEY");
        env1.put("value", "a".repeat(500));
        envVars.add(env1);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Name");
        body.put("code", "CODE");
        body.put("envVariables", envVars);

        Map<String, Object> result = clusterTypeService.createClusterType(body);
        @SuppressWarnings("unchecked")
        List<Map<String, String>> resultEnv = (List<Map<String, String>>) result.get("envVariables");
        assertEquals(1, resultEnv.size());
        assertEquals("a".repeat(500), resultEnv.get(0).get("value"));
    }

    // ── updateClusterType ─────────────────────────────────────────

    /**
     * Tests update cluster type success.
     */
    @Test
    public void testUpdateClusterType_success() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Original");
        body.put("code", "ORIG");
        body.put("description", "orig desc");
        Map<String, Object> created = clusterTypeService.createClusterType(body);
        String id = (String) created.get("id");

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("name", "Updated");
        updates.put("description", "new desc");

        Map<String, Object> result = clusterTypeService.updateClusterType(id, updates);
        assertEquals("Updated", result.get("name"));
        assertEquals("new desc", result.get("description"));
    }

    /**
     * Tests update cluster type partial update.
     */
    @Test
    public void testUpdateClusterType_partialUpdate() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Original");
        body.put("code", "ORIG");
        body.put("description", "orig desc");
        Map<String, Object> created = clusterTypeService.createClusterType(body);
        String id = (String) created.get("id");

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("description", "new desc only");

        Map<String, Object> result = clusterTypeService.updateClusterType(id, updates);
        assertEquals("Original", result.get("name"));
        assertEquals("ORIG", result.get("code"));
        assertEquals("new desc only", result.get("description"));
    }

    /**
     * Tests update cluster type not found.
     */
    @Test(expected = NotFoundException.class)
    public void testUpdateClusterType_notFound() throws Exception {
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("name", "NewName");
        clusterTypeService.updateClusterType("nonexistent", updates);
    }

    // ── updateClusterType validation ───────────────────────────────

    /**
     * Tests update cluster type name blank.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testUpdateClusterType_nameBlank() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Original");
        body.put("code", "ORIG");
        Map<String, Object> created = clusterTypeService.createClusterType(body);
        String id = (String) created.get("id");

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("name", "   ");
        clusterTypeService.updateClusterType(id, updates);
    }

    /**
     * Tests update cluster type name too long.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testUpdateClusterType_nameTooLong() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Original");
        body.put("code", "ORIG");
        Map<String, Object> created = clusterTypeService.createClusterType(body);
        String id = (String) created.get("id");

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("name", "a".repeat(101));
        clusterTypeService.updateClusterType(id, updates);
    }

    /**
     * Tests update cluster type name contains xss.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testUpdateClusterType_nameXss() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Original");
        body.put("code", "ORIG");
        Map<String, Object> created = clusterTypeService.createClusterType(body);
        String id = (String) created.get("id");

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("name", "<script>");
        clusterTypeService.updateClusterType(id, updates);
    }

    /**
     * Tests update cluster type code blank.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testUpdateClusterType_codeBlank() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Original");
        body.put("code", "ORIG");
        Map<String, Object> created = clusterTypeService.createClusterType(body);
        String id = (String) created.get("id");

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("code", "   ");
        clusterTypeService.updateClusterType(id, updates);
    }

    /**
     * Tests update cluster type code too long.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testUpdateClusterType_codeTooLong() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Original");
        body.put("code", "ORIG");
        Map<String, Object> created = clusterTypeService.createClusterType(body);
        String id = (String) created.get("id");

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("code", "a".repeat(51));
        clusterTypeService.updateClusterType(id, updates);
    }

    /**
     * Tests update cluster type code contains xss.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testUpdateClusterType_codeXss() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Original");
        body.put("code", "ORIG");
        Map<String, Object> created = clusterTypeService.createClusterType(body);
        String id = (String) created.get("id");

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("code", "<script>");
        clusterTypeService.updateClusterType(id, updates);
    }

    /**
     * Tests update cluster type duplicate code.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testUpdateClusterType_duplicateCode() throws Exception {
        Map<String, Object> body1 = new LinkedHashMap<>();
        body1.put("name", "First");
        body1.put("code", "FIRST");
        clusterTypeService.createClusterType(body1);

        Map<String, Object> body2 = new LinkedHashMap<>();
        body2.put("name", "Second");
        body2.put("code", "SECOND");
        Map<String, Object> created = clusterTypeService.createClusterType(body2);
        String id = (String) created.get("id");

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("code", "FIRST");
        clusterTypeService.updateClusterType(id, updates);
    }

    /**
     * Tests update cluster type duplicate name.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testUpdateClusterType_duplicateName() throws Exception {
        Map<String, Object> body1 = new LinkedHashMap<>();
        body1.put("name", "First");
        body1.put("code", "FIRST");
        clusterTypeService.createClusterType(body1);

        Map<String, Object> body2 = new LinkedHashMap<>();
        body2.put("name", "Second");
        body2.put("code", "SECOND");
        Map<String, Object> created = clusterTypeService.createClusterType(body2);
        String id = (String) created.get("id");

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("name", "First");
        clusterTypeService.updateClusterType(id, updates);
    }

    /**
     * Tests update cluster type description too long.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testUpdateClusterType_descriptionTooLong() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Original");
        body.put("code", "ORIG");
        Map<String, Object> created = clusterTypeService.createClusterType(body);
        String id = (String) created.get("id");

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("description", "a".repeat(501));
        clusterTypeService.updateClusterType(id, updates);
    }

    /**
     * Tests update cluster type description allows xss characters.
     */
    @Test
    public void testUpdateClusterType_descriptionAllowsXssCharacters() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Original");
        body.put("code", "ORIG");
        Map<String, Object> created = clusterTypeService.createClusterType(body);
        String id = (String) created.get("id");

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("description", "<img src=x onerror=alert(1)>");
        Map<String, Object> result = clusterTypeService.updateClusterType(id, updates);
        assertEquals("<img src=x onerror=alert(1)>", result.get("description"));
    }

    /**
     * Tests update cluster type knowledge too long.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testUpdateClusterType_knowledgeTooLong() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Original");
        body.put("code", "ORIG");
        Map<String, Object> created = clusterTypeService.createClusterType(body);
        String id = (String) created.get("id");

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("knowledge", "a".repeat(2001));
        clusterTypeService.updateClusterType(id, updates);
    }

    /**
     * Tests update cluster type knowledge allows xss characters.
     */
    @Test
    public void testUpdateClusterType_knowledgeAllowsXssCharacters() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Original");
        body.put("code", "ORIG");
        Map<String, Object> created = clusterTypeService.createClusterType(body);
        String id = (String) created.get("id");

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("knowledge", "<>\"'&`/");
        Map<String, Object> result = clusterTypeService.updateClusterType(id, updates);
        assertEquals("<>\"'&`/", result.get("knowledge"));
    }

    /**
     * Tests update cluster type command prefix contains xss.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testUpdateClusterType_commandPrefixXss() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Original");
        body.put("code", "ORIG");
        Map<String, Object> created = clusterTypeService.createClusterType(body);
        String id = (String) created.get("id");

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("commandPrefix", "<script>");
        clusterTypeService.updateClusterType(id, updates);
    }

    /**
     * Tests update cluster type command prefix too long.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testUpdateClusterType_commandPrefixTooLong() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Original");
        body.put("code", "ORIG");
        Map<String, Object> created = clusterTypeService.createClusterType(body);
        String id = (String) created.get("id");

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("commandPrefix", "a".repeat(101));
        clusterTypeService.updateClusterType(id, updates);
    }

    // ── boundary values ────────────────────────────────────────────

    /**
     * Tests update cluster type same code allowed.
     */
    @Test
    public void testUpdateClusterType_sameCodeAllowed() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Original");
        body.put("code", "ORIG");
        Map<String, Object> created = clusterTypeService.createClusterType(body);
        String id = (String) created.get("id");

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("code", "ORIG");

        Map<String, Object> result = clusterTypeService.updateClusterType(id, updates);
        assertEquals("ORIG", result.get("code"));
    }

    /**
     * Tests create cluster type name at max length.
     */
    @Test
    public void testCreateClusterType_nameAtMaxLength() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "a".repeat(100));
        body.put("code", "CODE");

        Map<String, Object> result = clusterTypeService.createClusterType(body);
        assertEquals("a".repeat(100), result.get("name"));
    }

    /**
     * Tests create cluster type code at max length.
     */
    @Test
    public void testCreateClusterType_codeAtMaxLength() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Name");
        body.put("code", "a".repeat(50));

        Map<String, Object> result = clusterTypeService.createClusterType(body);
        assertEquals("a".repeat(50), result.get("code"));
    }

    /**
     * Tests create cluster type description at max length.
     */
    @Test
    public void testCreateClusterType_descriptionAtMaxLength() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Name");
        body.put("code", "CODE");
        body.put("description", "a".repeat(500));

        Map<String, Object> result = clusterTypeService.createClusterType(body);
        assertEquals("a".repeat(500), result.get("description"));
    }

    /**
     * Tests create cluster type knowledge at max length.
     */
    @Test
    public void testCreateClusterType_knowledgeAtMaxLength() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Name");
        body.put("code", "CODE");
        body.put("knowledge", "a".repeat(2000));

        Map<String, Object> result = clusterTypeService.createClusterType(body);
        assertEquals("a".repeat(2000), result.get("knowledge"));
    }

    // ── deleteClusterType ─────────────────────────────────────────

    /**
     * Tests delete cluster type success.
     */
    @Test
    public void testDeleteClusterType_success() throws Exception {
        createClusterType("ct-del", "ToDelete", "DEL");

        boolean deleted = clusterTypeService.deleteClusterType("ct-del");
        assertTrue(deleted);
        assertTrue(clusterTypeService.listClusterTypes().isEmpty());
    }

    /**
     * Tests delete cluster type not found.
     */
    @Test(expected = NotFoundException.class)
    public void testDeleteClusterType_notFound() throws Exception {
        clusterTypeService.deleteClusterType("nonexistent");
    }

    /**
     * Tests delete cluster type file removed.
     *
     * @throws IOException if the operation fails
     */
    @Test
    public void testDeleteClusterType_fileRemoved() throws IOException {
        createClusterType("ct-del", "ToDelete", "DEL");
        assertTrue(Files.exists(clusterTypesDir.resolve("ct-del.json")));

        try {
            clusterTypeService.deleteClusterType("ct-del");
        } catch (ConflictException | NotFoundException e) {
            fail("Deletion should succeed when cluster type is not in use: " + e.getMessage());
        }
        assertFalse(Files.exists(clusterTypesDir.resolve("ct-del.json")));
    }

    /**
     * Tests delete cluster type when in use by cluster name throws exception.
     *
     * @throws IOException if the operation fails
     */
    @Test
    public void testDeleteClusterType_inUseByName_throwsException() throws IOException {
        createClusterType("ct-web", "Web Cluster", "WEB");

        // Create a cluster that uses this cluster type by name
        createCluster("cluster-1", "prod-web-1", "Web Cluster", "group-1");

        try {
            clusterTypeService.deleteClusterType("ct-web");
            fail("Expected ConflictException when cluster type is in use by cluster");
        } catch (ConflictException e) {
            assertTrue(e.getMessage().contains("it is being used by cluster"));
            assertTrue(e.getMessage().contains("prod-web-1"));
        } catch (NotFoundException e) {
            fail("Should not throw NotFoundException for existing cluster type: " + e.getMessage());
        }
    }

    /**
     * Tests delete cluster type when in use by cluster code throws exception.
     *
     * @throws IOException if the operation fails
     */
    @Test
    public void testDeleteClusterType_inUseByCode_throwsException() throws IOException {
        createClusterType("ct-db", "Database Cluster", "DB");

        // Create a cluster that uses this cluster type by code
        createCluster("cluster-2", "prod-db-1", "DB", "group-1");

        try {
            clusterTypeService.deleteClusterType("ct-db");
            fail("Expected ConflictException when cluster type is in use by cluster");
        } catch (ConflictException e) {
            assertTrue(e.getMessage().contains("it is being used by cluster"));
            assertTrue(e.getMessage().contains("prod-db-1"));
        } catch (NotFoundException e) {
            fail("Should not throw NotFoundException for existing cluster type: " + e.getMessage());
        }
    }

    /**
     * Tests delete cluster type when not in use succeeds even with clusters present.
     *
     * @throws IOException if the operation fails
     */
    @Test
    public void testDeleteClusterType_notInUse_succeeds() throws IOException {
        createClusterType("ct-app", "App Cluster", "APP");

        // Create a cluster that uses a different cluster type
        createCluster("cluster-3", "prod-app-1", "Web Cluster", "group-1");

        try {
            boolean deleted = clusterTypeService.deleteClusterType("ct-app");
            assertTrue(deleted);
        } catch (ConflictException | NotFoundException e) {
            fail("Deletion should succeed when cluster type is not in use: " + e.getMessage());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────

    /**
     * Creates a cluster type directly in the data directory (bypassing the service).
     * Used to set up pre-existing data for list/get/delete tests.
     *
     * @param id the entity identifier used as filename
     * @param name the cluster type name
     * @param code the cluster type code
     */
    private void createClusterType(String id, String name, String code) {
        Map<String, Object> ct = new LinkedHashMap<>();
        ct.put("id", id);
        ct.put("name", name);
        ct.put("code", code);
        ct.put("description", "");
        ct.put("color", "#10b981");
        ct.put("knowledge", "");
        ct.put("commandPrefix", "");
        ct.put("envVariables", List.of());
        ct.put("mode", "peer");
        ct.put("solutionType", "universal");
        ct.put("createdAt", "2026-06-09T00:00:00Z");
        ct.put("updatedAt", "2026-06-09T00:00:00Z");

        try {
            Path file = clusterTypesDir.resolve(id + ".json");
            String json = new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(ct);
            Files.writeString(file, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Creates a cluster directly in the data directory (bypassing the service).
     * Used to set up pre-existing data for deletion tests.
     *
     * @param id the cluster identifier
     * @param name the cluster name
     * @param type the cluster type (can be name or code)
     * @param groupId the environment group ID
     */
    private void createCluster(String id, String name, String type, String groupId) {
        Path clustersDir = Path.of(tempFolder.getRoot().getAbsolutePath())
            .toAbsolutePath()
            .normalize()
            .resolve("gateway")
            .resolve("data")
            .resolve("clusters");

        try {
            Files.createDirectories(clustersDir);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        Map<String, Object> cluster = new LinkedHashMap<>();
        cluster.put("id", id);
        cluster.put("name", name);
        cluster.put("type", type);
        cluster.put("groupId", groupId);
        cluster.put("description", "");
        cluster.put("knowledge", "");
        cluster.put("port", 8080);
        cluster.put("username", "");
        cluster.put("password", "");
        cluster.put("createdAt", "2026-06-12T00:00:00Z");
        cluster.put("updatedAt", "2026-06-12T00:00:00Z");

        try {
            Path file = clustersDir.resolve(id + ".json");
            String json = new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(cluster);
            Files.writeString(file, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}

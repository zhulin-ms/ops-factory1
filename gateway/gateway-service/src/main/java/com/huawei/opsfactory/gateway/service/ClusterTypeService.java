/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.opsfactory.gateway.service;

import com.huawei.opsfactory.gateway.common.util.ValidationUtils;
import com.huawei.opsfactory.gateway.config.GatewayProperties;
import com.huawei.opsfactory.gateway.exception.BadRequestException;
import com.huawei.opsfactory.gateway.exception.ConflictException;
import com.huawei.opsfactory.gateway.exception.NotFoundException;

import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Manages cluster type definitions including mode, command prefix, and environment variables.
 *
 * @author x00000000
 * @since 2026-05-09
 */
@Service
public class ClusterTypeService extends JsonFileEntityStore {
    private final GatewayProperties properties;

    private final SolutionTypeService solutionTypeService;

    private ClusterService clusterService;

    /**
     * Creates the cluster type service instance.
     *
     * @param properties the gateway configuration properties
     * @param solutionTypeService the solution type service for validating solution type references
     */
    public ClusterTypeService(GatewayProperties properties, SolutionTypeService solutionTypeService) {
        super("cluster-type");
        this.properties = properties;
        this.solutionTypeService = solutionTypeService;
    }

    /**
     * Sets the cluster service via lazy injection.
     *
     * @param clusterService the cluster service
     */
    @Lazy
    @Autowired
    public void setClusterService(ClusterService clusterService) {
        this.clusterService = clusterService;
    }

    /**
     * Initializes the cluster types data directory at startup.
     */
    @PostConstruct
    public void init() {
        initDataDir(properties.getGatewayRootPath().resolve("data"), "cluster-types");
    }

    // ── CRUD Operations ──────────────────────────────────────────────

    /**
     * Lists all cluster types.
     *
     * @return the result
     */
    public List<Map<String, Object>> listClusterTypes() {
        List<Map<String, Object>> types = listEntities();
        for (Map<String, Object> type : types) {
            normalizeSolutionType(type);
        }
        return types;
    }

    /**
     * Gets a cluster type by its ID.
     *
     * @param id entity identifier
     * @return a cluster type by its ID
     * @throws NotFoundException if the cluster type is not found
     */
    public Map<String, Object> getClusterType(String id) throws NotFoundException {
        Map<String, Object> ct = readFile(resolveEntityFile(id));
        if (ct == null) {
            throw new NotFoundException("Cluster type not found");
        }
        normalizeSolutionType(ct);
        return ct;
    }

    /**
     * Creates a new cluster type from the provided field map.
     * Validates all fields before persistence.
     *
     * @param body request body
     * @return the created cluster type map
     * @throws IllegalArgumentException if validation fails or duplicate name/code exists
     */
    public Map<String, Object> createClusterType(Map<String, Object> body) {
        // Validate required fields
        String name = ValidationUtils.validateStringField(body, "name", "Cluster type name", 100, true);
        String code = ValidationUtils.validateStringField(body, "code", "Cluster type code", 50, true);
        validateNameAndCodeUnique(name, code, null);

        // Validate optional fields
        String description = ValidationUtils.validateLengthOnly(body, "description", "Description", 500);
        String knowledge = ValidationUtils.validateLengthOnly(body, "knowledge", "Knowledge", 2000);
        String commandPrefix = ValidationUtils.validateStringField(body, "commandPrefix", "Command prefix", 100, false);

        // Validate and extract mode
        String mode = body.getOrDefault("mode", "peer").toString();
        if (!"peer".equals(mode) && !"primary-backup".equals(mode)) {
            throw new IllegalArgumentException("Invalid mode. Must be 'peer' or 'primary-backup'");
        }

        // Validate environment variables
        List<Map<String, String>> envVariables = validateEnvVariables(body);

        // Validate solution type reference
        String solutionType = solutionTypeService.validateSolutionTypeReference(
            body.getOrDefault("solutionType", "universal"));

        // Default color
        String color = body.getOrDefault("color", "#10b981").toString();

        String id = UUID.randomUUID().toString();
        String now = Instant.now().toString();

        Map<String, Object> ct = new LinkedHashMap<>();
        ct.put("id", id);
        ct.put("name", name);
        ct.put("code", code);
        ct.put("description", description);
        ct.put("color", color);
        ct.put("knowledge", knowledge);
        ct.put("commandPrefix", commandPrefix.isEmpty() ? null : commandPrefix);
        ct.put("envVariables", envVariables);
        ct.put("mode", mode);
        ct.put("solutionType", solutionType);
        ct.put("createdAt", now);
        ct.put("updatedAt", now);

        writeEntityFile(id, ct);
        log.info("Created cluster type: id={}, name={}, code={}", id, name, code);
        return ct;
    }

    /**
     * Updates an existing cluster type with the provided field map.
     * Only fields present in the body are updated; each field is validated before being applied.
     *
     * @param id the cluster type identifier to update
     * @param body the field map containing fields to update
     * @return the updated cluster type map
     * @throws NotFoundException if the cluster type is not found
     * @throws BadRequestException if the mode value is invalid
     */
    public Map<String, Object> updateClusterType(String id, Map<String, Object> body)
            throws NotFoundException, BadRequestException {
        Map<String, Object> ct = readFile(resolveEntityFile(id));
        if (ct == null) {
            throw new NotFoundException("Cluster type not found");
        }
        normalizeSolutionType(ct);

        if (body.containsKey("name")) {
            String newName = ValidationUtils.validateStringField(body, "name", "Cluster type name", 100, true);
            validateNameAndCodeUnique(newName, null, id);
            ct.put("name", newName);
        }
        if (body.containsKey("code")) {
            String newCode = ValidationUtils.validateStringField(body, "code", "Cluster type code", 50, true);
            validateNameAndCodeUnique(null, newCode, id);
            ct.put("code", newCode);
        }
        if (body.containsKey("description")) {
            String newDescription = ValidationUtils.validateLengthOnly(body, "description", "Description", 500);
            ct.put("description", newDescription);
        }
        if (body.containsKey("knowledge")) {
            String newKnowledge = ValidationUtils.validateLengthOnly(body, "knowledge", "Knowledge", 2000);
            ct.put("knowledge", newKnowledge);
        }
        if (body.containsKey("commandPrefix")) {
            String newCommandPrefix = ValidationUtils.validateStringField(body, "commandPrefix", "Command prefix", 100, false);
            ct.put("commandPrefix", newCommandPrefix.isEmpty() ? null : newCommandPrefix);
        }
        if (body.containsKey("envVariables")) {
            List<Map<String, String>> validatedEnv = validateEnvVariables(body);
            ct.put("envVariables", validatedEnv);
        }
        if (body.containsKey("mode")) {
            String mode = (String) body.get("mode");
            if (!"peer".equals(mode) && !"primary-backup".equals(mode)) {
                throw new BadRequestException("Invalid mode. Must be 'peer' or 'primary-backup'");
            }
            ct.put("mode", mode);
        }
        if (body.containsKey("solutionType")) {
            ct.put("solutionType", solutionTypeService.validateSolutionTypeReference(body.get("solutionType")));
        }
        if (body.containsKey("color")) {
            ct.put("color", body.get("color"));
        }

        ct.put("updatedAt", Instant.now().toString());
        writeEntityFile(id, ct);
        log.info("Updated cluster type: id={}", id);
        return ct;
    }

    /**
     * Deletes a cluster type by its ID.
     *
     * @param id entity identifier
     * @return true if the cluster type was deleted, false if it was not found
     * @throws ConflictException if the cluster type is in use
     */
    public boolean deleteClusterType(String id) throws ConflictException, NotFoundException {
        // Get the cluster type to check its name and code
        Map<String, Object> ct = getClusterType(id);

        String name = ct.get("name") != null ? ct.get("name").toString() : "";
        String code = ct.get("code") != null ? ct.get("code").toString() : "";

        // Check if any cluster is using this cluster type by name or code
        if (clusterService != null) {
            List<Map<String, Object>> clusters = clusterService.listClusters(null, null);
            for (Map<String, Object> cluster : clusters) {
                String clusterType = cluster.get("type") != null ? cluster.get("type").toString() : "";
                String clusterName = cluster.get("name") != null ? cluster.get("name").toString() : "";

                if (name.equals(clusterType) || code.equals(clusterType)) {
                    throw new ConflictException("Cannot delete cluster type: it is being used by cluster '" + clusterName + "'");
                }
            }
        }
        return deleteEntityFile(id);
    }

    /**
     * Normalize solutionType from legacy UUID to code.
     *
     * @param ct cluster type entity
     */
    private void normalizeSolutionType(Map<String, Object> ct) {
        Object solutionType = ct.get("solutionType");
        if (solutionType != null && !"universal".equals(solutionType)) {
            try {
                String normalizedCode = solutionTypeService.validateSolutionTypeReference(solutionType);
                ct.put("solutionType", normalizedCode);
            } catch (IllegalArgumentException e) {
                // If validation fails, keep original value (may be deleted solution type)
                log.debug("Failed to normalize solutionType: {}", solutionType);
            }
        }
    }

    // ── Validation Helpers ────────────────────────────────────────────────

    /**
     * Validates environment variables array.
     * Checks for XSS characters and duplicate keys (case-insensitive).
     *
     * @param body request body containing envVariables field
     * @return validated and sanitized list of environment variable maps
     * @throws IllegalArgumentException if validation fails
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, String>> validateEnvVariables(Map<String, Object> body) {
        Object envObj = body.get("envVariables");
        if (envObj == null) {
            return new ArrayList<>();
        }

        if (!(envObj instanceof List)) {
            throw new IllegalArgumentException("Environment variables must be an array");
        }

        List<?> envList = (List<?>) envObj;
        List<Map<String, String>> result = new ArrayList<>();
        Map<String, String> seenKeys = new HashMap<>();

        for (int i = 0; i < envList.size(); i++) {
            Object itemObj = envList.get(i);
            if (!(itemObj instanceof Map)) {
                throw new IllegalArgumentException("Environment variable at index " + i + " must be an object");
            }

            Map<?, ?> item = (Map<?, ?>) itemObj;
            Object keyObj = item.get("key");
            Object valueObj = item.get("value");

            String key = keyObj != null ? keyObj.toString().trim() : "";
            String value = valueObj != null ? valueObj.toString().trim() : "";

            // Skip empty keys
            if (key.isEmpty()) {
                continue;
            }

            // Validate key length
            ValidationUtils.requireMaxLength(key, 100, "Environment variable key at index " + i);

            // Validate value length
            ValidationUtils.requireMaxLength(value, 500, "Environment variable value at index " + i);

            // Validate key with XSS check (strict)
            if (ValidationUtils.hasXssChars(key)) {
                throw new IllegalArgumentException(
                    "Environment variable key at index " + i + " contains invalid characters (< > \" ' & ` /)");
            }

            // Validate value with permissive check (allows / for paths)
            if (ValidationUtils.hasDangerousChars(value)) {
                throw new IllegalArgumentException(
                    "Environment variable value at index " + i + " contains invalid characters (< > \" ' & `)");
            }

            // Check for duplicate keys (case-insensitive)
            String lowerKey = key.toLowerCase(Locale.ROOT);
            if (seenKeys.containsKey(lowerKey)) {
                throw new IllegalArgumentException(
                    "Duplicate environment variable key: " + key + " (conflicts with " + seenKeys.get(lowerKey) + ")");
            }
            seenKeys.put(lowerKey, key);

            Map<String, String> envVar = new LinkedHashMap<>();
            envVar.put("key", key);
            envVar.put("value", value);
            result.add(envVar);
        }

        return result;
    }

    /**
     * Validates that the cluster type name and code are unique.
     *
     * @param name the name to validate (may be null)
     * @param code the code to validate (may be null)
     * @param excludeId the id to exclude from uniqueness check (may be null)
     * @throws IllegalArgumentException if name or code already exists
     */
    private void validateNameAndCodeUnique(String name, String code, String excludeId) {
        List<Map<String, Object>> existing = listClusterTypes();
        for (Map<String, Object> ct : existing) {
            String existingId = ct.get("id") != null ? ct.get("id").toString() : "";
            if (excludeId != null && existingId.equals(excludeId)) {
                continue;
            }
            if (name != null && !name.isBlank()) {
                String existingName = ct.get("name") != null ? ct.get("name").toString() : "";
                if (name.equalsIgnoreCase(existingName)) {
                    throw new IllegalArgumentException("Cluster type name already exists: " + name);
                }
            }
            if (code != null && !code.isBlank()) {
                String existingCode = ct.get("code") != null ? ct.get("code").toString() : "";
                if (code.equalsIgnoreCase(existingCode)) {
                    throw new IllegalArgumentException("Cluster type code already exists: " + code);
                }
            }
        }
    }
}

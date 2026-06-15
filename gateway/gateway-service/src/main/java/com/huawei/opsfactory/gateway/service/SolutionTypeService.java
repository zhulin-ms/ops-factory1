/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.opsfactory.gateway.service;

import com.huawei.opsfactory.gateway.common.util.ValidationUtils;
import com.huawei.opsfactory.gateway.config.GatewayProperties;
import com.huawei.opsfactory.gateway.exception.ConflictException;
import com.huawei.opsfactory.gateway.exception.NotFoundException;

import jakarta.annotation.PostConstruct;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Manages solution type definitions persisted as JSON files under the gateway data directory.
 *
 * @author x00000000
 * @since 2026-05-30
 */
@Service
public class SolutionTypeService extends JsonFileEntityStore {
    // Hex color pattern: #RRGGBB format, safe with bounded quantifier {6}
    private static final Pattern HEX_COLOR_PATTERN = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    private final GatewayProperties properties;

    /**
     * Creates the solution type service instance.
     *
     * @param properties gateway properties
     */
    public SolutionTypeService(GatewayProperties properties) {
        super("solution-type");
        this.properties = properties;
    }

    /**
     * Initializes the solution types data directory at startup.
     */
    @PostConstruct
    public void init() {
        initDataDir(properties.getGatewayRootPath().resolve("data"), "solution-types");
    }

    // ── CRUD Operations ──────────────────────────────────────────────

    /**
     * Lists all solution types persisted in the data directory.
     *
     * @return a list of all solution type maps; empty list if the directory does not exist or is empty
     */
    public List<Map<String, Object>> listSolutionTypes() {
        return listEntities();
    }

    /**
     * Gets a solution type by its ID.
     *
     * @param id entity identifier
     * @return a solution type by its ID
     * @throws NotFoundException if the solution type is not found
     */
    public Map<String, Object> getSolutionType(String id) throws NotFoundException {
        Map<String, Object> st = readFile(resolveEntityFile(id));
        if (st == null) {
            throw new NotFoundException("Solution type not found");
        }
        return st;
    }

    /**
     * Gets a solution type by its code.
     *
     * @param code solution type code
     * @return the solution type map
     * @throws NotFoundException if the solution type is not found
     */
    public Map<String, Object> getSolutionTypeByCode(String code) throws NotFoundException {
        List<Map<String, Object>> types = listSolutionTypes();
        for (Map<String, Object> st : types) {
            String stCode = st.get("code") != null ? st.get("code").toString() : "";
            if (code.equals(stCode)) {
                return st;
            }
        }
        throw new NotFoundException("Solution type not found: " + code);
    }

    /**
     * Creates a new solution type from the provided field map.
     * Validates all fields (name, code, description, color, knowledge) before persistence.
     *
     * @param body request body containing solution type fields
     * @return the created solution type map including generated id and timestamps
     * @throws IllegalArgumentException if validation fails or the code already exists
     * @throws ConflictException if the file cannot be written
     */
    public Map<String, Object> createSolutionType(Map<String, Object> body) throws ConflictException {
        String name = ValidationUtils.validateStringField(body, "name", "Solution type name", 100, true);
        String code = ValidationUtils.validateStringField(body, "code", "Solution type code", 50, true);
        validateNameAndCodeUnique(name, code, null);

        // description and knowledge: length validation only, no XSS check
        String description = ValidationUtils.validateLengthOnly(body, "description", "Description", 500);
        String knowledge = ValidationUtils.validateLengthOnly(body, "knowledge", "Knowledge", 2000);

        String color = body.getOrDefault("color", "#8b5cf6").toString();
        if (!HEX_COLOR_PATTERN.matcher(color).matches()) {
            color = "#8b5cf6";
        }

        String id = UUID.randomUUID().toString();
        String now = Instant.now().toString();

        Map<String, Object> st = new LinkedHashMap<>();
        st.put("id", id);
        st.put("name", name);
        st.put("code", code);
        st.put("description", description);
        st.put("color", color);
        st.put("knowledge", knowledge);
        st.put("createdAt", now);
        st.put("updatedAt", now);

        writeEntityFile(id, st);
        log.info("Created solution type: id={}, name={}, code={}", id, st.get("name"), st.get("code"));
        return st;
    }

    /**
     * Updates an existing solution type with the provided field map.
     * Only fields present in the body are updated; each field is validated before being applied.
     *
     * @param id entity identifier
     * @param body updated fields
     * @return the updated solution type map
     * @throws NotFoundException if the solution type is not found
     * @throws IllegalArgumentException if field validation fails or the new code already exists
     * @throws ConflictException if the file cannot be written
     */
    public Map<String, Object> updateSolutionType(String id, Map<String, Object> body) throws NotFoundException, ConflictException {
        Map<String, Object> st = readFile(resolveEntityFile(id));
        if (st == null) {
            throw new NotFoundException("Solution type not found");
        }

        if (body.containsKey("name")) {
            String newName = ValidationUtils.validateStringField(body, "name", "Solution type name", 100, true);
            validateNameAndCodeUnique(newName, null, id);
            st.put("name", newName);
        }
        if (body.containsKey("code")) {
            String newCode = ValidationUtils.validateStringField(body, "code", "Solution type code", 50, true);
            validateNameAndCodeUnique(null, newCode, id);
            st.put("code", newCode);
        }
        if (body.containsKey("description")) {
            String newDescription = ValidationUtils.validateLengthOnly(body, "description", "Description", 500);
            st.put("description", newDescription);
        }
        if (body.containsKey("color")) {
            Object colorObj = body.get("color");
            if (colorObj == null) {
                throw new IllegalArgumentException("Color is required");
            }
            String newColor = colorObj.toString();
            if (!HEX_COLOR_PATTERN.matcher(newColor).matches()) {
                newColor = "#8b5cf6";
            }
            st.put("color", newColor);
        }
        if (body.containsKey("knowledge")) {
            String newKnowledge = ValidationUtils.validateLengthOnly(body, "knowledge", "Knowledge", 2000);
            st.put("knowledge", newKnowledge);
        }

        st.put("updatedAt", Instant.now().toString());
        writeEntityFile(id, st);
        log.info("Updated solution type: id={}", id);
        return st;
    }

    /**
     * Deletes a solution type by its ID.
     * Checks if the solution type is being used by SOPs or cluster types before deletion.
     *
     * @param id entity identifier
     * @return true if the file was deleted, false if it did not exist
     * @throws ConflictException if the solution type is in use
     */
    public boolean deleteSolutionType(String id) throws ConflictException {
        // Get the solution type code first
        Map<String, Object> st = readFile(resolveEntityFile(id));
        if (st == null) {
            return false;
        }
        String code = st.get("code") != null ? st.get("code").toString() : "";

        // Check if the solution type is in use
        Map<String, List<String>> usage = checkSolutionTypeUsage(code);
        if (!usage.isEmpty()) {
            StringBuilder errorMsg = new StringBuilder("Solution type '");
            errorMsg.append(st.get("name") != null ? st.get("name").toString() : code);
            errorMsg.append("' is in use by: ");
            if (usage.containsKey("sops")) {
                errorMsg.append(usage.get("sops").size()).append(" SOP(s) - ");
                errorMsg.append(String.join(", ", usage.get("sops")));
            }
            if (usage.containsKey("clusterTypes")) {
                if (usage.containsKey("sops")) {
                    errorMsg.append(", ");
                }
                errorMsg.append(usage.get("clusterTypes").size()).append(" Cluster Type(s) - ");
                errorMsg.append(String.join(", ", usage.get("clusterTypes")));
            }
            throw new ConflictException(errorMsg.toString());
        }

        boolean deleted = deleteEntityFile(id);
        if (deleted) {
            log.info("Deleted solution type: id={}, code={}", id, code);
        }
        return deleted;
    }

    /**
     * Checks if a solution type is being used by SOPs or cluster types.
     *
     * @param code the solution type code
     * @return a map with usage info: {"sops": [...], "clusterTypes": [...]} or empty if not in use
     */
    public Map<String, List<String>> checkSolutionTypeUsage(String code) {
        Map<String, List<String>> usage = new LinkedHashMap<>();

        // Check SOPs
        Path sopsDir = properties.getGatewayRootPath().resolve("data").resolve("sops");
        if (Files.isDirectory(sopsDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(sopsDir, "*.json")) {
                for (Path file : stream) {
                    if (!Files.isRegularFile(file)) {
                        continue;
                    }
                    Map<String, Object> sop = readFile(file);
                    if (sop != null) {
                        String targetSolution = sop.get("targetSolution") != null ? sop.get("targetSolution").toString() : "";
                        if (code.equals(targetSolution)) {
                            usage.computeIfAbsent("sops", k -> new ArrayList<>()).add(sop.get("name") != null ? sop.get("name").toString() : "unnamed");
                        }
                    }
                }
            } catch (IOException e) {
                log.error("Failed to check SOP usage for solution type: {}", code, e);
            }
        }

        // Check cluster types
        Path clusterTypesDir = properties.getGatewayRootPath().resolve("data").resolve("cluster-types");
        if (Files.isDirectory(clusterTypesDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(clusterTypesDir, "*.json")) {
                for (Path file : stream) {
                    if (!Files.isRegularFile(file)) {
                        continue;
                    }
                    Map<String, Object> ct = readFile(file);
                    if (ct != null) {
                        String solutionType = ct.get("solutionType") != null ? ct.get("solutionType").toString() : "";
                        if (code.equals(solutionType)) {
                            usage.computeIfAbsent("clusterTypes", k -> new ArrayList<>()).add(ct.get("name") != null ? ct.get("name").toString() : "unnamed");
                        }
                    }
                }
            } catch (IOException e) {
                log.error("Failed to check cluster type usage for solution type: {}", code, e);
            }
        }

        return usage;
    }

    // ── Validation ────────────────────────────────────────────────────

    /**
     * Validates that the solution type name and code are unique.
     *
     * @param name the name to validate (may be null)
     * @param code the code to validate (may be null)
     * @param excludeId the id to exclude from uniqueness check (may be null)
     * @throws IllegalArgumentException if name or code already exists
     */
    private void validateNameAndCodeUnique(String name, String code, String excludeId) {
        List<Map<String, Object>> existing = listSolutionTypes();
        for (Map<String, Object> st : existing) {
            String existingId = st.get("id") != null ? st.get("id").toString() : "";
            if (excludeId != null && existingId.equals(excludeId)) {
                continue;
            }
            if (name != null && !name.isBlank()) {
                String existingName = st.get("name") != null ? st.get("name").toString() : "";
                if (name.equalsIgnoreCase(existingName)) {
                    throw new IllegalArgumentException("Solution type name already exists: " + name);
                }
            }
            if (code != null && !code.isBlank()) {
                String existingCode = st.get("code") != null ? st.get("code").toString() : "";
                if (code.equalsIgnoreCase(existingCode)) {
                    throw new IllegalArgumentException("Solution type code already exists: " + code);
                }
            }
        }
    }

    /**
     * Validates a referenced solution type, allowing the universal default.
     * Accepts only solution type code.
     *
     * @param value referenced solution type code or {@code null}
     * @return normalized solution type code
     * @throws IllegalArgumentException if solution type code is not found
     */
    public String validateSolutionTypeReference(Object value) {
        if (value == null) {
            return "universal";
        }
        String solutionType = value.toString();
        if ("universal".equals(solutionType)) {
            return solutionType;
        }

        Map<String, Object> st;
        try {
            st = getSolutionTypeByCode(solutionType);
        } catch (NotFoundException e) {
            throw new IllegalArgumentException("Solution type not found: " + solutionType, e);
        }

        String code = st.get("code") != null ? st.get("code").toString() : "";
        if (code.isBlank()) {
            throw new IllegalArgumentException("Solution type has empty code: " + solutionType);
        }
        return code;
    }

}

/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.opsfactory.gateway.service;

import com.huawei.opsfactory.gateway.common.util.ValidationUtils;
import com.huawei.opsfactory.gateway.config.GatewayProperties;
import com.huawei.opsfactory.gateway.exception.ConflictException;
import com.huawei.opsfactory.gateway.exception.NotFoundException;

import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Provides CRUD operations, topology queries, and host-id synchronization for business services.
 *
 * @author x00000000
 * @since 2026-05-09
 */
@Service
public class BusinessServiceService extends JsonFileEntityStore {

    private final GatewayProperties properties;

    private ClusterService clusterService;

    private HostService hostService;

    private HostRelationService hostRelationService;

    private ClusterRelationService clusterRelationService;

    private BusinessTypeService businessTypeService;

    private HostGroupService hostGroupService;

    /**
     * Creates the business service service instance.
     */
    public BusinessServiceService(GatewayProperties properties) {
        super("business-service");
        this.properties = properties;
    }

    /**
     * Sets the cluster service via lazy injection.
     *
     * @param clusterService the cluster service via lazy injection
     */
    @Lazy
    @Autowired
    public void setClusterService(ClusterService clusterService) {
        this.clusterService = clusterService;
    }

    /**
     * Sets the host service via lazy injection.
     *
     * @param hostService the host service via lazy injection
     */
    @Lazy
    @Autowired
    public void setHostService(HostService hostService) {
        this.hostService = hostService;
    }

    /**
     * Sets the host relation service via lazy injection.
     *
     * @param hostRelationService the host relation service via lazy injection
     */
    @Lazy
    @Autowired
    public void setHostRelationService(HostRelationService hostRelationService) {
        this.hostRelationService = hostRelationService;
    }

    /**
     * Sets the cluster relation service via lazy injection.
     *
     * @param clusterRelationService the cluster relation service via lazy injection
     */
    @Lazy
    @Autowired
    public void setClusterRelationService(ClusterRelationService clusterRelationService) {
        this.clusterRelationService = clusterRelationService;
    }

    /**
     * Sets the business type service via lazy injection.
     *
     * @param businessTypeService the business type service via lazy injection
     */
    @Lazy
    @Autowired
    public void setBusinessTypeService(BusinessTypeService businessTypeService) {
        this.businessTypeService = businessTypeService;
    }

    /**
     * Sets the host group service via lazy injection.
     *
     * @param hostGroupService the host group service via lazy injection
     */
    @Lazy
    @Autowired
    public void setHostGroupService(HostGroupService hostGroupService) {
        this.hostGroupService = hostGroupService;
    }

    /**
     * Initializes the business services data directory at startup.
     */
    @PostConstruct
    public void init() {
        initDataDir(properties.getGatewayRootPath().resolve("data"), "business-services");
    }

    // ── CRUD Operations ──────────────────────────────────────────────

    /**
     * Lists business services optionally filtered by group ID and host ID.
     *
     * @param groupId group identifier
     * @param hostId host identifier
     * @return the result
     */
    public List<Map<String, Object>> listBusinessServices(String groupId, String hostId) {
        List<Map<String, Object>> services = new ArrayList<>();
        if (!Files.isDirectory(getDataDir())) {
            return services;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(getDataDir(), "*.json")) {
            for (Path file : stream) {
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                Map<String, Object> bs = readFile(file);
                if (bs == null) {
                    continue;
                }
                // Filter by groupId
                if (groupId != null && !groupId.isEmpty()) {
                    Object bsGroupId = bs.get("groupId");
                    if (!groupId.equals(bsGroupId)) {
                        continue;
                    }
                }
                // Filter by hostId — check if hostIds list contains it
                if (hostId != null && !hostId.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    List<String> hostIds = (List<String>) bs.get("hostIds");
                    if (hostIds == null || !hostIds.contains(hostId)) {
                        continue;
                    }
                }
                services.add(bs);
            }
        } catch (IOException e) {
            log.error("Failed to list business-services from {}", getDataDir(), e);
        }
        return services;
    }

    /**
     * Gets a business service by its ID.
     *
     * @param id entity identifier
     * @return a business service by its ID
     */
    public Map<String, Object> getBusinessService(String id) throws NotFoundException {
        Path file = resolveEntityFile(id);
        Map<String, Object> bs = readFile(file);
        if (bs == null) {
            throw new NotFoundException("Business service not found");
        }
        return bs;
    }

    /**
     * Creates a new business service from the provided field map.
     *
     * @param body request body
     * @return the created business service map
     * @throws ConflictException if name already exists in the group
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> createBusinessService(Map<String, Object> body) throws ConflictException, NotFoundException {
        String name = ValidationUtils.validateStringField(body, "name", "Business service name", 100, true);

        String groupId = ValidationUtils.requireNonBlank(body, "groupId", "Group is required");
        String businessTypeId = ValidationUtils.requireNonBlank(body, "businessTypeId", "Business type is required");

        // Validate business type exists
        if (businessTypeService != null) {
            try {
                businessTypeService.getBusinessType(businessTypeId);
            } catch (NotFoundException e) {
                throw new NotFoundException("Business type not found: " + businessTypeId);
            }
        }

        // Check for duplicate business service name in related group hierarchy
        List<Map<String, Object>> allBusinessServices = listBusinessServices(null, null);
        boolean nameDuplicate = allBusinessServices.stream()
            .anyMatch(s -> {
                String bsGroupId = s.get("groupId") != null ? s.get("groupId").toString() : null;
                return name.equalsIgnoreCase(String.valueOf(s.get("name")))
                    && doGroupHierarchiesOverlap(groupId, bsGroupId);
            });
        if (nameDuplicate) {
            throw new ConflictException("Business service name already exists in this group hierarchy");
        }

        // Validate and check for duplicate business service code globally
        String code = ValidationUtils.validateStringField(body, "code", "Business service code", 50, false);
        if (code != null && !code.isEmpty()) {
            boolean codeDuplicate = allBusinessServices.stream()
                .anyMatch(s -> {
                    String bsCode = s.get("code") != null ? s.get("code").toString() : "";
                    return code.equalsIgnoreCase(bsCode);
                });
            if (codeDuplicate) {
                throw new ConflictException("Business service code already exists");
            }
        }

        ValidationUtils.validateStringField(body, "description", "Description", 500, false);

        String id = UUID.randomUUID().toString();
        String now = Instant.now().toString();

        Map<String, Object> bs = new LinkedHashMap<>();
        bs.put("id", id);
        bs.put("name", name);
        bs.put("code", code != null ? code : "");
        bs.put("groupId", groupId);
        bs.put("description", body.getOrDefault("description", ""));
        bs.put("hostIds", body.getOrDefault("hostIds", new ArrayList<String>()));
        bs.put("tags", body.getOrDefault("tags", new ArrayList<String>()));
        bs.put("priority", body.getOrDefault("priority", ""));
        bs.put("contactInfo", body.getOrDefault("contactInfo", ""));
        bs.put("businessTypeId", businessTypeId);
        bs.put("enabled", body.getOrDefault("enabled", true));
        bs.put("createdAt", now);
        bs.put("updatedAt", now);

        writeEntityFile(id, bs);
        log.info("Created business service: id={}, name={}, code={}", id, bs.get("name"), bs.get("code"));
        return bs;
    }

    /**
     * Updates an existing business service with the provided field map.
     *
     * @param id business service identifier
     * @param body request body containing updated fields
     * @return the updated business service map
     * @throws NotFoundException if business service not found
     * @throws ConflictException if name already exists in the group
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> updateBusinessService(String id, Map<String, Object> body) throws NotFoundException, ConflictException {
        Path file = resolveEntityFile(id);
        Map<String, Object> bs = readFile(file);
        if (bs == null) {
            throw new NotFoundException("Business service not found");
        }

        if (body.containsKey("name")) {
            String newName = ValidationUtils.validateStringField(body, "name", "Business service name", 100, true);

            Object groupIdObj = body.containsKey("groupId") ? body.get("groupId") : bs.get("groupId");
            String groupId = groupIdObj != null ? groupIdObj.toString() : "";
            // Check for duplicate business service name in related group hierarchy
            List<Map<String, Object>> allBusinessServices = listBusinessServices(null, null);
            boolean nameDuplicate = allBusinessServices.stream()
                .filter(s -> !id.equals(s.get("id")))
                .anyMatch(s -> {
                    String bsGroupId = s.get("groupId") != null ? s.get("groupId").toString() : null;
                    return newName.equalsIgnoreCase(String.valueOf(s.get("name")))
                        && doGroupHierarchiesOverlap(groupId, bsGroupId);
                });
            if (nameDuplicate) {
                throw new ConflictException("Business service name already exists in this group hierarchy");
            }
            bs.put("name", newName);
        }
        if (body.containsKey("code")) {
            String code = ValidationUtils.validateStringField(body, "code", "Business service code", 50, false);
            if (code != null && !code.isEmpty()) {
                // Check for duplicate business service code globally
                List<Map<String, Object>> allBusinessServices = listBusinessServices(null, null);
                boolean codeDuplicate = allBusinessServices.stream()
                    .filter(s -> !id.equals(s.get("id")))
                    .anyMatch(s -> {
                        String bsCode = s.get("code") != null ? s.get("code").toString() : "";
                        return code.equalsIgnoreCase(bsCode);
                    });
                if (codeDuplicate) {
                    throw new ConflictException("Business service code already exists");
                }
            }
            bs.put("code", code);
        }
        if (body.containsKey("groupId")) {
            String newGroupId = ValidationUtils.requireNonBlank(body, "groupId", "Group is required");
            bs.put("groupId", newGroupId);
        }
        if (body.containsKey("description")) {
            String description = ValidationUtils.validateStringField(body, "description", "Description", 500, false);
            bs.put("description", description);
        }
        if (body.containsKey("hostIds")) {
            bs.put("hostIds", body.get("hostIds"));
        }
        if (body.containsKey("tags")) {
            bs.put("tags", body.get("tags"));
        }
        if (body.containsKey("priority")) {
            bs.put("priority", body.get("priority"));
        }
        if (body.containsKey("contactInfo")) {
            bs.put("contactInfo", body.get("contactInfo"));
        }
        if (body.containsKey("businessTypeId")) {
            String newBusinessTypeId = ValidationUtils.requireNonBlank(body, "businessTypeId", "Business type is required");
            // Validate business type exists
            if (businessTypeService != null) {
                try {
                    businessTypeService.getBusinessType(newBusinessTypeId);
                } catch (NotFoundException e) {
                    throw new NotFoundException("Business type not found: " + newBusinessTypeId);
                }
            }
            bs.put("businessTypeId", newBusinessTypeId);
        }
        if (body.containsKey("enabled")) {
            bs.put("enabled", body.get("enabled"));
        }

        bs.put("updatedAt", Instant.now().toString());
        writeEntityFile(id, bs);
        log.info("Updated business service: id={}", id);
        return bs;
    }

    /**
     * Deletes a business service by ID, cascading to related host and cluster relations.
     *
     * @param id entity identifier
     * @return the result
     */
    public boolean deleteBusinessService(String id) {
        // Cascade delete related HostRelation records
        if (hostRelationService != null) {
            hostRelationService.deleteRelationsByBusinessService(id);
        }
        // Cascade delete related ClusterRelation records
        if (clusterRelationService != null) {
            clusterRelationService.deleteRelationsByBusinessService(id);
        }

        Path file = resolveEntityFile(id);
        try {
            if (Files.exists(file)) {
                Files.delete(file);
                log.info("Deleted business service: id={}", id);
                return true;
            }
            return false;
        } catch (IOException e) {
            log.error("Failed to delete business-service file: {}", file, e);
            return false;
        }
    }

    // ── Query Methods ────────────────────────────────────────────────

    /**
     * Get business service with resolved host info.
     *
     * @param id entity identifier
     * @return business service with resolved host info
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getWithResolvedHosts(String id) throws NotFoundException {
        Map<String, Object> bs = getBusinessService(id);

        List<String> hostIds = (List<String>) bs.getOrDefault("hostIds", new ArrayList<>());
        List<Map<String, Object>> resolvedHosts = new ArrayList<>();

        for (String hid : hostIds) {
            try {
                Map<String, Object> host = hostService.getHost(hid);
                resolvedHosts.add(host);
            } catch (NotFoundException e) {
                log.warn("Host {} not found for business service {}", hid, id);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>(bs);
        result.put("resolvedHosts", resolvedHosts);
        result.put("totalHostCount", resolvedHosts.size());
        return result;
    }

    /**
     * Get hosts for the entry resources of a business service.
     *
     * @param id entity identifier
     * @return hosts for the entry resources of a business service
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getHostsForBusinessService(String id) throws NotFoundException {
        Map<String, Object> bs = getBusinessService(id);
        List<String> hostIds = (List<String>) bs.getOrDefault("hostIds", new ArrayList<>());

        List<Map<String, Object>> hosts = new ArrayList<>();
        for (String hid : hostIds) {
            try {
                hosts.add(hostService.getHost(hid));
            } catch (NotFoundException e) {
                log.warn("Host {} not found for business service {}", hid, id);
            }
        }
        return hosts;
    }

    /**
     * Get topology for a business service: entry hosts + N-hop downstream expansion.
     * Returns { nodes, edges }
     *
     * @param id entity identifier
     * @return topology for a business service: entry hosts + N-hop downstream expansion
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getTopologyForBusinessService(String id) throws NotFoundException {
        Map<String, Object> bs = getBusinessService(id);
        List<String> entryHostIdsList = (List<String>) bs.getOrDefault("hostIds", new ArrayList<>());
        List<Map<String, Object>> allRelations = listAllRelations();
        Map<String, Map<String, Object>> hostMap = loadEntryHosts(id, entryHostIdsList);
        LinkedHashSet<String> entryHostIds = new LinkedHashSet<>(hostMap.keySet());
        expandDownstreamHosts(id, hostMap, entryHostIds, allRelations);

        List<Map<String, Object>> edges = collectDiscoveredEdges(hostMap, allRelations);
        Map<String, Map<String, Object>> clusterMap = buildClusterLookup();
        List<Map<String, Object>> nodes = buildTopologyNodes(bs, hostMap, clusterMap, entryHostIds);
        prependBusinessServiceEdges(id, bs, hostMap, edges);
        return buildTopologyResult(nodes, edges);
    }

    /**
     * Migrate from Host.business field: group by (businessName, groupId) -> create BusinessService.
     *
     * @return the migrate from Host.business field: group by (businessName, groupId) -> create BusinessService
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> migrateFromBusinessField() {
        List<Map<String, Object>> allHosts = hostService.listHosts(new String[0]);
        List<Map<String, Object>> allClusters = clusterService.listClusters(null, null);
        Map<String, String> clusterGroupMap = buildClusterGroupMap(allClusters);
        Map<String, List<Map<String, Object>>> grouped = groupHostsByBusinessAndGroup(allHosts, clusterGroupMap);
        List<Map<String, Object>> created = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : grouped.entrySet()) {
            List<Map<String, Object>> hosts = entry.getValue();
            String business = (String) hosts.get(0).get("business");
            MigrationCandidate candidate = buildMigrationCandidate(hosts, clusterGroupMap);
            if (businessServiceExists(business, candidate.groupId())) {
                continue;
            }
            try {
                created.add(createBusinessService(buildMigrationBody(business, candidate)));
            } catch (ConflictException | NotFoundException e) {
                log.warn("Skipping business service creation during migration: {}", e.getMessage());
            }
        }
        Map<String, Object> result = buildMigrationResult(created);
        log.info("Migration complete: created {} business services", created.size());
        return result;
    }

    // ── Keyword search ───────────────────────────────────────────────

    /**
     * Sync hostIds on a business service from its HostRelation records.
     *
     * @param bsId bs id
     */
    @SuppressWarnings("unchecked")
    public void syncHostIdsFromRelations(String bsId) throws NotFoundException {
        Map<String, Object> bs = getBusinessService(bsId);
        List<Map<String, Object>> rels = hostRelationService.listRelations(null, null, null, "business-service", bsId);
        List<String> newHostIds = rels.stream().map(r -> (String) r.get("targetHostId")).collect(Collectors.toList());
        bs.put("hostIds", newHostIds);
        bs.put("updatedAt", Instant.now().toString());
        writeEntityFile(bsId, bs);
    }

    /**
     * Sync hostIds on a business service from its ClusterRelation records.
     * Derives entry hosts from ClusterRelation where sourceType="business-service" and sourceId=bsId.
     * Resolves targetId (cluster) -> get cluster's hosts -> populate BS.hostIds.
     *
     * @param bsId bs id
     */
    @SuppressWarnings("unchecked")
    public void syncHostIdsFromClusterRelations(String bsId) throws NotFoundException {
        Map<String, Object> bs = getBusinessService(bsId);
        List<Map<String, Object>> allClusterRels = clusterRelationService.listRelations(null);
        List<String> newHostIds = new ArrayList<>();
        for (Map<String, Object> rel : allClusterRels) {
            String sourceType = (String) rel.getOrDefault("sourceType", "cluster");
            String sourceId = (String) rel.get("sourceId");
            if (!"business-service".equals(sourceType) || !bsId.equals(sourceId)) {
                continue;
            }
            String targetClusterId = (String) rel.get("targetId");
            if (targetClusterId == null) {
                continue;
            }
            List<Map<String, Object>> clusterHosts = hostService.listHostsByCluster(targetClusterId);
            for (Map<String, Object> h : clusterHosts) {
                String hid = (String) h.get("id");
                if (hid != null && !newHostIds.contains(hid)) {
                    newHostIds.add(hid);
                }
            }
        }
        bs.put("hostIds", newHostIds);
        bs.put("updatedAt", Instant.now().toString());
        writeEntityFile(bsId, bs);
    }

    /**
     * Remove a host from all business services' hostIds (called when a host is deleted).
     *
     * @param hostId remove a host from all business services' hostIds (called when a host is deleted)
     */
    @SuppressWarnings("unchecked")
    public void removeHostFromAllBusinessServices(String hostId) {
        List<Map<String, Object>> allBs = listBusinessServices(null, null);
        for (Map<String, Object> bs : allBs) {
            List<String> hostIds = (List<String>) bs.getOrDefault("hostIds", new ArrayList<>());
            if (hostIds.remove(hostId)) {
                bs.put("hostIds", hostIds);
                bs.put("updatedAt", Instant.now().toString());
                writeEntityFile((String) bs.get("id"), bs);
            }
        }
    }

    // ── Keyword search ───────────────────────────────────────────────

    /**
     * Search business services by keyword matching against name, code, and tags.
     *
     * @param keyword search business services by keyword matching against name, code, and tags
     * @return the search business services by keyword matching against name, code, and tags
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> searchByKeyword(String keyword) {
        List<Map<String, Object>> all = listBusinessServices(null, null);
        if (keyword == null || keyword.trim().isEmpty()) {
            return all;
        }
        String kw = keyword.trim().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> bs : all) {
            String name = String.valueOf(bs.getOrDefault("name", "")).toLowerCase(Locale.ROOT);
            String code = String.valueOf(bs.getOrDefault("code", "")).toLowerCase(Locale.ROOT);
            List<String> tags = (List<String>) bs.getOrDefault("tags", new ArrayList<>());

            boolean match = name.contains(kw) || code.contains(kw);
            if (!match) {
                for (String tag : tags) {
                    if (tag.toLowerCase(Locale.ROOT).contains(kw)) {
                        match = true;
                        break;
                    }
                }
            }
            if (match) {
                results.add(bs);
            }
        }
        return results;
    }

    // ── Internal helpers ─────────────────────────────────────────────

    private Map<String, Map<String, Object>> loadEntryHosts(String businessServiceId, List<String> entryHostIdsList) {
        Map<String, Map<String, Object>> hostMap = new LinkedHashMap<>();
        for (String hostId : entryHostIdsList) {
            try {
                hostMap.put(hostId, hostService.getHost(hostId));
            } catch (NotFoundException e) {
                log.warn("Entry host {} not found for business service {}", hostId, businessServiceId);
            }
        }
        return hostMap;
    }

    private void expandDownstreamHosts(String businessServiceId, Map<String, Map<String, Object>> hostMap,
        LinkedHashSet<String> entryHostIds, List<Map<String, Object>> allRelations) {
        LinkedHashSet<String> frontier = new LinkedHashSet<>(entryHostIds);
        for (int hop = 0; hop < 5 && !frontier.isEmpty(); hop++) {
            frontier = collectNextFrontier(businessServiceId, hostMap, frontier, allRelations);
        }
    }

    private LinkedHashSet<String> collectNextFrontier(String businessServiceId, Map<String, Map<String, Object>> hostMap,
        LinkedHashSet<String> frontier, List<Map<String, Object>> allRelations) {
        LinkedHashSet<String> nextFrontier = new LinkedHashSet<>();
        for (Map<String, Object> rel : allRelations) {
            String sourceId = (String) rel.get("sourceHostId");
            String targetId = (String) rel.get("targetHostId");
            if (!frontier.contains(sourceId) || hostMap.containsKey(targetId)) {
                continue;
            }
            try {
                hostMap.put(targetId, hostService.getHost(targetId));
                nextFrontier.add(targetId);
            } catch (NotFoundException e) {
                log.debug("Skipping missing downstream host {} for business service {}", targetId, businessServiceId);
            }
        }
        return nextFrontier;
    }

    private List<Map<String, Object>> collectDiscoveredEdges(Map<String, Map<String, Object>> hostMap,
        List<Map<String, Object>> allRelations) {
        List<Map<String, Object>> edges = new ArrayList<>();
        for (Map<String, Object> rel : allRelations) {
            String sourceId = (String) rel.get("sourceHostId");
            String targetId = (String) rel.get("targetHostId");
            if (hostMap.containsKey(sourceId) && hostMap.containsKey(targetId)) {
                edges.add(buildHostEdge(sourceId, targetId, rel.get("description")));
            }
        }
        return edges;
    }

    private Map<String, Object> buildHostEdge(String sourceId, String targetId, Object description) {
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("source", sourceId);
        edge.put("target", targetId);
        edge.put("description", description);
        return edge;
    }

    private Map<String, Map<String, Object>> buildClusterLookup() {
        Map<String, Map<String, Object>> clusterMap = new LinkedHashMap<>();
        for (Map<String, Object> cluster : clusterService.listClusters(null, null)) {
            clusterMap.put((String) cluster.get("id"), cluster);
        }
        return clusterMap;
    }

    private List<Map<String, Object>> buildTopologyNodes(Map<String, Object> bs, Map<String, Map<String, Object>> hostMap,
        Map<String, Map<String, Object>> clusterMap, LinkedHashSet<String> entryHostIds) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(buildBusinessServiceNode(bs));
        for (Map.Entry<String, Map<String, Object>> entry : hostMap.entrySet()) {
            Map<String, Object> node = buildHostNode(entry.getValue(), clusterMap);
            node.put("isEntry", entryHostIds.contains(entry.getKey()));
            nodes.add(node);
        }
        return nodes;
    }

    private Map<String, Object> buildBusinessServiceNode(Map<String, Object> bs) {
        Map<String, Object> bsNode = new LinkedHashMap<>();
        bsNode.put("id", bs.get("id"));
        bsNode.put("name", bs.get("name"));
        bsNode.put("ip", null);
        bsNode.put("clusterType", null);
        bsNode.put("clusterName", null);
        bsNode.put("purpose", null);
        bsNode.put("groupId", bs.get("groupId"));
        bsNode.put("nodeType", "business-service");
        return bsNode;
    }

    private void prependBusinessServiceEdges(String businessServiceId, Map<String, Object> bs,
        Map<String, Map<String, Object>> hostMap, List<Map<String, Object>> edges) {
        List<Map<String, Object>> bsRelations =
            hostRelationService.listRelations(null, null, null, "business-service", businessServiceId);
        for (Map<String, Object> rel : bsRelations) {
            String targetId = (String) rel.get("targetHostId");
            if (targetId != null && hostMap.containsKey(targetId)) {
                edges.add(0, buildBusinessEntryEdge(bs, targetId, rel));
            }
        }
    }

    private Map<String, Object> buildBusinessEntryEdge(Map<String, Object> bs, String targetId, Map<String, Object> rel) {
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("source", bs.get("id"));
        edge.put("target", targetId);
        edge.put("description", rel.getOrDefault("description", ""));
        edge.put("type", "business-entry");
        return edge;
    }

    private Map<String, Object> buildTopologyResult(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes", nodes);
        result.put("edges", edges);
        return result;
    }

    private Map<String, String> buildClusterGroupMap(List<Map<String, Object>> allClusters) {
        Map<String, String> clusterGroupMap = new LinkedHashMap<>();
        for (Map<String, Object> cluster : allClusters) {
            String clusterId = (String) cluster.get("id");
            String groupId = (String) cluster.get("groupId");
            if (clusterId != null && groupId != null) {
                clusterGroupMap.put(clusterId, groupId);
            }
        }
        return clusterGroupMap;
    }

    private Map<String, List<Map<String, Object>>> groupHostsByBusinessAndGroup(List<Map<String, Object>> allHosts,
        Map<String, String> clusterGroupMap) {
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> host : allHosts) {
            String business = (String) host.get("business");
            if (business == null || business.trim().isEmpty()) {
                continue;
            }
            String groupId = resolveHostGroupId(host, clusterGroupMap);
            String key = business + "@" + (groupId != null ? groupId : "unknown");
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(host);
        }
        return grouped;
    }

    private String resolveHostGroupId(Map<String, Object> host, Map<String, String> clusterGroupMap) {
        String clusterId = (String) host.get("clusterId");
        return clusterId != null ? clusterGroupMap.get(clusterId) : null;
    }

    private MigrationCandidate buildMigrationCandidate(List<Map<String, Object>> hosts, Map<String, String> clusterGroupMap) {
        LinkedHashSet<String> hostIds = new LinkedHashSet<>();
        String groupId = null;
        for (Map<String, Object> host : hosts) {
            addHostId(hostIds, host);
            if (groupId == null) {
                groupId = resolveHostGroupId(host, clusterGroupMap);
            }
        }
        return new MigrationCandidate(groupId, new ArrayList<>(hostIds));
    }

    private void addHostId(LinkedHashSet<String> hostIds, Map<String, Object> host) {
        String hostId = (String) host.get("id");
        if (hostId != null) {
            hostIds.add(hostId);
        }
    }

    private boolean businessServiceExists(String business, String groupId) {
        for (Map<String, Object> existing : listBusinessServices(groupId, null)) {
            if (business.equals(existing.get("name"))) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> buildMigrationBody(String business, MigrationCandidate candidate) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", business);
        body.put("code", "");
        body.put("groupId", candidate.groupId());
        body.put("description", business);
        body.put("hostIds", candidate.hostIds());
        body.put("tags", List.of(business));
        body.put("priority", "");
        return body;
    }

    private Map<String, Object> buildMigrationResult(List<Map<String, Object>> created) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("migrated", created.size());
        result.put("businessServices", created);
        return result;
    }

    private record MigrationCandidate(String groupId, List<String> hostIds) {
    }

    private List<Map<String, Object>> listAllRelations() {
        List<Map<String, Object>> relations = new ArrayList<>();
        Path relDir = getDataDir().getParent().resolve("host-relations");
        if (!Files.isDirectory(relDir)) {
            return relations;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(relDir, "*.json")) {
            for (Path file : stream) {
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                Map<String, Object> rel = readFile(file);
                if (rel != null) {
                    relations.add(rel);
                }
            }
        } catch (IOException e) {
            log.error("Failed to list relations", e);
        }
        return relations;
    }

    private Map<String, Object> buildHostNode(Map<String, Object> h, Map<String, Map<String, Object>> clusterMap) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", h.get("id"));
        node.put("name", h.get("name"));
        node.put("ip", h.get("ip"));
        node.put("businessIp", h.get("businessIp"));
        String hostClusterId = h.get("clusterId") != null ? h.get("clusterId").toString() : null;
        node.put("clusterId", hostClusterId);
        Map<String, Object> cluster = hostClusterId != null ? clusterMap.get(hostClusterId) : null;
        node.put("clusterType", cluster != null ? cluster.get("type") : null);
        node.put("clusterName", cluster != null ? cluster.get("name") : null);
        node.put("purpose", h.get("purpose"));
        node.put("groupId", cluster != null ? cluster.get("groupId") : null);
        node.put("tags", h.get("tags"));
        return node;
    }

    // ── Group Hierarchy Helpers ──────────────────────────────────────────

    /**
     * Gets the ancestor IDs of a group (including itself) by traversing up the parentId chain.
     *
     * @param groupId the group ID to start from
     * @return set of ancestor group IDs (including the input group)
     */
    private LinkedHashSet<String> getAncestorGroupIds(String groupId) {
        LinkedHashSet<String> ancestorIds = new LinkedHashSet<>();
        if (hostGroupService == null) {
            ancestorIds.add(groupId);
            return ancestorIds;
        }

        String currentId = groupId;
        while (currentId != null && !currentId.isBlank()) {
            ancestorIds.add(currentId);
            try {
                Map<String, Object> group = hostGroupService.getGroup(currentId);
                Object parentId = group.get("parentId");
                currentId = (parentId != null && !parentId.toString().isBlank()) ? parentId.toString() : null;
            } catch (NotFoundException e) {
                break;
            }
        }
        return ancestorIds;
    }

    /**
     * Gets the descendant IDs of a group (including itself) by finding all children recursively.
     *
     * @param groupId the group ID to start from
     * @return set of descendant group IDs (including the input group)
     */
    private LinkedHashSet<String> getDescendantGroupIds(String groupId) {
        LinkedHashSet<String> descendantIds = new LinkedHashSet<>();
        if (hostGroupService == null) {
            descendantIds.add(groupId);
            return descendantIds;
        }

        descendantIds.add(groupId);
        List<Map<String, Object>> allGroups = hostGroupService.listGroups();

        // Find all direct children of groupId
        List<Map<String, Object>> children = allGroups.stream()
            .filter(g -> {
                Object parentId = g.get("parentId");
                return groupId.equals(parentId != null ? parentId.toString() : null);
            })
            .toList();

        // Recursively get descendants of each child
        for (Map<String, Object> child : children) {
            String childId = child.get("id") != null ? child.get("id").toString() : "";
            if (!childId.isBlank()) {
                descendantIds.addAll(getDescendantGroupIds(childId));
            }
        }

        return descendantIds;
    }

    /**
     * Gets all related group IDs (ancestors + descendants) for a given group.
     * This represents the entire group hierarchy tree that the group belongs to.
     *
     * @param groupId the group ID
     * @return set of all related group IDs in the hierarchy
     */
    private LinkedHashSet<String> getRelatedGroupIds(String groupId) {
        LinkedHashSet<String> ancestorIds = getAncestorGroupIds(groupId);
        LinkedHashSet<String> allRelatedIds = new LinkedHashSet<>(ancestorIds);

        // Add all descendants of each ancestor
        for (String ancestorId : ancestorIds) {
            allRelatedIds.addAll(getDescendantGroupIds(ancestorId));
        }

        return allRelatedIds;
    }

    /**
     * Checks if two group hierarchies overlap by comparing their related group IDs.
     *
     * @param groupId1 first group ID
     * @param groupId2 second group ID
     * @return true if the group hierarchies overlap
     */
    private boolean doGroupHierarchiesOverlap(String groupId1, String groupId2) {
        if (groupId1 == null || groupId2 == null) {
            return false;
        }

        LinkedHashSet<String> relatedIds1 = getRelatedGroupIds(groupId1);
        LinkedHashSet<String> relatedIds2 = getRelatedGroupIds(groupId2);

        // Check if any ID exists in both sets
        return relatedIds1.stream().anyMatch(relatedIds2::contains);
    }

}

/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.opsfactory.gateway.controller.base;

import com.huawei.opsfactory.gateway.exception.BadRequestException;
import com.huawei.opsfactory.gateway.exception.ConflictException;
import com.huawei.opsfactory.gateway.exception.NotFoundException;
import com.huawei.opsfactory.gateway.service.ClusterTypeService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Base controller for CRUD operations on cluster type definitions.
 *
 * @author x00000000
 * @since 2026-06-06
 */
public abstract class BaseClusterTypeController {
    protected final ClusterTypeService clusterTypeService;

    /**
     * Creates the base cluster type controller instance.
     *
     * @param clusterTypeService service handling cluster type CRUD operations
     */
    public BaseClusterTypeController(ClusterTypeService clusterTypeService) {
        this.clusterTypeService = clusterTypeService;
    }

    /**
     * Lists all cluster type definitions.
     *
     * @param request current HTTP request
     * @return a map with "clusterTypes" list
     */
    @GetMapping
    public Map<String, Object> listClusterTypes(HttpServletRequest request) {
        List<Map<String, Object>> types = clusterTypeService.listClusterTypes();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("clusterTypes", types);
        return result;
    }

    /**
     * Gets a cluster type by ID.
     *
     * @param id cluster type identifier
     * @param request current HTTP request
     * @return ResponseEntity containing the cluster type or 404
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getClusterType(@PathVariable("id") String id,
        HttpServletRequest request) throws NotFoundException {
        Map<String, Object> ct = clusterTypeService.getClusterType(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("clusterType", ct);
        return ResponseEntity.ok(body);
    }

    /**
     * Creates a new cluster type.
     *
     * @param request request body containing cluster type fields
     * @param httpRequest current HTTP request
     * @return ResponseEntity with created cluster type or 400
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createClusterType(@RequestBody Map<String, Object> request,
        HttpServletRequest httpRequest) {
        Map<String, Object> ct = clusterTypeService.createClusterType(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("clusterType", ct);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /**
     * Updates a cluster type by ID.
     *
     * @param id cluster type identifier
     * @param request request body containing updated fields
     * @param httpRequest current HTTP request
     * @return ResponseEntity with updated cluster type or 404
     */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateClusterType(@PathVariable("id") String id,
        @RequestBody Map<String, Object> request, HttpServletRequest httpRequest)
        throws NotFoundException, BadRequestException {
        Map<String, Object> ct = clusterTypeService.updateClusterType(id, request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("clusterType", ct);
        return ResponseEntity.ok(body);
    }

    /**
     * Deletes a cluster type by ID.
     *
     * @param id cluster type identifier
     * @param request current HTTP request
     * @return ResponseEntity with success status or 404/409
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteClusterType(@PathVariable("id") String id,
        HttpServletRequest request) throws ConflictException, NotFoundException {
        boolean deleted = clusterTypeService.deleteClusterType(id);
        if (!deleted) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("error", "Cluster type not found: " + id);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        return ResponseEntity.ok(body);
    }
}

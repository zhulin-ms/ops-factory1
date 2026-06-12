/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.opsfactory.gateway.controller.machine;

import com.huawei.opsfactory.common.aop.BasicAuth;
import com.huawei.opsfactory.gateway.controller.base.BaseClusterTypeController;
import com.huawei.opsfactory.gateway.exception.BadRequestException;
import com.huawei.opsfactory.gateway.exception.ConflictException;
import com.huawei.opsfactory.gateway.exception.NotFoundException;
import com.huawei.opsfactory.gateway.service.ClusterTypeService;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.servicecomb.provider.rest.common.RestSchema;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Machine-to-machine REST controller for cluster type CRUD operations.
 * <p>
 * Requires Basic authentication for all endpoints.
 *
 * @author x00000000
 * @since 2026-05-09
 */
@RestController
@RestSchema(schemaId = "clusterTypeMachineController")
@RequestMapping("/machine/gateway/cluster-types")
public class ClusterTypeMachineController extends BaseClusterTypeController {

    /**
     * Creates the cluster type machine controller instance.
     *
     * @param clusterTypeService service handling cluster type CRUD operations
     */
    public ClusterTypeMachineController(ClusterTypeService clusterTypeService) {
        super(clusterTypeService);
    }

    /**
     * Lists all cluster type definitions.
     *
     * @param request current HTTP request
     * @return a map with "clusterTypes" list
     */
    @Override
    @GetMapping
    @BasicAuth
    public Map<String, Object> listClusterTypes(HttpServletRequest request) {
        return super.listClusterTypes(request);
    }

    /**
     * Gets a cluster type by ID.
     *
     * @param id cluster type identifier
     * @param request current HTTP request
     * @return response entity containing the cluster type or 404 if not found
     * @throws NotFoundException if cluster type not found
     */
    @Override
    @GetMapping("/{id}")
    @BasicAuth
    public ResponseEntity<Map<String, Object>> getClusterType(@PathVariable("id") String id,
        HttpServletRequest request) throws NotFoundException {
        return super.getClusterType(id, request);
    }

    /**
     * Creates a new cluster type.
     *
     * @param request request body containing cluster type fields
     * @param httpRequest current HTTP request
     * @return response entity with created cluster type or 400 if validation fails
     */
    @Override
    @PostMapping
    @BasicAuth
    public ResponseEntity<Map<String, Object>> createClusterType(@RequestBody Map<String, Object> request,
        HttpServletRequest httpRequest) {
        return super.createClusterType(request, httpRequest);
    }

    /**
     * Updates a cluster type by ID.
     *
     * @param id cluster type identifier
     * @param request request body containing updated fields
     * @param httpRequest current HTTP request
     * @return response entity with updated cluster type or 404 if not found
     * @throws NotFoundException if cluster type not found
     * @throws BadRequestException if validation fails
     */
    @Override
    @PutMapping("/{id}")
    @BasicAuth
    public ResponseEntity<Map<String, Object>> updateClusterType(@PathVariable("id") String id,
        @RequestBody Map<String, Object> request, HttpServletRequest httpRequest)
        throws NotFoundException, BadRequestException {
        return super.updateClusterType(id, request, httpRequest);
    }

    /**
     * Deletes a cluster type by ID.
     *
     * @param id cluster type identifier
     * @param request current HTTP request
     * @return response entity with success status or 404 if not found
     * @throws ConflictException if the cluster type is in use
     * @throws NotFoundException if the cluster type is not found
     */
    @Override
    @DeleteMapping("/{id}")
    @BasicAuth
    public ResponseEntity<Map<String, Object>> deleteClusterType(@PathVariable("id") String id,
        HttpServletRequest request) throws ConflictException, NotFoundException {
        return super.deleteClusterType(id, request);
    }
}

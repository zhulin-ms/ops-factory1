/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.opsfactory.gateway.controller;

import com.huawei.opsfactory.gateway.exception.ConflictException;
import com.huawei.opsfactory.gateway.exception.NotFoundException;
import com.huawei.opsfactory.gateway.service.BusinessTypeService;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.servicecomb.provider.rest.common.RestSchema;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for CRUD operations on business type definitions.
 *
 * @author x00000000
 * @since 2026-05-09
 */
@RestController
@RestSchema(schemaId = "businessTypeController")
@RequestMapping("/api/gateway/business-types")
public class BusinessTypeController {
    private final BusinessTypeService businessTypeService;

    /**
     * Creates the business type controller instance.
     *
     * @param businessTypeService service handling business type CRUD operations
     */
    public BusinessTypeController(BusinessTypeService businessTypeService) {
        this.businessTypeService = businessTypeService;
    }

    /**
     * Lists all business type definitions.
     *
     * @param request current HTTP request
     * @return a map with "businessTypes" list
     */
    @GetMapping
    public Map<String, Object> listBusinessTypes(HttpServletRequest request) {
        List<Map<String, Object>> types = businessTypeService.listBusinessTypes();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("businessTypes", types);
        return result;
    }

    /**
     * Gets a business type by ID.
     *
     * @param id business type identifier
     * @param request current HTTP request
     * @return ResponseEntity containing the business type or 404
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getBusinessType(@PathVariable("id") String id,
        HttpServletRequest request) throws NotFoundException {
        Map<String, Object> bt = businessTypeService.getBusinessType(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("businessType", bt);
        return ResponseEntity.ok(body);
    }

    /**
     * Creates a new business type.
     *
     * @param request request body containing business type fields
     * @param httpRequest current HTTP request
     * @return ResponseEntity with created business type or 400
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createBusinessType(@RequestBody Map<String, Object> request,
        HttpServletRequest httpRequest) {
        Map<String, Object> bt = businessTypeService.createBusinessType(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("businessType", bt);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /**
     * Updates a business type by ID.
     *
     * @param id business type identifier
     * @param request request body containing updated fields
     * @param httpRequest current HTTP request
     * @return ResponseEntity with updated business type or 404
     */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateBusinessType(@PathVariable("id") String id,
        @RequestBody Map<String, Object> request, HttpServletRequest httpRequest) throws NotFoundException {
        Map<String, Object> bt = businessTypeService.updateBusinessType(id, request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("businessType", bt);
        return ResponseEntity.ok(body);
    }

    /**
     * Deletes a business type by ID.
     *
     * @param id business type identifier
     * @param request current HTTP request
     * @return ResponseEntity with success status or 404/409
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteBusinessType(@PathVariable("id") String id,
        HttpServletRequest request) throws ConflictException {
        boolean deleted = businessTypeService.deleteBusinessType(id);
        if (!deleted) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("error", "Business type not found: " + id);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        return ResponseEntity.ok(body);
    }
}
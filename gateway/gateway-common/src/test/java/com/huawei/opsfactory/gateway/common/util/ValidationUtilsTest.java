/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.opsfactory.gateway.common.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * Test coverage for ValidationUtils.
 *
 * @author x00000000
 * @since 2026-06-15
 */
public class ValidationUtilsTest {
    /**
     * Tests require non blank with body present value.
     */
    @Test
    public void testRequireNonBlank_body_presentValue() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Test");
        String result = ValidationUtils.requireNonBlank(body, "name", "Name is required");
        assertEquals("Test", result);
    }

    /**
     * Tests require non blank with body whitespace trimmed.
     */
    @Test
    public void testRequireNonBlank_body_whitespaceTrimmed() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "  Test  ");
        String result = ValidationUtils.requireNonBlank(body, "name", "Name is required");
        assertEquals("Test", result);
    }

    /**
     * Tests require non blank with body null value throws exception.
     */
    @Test
    public void testRequireNonBlank_body_nullValue_throwsException() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", null);
        try {
            ValidationUtils.requireNonBlank(body, "name", "Name is required");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertEquals("Name is required", e.getMessage());
        }
    }

    /**
     * Tests require non blank with body empty string throws exception.
     */
    @Test
    public void testRequireNonBlank_body_emptyString_throwsException() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "");
        try {
            ValidationUtils.requireNonBlank(body, "name", "Name is required");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertEquals("Name is required", e.getMessage());
        }
    }

    /**
     * Tests require non blank with body whitespace only throws exception.
     */
    @Test
    public void testRequireNonBlank_body_whitespaceOnly_throwsException() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "   ");
        try {
            ValidationUtils.requireNonBlank(body, "name", "Name is required");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertEquals("Name is required", e.getMessage());
        }
    }

    /**
     * Tests require non blank with body missing field throws exception.
     */
    @Test
    public void testRequireNonBlank_body_missingField_throwsException() {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            ValidationUtils.requireNonBlank(body, "name", "Name is required");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertEquals("Name is required", e.getMessage());
        }
    }

    /**
     * Tests require non blank with string present value.
     */
    @Test
    public void testRequireNonBlank_string_presentValue() {
        String result = ValidationUtils.requireNonBlank("Test", "Name");
        assertEquals("Test", result);
    }

    /**
     * Tests require non blank with string null throws exception.
     */
    @Test
    public void testRequireNonBlank_string_null_throwsException() {
        try {
            ValidationUtils.requireNonBlank((String) null, "Name");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertEquals("Name is required", e.getMessage());
        }
    }

    /**
     * Tests require non blank with string empty throws exception.
     */
    @Test
    public void testRequireNonBlank_string_empty_throwsException() {
        try {
            ValidationUtils.requireNonBlank("", "Name");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertEquals("Name is required", e.getMessage());
        }
    }

    /**
     * Tests require max length within limit.
     */
    @Test
    public void testRequireMaxLength_withinLimit() {
        ValidationUtils.requireMaxLength("test", 10, "Field");
        // No exception
    }

    /**
     * Tests require max length at limit.
     */
    @Test
    public void testRequireMaxLength_atLimit() {
        ValidationUtils.requireMaxLength("test", 4, "Field");
        // No exception
    }

    /**
     * Tests require max length exceeds limit throws exception.
     */
    @Test
    public void testRequireMaxLength_exceedsLimit_throwsException() {
        try {
            ValidationUtils.requireMaxLength("test", 3, "Field");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("exceeds maximum length of 3"));
        }
    }

    /**
     * Tests require max length null passes.
     */
    @Test
    public void testRequireMaxLength_null_passes() {
        ValidationUtils.requireMaxLength(null, 10, "Field");
        // No exception
    }

    /**
     * Tests require no xss chars clean string.
     */
    @Test
    public void testRequireNoXssChars_cleanString() {
        ValidationUtils.requireNoXssChars("clean string", "Field");
        // No exception
    }

    /**
     * Tests require no xss chars with angle bracket throws exception.
     */
    @Test
    public void testRequireNoXssChars_angleBracket_throwsException() {
        try {
            ValidationUtils.requireNoXssChars("test<script>", "Field");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("contains invalid characters"));
        }
    }

    /**
     * Tests require no xss chars with quote throws exception.
     */
    @Test
    public void testRequireNoXssChars_quote_throwsException() {
        try {
            ValidationUtils.requireNoXssChars("test\"value", "Field");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("contains invalid characters"));
        }
    }

    /**
     * Tests require no xss chars with ampersand throws exception.
     */
    @Test
    public void testRequireNoXssChars_ampersand_throwsException() {
        try {
            ValidationUtils.requireNoXssChars("test&value", "Field");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("contains invalid characters"));
        }
    }

    /**
     * Tests require no xss chars with backtick throws exception.
     */
    @Test
    public void testRequireNoXssChars_backtick_throwsException() {
        try {
            ValidationUtils.requireNoXssChars("test`value", "Field");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("contains invalid characters"));
        }
    }

    /**
     * Tests require no xss chars with slash throws exception.
     */
    @Test
    public void testRequireNoXssChars_slash_throwsException() {
        try {
            ValidationUtils.requireNoXssChars("test/value", "Field");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("contains invalid characters"));
        }
    }

    /**
     * Tests require no xss chars null passes.
     */
    @Test
    public void testRequireNoXssChars_null_passes() {
        ValidationUtils.requireNoXssChars(null, "Field");
        // No exception
    }

    /**
     * Tests require no xss chars empty passes.
     */
    @Test
    public void testRequireNoXssChars_empty_passes() {
        ValidationUtils.requireNoXssChars("", "Field");
        // No exception
    }

    /**
     * Tests require ascii only ascii string.
     */
    @Test
    public void testRequireAsciiOnly_asciiString() {
        ValidationUtils.requireAsciiOnly("ASCII", "Field");
        // No exception
    }

    /**
     * Tests require ascii only non ascii throws exception.
     */
    @Test
    public void testRequireAsciiOnly_nonAscii_throwsException() {
        try {
            ValidationUtils.requireAsciiOnly("test中文", "Field");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("must contain only ASCII characters"));
        }
    }

    /**
     * Tests require ascii only null passes.
     */
    @Test
    public void testRequireAsciiOnly_null_passes() {
        ValidationUtils.requireAsciiOnly(null, "Field");
        // No exception
    }

    /**
     * Tests has xss chars clean string returns false.
     */
    @Test
    public void testHasXssChars_cleanString_returnsFalse() {
        assertFalse(ValidationUtils.hasXssChars("clean string"));
    }

    /**
     * Tests has xss chars with script tag returns true.
     */
    @Test
    public void testHasXssChars_withScriptTag_returnsTrue() {
        assertTrue(ValidationUtils.hasXssChars("<script>"));
    }

    /**
     * Tests has xss chars with quote returns true.
     */
    @Test
    public void testHasXssChars_withQuote_returnsTrue() {
        assertTrue(ValidationUtils.hasXssChars("test\"value"));
    }

    /**
     * Tests has xss chars null returns false.
     */
    @Test
    public void testHasXssChars_null_returnsFalse() {
        assertFalse(ValidationUtils.hasXssChars(null));
    }

    /**
     * Tests has xss chars empty returns false.
     */
    @Test
    public void testHasXssChars_empty_returnsFalse() {
        assertFalse(ValidationUtils.hasXssChars(""));
    }

    /**
     * Tests has dangerous chars clean string returns false.
     */
    @Test
    public void testHasDangerousChars_cleanString_returnsFalse() {
        assertFalse(ValidationUtils.hasDangerousChars("clean/string"));
    }

    /**
     * Tests has dangerous chars allows slash returns false.
     */
    @Test
    public void testHasDangerousChars_allowsSlash_returnsFalse() {
        assertFalse(ValidationUtils.hasDangerousChars("/usr/local/bin"));
    }

    /**
     * Tests has dangerous chars with script tag returns true.
     */
    @Test
    public void testHasDangerousChars_withScriptTag_returnsTrue() {
        assertTrue(ValidationUtils.hasDangerousChars("<script>"));
    }

    /**
     * Tests has dangerous chars null returns false.
     */
    @Test
    public void testHasDangerousChars_null_returnsFalse() {
        assertFalse(ValidationUtils.hasDangerousChars(null));
    }

    /**
     * Tests validate string field required present.
     */
    @Test
    public void testValidateStringField_required_present() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Test");
        String result = ValidationUtils.validateStringField(body, "name", "Name", 50, true);
        assertEquals("Test", result);
    }

    /**
     * Tests validate string field required missing throws exception.
     */
    @Test
    public void testValidateStringField_required_missing_throwsException() {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            ValidationUtils.validateStringField(body, "name", "Name", 50, true);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertEquals("Name is required", e.getMessage());
        }
    }

    /**
     * Tests validate string field optional missing returns empty.
     */
    @Test
    public void testValidateStringField_optional_missing_returnsEmpty() {
        Map<String, Object> body = new LinkedHashMap<>();
        String result = ValidationUtils.validateStringField(body, "name", "Name", 50, false);
        assertEquals("", result);
    }

    /**
     * Tests validate string field xss throws exception.
     */
    @Test
    public void testValidateStringField_xss_throwsException() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "<script>");
        try {
            ValidationUtils.validateStringField(body, "name", "Name", 50, true);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("contains invalid characters"));
        }
    }

    /**
     * Tests validate string field too long throws exception.
     */
    @Test
    public void testValidateStringField_tooLong_throwsException() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "a".repeat(51));
        try {
            ValidationUtils.validateStringField(body, "name", "Name", 50, true);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("exceeds maximum length of 50"));
        }
    }

    /**
     * Tests validate length only returns trimmed string.
     */
    @Test
    public void testValidateLengthOnly_returnsTrimmedString() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("description", "  test description  ");
        String result = ValidationUtils.validateLengthOnly(body, "description", "Description", 500);
        assertEquals("test description", result);
    }

    /**
     * Tests validate length only null returns empty.
     */
    @Test
    public void testValidateLengthOnly_null_returnsEmpty() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("description", null);
        String result = ValidationUtils.validateLengthOnly(body, "description", "Description", 500);
        assertEquals("", result);
    }

    /**
     * Tests validate length only missing field returns empty.
     */
    @Test
    public void testValidateLengthOnly_missingField_returnsEmpty() {
        Map<String, Object> body = new LinkedHashMap<>();
        String result = ValidationUtils.validateLengthOnly(body, "description", "Description", 500);
        assertEquals("", result);
    }

    /**
     * Tests validate length only within limit.
     */
    @Test
    public void testValidateLengthOnly_withinLimit() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("description", "a".repeat(500));
        String result = ValidationUtils.validateLengthOnly(body, "description", "Description", 500);
        assertEquals(500, result.length());
    }

    /**
     * Tests validate length only exceeds limit throws exception.
     */
    @Test
    public void testValidateLengthOnly_exceedsLimit_throwsException() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("description", "a".repeat(501));
        try {
            ValidationUtils.validateLengthOnly(body, "description", "Description", 500);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Description exceeds maximum length of 500"));
        }
    }

    /**
     * Tests validate length only allows xss characters.
     */
    @Test
    public void testValidateLengthOnly_allowsXssCharacters() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("description", "<script>alert('xss')</script>");
        String result = ValidationUtils.validateLengthOnly(body, "description", "Description", 500);
        assertEquals("<script>alert('xss')</script>", result);
    }

    /**
     * Tests validate length only allows all xss chars.
     */
    @Test
    public void testValidateLengthOnly_allowsAllXssChars() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("description", "<>\"'&`/");
        String result = ValidationUtils.validateLengthOnly(body, "description", "Description", 500);
        assertEquals("<>\"'&`/", result);
    }

    /**
     * Tests validate length only zero max length means no limit.
     */
    @Test
    public void testValidateLengthOnly_zeroMaxLength_noLimit() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("description", "a".repeat(5000));
        String result = ValidationUtils.validateLengthOnly(body, "description", "Description", 0);
        assertEquals(5000, result.length());
    }

    /**
     * Tests validate length only empty string returns empty.
     */
    @Test
    public void testValidateLengthOnly_emptyString_returnsEmpty() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("description", "");
        String result = ValidationUtils.validateLengthOnly(body, "description", "Description", 500);
        assertEquals("", result);
    }

    /**
     * Tests validate length only whitespace only returns empty.
     */
    @Test
    public void testValidateLengthOnly_whitespaceOnly_returnsEmpty() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("description", "   ");
        String result = ValidationUtils.validateLengthOnly(body, "description", "Description", 500);
        assertEquals("", result);
    }

    /**
     * Tests require unique keys empty list passes.
     */
    @Test
    public void testRequireUniqueKeys_emptyList_passes() {
        ValidationUtils.requireUniqueKeys(List.of(), "key", "Duplicate keys found");
        // No exception
    }

    /**
     * Tests require unique keys null passes.
     */
    @Test
    public void testRequireUniqueKeys_null_passes() {
        ValidationUtils.requireUniqueKeys(null, "key", "Duplicate keys found");
        // No exception
    }

    /**
     * Tests require unique keys all unique passes.
     */
    @Test
    public void testRequireUniqueKeys_allUnique_passes() {
        Map<String, Object> item1 = new LinkedHashMap<>();
        item1.put("key", "value1");
        Map<String, Object> item2 = new LinkedHashMap<>();
        item2.put("key", "value2");
        ValidationUtils.requireUniqueKeys(List.of(item1, item2), "key", "Duplicate keys found");
        // No exception
    }

    /**
     * Tests require unique keys duplicate throws exception.
     */
    @Test
    public void testRequireUniqueKeys_duplicate_throwsException() {
        Map<String, Object> item1 = new LinkedHashMap<>();
        item1.put("key", "value1");
        Map<String, Object> item2 = new LinkedHashMap<>();
        item2.put("key", "value1");
        try {
            ValidationUtils.requireUniqueKeys(List.of(item1, item2), "key", "Duplicate keys found");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertEquals("Duplicate keys found", e.getMessage());
        }
    }

    /**
     * Tests require unique keys null keys ignored.
     */
    @Test
    public void testRequireUniqueKeys_nullKeys_ignored() {
        Map<String, Object> item1 = new LinkedHashMap<>();
        item1.put("key", null);
        Map<String, Object> item2 = new LinkedHashMap<>();
        item2.put("key", null);
        ValidationUtils.requireUniqueKeys(List.of(item1, item2), "key", "Duplicate keys found");
        // No exception
    }
}

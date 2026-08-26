package com.dataease.migration.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataEaseVersionValidatorTest {

    @Test
    void findsDataEaseImageInsideServiceBlockAndIgnoresOtherServices() {
        String compose = """
                version: '2.1'
                services:
                  dataease:
                    image: registry.cn-qingdao.aliyuncs.com/dataease/dataease:v2.10.26
                    container_name: dataease
                  mysql:
                    image: mysql:5.7.44
                """;
        assertEquals("registry.cn-qingdao.aliyuncs.com/dataease/dataease:v2.10.26",
                DataEaseVersionValidator.findDataEaseImage(compose));
    }

    @Test
    void findsRootLevelDataEaseImage() {
        String compose = """
                dataease:
                  image: "dataease:v2.10.27" # 版本注释
                mysql:
                  image: mysql:8.0
                """;
        assertEquals("dataease:v2.10.27", DataEaseVersionValidator.findDataEaseImage(compose));
    }

    @Test
    void returnsNullWhenDataEaseImageIsMissing() {
        assertNull(DataEaseVersionValidator.findDataEaseImage("""
                services:
                  mysql:
                    image: mysql:8.0
                """));
    }

    @Test
    void extractsTagAfterLastColon() {
        assertEquals("v2.10.26",
                DataEaseVersionValidator.tagOf("registry.cn-qingdao.aliyuncs.com/dataease/dataease:v2.10.26"));
        assertEquals("dev-v2", DataEaseVersionValidator.tagOf("registry:5000/dataease/dataease:dev-v2"));
        assertEquals("v2.10.26",
                DataEaseVersionValidator.tagOf("registry/dataease:v2.10.26@sha256:abcdef"));
        assertNull(DataEaseVersionValidator.tagOf("dataease"));
        assertNull(DataEaseVersionValidator.tagOf("dataease:"));
    }

    @Test
    void acceptsDevTagAndVersionsAtOrAboveMinimum() {
        assertTrue(DataEaseVersionValidator.isSupportedTag("dev-v2"));
        assertTrue(DataEaseVersionValidator.isSupportedTag("v2.10.26"));
        assertTrue(DataEaseVersionValidator.isSupportedTag("v2.10.27"));
        assertTrue(DataEaseVersionValidator.isSupportedTag("v2.11.0"));
        assertTrue(DataEaseVersionValidator.isSupportedTag("v3.0.0"));
    }

    @Test
    void rejectsVersionsBelowMinimumOrNonVersionTags() {
        assertFalse(DataEaseVersionValidator.isSupportedTag("v2.10.25"));
        assertFalse(DataEaseVersionValidator.isSupportedTag("v2.9.9"));
        assertFalse(DataEaseVersionValidator.isSupportedTag("latest"));
        assertFalse(DataEaseVersionValidator.isSupportedTag(null));
    }
}

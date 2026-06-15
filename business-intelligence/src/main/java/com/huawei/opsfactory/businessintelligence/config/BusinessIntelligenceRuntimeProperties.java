/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.opsfactory.businessintelligence.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "business-intelligence")
/**
 * Business Intelligence Runtime Properties.
 *
 * @author x00000000
 * @since 2026-05-27
 */
public class BusinessIntelligenceRuntimeProperties {

    private String corsOrigin = "*";
    private Runtime runtime = new Runtime();
    private Logging logging = new Logging();

    public String getCorsOrigin() {
        return corsOrigin;
    }

    public void setCorsOrigin(String corsOrigin) {
        this.corsOrigin = corsOrigin;
    }

    public Runtime getRuntime() {
        return runtime;
    }

    public void setRuntime(Runtime runtime) {
        this.runtime = runtime;
    }

    public Logging getLogging() {
        return logging;
    }

    public void setLogging(Logging logging) {
        this.logging = logging;
    }

    public String getBaseDir() {
        return runtime.getBaseDir();
    }

    public void setBaseDir(String baseDir) {
        runtime.setBaseDir(baseDir);
    }

    public boolean isCacheEnabled() {
        return runtime.isCacheEnabled();
    }

    public void setCacheEnabled(boolean cacheEnabled) {
        runtime.setCacheEnabled(cacheEnabled);
    }

    public String getExportBiUrl() {
        return runtime.getExportBiUrl();
    }

    public void setExportBiUrl(String exportBiUrl) {
        runtime.setExportBiUrl(exportBiUrl);
    }

    /**
     * Runtime.
     *
     * @author x00000000
     * @since 2026-05-27
     */
    public static class Runtime {

        private String baseDir = "./data";
        private boolean cacheEnabled = true;
        private String exportBiUrl = "";

        public String getBaseDir() {
            return baseDir;
        }

        public void setBaseDir(String baseDir) {
            this.baseDir = baseDir;
        }

        public boolean isCacheEnabled() {
            return cacheEnabled;
        }

        public void setCacheEnabled(boolean cacheEnabled) {
            this.cacheEnabled = cacheEnabled;
        }

        public String getExportBiUrl() {
            return exportBiUrl;
        }

        public void setExportBiUrl(String exportBiUrl) {
            this.exportBiUrl = exportBiUrl;
        }
    }

/**
     * Logging.
     *
     * @author x00000000
     * @since 2026-05-27
     */
    public static class Logging {

        private boolean accessLogEnabled = true;

        public boolean isAccessLogEnabled() {
            return accessLogEnabled;
        }

        public void setAccessLogEnabled(boolean accessLogEnabled) {
            this.accessLogEnabled = accessLogEnabled;
        }
    }
}

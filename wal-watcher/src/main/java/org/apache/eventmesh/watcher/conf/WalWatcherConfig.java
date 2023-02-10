package org.apache.eventmesh.watcher.conf;

import java.io.File;

public class WalWatcherConfig {
    /** default base dir, stores all IoTDB runtime files */
    private static final String DEFAULT_BASE_DIR = "wal-watcher";
    private static final String SRC = "src";
    private static final String MAIN = "main";
    private static final String RESOURCES = "resources";
    private static final String WAL_DATA_DIR_NAME = "wal-data";
    private static final String PROPERTIES_FILE_NAME = "wal-watcher.properties";
    /** wal-watcher.properties directory */
    private String propertiesPath;
    /** Wal directory. */
    private String walDir;

    public WalWatcherConfig() {
        propertiesPath = DEFAULT_BASE_DIR + File.separator + SRC + File.separator + MAIN + File.separator + RESOURCES + File.separator + PROPERTIES_FILE_NAME;
        walDir = DEFAULT_BASE_DIR + File.separator + WAL_DATA_DIR_NAME;
    }

    public String getWalDir() {
        return walDir;
    }

    public void setWalDir(String walDir) {
        this.walDir = walDir;
    }

    public String getPropertiesDir() {
        return propertiesPath;
    }

    public void setPropertiesDir(String propertiesDir) {
        this.propertiesPath = propertiesDir;
    }

    public File getPropertiesFile() {
        return new File(propertiesPath);
    }
}

package org.apache.eventmesh.watcher.conf;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.util.Properties;

public class WalWatcherDescriptor {
    private static final String WAL_DATA_DIR = "wal_data_dir";
    private static final String CHARSET_NAME = "UTF-8";
    private final WalWatcherConfig config;

    public WalWatcherDescriptor() {
        config = new WalWatcherConfig();
        loadProperties();
    }

    public WalWatcherConfig getConfig() {
        return config;
    }

    public void loadProperties() {
        // load
        File propertiesFile = config.getPropertiesFile();
        Properties properties = new Properties();
        try (InputStream inputStream = new FileInputStream(propertiesFile);
             InputStreamReader inputStreamReader =
                     new InputStreamReader(inputStream, CHARSET_NAME)) {
            properties.load(inputStreamReader);
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        // set
        setProperties(properties);
    }

    private void setProperties(Properties properties) {
        config.setWalDir(properties.getProperty(WAL_DATA_DIR, config.getWalDir()));
    }
}

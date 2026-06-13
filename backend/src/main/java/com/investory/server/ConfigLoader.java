package com.investory.server;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

public class ConfigLoader {
    private static final Logger log = Logger.getLogger(ConfigLoader.class.getName());
    private static final Map<String, String> config = new HashMap<>();
    private static boolean loaded = false;

    public static synchronized void load(String propertiesPath) {
        if (loaded) return;
        try (InputStream is = ConfigLoader.class.getClassLoader().getResourceAsStream(propertiesPath)) {
            if (is == null) { log.warning("Properties not found: " + propertiesPath); return; }
            Properties props = new Properties();
            props.load(is);
            for (String key : props.stringPropertyNames()) {
                config.put(key, resolveEnv(props.getProperty(key)));
            }
            loaded = true;
        } catch (Exception e) {
            log.warning("Failed to load config: " + e.getMessage());
        }
    }

    private static String resolveEnv(String value) {
        if (value == null) return null;
        String result = value;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?\\}").matcher(value);
        while (m.find()) {
            String envVal = System.getenv(m.group(1));
            if (envVal == null || envVal.isBlank()) envVal = m.group(2);
            if (envVal != null) result = result.replace(m.group(0), envVal);
        }
        return result;
    }

    public static String get(String key) { return config.get(key); }
    public static String get(String key, String def) { return config.getOrDefault(key, def); }
    public static int getInt(String key, int def) {
        String v = config.get(key);
        if (v == null || v.isBlank()) return def;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return def; }
    }
}

package com.investory.server;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

public class AppContext {
    private static final Logger log = Logger.getLogger(AppContext.class.getName());
    private static final Map<Class<?>, Object> beans = new HashMap<>();

    public static synchronized <T> void register(Class<T> type, T instance) {
        beans.put(type, instance);
    }

    @SuppressWarnings("unchecked")
    public static synchronized <T> T get(Class<T> type) {
        T bean = (T) beans.get(type);
        if (bean == null) throw new IllegalStateException("No bean: " + type.getName());
        return bean;
    }

    public static boolean has(Class<?> type) { return beans.containsKey(type); }
}

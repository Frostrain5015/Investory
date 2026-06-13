package com.investory.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.lang.reflect.Type;

/**
 * JSON 序列化/反序列化工具类。
 * <p>
 * 对 Google Gson 库进行统一封装，提供全局共享的 {@link Gson} 实例。
 * 配置说明：
 * <ul>
 *   <li>日期格式统一使用 {@code yyyy-MM-dd}，适配 {@link java.util.Date} 类型字段</li>
 *   <li>开启 {@code serializeNulls()}，确保值为 null 的字段也会输出到 JSON 中，
 *       避免前端因字段缺失引发的解析异常</li>
 * </ul>
 * 所有需要 JSON 处理的地方应统一使用本工具类，避免各处创建独立 Gson 实例导致配置不一致。
 * </p>
 */
public class JsonUtil {

    /**
     * 全局共享的 Gson 实例（线程安全，可复用）。
     * 使用 {@link GsonBuilder} 构建以应用自定义配置：
     * - 日期格式：yyyy-MM-dd
     * - null 字段序列化：开启（输出 JSON 时保留 null 字段）
     */
    private static final Gson GSON = new GsonBuilder()
            .setDateFormat("yyyy-MM-dd")   // 统一日期格式，避免各地格式不一致
            .serializeNulls()              // null 字段也输出，防止前端解析时字段缺失
            .create();

    /**
     * 将 Java 对象序列化为 JSON 字符串。
     *
     * @param obj 待序列化的对象，可为任意类型（包括集合、Map 等）
     * @return 对应的 JSON 字符串；若 obj 为 null，返回字符串 {@code "null"}
     */
    public static String toJson(Object obj) {
        return GSON.toJson(obj);
    }

    /**
     * 将 JSON 字符串反序列化为指定类型的对象（适用于简单 POJO 类型）。
     *
     * @param json  待反序列化的 JSON 字符串
     * @param clazz 目标类型的 Class 对象，例如 {@code Stock.class}
     * @param <T>   目标类型泛型
     * @return 反序列化后的对象实例；若 JSON 为 null 或 "null"，返回 null
     */
    public static <T> T fromJson(String json, Class<T> clazz) {
        return GSON.fromJson(json, clazz);
    }

    /**
     * 将 JSON 字符串反序列化为指定泛型类型的对象（适用于带泛型的复杂类型）。
     * <p>
     * 常见用法示例：反序列化泛型集合
     * <pre>{@code
     *   Type listType = new TypeToken<List<Stock>>(){}.getType();
     *   List<Stock> stocks = JsonUtil.fromJson(json, listType);
     * }</pre>
     * </p>
     *
     * @param json JSON 字符串
     * @param type 目标类型的 {@link Type} 对象，通常通过 {@code TypeToken} 获取
     * @param <T>  目标类型泛型
     * @return 反序列化后的对象实例
     */
    public static <T> T fromJson(String json, Type type) {
        return GSON.fromJson(json, type);
    }
}

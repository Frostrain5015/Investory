package com.investory.model;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Validated transport boundary; strategy/config remain extensible engine documents. */
public record BacktestRequest(String name, String strategyType,
                              Map<String, Object> strategy, Map<String, Object> config) {
    public BacktestRequest {
        name = name == null || name.isBlank() ? "未命名策略" : name;
        strategyType = strategyType == null ? "simple" : strategyType;
        if (!Set.of("simple", "advanced", "walk_forward", "optimize").contains(strategyType)) {
            throw new IllegalArgumentException("不支持的回测类型");
        }
        if (strategy == null || config == null) throw new IllegalArgumentException("缺少策略或回测配置");
        strategy = Collections.unmodifiableMap(new LinkedHashMap<>(strategy));
        config = Collections.unmodifiableMap(new LinkedHashMap<>(config));
        try {
            LocalDate start = LocalDate.parse(String.valueOf(config.get("startDate")));
            LocalDate end = LocalDate.parse(String.valueOf(config.get("endDate")));
            if (start.isAfter(end)) throw new IllegalArgumentException("开始日期不能晚于结束日期");
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("回测日期必须为 yyyy-MM-dd", e);
        }
    }
}

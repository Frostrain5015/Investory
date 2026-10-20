package com.investory.crawler;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** The collector's explicit result distinguishes fresh writes, no changes and unpublished data. */
public final class CrawlRunSummary {
    private JsonObject result;

    public synchronized void accept(String line) {
        int marker = line.indexOf("RESULT: ");
        if (marker < 0) return;
        try {
            JsonObject parsed = JsonParser.parseString(line.substring(marker + 8)).getAsJsonObject();
            if (parsed.get("rows_written").getAsInt() < 0 || parsed.get("stocks_failed").getAsInt() < 0) {
                throw new IllegalArgumentException("Negative crawl counters");
            }
            result = parsed;
        } catch (RuntimeException invalid) {
            result = null;
        }
    }

    public synchronized int rowsWritten() { return result == null ? 0 : result.get("rows_written").getAsInt(); }
    public synchronized int stocksFailed() { return result == null ? 0 : result.get("stocks_failed").getAsInt(); }

    public synchronized String status(int exitCode) {
        if (exitCode != 0 || result == null) return "error";
        String outcome = result.has("outcome") ? result.get("outcome").getAsString() : "";
        if ("error".equals(outcome)) return "error";
        if ("pending".equals(outcome)) return stocksFailed() == 0 ? "pending" : "error";
        if (rowsWritten() == 0) return stocksFailed() == 0 ? "no_change" : "error";
        return "ok";
    }
}

package com.investory.crawler;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CrawlRunSummaryTest {
    @Test
    void unpublishedDataIsPendingInsteadOfSuccessfulZeroWrites() {
        var summary = new CrawlRunSummary();
        summary.accept("INFO RESULT: {\"outcome\":\"pending\",\"rows_written\":0,\"stocks_failed\":0}");
        assertEquals("pending", summary.status(0));
    }

    @Test
    void unchangedDataAndFailedDataAreDifferentOutcomes() {
        var summary = new CrawlRunSummary();
        summary.accept("RESULT: {\"rows_written\":0,\"stocks_failed\":0}");
        assertEquals("no_change", summary.status(0));
        summary.accept("RESULT: {\"rows_written\":0,\"stocks_failed\":12}");
        assertEquals("error", summary.status(0));
    }

    @Test
    void missingOrMalformedResultCannotSucceedEvenWhenProcessExitsZero() {
        var summary = new CrawlRunSummary();
        assertEquals("error", summary.status(0));
        summary.accept("RESULT: {malformed}");
        assertEquals("error", summary.status(0));
    }

    @Test
    void processFailurePreservesReportedCountersWithoutMarkingSuccess() {
        var summary = new CrawlRunSummary();
        summary.accept("RESULT: {\"rows_written\":23,\"stocks_failed\":2}");
        assertEquals("ok", summary.status(0));
        assertEquals("error", summary.status(1));
        assertEquals(23, summary.rowsWritten());
        assertEquals(2, summary.stocksFailed());
    }
}

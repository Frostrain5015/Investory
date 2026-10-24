package com.investory.crawler;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PublicAnswerFilterTest {
    @Test
    void hiddenTagsAcrossFramesNeverReachTheAnswer() {
        var filter = new PublicAnswerFilter();
        assertEquals("", filter.accept("<thi"));
        assertEquals("", filter.accept("nk>内部分析，不可发送"));
        assertEquals("", filter.accept("</thin"));
        assertEquals("正文", filter.accept("k>正文"));
    }

    @Test
    void preservesVisibleTextAndSuppressesUnclosedReasoning() {
        assertEquals("前文后文", PublicAnswerFilter.sanitize("前文<THINKING>secret</THINKING>后文"));
        assertEquals("可见", PublicAnswerFilter.sanitize("可见<think>未闭合内容"));
        assertEquals("a < b，<span>正文</span>", PublicAnswerFilter.sanitize("a < b，<span>正文</span>"));
    }

    @Test
    void frameWhitespaceCannotLeakATag() {
        var filter = new PublicAnswerFilter();
        assertEquals("", filter.accept("<th\n"));
        assertEquals("", filter.accept("ink>secret"));
        assertEquals("答案", filter.accept("</think>答案"));
    }
}

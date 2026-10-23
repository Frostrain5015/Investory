package com.investory.service;

import com.investory.dao.DailyPortfolioValueDao;
import com.investory.marketdata.HistoricalPriceService;
import com.investory.model.DailyValue;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PortfolioValueCalculatorTest {
    @Test void providerFailurePreservesAllExistingSnapshots() {
        var daily=mock(DailyPortfolioValueDao.class); var ledger=mock(PnlLedgerService.class);
        var calculator=new PortfolioValueCalculator();
        ReflectionTestUtils.setField(calculator,"dailyDao",daily);
        ReflectionTestUtils.setField(calculator,"pnlLedgerService",ledger);
        when(ledger.calculateDailyValues(eq(1L),any(),any())).thenThrow(new HistoricalPriceService.HistoryUnavailable());
        assertThrows(HistoricalPriceService.HistoryUnavailable.class,()->calculator.backfillFrom(1L,LocalDate.of(2025,1,1)));
        verifyNoInteractions(daily);
    }
    @Test void calculationFinishesBeforeReplacingSnapshots() {
        var daily=mock(DailyPortfolioValueDao.class); var ledger=mock(PnlLedgerService.class);
        var calculator=new PortfolioValueCalculator(); var from=LocalDate.of(2025,1,1); var value=new DailyValue();
        ReflectionTestUtils.setField(calculator,"dailyDao",daily);
        ReflectionTestUtils.setField(calculator,"pnlLedgerService",ledger);
        when(ledger.calculateDailyValues(eq(1L),eq(from),any())).thenReturn(List.of(value));
        calculator.backfillFrom(1L,from);
        var order=inOrder(ledger,daily);
        order.verify(ledger).calculateDailyValues(eq(1L),eq(from),any());
        order.verify(daily).deleteFrom(1L,from); order.verify(daily).upsert(value);
    }
}

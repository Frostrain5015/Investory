package com.investory.service;

import com.investory.dao.StockPriceDao;
import com.investory.model.DailyValue;
import com.investory.model.Holding;
import com.investory.model.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PnlFormulaServiceTest {

    private JdbcTemplate jdbc;
    private PnlLedgerService ledgerService;
    private PortfolioAnalysisService analysisService;

    @BeforeEach
    void setUp() {
        DataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:pnl_formula;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        createSchema();
        seedScenario();

        StockPriceDao stockPriceDao = new StockPriceDao();
        inject(stockPriceDao, "jdbc", jdbc);

        ledgerService = new PnlLedgerService();
        inject(ledgerService, "jdbc", jdbc);
        inject(ledgerService, "stockPriceDao", stockPriceDao);

        analysisService = new PortfolioAnalysisService();
        inject(analysisService, "jdbc", jdbc);
    }

    @Test
    void costBasisRemainsWithRemainingSharesAfterPartialSell() {
        Transaction buy = tx("BUY", "100", "10", "1");
        Transaction sell = tx("SELL", "50", "12", "1");

        Holding holding = new CostCalculationService().rebuild(List.of(buy, sell));

        assertEquals(new BigDecimal("50.0000"), holding.getTotalShares());
        assertEquals(new BigDecimal("500.5000"), holding.getTotalInvested());
        assertEquals(new BigDecimal("10.0100"), holding.getAvgCost());
    }

    @Test
    void dailyLedgerExcludesCapitalFlowsAndIncludesSellFeeAndDividend() {
        List<DailyValue> values = ledgerService.calculateDailyValues(
                1L, LocalDate.parse("2026-01-02"), LocalDate.parse("2026-01-05"));

        assertEquals(4, values.size());
        assertDaily(values.get(0), "2026-01-02", "1100.00", "99.00");
        assertDaily(values.get(1), "2026-01-03", "1200.00", "100.00");
        assertDaily(values.get(2), "2026-01-04", "1274.00", "74.00");
        assertDaily(values.get(3), "2026-01-05", "1294.00", "20.00");
    }

    @Test
    void detailUsesPerStockNetContributionAcrossThePeriod() {
        Map<String, Object> detail = ledgerService.buildDetail(
                1L, "2026-01-04", LocalDate.parse("2026-01-03"), LocalDate.parse("2026-01-04"));

        assertEquals(new BigDecimal("74.00"), detail.get("totalPnl"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> holdings = (List<Map<String, Object>>) detail.get("holdings");
        assertEquals(1, holdings.size());
        assertEquals("Test Corp", holdings.get(0).get("stockName"));
        assertEquals(new BigDecimal("74.00"), holdings.get(0).get("pnl"));
        assertEquals(new BigDecimal("625.00"), holdings.get(0).get("marketValue"));
    }

    @Test
    void realizedPnlReplaysSellCostBasisAndAddsDividends() {
        assertEquals(new BigDecimal("168.50"), analysisService.totalRealizedPnl(1L));
    }

    private void createSchema() {
        jdbc.execute("DROP TABLE IF EXISTS dividends");
        jdbc.execute("DROP TABLE IF EXISTS stock_prices");
        jdbc.execute("DROP TABLE IF EXISTS transactions");
        jdbc.execute("DROP TABLE IF EXISTS stocks");
        jdbc.execute("DROP TABLE IF EXISTS exchange_rates");
        jdbc.execute("""
                CREATE TABLE stocks (
                    id BIGINT PRIMARY KEY,
                    symbol VARCHAR(32),
                    name VARCHAR(128),
                    market VARCHAR(16),
                    currency VARCHAR(8)
                )
                """);
        jdbc.execute("""
                CREATE TABLE transactions (
                    id BIGINT PRIMARY KEY,
                    portfolio_id BIGINT,
                    stock_id BIGINT,
                    type VARCHAR(32),
                    shares DECIMAL(18,4),
                    price DECIMAL(18,4),
                    fee DECIMAL(18,4),
                    trade_date DATE,
                    currency VARCHAR(8)
                )
                """);
        jdbc.execute("""
                CREATE TABLE dividends (
                    id BIGINT PRIMARY KEY,
                    portfolio_id BIGINT,
                    stock_id BIGINT,
                    amount_per_share DECIMAL(18,4),
                    shares_held DECIMAL(18,4),
                    total_amount DECIMAL(18,4),
                    record_date DATE
                )
                """);
        jdbc.execute("""
                CREATE TABLE stock_prices (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    stock_id BIGINT,
                    trade_date DATE,
                    open DECIMAL(18,4),
                    close DECIMAL(18,4),
                    high DECIMAL(18,4),
                    low DECIMAL(18,4),
                    volume BIGINT
                )
                """);
        jdbc.execute("""
                CREATE TABLE exchange_rates (
                    currency VARCHAR(8) PRIMARY KEY,
                    rate DECIMAL(18,8)
                )
                """);
    }

    private void seedScenario() {
        jdbc.update("INSERT INTO exchange_rates (currency, rate) VALUES ('CNY', 1)");
        jdbc.update("INSERT INTO stocks (id, symbol, name, market, currency) VALUES (1, 'TEST.CN', 'Test Corp', 'SH', 'CNY')");
        jdbc.update("INSERT INTO transactions (id, portfolio_id, stock_id, type, shares, price, fee, trade_date, currency) VALUES (1, 1, NULL, 'TRANSFER_IN', 1001, 0, 0, '2026-01-01', 'CNY')");
        jdbc.update("INSERT INTO transactions (id, portfolio_id, stock_id, type, shares, price, fee, trade_date, currency) VALUES (2, 1, 1, 'BUY', 100, 10, 1, '2026-01-02', 'CNY')");
        jdbc.update("INSERT INTO transactions (id, portfolio_id, stock_id, type, shares, price, fee, trade_date, currency) VALUES (3, 1, 1, 'SELL', 50, 13, 1, '2026-01-04', 'CNY')");
        jdbc.update("INSERT INTO dividends (id, portfolio_id, stock_id, amount_per_share, shares_held, total_amount, record_date) VALUES (1, 1, 1, 0.4, 50, 20, '2026-01-05')");
        insertPrice("2026-01-02", "11");
        insertPrice("2026-01-03", "12");
        insertPrice("2026-01-04", "12.5");
        insertPrice("2026-01-05", "12.5");
    }

    private void insertPrice(String date, String close) {
        jdbc.update("INSERT INTO stock_prices (stock_id, trade_date, open, close, high, low, volume) VALUES (1, ?, ?, ?, ?, ?, 0)",
                LocalDate.parse(date), new BigDecimal(close), new BigDecimal(close), new BigDecimal(close), new BigDecimal(close));
    }

    private Transaction tx(String type, String shares, String price, String fee) {
        Transaction t = new Transaction();
        t.setType(type);
        t.setShares(new BigDecimal(shares));
        t.setPrice(new BigDecimal(price));
        t.setFee(new BigDecimal(fee));
        return t;
    }

    private void assertDaily(DailyValue value, String date, String totalValue, String dailyPnl) {
        assertEquals(LocalDate.parse(date), value.getSnapshotDate());
        assertEquals(new BigDecimal(totalValue), value.getTotalValue());
        assertEquals(new BigDecimal(dailyPnl), value.getDailyPnl());
    }

    private static void inject(Object target, String fieldName, Object value) {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field field = c.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
        throw new IllegalArgumentException("Field not found: " + fieldName);
    }
}

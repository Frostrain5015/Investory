package com.investory.controller.api;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminRetirementTest {
    @Test
    void legacyControlsAreGoneWhileHistoricalRecordsRemainReadable() throws Exception {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:admin_retirement;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", ""));
        jdbc.execute("CREATE TABLE crawl_history(id BIGINT PRIMARY KEY, market VARCHAR, started_at TIMESTAMP, ended_at TIMESTAMP, rows_written INT, stocks_failed INT, status VARCHAR)");
        jdbc.update("INSERT INTO crawl_history VALUES(1,'a',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,100,0,'ok')");
        var mvc = MockMvcBuilders.standaloneSetup(new AdminController(jdbc)).build();
        var session = new MockHttpSession(); session.setAttribute("isAdmin", true);
        mvc.perform(get("/api/admin/crawl/a").session(session)).andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/crawl/status").session(session)).andExpect(status().isNotFound());
        for (String action : new String[]{"stop", "pause", "resume"})
            mvc.perform(post("/api/admin/crawl/" + action).session(session)).andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/crawl-history").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].rows_written").value(100));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM crawl_history", Integer.class));
    }
}

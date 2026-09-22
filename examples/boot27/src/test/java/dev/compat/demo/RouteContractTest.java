package dev.compat.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Executes real Spring MVC mappings in a Boot context without binding a TCP port. */
@SpringBootTest
@AutoConfigureMockMvc
public class RouteContractTest {
    @Autowired
    private MockMvc mvc;

    /** Confirms the canonical route remains usable across the migration. */
    @Test
    public void canonicalRouteWorks() throws Exception {
        mvc.perform(get("/api/greeting")).andExpect(status().isOk()).andExpect(content().string("hello"));
    }

    /** Measures the default trailing-slash behavior against the selected version contract. */
    @Test
    public void recordsTrailingSlashBehavior() throws Exception {
        MvcResult result = mvc.perform(get("/api/greeting/")).andReturn();
        int observed = result.getResponse().getStatus();
        System.out.println("ROUTE_RECEIPT boot=" + SpringBootVersion.getVersion()
                + " path=/api/greeting/ observed=" + observed);
        assertEquals(Integer.parseInt(System.getProperty("expectedSlashStatus", "200")), observed);
    }

    /** Verifies that the catalog's explicit-pair compatibility fix restores both forms. */
    @Test
    public void explicitPairRestoresBothRoutes() throws Exception {
        mvc.perform(get("/api/paired")).andExpect(status().isOk()).andExpect(content().string("paired"));
        mvc.perform(get("/api/paired/")).andExpect(status().isOk()).andExpect(content().string("paired"));
        System.out.println("FIX_RECEIPT boot=" + SpringBootVersion.getVersion() + " explicit-pair=200,200 body=paired");
    }
}

package com.pressure;

import com.pressure.triage.LoadMonitor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class HttpFlowTests {

    @Autowired private MockMvc mvc;
    @Autowired private LoadMonitor monitor;

    @BeforeEach
    void resetState() {
        monitor.reset();
    }

    @Test
    void invalid_body_blank_user_id_returns_400() throws Exception {
        mvc.perform(post("/api/work").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"\",\"tier\":\"FREE\",\"costUnits\":1,\"operation\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalid_body_negative_cost_returns_400() throws Exception {
        mvc.perform(post("/api/work").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"u\",\"tier\":\"FREE\",\"costUnits\":-1,\"operation\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalid_tier_returns_400() throws Exception {
        mvc.perform(post("/api/work").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"u\",\"tier\":\"NOPE\",\"costUnits\":1,\"operation\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void admitted_request_includes_op_digest() throws Exception {
        mvc.perform(post("/api/work").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"u\",\"tier\":\"PREMIUM\",\"costUnits\":1,\"operation\":\"compute\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.degraded").value(false))
                .andExpect(jsonPath("$.opDigest").exists())
                .andExpect(jsonPath("$.operation").value("compute"));
    }
}

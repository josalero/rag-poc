package com.example.rag.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SkillsQueryControllerTest {

    private RagService ragService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ragService = mock(RagService.class);
        SkillsQueryController controller = new SkillsQueryController(ragService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setValidator(new org.springframework.validation.beanvalidation.LocalValidatorFactoryBean())
                .build();
    }

    @Test
    void query_withEmptyQuestion_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void query_withValidQuestion_returnsOkAndAnswer() throws Exception {
        when(ragService.query(anyString(), any(), any())).thenReturn(QueryResponse.of(
                "Test answer.",
                List.of(new QueryResponse.SourceSegment("Excerpt from resume.", "resume1.pdf", 0.93))));

        mockMvc.perform(post("/api/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Who has Java?\",\"maxResults\":30,\"minScore\":0.2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Test answer."))
                .andExpect(jsonPath("$.sources").isArray());
    }
}

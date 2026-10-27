package com.app.maria.domain.document.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.domain.document.dto.DocumentFileDTO;
import com.app.maria.domain.document.dto.response.DocumentIssueResponseDTO;
import com.app.maria.domain.document.service.DocumentIssueService;
import com.app.maria.domain.document.type.DocumentStatus;
import com.app.maria.domain.document.type.DocumentType;
import com.app.maria.domain.document.type.IssuedByType;
import com.app.maria.global.config.CustomerSecurityConfig;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import com.app.maria.global.security.customer.CustomerPrincipal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(CustomerDocumentApi.class)
@Import(CustomerSecurityConfig.class)
class CustomerDocumentApiTest {

    private static final CustomerPrincipal PRINCIPAL =
            new CustomerPrincipal(3L, "customer@test.com", "테스트고객");

    @Autowired private MockMvc mockMvc;

    @MockitoBean private DocumentIssueService documentIssueService;

    private static RequestPostProcessor customerAuth() {
        return authentication(
                new UsernamePasswordAuthenticationToken(
                        PRINCIPAL, null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));
    }

    private static DocumentIssueResponseDTO issued() {
        return DocumentIssueResponseDTO.builder()
                .documentIssueId(5L)
                .accountId(7L)
                .docType(DocumentType.TAX_RELIEF_CERT)
                .issueNo("RIA-2027-ABCD2345")
                .status(DocumentStatus.ISSUED)
                .issuedByType(IssuedByType.CUSTOMER)
                .issuedAt(LocalDateTime.of(2027, 4, 1, 10, 0))
                .build();
    }

    @Test
    void unauthenticated_returnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/customer/documents")).andExpect(status().isUnauthorized());
    }

    @Test
    void issueWithoutCsrf_returnsForbidden() throws Exception {
        mockMvc.perform(post("/api/customer/documents").with(customerAuth()))
                .andExpect(status().isForbidden());

        verify(documentIssueService, never()).issueForCustomer(any());
    }

    @Test
    void issue_usesLoggedInCustomerId() throws Exception {
        when(documentIssueService.issueForCustomer(3L)).thenReturn(issued());

        mockMvc.perform(post("/api/customer/documents").with(customerAuth()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.issueNo").value("RIA-2027-ABCD2345"))
                .andExpect(jsonPath("$.data.status").value("ISSUED"));
    }

    @Test
    void issue_whenNotOpen_returnsConflictWithCode() throws Exception {
        when(documentIssueService.issueForCustomer(3L))
                .thenThrow(new AppException(ErrorType.DOCUMENT_ISSUE_NOT_OPEN));

        mockMvc.perform(post("/api/customer/documents").with(customerAuth()).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOCUMENT_ISSUE_NOT_OPEN"));
    }

    @Test
    void list_returnsOwnDocuments() throws Exception {
        when(documentIssueService.getByCustomer(3L)).thenReturn(List.of(issued()));

        mockMvc.perform(get("/api/customer/documents").with(customerAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].documentIssueId").value(5));
    }

    @Test
    void download_sendsPdfAsAttachment() throws Exception {
        byte[] pdf = "%PDF-test".getBytes(StandardCharsets.UTF_8);
        when(documentIssueService.downloadForCustomer(3L, 5L))
                .thenReturn(new DocumentFileDTO("RIA증빙서_RIA-2027-ABCD2345.pdf", pdf));

        mockMvc.perform(get("/api/customer/documents/5/download").with(customerAuth()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(
                        header().string(
                                        "Content-Disposition",
                                        org.hamcrest.Matchers.allOf(
                                                org.hamcrest.Matchers.startsWith("attachment"),
                                                org.hamcrest.Matchers.containsString(
                                                        "filename*=UTF-8''"))))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().longValue("Content-Length", pdf.length))
                .andExpect(content().bytes(pdf));
    }

    @Test
    void download_ofOthersDocument_returnsNotFound() throws Exception {
        when(documentIssueService.downloadForCustomer(3L, 99L))
                .thenThrow(new AppException(ErrorType.DOCUMENT_NOT_FOUND));

        mockMvc.perform(get("/api/customer/documents/99/download").with(customerAuth()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
    }
}

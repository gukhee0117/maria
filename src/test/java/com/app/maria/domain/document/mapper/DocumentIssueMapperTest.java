package com.app.maria.domain.document.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.app.maria.domain.document.dto.DocumentIssueDTO;
import com.app.maria.domain.document.type.DocumentStatus;
import com.app.maria.domain.document.type.DocumentType;
import com.app.maria.domain.document.type.IssuedByType;
import java.io.IOException;
import java.io.Reader;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import javax.sql.DataSource;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DocumentIssueMapperTest {

    private static final Long ACCOUNT_ID = 1L;
    private static final Long OTHER_ACCOUNT_ID = 2L;
    private static final LocalDateTime NOW = LocalDateTime.of(2027, 4, 1, 10, 0);
    private static final byte[] PDF = {'%', 'P', 'D', 'F', 0, 1, 2, (byte) 255};

    private static SqlSessionFactory sqlSessionFactory;
    private static DataSource dataSource;

    private SqlSession sqlSession;
    private DocumentIssueMapper mapper;

    @BeforeAll
    static void configureMyBatis() throws IOException {
        try (Reader reader = Resources.getResourceAsReader("mybatis-document-test-config.xml")) {
            sqlSessionFactory = new SqlSessionFactoryBuilder().build(reader);
        }
        dataSource = sqlSessionFactory.getConfiguration().getEnvironment().getDataSource();
    }

    @BeforeEach
    void setUp() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS document_issue");
            statement.execute("DROP TABLE IF EXISTS account");
            statement.execute("DROP TABLE IF EXISTS customer");
            statement.execute(
                    "CREATE TABLE customer (customer_id BIGINT PRIMARY KEY, name VARCHAR(50) NOT NULL)");
            statement.execute(
                    "CREATE TABLE account (account_id BIGINT PRIMARY KEY, customer_id BIGINT NOT NULL)");
            statement.execute(
                    """
                    CREATE TABLE document_issue (
                        document_issue_id BIGINT PRIMARY KEY AUTO_INCREMENT,
                        account_id BIGINT NOT NULL,
                        calc_id BIGINT NOT NULL,
                        doc_type VARCHAR(30) NOT NULL,
                        issue_no VARCHAR(30) NOT NULL UNIQUE,
                        status VARCHAR(10) NOT NULL,
                        file_content MEDIUMBLOB NOT NULL,
                        file_hash CHAR(64) NOT NULL,
                        issued_by_type VARCHAR(10) NOT NULL,
                        issued_by_id BIGINT NOT NULL,
                        issued_at DATETIME NOT NULL,
                        revoked_at DATETIME NULL,
                        recorded_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("INSERT INTO customer VALUES (10, '홍길동')");
            statement.execute("INSERT INTO account VALUES (1, 10), (2, 10)");
        }
        sqlSession = sqlSessionFactory.openSession(true);
        mapper = sqlSession.getMapper(DocumentIssueMapper.class);
    }

    @AfterEach
    void tearDown() {
        sqlSession.close();
    }

    private DocumentIssueDTO issue(Long accountId, String issueNo, LocalDateTime issuedAt) {
        DocumentIssueDTO dto =
                DocumentIssueDTO.builder()
                        .accountId(accountId)
                        .calcId(100L)
                        .docType(DocumentType.TAX_RELIEF_CERT)
                        .issueNo(issueNo)
                        .status(DocumentStatus.ISSUED)
                        .fileContent(PDF)
                        .fileHash("a".repeat(64))
                        .issuedByType(IssuedByType.CUSTOMER)
                        .issuedById(10L)
                        .issuedAt(issuedAt)
                        .build();
        mapper.insert(dto);
        return dto;
    }

    @Test
    @DisplayName("저장하면 생성된 ID가 채워지고, 파일은 바이트 그대로 읽힌다")
    void insert_and_selectWithFile() {
        DocumentIssueDTO saved = issue(ACCOUNT_ID, "RIA-2027-AAAAAAAA", NOW);

        assertThat(saved.getDocumentIssueId()).isNotNull();
        DocumentIssueDTO read = mapper.selectWithFileById(saved.getDocumentIssueId()).orElseThrow();
        assertThat(read.getFileContent()).isEqualTo(PDF);
        assertThat(read.getStatus()).isEqualTo(DocumentStatus.ISSUED);
        assertThat(read.getDocType()).isEqualTo(DocumentType.TAX_RELIEF_CERT);
        assertThat(read.getIssuedByType()).isEqualTo(IssuedByType.CUSTOMER);
        assertThat(read.getIssuedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("목록과 발급본 조회는 파일을 읽지 않는다")
    void listQueries_doNotReadFile() {
        issue(ACCOUNT_ID, "RIA-2027-AAAAAAAA", NOW);

        assertThat(mapper.selectByAccountId(ACCOUNT_ID)).allMatch(d -> d.getFileContent() == null);
        assertThat(mapper.selectIssuedByAccountId(ACCOUNT_ID).orElseThrow().getFileContent())
                .isNull();
    }

    @Test
    @DisplayName("목록은 최신 발급이 먼저, 다른 계좌는 섞이지 않는다")
    void selectByAccountId_orderAndScope() {
        issue(ACCOUNT_ID, "RIA-2027-AAAAAAAA", NOW);
        issue(ACCOUNT_ID, "RIA-2027-BBBBBBBB", NOW.plusDays(1));
        issue(OTHER_ACCOUNT_ID, "RIA-2027-CCCCCCCC", NOW.plusDays(2));

        List<DocumentIssueDTO> list = mapper.selectByAccountId(ACCOUNT_ID);

        assertThat(list)
                .extracting(DocumentIssueDTO::getIssueNo)
                .containsExactly("RIA-2027-BBBBBBBB", "RIA-2027-AAAAAAAA");
    }

    @Test
    @DisplayName("취소: 그 계좌의 ISSUED만 REVOKED로 바꾸고 취소 시각을 남긴다")
    void revokeIssuedByAccountId_onlyThatAccount() {
        issue(ACCOUNT_ID, "RIA-2027-AAAAAAAA", NOW);
        issue(OTHER_ACCOUNT_ID, "RIA-2027-CCCCCCCC", NOW);
        LocalDateTime revokedAt = NOW.plusDays(10);

        int updated = mapper.revokeIssuedByAccountId(ACCOUNT_ID, revokedAt);

        assertThat(updated).isEqualTo(1);
        DocumentIssueDTO revoked = mapper.selectByAccountId(ACCOUNT_ID).get(0);
        assertThat(revoked.getStatus()).isEqualTo(DocumentStatus.REVOKED);
        assertThat(revoked.getRevokedAt()).isEqualTo(revokedAt);
        assertThat(mapper.selectIssuedByAccountId(ACCOUNT_ID)).isEmpty();
        assertThat(mapper.selectIssuedByAccountId(OTHER_ACCOUNT_ID)).isPresent();
        assertThat(mapper.revokeIssuedByAccountId(ACCOUNT_ID, revokedAt)).isZero();
    }

    @Test
    @DisplayName("발급번호는 UNIQUE라 같은 번호로 두 번 저장되지 않는다")
    void issueNo_unique() {
        issue(ACCOUNT_ID, "RIA-2027-AAAAAAAA", NOW);

        assertThatThrownBy(() -> issue(OTHER_ACCOUNT_ID, "RIA-2027-AAAAAAAA", NOW))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    @DisplayName("계좌 주인의 이름을 조회한다")
    void selectHolderName() {
        assertThat(mapper.selectHolderName(ACCOUNT_ID)).contains("홍길동");
        assertThat(mapper.selectHolderName(999L)).isEmpty();
    }
}

package com.app.maria.domain.document.mapper;

import com.app.maria.domain.document.dto.DocumentIssueDTO;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DocumentIssueMapper {
    void insert(DocumentIssueDTO documentIssueDTO);

    /** 파일(file_content)은 읽지 않는다. */
    List<DocumentIssueDTO> selectByAccountId(@Param("accountId") Long accountId);

    /** 파일은 읽지 않는다. */
    Optional<DocumentIssueDTO> selectIssuedByAccountId(@Param("accountId") Long accountId);

    /** 파일을 포함해 한 행을 읽는다. 다운로드에서만 쓴다. */
    Optional<DocumentIssueDTO> selectWithFileById(@Param("documentIssueId") Long documentIssueId);

    int revokeIssuedByAccountId(
            @Param("accountId") Long accountId, @Param("revokedAt") LocalDateTime revokedAt);

    Optional<String> selectHolderName(@Param("accountId") Long accountId);
}

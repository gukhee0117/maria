package com.app.maria.domain.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.app.maria.domain.member.dto.MemberDTO;
import com.app.maria.domain.member.dto.response.MemberResponseDTO;
import com.app.maria.domain.member.mapper.MemberMapper;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MemberServiceImplTest {

    @Mock private MemberMapper memberMapper;

    @InjectMocks private MemberServiceImpl memberService;

    @Test
    void getMemberByIdReturnsMemberWhenExists() {
        when(memberMapper.selectById("m1")).thenReturn(Optional.of(new MemberDTO("m1", "홍길동")));

        MemberResponseDTO result = memberService.getMemberById("m1");

        assertThat(result.getMid()).isEqualTo("m1");
        assertThat(result.getMname()).isEqualTo("홍길동");
    }

    @Test
    void getMemberByIdThrowsMemberNotFoundWhenMissing() {
        when(memberMapper.selectById("none")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> memberService.getMemberById("none"))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorType())
                .isEqualTo(ErrorType.MEMBER_NOT_FOUND);
    }
}

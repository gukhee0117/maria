package com.app.maria.global.client.domestictrade;

import com.app.maria.domain.domestic.dto.DomesticTradeHistoryDTO;
import com.app.maria.domain.domestic.dto.request.DomesticTradeRequestDTO;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import com.app.maria.global.response.ApiResponseDTO;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** 증권사의 국내 매매 체결 내역 조회. 증권사가 체결 시각 최신순으로 전체를 준다. 조회 실패나 비정상 응답(본문·data 없음)은 빈 목록이 아니라 예외다. */
@Component
public class DomesticTradeClient {

    private final RestClient restClient;

    public DomesticTradeClient(@Qualifier("returnSecuritiesRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public List<DomesticTradeHistoryDTO> findByCiHash(String ciHash) {
        try {
            ApiResponseDTO<List<DomesticTradeHistoryDTO>> apiResponse =
                    restClient
                            .post()
                            .uri("/api/domestic-trades")
                            .body(new DomesticTradeRequestDTO(ciHash))
                            .retrieve()
                            .body(
                                    new ParameterizedTypeReference<
                                            ApiResponseDTO<List<DomesticTradeHistoryDTO>>>() {});
            if (apiResponse == null || apiResponse.getData() == null) {
                throw new AppException(ErrorType.DOMESTIC_TRADE_API_INVALID_RESPONSE);
            }
            return apiResponse.getData();
        } catch (RestClientException e) {
            // ciHash는 시스템 간 연결 값이라 오류 로그에 남기지 않는다.
            throw new AppException(ErrorType.DOMESTIC_TRADE_API_UNAVAILABLE, "securities", e);
        }
    }
}

package com.app.maria.global.client.generalaccount;

import com.app.maria.global.client.generalaccount.dto.request.GeneralAccountRequestDTO;
import com.app.maria.global.client.generalaccount.dto.response.GeneralAccountResponseDTO;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import com.app.maria.global.exception.GeneralAccountApiException;
import com.app.maria.global.response.ApiResponseDTO;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class GeneralAccountClient {

    private final RestClient restClient;

    public GeneralAccountClient(@Qualifier("returnSecuritiesRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public GeneralAccountResponseDTO verifyGeneralAccount(GeneralAccountRequestDTO requestDTO) {
        try {
            ApiResponseDTO<GeneralAccountResponseDTO> apiResponse =
                    restClient
                            .post()
                            .uri("/api/general-accounts/verify")
                            .body(requestDTO)
                            .retrieve()
                            .body(
                                    new ParameterizedTypeReference<
                                            ApiResponseDTO<GeneralAccountResponseDTO>>() {});
            if (apiResponse == null || apiResponse.getData() == null) {
                throw new GeneralAccountApiException("인출 목적지 일반계좌를 확인할 수 없습니다.");
            }
            return apiResponse.getData();
        } catch (HttpClientErrorException e) {
            throw new AppException(
                    ErrorType.WITHDRAWAL_NOT_ALLOWED, requestDTO.getGeneralAccountId());

        } catch (RestClientException e) {
            throw new GeneralAccountApiException("증권사 일반계좌 검증 API 호출에 실패했습니다.", e);
        }
    }
}

package com.app.maria.global.client.mydatafund;

import com.app.maria.domain.targetproduct.dto.response.MydataFundResponseDTO;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import com.app.maria.global.response.ApiResponseDTO;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class MydataFundClient {

    private final RestClient restClient;

    public MydataFundClient(@Qualifier("mydataRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public MydataFundResponseDTO getFund(String fundCode) {
        ApiResponseDTO<MydataFundResponseDTO> apiResponse =
                restClient
                        .get()
                        .uri("/api/mydata/funds/{fundCode}", fundCode)
                        .retrieve()
                        .body(
                                new ParameterizedTypeReference<
                                        ApiResponseDTO<MydataFundResponseDTO>>() {});

        if (apiResponse == null || apiResponse.getData() == null) {
            throw new AppException(ErrorType.TARGET_PRODUCT_FUND_NOT_FOUND, fundCode);
        }

        return apiResponse.getData();
    }
}

package com.app.maria.global.client.registrablestock;

import com.app.maria.domain.registrablestock.dto.RegistrableStockResponseDTO;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import com.app.maria.global.response.ApiResponseDTO;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class RegistrableStockClient {

    private final RestClient restClient;

    public RegistrableStockClient(@Qualifier("returnSecuritiesRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    /** 고객의 활성 일반계좌 전체에 걸친 적격 원본 lot 목록을 반환한다. 빈 목록은 정상 응답이다. */
    public List<RegistrableStockResponseDTO> findCustomerLots(String ciHash) {
        try {
            ApiResponseDTO<List<RegistrableStockResponseDTO>> apiResponse =
                    restClient
                            .get()
                            .uri("/api/registrable-stocks/customer-lots?ciHash={ciHash}", ciHash)
                            .retrieve()
                            .body(
                                    new ParameterizedTypeReference<
                                            ApiResponseDTO<
                                                    List<RegistrableStockResponseDTO>>>() {});
            if (apiResponse == null || apiResponse.getData() == null) {
                throw new AppException(ErrorType.REGISTRABLE_STOCK_NOT_FOUND, ciHash);
            }
            return apiResponse.getData();
        } catch (RestClientException e) {
            throw new AppException(ErrorType.REGISTRABLE_STOCK_NOT_FOUND, ciHash, e);
        }
    }
}

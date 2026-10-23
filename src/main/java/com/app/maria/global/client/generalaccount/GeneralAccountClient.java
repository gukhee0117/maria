package com.app.maria.global.client.generalaccount;

import com.app.maria.global.client.generalaccount.dto.request.GeneralAccountRequestDTO;
import com.app.maria.global.client.generalaccount.dto.response.GeneralAccountResponseDTO;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import com.app.maria.global.response.ApiResponseDTO;
import java.util.List;
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
                throw new AppException(
                        ErrorType.GENERAL_ACCOUNT_API_INVALID_RESPONSE,
                        requestDTO.getGeneralAccountId());
            }
            return apiResponse.getData();
        } catch (HttpClientErrorException e) {
            int statusCode = e.getStatusCode().value();
            if (statusCode == 400 || statusCode == 404) {
                throw new AppException(
                        ErrorType.GENERAL_ACCOUNT_NOT_AVAILABLE,
                        requestDTO.getGeneralAccountId(),
                        e);
            }
            throw new AppException(
                    ErrorType.GENERAL_ACCOUNT_API_UNAVAILABLE, requestDTO.getGeneralAccountId(), e);

        } catch (RestClientException e) {
            throw new AppException(
                    ErrorType.GENERAL_ACCOUNT_API_UNAVAILABLE, requestDTO.getGeneralAccountId(), e);
        }
    }

    public List<GeneralAccountResponseDTO> findByCiHash(String ciHash) {
        try {
            ApiResponseDTO<List<GeneralAccountResponseDTO>> apiResponse =
                    restClient
                            .get()
                            .uri("/api/general-accounts?ciHash={ciHash}", ciHash)
                            .retrieve()
                            .body(
                                    new ParameterizedTypeReference<
                                            ApiResponseDTO<
                                                    List<GeneralAccountResponseDTO>>>() {});
            if (apiResponse == null || apiResponse.getData() == null) {
                throw new AppException(ErrorType.GENERAL_ACCOUNT_API_INVALID_RESPONSE, ciHash);
            }
            return apiResponse.getData();
        } catch (HttpClientErrorException e) {
            throw new AppException(ErrorType.GENERAL_ACCOUNT_API_UNAVAILABLE, ciHash, e);
        } catch (RestClientException e) {
            throw new AppException(ErrorType.GENERAL_ACCOUNT_API_UNAVAILABLE, ciHash, e);
        }
    }
}

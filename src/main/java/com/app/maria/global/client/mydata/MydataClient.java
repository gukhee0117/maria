package com.app.maria.global.client.mydata;

import com.app.maria.global.config.properties.MydataApiProperties;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import com.app.maria.global.response.ApiResponseDTO;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class MydataClient {

    private static final String RIA_ACCOUNTS_PATH = "/api/mydata/ria-accounts";
    private static final String SYNC_RIA_ACCOUNT_PATH = RIA_ACCOUNTS_PATH + "/save";

    private final RestClient restClient;
    private final MydataApiProperties mydataApiProperties;

    public MydataClient(
            @Qualifier("mydataRestClient") RestClient restClient,
            MydataApiProperties mydataApiProperties) {
        this.restClient = restClient;
        this.mydataApiProperties = mydataApiProperties;
    }

    public BigDecimal getExternalSellTotal(String ciHash) {
        return sumOtherBrokers(ciHash, MydataRiaAccountDTO::getRiaCumulativeSell);
    }

    public BigDecimal getExternalConfiguredLimit(String ciHash) {
        return sumOtherBrokers(ciHash, MydataRiaAccountDTO::getRiaLimit);
    }

    public HttpStatusCode syncRiaAccount(String ciHash, BigDecimal riaLimit) {
        Map<String, String> body =
                Map.of(
                        "ciHash", ciHash,
                        "brokerName", mydataApiProperties.getOwnBrokerName(),
                        "riaLimit", String.valueOf(riaLimit));
        try {
            return restClient
                    .post()
                    .uri(SYNC_RIA_ACCOUNT_PATH)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity()
                    .getStatusCode();
        } catch (RestClientException e) {
            throw new AppException(ErrorType.MYDATA_API_UNAVAILABLE, ciHash, e);
        }
    }

    private BigDecimal sumOtherBrokers(
            String ciHash, Function<MydataRiaAccountDTO, BigDecimal> amount) {
        String ownBrokerName = mydataApiProperties.getOwnBrokerName();
        return fetchRiaAccounts(ciHash).stream()
                .filter(account -> !ownBrokerName.equals(account.getBrokerName()))
                .map(account -> requireAmount(amount.apply(account), ciHash))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private List<MydataRiaAccountDTO> fetchRiaAccounts(String ciHash) {
        ApiResponseDTO<List<MydataRiaAccountDTO>> response;
        try {
            response =
                    restClient
                            .post()
                            .uri(RIA_ACCOUNTS_PATH)
                            .body(Map.of("ciHash", ciHash))
                            .retrieve()
                            .body(
                                    new ParameterizedTypeReference<
                                            ApiResponseDTO<List<MydataRiaAccountDTO>>>() {});
        } catch (RestClientException e) {
            throw new AppException(ErrorType.MYDATA_API_UNAVAILABLE, ciHash, e);
        }
        if (response == null || response.getData() == null) {
            throw new AppException(ErrorType.MYDATA_API_INVALID_RESPONSE, ciHash);
        }
        return response.getData();
    }

    private BigDecimal requireAmount(BigDecimal amount, String ciHash) {
        if (amount == null || amount.signum() < 0) {
            throw new AppException(ErrorType.MYDATA_API_INVALID_RESPONSE, ciHash);
        }
        return amount;
    }
}

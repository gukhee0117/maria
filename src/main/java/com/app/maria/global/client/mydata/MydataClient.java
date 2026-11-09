package com.app.maria.global.client.mydata;

import com.app.maria.global.config.properties.MydataApiProperties;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import com.app.maria.global.response.ApiResponseDTO;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class MydataClient {

    private static final String RIA_ACCOUNTS_PATH = "/api/mydata/ria-accounts";

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
        } catch (HttpClientErrorException.NotFound e) {
            // myData가 모르는 고객은 타사 RIA 계좌가 없는 것이다. 본문으로 이 경우만 가려
            // 주소 오설정 같은 다른 404는 계속 연결 오류로 둔다(한도를 잘못 넓게 주지 않는다).
            // ponytail: 본문 문자열 일치. myData가 errorCode 필드를 주면 그걸로 바꾼다.
            if (e.getResponseBodyAsString().contains("CiHash")) {
                return List.of();
            }
            throw new AppException(ErrorType.MYDATA_API_UNAVAILABLE, ciHash, e);
        } catch (RestClientException e) {
            throw new AppException(ErrorType.MYDATA_API_UNAVAILABLE, ciHash, e);
        }
        if (response == null || response.getData() == null || response.getData().contains(null)) {
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

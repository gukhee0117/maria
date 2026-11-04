package com.app.maria.domain.stockpackage.service;

import com.app.maria.domain.stockpackage.dto.request.StockConditionParseRequestDTO;
import com.app.maria.domain.stockpackage.dto.response.StockConditionParseResponseDTO;

public interface StockConditionService {
    /**
     * 고객의 말을 후보 보기 조건으로 바꾼다. AI는 말에서 조건 값을 뽑는 데만 쓰고, 고객에게 보이는 문장은 검증을 통과한 값으로 문장은행에서 만든다. 투자 판단이나
     * 추천을 요구하는 말은 조건으로 바꾸지 않고 고정 안내를 준다. AI를 쓸 수 없어도 오류 없이 고정 안내로 응답한다.
     */
    StockConditionParseResponseDTO parse(StockConditionParseRequestDTO request);
}

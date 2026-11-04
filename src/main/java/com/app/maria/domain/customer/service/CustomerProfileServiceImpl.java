package com.app.maria.domain.customer.service;

import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.customer.dto.request.ContactRule;
import com.app.maria.domain.customer.dto.request.CustomerPasswordChangeRequestDTO;
import com.app.maria.domain.customer.dto.request.CustomerProfileUpdateRequestDTO;
import com.app.maria.domain.customer.dto.response.CustomerProfileResponseDTO;
import com.app.maria.domain.customer.entity.CustomerAuthEntity;
import com.app.maria.domain.customer.entity.CustomerAuthEntity.AuthType;
import com.app.maria.domain.customer.entity.CustomerEntity;
import com.app.maria.domain.customer.repository.CustomerAuthRepository;
import com.app.maria.domain.customer.repository.CustomerRepository;
import com.app.maria.domain.investorassessment.repository.InvestorAssessmentRepository;
import com.app.maria.domain.investorassessment.service.InvestorAssessmentService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 내 정보 조회·수정과 비밀번호 변경. */
@Service
@RequiredArgsConstructor
public class CustomerProfileServiceImpl implements CustomerProfileService {
    private final CustomerRepository customerRepository;
    private final CustomerAuthRepository customerAuthRepository;
    private final PasswordEncoder passwordEncoder;
    private final InvestorAssessmentService investorAssessmentService;
    private final InvestorAssessmentRepository assessmentRepository;
    private final AccountMapper accountMapper;

    @Override
    @Transactional(transactionManager = "jpaTransactionManager", readOnly = true)
    public CustomerProfileResponseDTO get(Long customerId) {
        return toResponse(find(customerId));
    }

    @Override
    @Transactional(transactionManager = "jpaTransactionManager")
    public CustomerProfileResponseDTO update(
            Long customerId, CustomerProfileUpdateRequestDTO request) {
        // 고객 행은 보내지 않은 컬럼까지 함께 UPDATE되므로, 잠그지 않고 읽으면 동시에 들어온 다른 변경(다른 연락처, 투자성향 진단)을 예전 값으로 되돌린다.
        CustomerEntity customer =
                customerRepository
                        .findByIdForUpdate(customerId)
                        .orElseThrow(
                                () -> new AppException(ErrorType.CUSTOMER_NOT_FOUND, customerId));
        String phone =
                request.getPhone() == null ? null : ContactRule.normalizePhone(request.getPhone());
        customer.changeContact(phone, request.getEmail());
        return toResponse(customer);
    }

    /**
     * 현재 비밀번호를 확인한 뒤 바꾼다. 세션은 그대로 유지한다. 같은 고객의 변경이 동시에 들어오면 둘 다 옛 비밀번호로 확인을 통과해 마지막 것이 이기므로, 고객 행을
     * 잠가 한 번에 하나씩 처리한다.
     */
    @Override
    @Transactional(transactionManager = "jpaTransactionManager")
    public void changePassword(Long customerId, CustomerPasswordChangeRequestDTO request) {
        customerRepository
                .findByIdForUpdate(customerId)
                .orElseThrow(() -> new AppException(ErrorType.CUSTOMER_NOT_FOUND, customerId));
        CustomerAuthEntity auth =
                customerAuthRepository
                        .findByCustomerCustomerIdAndType(customerId, AuthType.LOCAL)
                        .orElseThrow(() -> new AppException(ErrorType.CUSTOMER_PASSWORD_NOT_SET));
        String currentHash = auth.getPassword().getPasswordHash();
        if (!passwordEncoder.matches(request.getCurrentPassword(), currentHash)) {
            throw new AppException(ErrorType.CUSTOMER_PASSWORD_MISMATCH);
        }
        if (request.getCurrentPassword().equals(request.getNewPassword())) {
            throw new AppException(ErrorType.CUSTOMER_PASSWORD_UNCHANGED);
        }
        auth.getPassword().changeHash(passwordEncoder.encode(request.getNewPassword()));
    }

    private CustomerProfileResponseDTO toResponse(CustomerEntity customer) {
        Long customerId = customer.getCustomerId();
        return CustomerProfileResponseDTO.of(
                customer,
                investorAssessmentService.getMine(customerId),
                assessmentRepository.findTop3ByCustomerIdOrderByInvestorAssessmentIdDesc(
                        customerId),
                accountMapper.selectByCustomerId(customerId).orElse(null));
    }

    private CustomerEntity find(Long customerId) {
        return customerRepository
                .findById(customerId)
                .orElseThrow(() -> new AppException(ErrorType.CUSTOMER_NOT_FOUND, customerId));
    }
}

package com.app.maria.domain.customer.service;

import com.app.maria.domain.customer.dto.request.CustomerPasswordChangeRequestDTO;
import com.app.maria.domain.customer.dto.request.CustomerProfileUpdateRequestDTO;
import com.app.maria.domain.customer.dto.response.CustomerProfileResponseDTO;

public interface CustomerProfileService {
    CustomerProfileResponseDTO get(Long customerId);

    CustomerProfileResponseDTO update(Long customerId, CustomerProfileUpdateRequestDTO request);

    void changePassword(Long customerId, CustomerPasswordChangeRequestDTO request);
}

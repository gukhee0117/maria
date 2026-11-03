package com.app.maria.domain.customer.dto.response;

import com.app.maria.domain.customer.entity.CustomerEntity;
import java.time.LocalDate;

/** 내 정보 화면용. 연락처는 원문이고 마스킹은 화면이 한다. 닉네임은 조회만 하며 없으면 null이다. */
public record CustomerProfileResponseDTO(
        String name, LocalDate birthDate, String phone, String email, String nickname) {
    public static CustomerProfileResponseDTO of(CustomerEntity customer) {
        return new CustomerProfileResponseDTO(
                customer.getName(),
                customer.getBirthDate(),
                customer.getPhone(),
                customer.getEmail(),
                customer.getNickname());
    }
}

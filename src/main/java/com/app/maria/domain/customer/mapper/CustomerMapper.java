package com.app.maria.domain.customer.mapper;

import com.app.maria.domain.customer.dto.CustomerCiHashDTO;
import com.app.maria.domain.customer.dto.CustomerSearchDTO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CustomerMapper {

    List<CustomerCiHashDTO> selectRiaMonitoringCustomers();

    List<CustomerSearchDTO> searchEligibleCustomersByName(
            @Param("name") String name, @Param("limit") int limit);
}

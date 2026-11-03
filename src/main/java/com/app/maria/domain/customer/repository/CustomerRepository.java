package com.app.maria.domain.customer.repository;

import com.app.maria.domain.customer.entity.CustomerEntity;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** 가입과 투자성향 진단용 조회·저장. */
public interface CustomerRepository extends Repository<CustomerEntity, Long> {
    /** 같은 고객에게 동시에 가입하면 둘 다 "계정 없음"을 보고 계정이 두 개 생길 수 있어서, 조회와 동시에 행을 잠가 뒤 요청이 앞 요청의 커밋을 기다리게 한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CustomerEntity c where c.ciHash = :ciHash")
    Optional<CustomerEntity> findByCiHashForUpdate(@Param("ciHash") String ciHash);

    /** 재진단이 동시에 들어와도 마지막 진단이 현재 성향이 되도록, 조회와 동시에 행을 잠가 뒤 요청이 앞 요청의 커밋을 기다리게 한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CustomerEntity c where c.customerId = :customerId")
    Optional<CustomerEntity> findByIdForUpdate(@Param("customerId") Long customerId);

    Optional<CustomerEntity> findById(Long customerId);

    boolean existsByNickname(String nickname);

    CustomerEntity saveAndFlush(CustomerEntity customer);

    /** 글·댓글 목록에 작성자 닉네임과 성향 공개 여부를 한 번에 붙이기 위한 조회. */
    @Query(
            "select c.customerId as customerId, c.nickname as nickname,"
                    + " c.investorType as investorType,"
                    + " c.investorTypePublic as investorTypePublic"
                    + " from CustomerEntity c where c.customerId in :ids")
    List<CustomerCommunityProfile> findCommunityProfiles(@Param("ids") Collection<Long> ids);
}

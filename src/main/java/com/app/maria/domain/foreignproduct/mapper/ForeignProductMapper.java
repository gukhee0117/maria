package com.app.maria.domain.foreignproduct.mapper;

import com.app.maria.domain.foreignproduct.dto.ForeignProductDTO;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ForeignProductMapper {
    List<ForeignProductDTO> selectAll();

    Optional<ForeignProductDTO> selectById(@Param("foreignProductId") Long foreignProductId);

    List<ForeignProductDTO> selectByIdsOrderedByTickerAndId(@Param("ids") Collection<Long> ids);
}

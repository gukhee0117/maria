package com.app.maria.global.clock.service;

import com.app.maria.global.clock.dto.SystemClockDTO;
import com.app.maria.global.clock.mapper.SystemClockMapper;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BusinessClockServiceImpl implements BusinessClockService {

    private final SystemClockMapper systemClockMapper;

    @Override
    public LocalDateTime now() {
        SystemClockDTO systemClock =
                systemClockMapper
                        .selectSystemClock()
                        .orElseThrow(
                                () -> new AppException(ErrorType.SYSTEM_CLOCK_NOT_INITIALIZED));
        return systemClock.getCurrentDatetime();
    }
}

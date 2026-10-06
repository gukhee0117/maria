package com.app.maria.domain.account.service;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.infra.RedisMydataSyncTaskRepository;
import com.app.maria.domain.account.infra.RedisMydataSyncTaskRepository.ClaimedTask;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.account.provider.MydataProvider;
import com.app.maria.domain.account.type.Status;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountMydataSyncService {
    private static final int RETRY_BATCH_SIZE = 100;

    private final AccountMapper accountMapper;
    private final MydataProvider mydataProvider;
    private final RedisMydataSyncTaskRepository taskRepository;

    public void create(AccountDTO account) {
        taskRepository.enqueue(account.getAccountId(), SyncOperation.SYNC.name());
    }

    public void updateLimit(AccountDTO account) {
        taskRepository.enqueue(account.getAccountId(), SyncOperation.SYNC.name());
    }

    @Scheduled(fixedDelayString = "${custom.mydata.ria-sync-delay-ms:1000}")
    public void retryOpenedAccounts() {
        taskRepository.claimDueTasks(RETRY_BATCH_SIZE).forEach(this::process);
    }

    private void process(ClaimedTask task) {
        try {
            retry(task);
        } finally {
            taskRepository.release(task);
        }
    }

    private void retry(ClaimedTask task) {
        SyncOperation operation = parseOperation(task);
        if (operation == null) {
            taskRepository.complete(task);
            return;
        }

        try {
            AccountDTO account = accountMapper.selectByAccountId(task.accountId()).orElse(null);
            if (account == null || account.getStatus() != Status.OPENED) {
                taskRepository.complete(task);
                return;
            }

            String ciHash =
                    accountMapper
                            .selectCiHashByCustomerId(account.getCustomerId())
                            .orElseThrow(
                                    () ->
                                            new AppException(
                                                    ErrorType.ACCOUNT_CUSTOMER_IDENTITY_NOT_FOUND,
                                                    account.getCustomerId()));
            synchronize(account, ciHash, task);
        } catch (RuntimeException exception) {
            log.error("MyData RIA 계좌 재동기화 실패: accountId={}", task.accountId(), exception);
            taskRepository.reschedule(task, operation.name());
        }
    }

    private SyncOperation parseOperation(ClaimedTask task) {
        try {
            return SyncOperation.from(task.value());
        } catch (IllegalArgumentException exception) {
            log.error("유효하지 않은 MyData 동기화 작업을 제거합니다: accountId={}", task.accountId(), exception);
            return null;
        }
    }

    private void synchronize(AccountDTO account, String ciHash, ClaimedTask task) {
        try {
            boolean successful = mydataProvider.syncRiaAccount(ciHash, account).is2xxSuccessful();
            if (successful) {
                taskRepository.complete(task);
                return;
            }
            taskRepository.reschedule(task, SyncOperation.SYNC.name());
            log.error("MyData RIA 계좌 동기화 실패: accountId={}", account.getAccountId());
        } catch (RuntimeException exception) {
            log.error("MyData RIA 계좌 동기화 예외: accountId={}", account.getAccountId(), exception);
            taskRepository.reschedule(task, SyncOperation.SYNC.name());
        }
    }

    private enum SyncOperation {
        SYNC;

        private static SyncOperation from(String taskValue) {
            if (taskValue == null) {
                throw new IllegalArgumentException("유효하지 않은 MyData 동기화 작업입니다.");
            }

            return switch (taskValue.split(":", 2)[0]) {
                case "SYNC", "CREATE", "UPDATE_LIMIT" -> SYNC;
                default -> throw new IllegalArgumentException("유효하지 않은 MyData 동기화 작업입니다.");
            };
        }
    }
}

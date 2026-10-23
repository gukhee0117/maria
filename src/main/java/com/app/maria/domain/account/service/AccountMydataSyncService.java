package com.app.maria.domain.account.service;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.infra.RedisMydataSyncTaskRepository;
import com.app.maria.domain.account.infra.RedisMydataSyncTaskRepository.ClaimedTask;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.account.type.Status;
import com.app.maria.global.client.mydata.MydataClient;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountMydataSyncService {
    private static final int RETRY_BATCH_SIZE = 100;
    private static final String SYNC = "SYNC";
    // 큐에 남아 있을 수 있는 옛 작업 이름(CREATE, UPDATE_LIMIT)도 SYNC로 처리한다
    private static final Set<String> KNOWN_TASKS = Set.of(SYNC, "CREATE", "UPDATE_LIMIT");

    private final AccountMapper accountMapper;
    private final MydataClient mydataClient;
    private final RedisMydataSyncTaskRepository taskRepository;

    public void create(AccountDTO account) {
        taskRepository.enqueue(account.getAccountId(), SYNC);
    }

    public void updateLimit(AccountDTO account) {
        taskRepository.enqueue(account.getAccountId(), SYNC);
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
        if (task.value() == null || !KNOWN_TASKS.contains(task.value().split(":", 2)[0])) {
            log.error(
                    "유효하지 않은 MyData 동기화 작업을 제거합니다: accountId={}, value={}",
                    task.accountId(),
                    task.value());
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
            taskRepository.reschedule(task, SYNC);
        }
    }

    private void synchronize(AccountDTO account, String ciHash, ClaimedTask task) {
        try {
            mydataClient.syncRiaAccount(ciHash, account.getLimitAmount());
            taskRepository.complete(task);
        } catch (RuntimeException exception) {
            log.error("MyData RIA 계좌 동기화 예외: accountId={}", account.getAccountId(), exception);
            taskRepository.reschedule(task, SYNC);
        }
    }
}

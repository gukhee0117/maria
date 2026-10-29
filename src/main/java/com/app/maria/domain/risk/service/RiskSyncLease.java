package com.app.maria.domain.risk.service;

public interface RiskSyncLease {
    boolean runIfAcquired(Runnable work);
}

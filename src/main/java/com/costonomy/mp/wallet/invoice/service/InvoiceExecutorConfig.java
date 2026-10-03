package com.costonomy.mp.wallet.invoice.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/** A small bounded pool for reading bills, so a slow reader cannot take threads from anything else (D-113). */
@Configuration
public class InvoiceExecutorConfig {

    @Bean(name = "invoiceReadExecutor")
    public ThreadPoolTaskExecutor invoiceReadExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("invoice-read-");
        // A full queue refuses; the retry job finds the bill instead.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}

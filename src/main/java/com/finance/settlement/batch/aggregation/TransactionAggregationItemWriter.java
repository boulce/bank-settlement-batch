package com.finance.settlement.batch.aggregation;

import com.finance.settlement.domain.DailyTransactionSummary;
import com.finance.settlement.repository.DailyTransactionSummaryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionAggregationItemWriter implements ItemWriter<DailyTransactionSummary> {

    private final DailyTransactionSummaryRepository summaryRepository;

    @Override
    public void write(@NonNull Chunk<? extends DailyTransactionSummary> chunk) {
        summaryRepository.saveAll(chunk.getItems());
        log.debug("Saved {} summaries", chunk.size());
    }
}

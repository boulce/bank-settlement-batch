package com.finance.settlement.batch.closing;

import com.finance.settlement.domain.MonthlyAccountSummary;
import com.finance.settlement.repository.MonthlyAccountSummaryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MonthlyClosingItemWriter implements ItemWriter<MonthlyAccountSummary> {

    private final MonthlyAccountSummaryRepository repository;

    @Override
    public void write(@NonNull Chunk<? extends MonthlyAccountSummary> chunk) {
        repository.saveAll(chunk.getItems());
        log.debug("Saved {} monthly summaries", chunk.size());
    }
}

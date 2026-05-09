package com.finance.settlement.batch.journal;

import com.finance.settlement.domain.JournalEntry;
import com.finance.settlement.repository.JournalEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Processor가 List를 반환하므로 Writer는 chunk를 평탄화(flatten) 후 saveAll한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JournalEntryItemWriter implements ItemWriter<List<JournalEntry>> {

    private final JournalEntryRepository journalEntryRepository;

    @Override
    public void write(@NonNull Chunk<? extends List<JournalEntry>> chunk) {
        List<JournalEntry> flat = new ArrayList<>(chunk.size() * 2);
        for (List<JournalEntry> pair : chunk) {
            flat.addAll(pair);
        }
        journalEntryRepository.saveAll(flat);
        log.debug("Saved {} journal entries", flat.size());
    }
}

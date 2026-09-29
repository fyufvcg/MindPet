package service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Named orchestration boundary for fact deduplication and supersession. */
@Service
public class FactMergeService {
    private final MemoryFactService memoryFacts;

    public FactMergeService(MemoryFactService memoryFacts) {
        this.memoryFacts = memoryFacts;
    }

    @Transactional
    public MemoryFactService.SavedFact merge(String userId, MemoryFactService.FactCandidate candidate) {
        return memoryFacts.merge(userId, candidate);
    }
}

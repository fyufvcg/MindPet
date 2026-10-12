package service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

/** One shared budget, whole evidence paths and deduplication by stored source mappings. */
public final class MemoryRetrievalSelection {
    private MemoryRetrievalSelection() {}
    public record Item(String id, String kind, String meaning, double score, List<String> required,
                       Set<String> graphFacts, boolean selectable, Set<String> facets) {
        public Item(String id, String kind, String meaning, double score, List<String> required,
                    Set<String> graphFacts, boolean selectable) {
            this(id, kind, meaning, score, required, graphFacts, selectable, Set.of());
        }
        public Item {
            required = List.copyOf(required); graphFacts = Set.copyOf(graphFacts); facets = Set.copyOf(facets);
        }
        boolean graph() { return kind.equals("kg"); }
    }

    public static List<String> select(List<Item> items, MemoryRetrievalService.Options options,
                                      ToIntFunction<List<String>> tokenCount) {
        Map<String, Item> byId = new LinkedHashMap<>();
        for (Item item : items) byId.put(item.id(), item);
        State state = new State(byId, options, tokenCount);
        List<Item> ranked = items.stream().filter(Item::selectable)
            .sorted(Comparator.comparingDouble(Item::score).reversed().thenComparing(Item::id)).toList();
        // Reserve coverage for each requested facet, subject to the same atomic path and token budget.
        Set<String> coveredFacets = new LinkedHashSet<>(), tried = new LinkedHashSet<>();
        while (true) {
            Item best = ranked.stream().filter(item -> !tried.contains(item.id()))
                .filter(item -> item.facets().stream().anyMatch(facet -> !coveredFacets.contains(facet)))
                .max(Comparator.comparingDouble((Item item) -> item.score()
                    + 0.2 * item.facets().stream().filter(facet -> !coveredFacets.contains(facet)).count())
                    .thenComparing(Item::id)).orElse(null);
            if (best == null) break;
            tried.add(best.id());
            if (state.add(best)) {
                coveredFacets.clear();
                for (Item selected : state.selected.values()) coveredFacets.addAll(selected.facets());
            }
        }
        // Scores, rather than a channel-wide KG preference, govern remaining budget.
        for (Item item : ranked) state.add(item);
        return List.copyOf(state.selected.keySet());
    }

    private static final class State {
        final Map<String, Item> all, selected = new LinkedHashMap<>();
        final MemoryRetrievalService.Options options;
        final ToIntFunction<List<String>> tokenCount;
        State(Map<String, Item> all, MemoryRetrievalService.Options options, ToIntFunction<List<String>> tokenCount) {
            this.all = all; this.options = options; this.tokenCount = tokenCount;
        }
        boolean add(Item item) {
            if (selected.containsKey(item.id())) return true;
            Set<String> covered = new LinkedHashSet<>(), meanings = new LinkedHashSet<>();
            for (Item old : selected.values()) { covered.addAll(old.graphFacts()); if (!old.graph()) meanings.add(old.meaning()); }
            if (!item.graph()) covered.addAll(item.graphFacts());
            if (!item.graph() && meanings.contains(item.meaning())) return false;
            if (item.graph() && covered.containsAll(item.graphFacts())) return false;
            Map<String, Item> proposal = new LinkedHashMap<>(selected);
            for (String id : item.required()) {
                Item required = all.get(id);
                if (required == null) return false;
                if (required.graph() && covered.containsAll(required.graphFacts())) continue;
                proposal.put(id, required);
            }
            if (!item.graph()) {
                // A mapped RAG block preserves the original assertions, so their duplicate triples cost no tokens.
                proposal.entrySet().removeIf(e -> e.getValue().graph()
                    && item.graphFacts().containsAll(e.getValue().graphFacts()));
            }
            List<String> ids = new ArrayList<>(proposal.keySet());
            if (ids.equals(new ArrayList<>(selected.keySet()))) return false;
            long graphCount = proposal.values().stream().filter(Item::graph).count();
            if (proposal.size() > options.limit() || graphCount > options.graphLimit()
                    || tokenCount.applyAsInt(ids) > options.tokenBudget()) return false;
            selected.clear(); selected.putAll(proposal);
            return true;
        }
    }
}

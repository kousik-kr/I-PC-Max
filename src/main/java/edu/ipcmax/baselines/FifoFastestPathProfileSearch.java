package edu.ipcmax.baselines;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.function.PiecewiseLinearFn;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.profile.TemporalProfileWork;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Exact functional fastest-path search over a continuous departure domain.
 *
 * <p>When all edge functions share one temporal support domain, this uses a
 * function-valued, priority-queue label-correcting search: each relaxation
 * composes complete profiles and merges them with an exact lower envelope. With
 * differing support domains, it expands vertex-simple route labels so a fast
 * but unsupported prefix cannot hide a slower prefix that can enter a later
 * edge's support. Neither mode samples departures or limits breakpoints.</p>
 *
 * <p>The cited THop paper describes this step as a fastest-path search and
 * cites Li et al. for its functional-search complexity. The cited work does
 * not specify a full all-destinations FPP API with partial edge support and
 * exact endpoint ownership. The common-support mode follows its functional
 * relaxation strategy. The partial-support fallback uses finite simple-route
 * label expansion to preserve the repository's unavailable-time semantics;
 * that fallback has an exponential worst case.</p>
 */
public final class FifoFastestPathProfileSearch {
    private final TDGraph graph;

    public FifoFastestPathProfileSearch(TDGraph graph) {
        this.graph = Objects.requireNonNull(graph, "graph");
        validateFifoGraph(graph);
    }

    /** Searches from {@code source} for all destinations over the given domain. */
    public SearchResult search(int source, Domain departureDomain) {
        graph.node(source);
        if (departureDomain == null || departureDomain.isEmpty()) {
            throw new IllegalArgumentException("a nonempty departure domain is required");
        }
        long started = System.nanoTime();
        Map<String, Long> work = new HashMap<>();
        long initialHeap = usedHeapBytes();
        ExactTemporalProfile identity = ExactTemporalProfile.identity(departureDomain);
        Map<Integer, ExactTemporalProfile> best = new HashMap<>();
        ProfileSearchCounts counts;

        try (TemporalProfileWork.Scope ignored = TemporalProfileWork.install(
                (name, amount) -> work.merge(name, amount, Long::sum))) {
            counts = hasCommonSupportDomain()
                    ? runProfileLabelCorrecting(source, identity, best, initialHeap)
                    : runSimpleRouteExpansion(source, identity, best, initialHeap);
        }

        return new SearchResult(
                source,
                departureDomain,
                best,
                new SearchStatistics(
                        System.nanoTime() - started,
                        counts.expanded(),
                        counts.generated(),
                        work.getOrDefault("thop_temporal_compositions", 0L),
                        work.getOrDefault("thop_envelope_operations", 0L),
                        counts.sampledPeakUsedHeapBytes()));
    }

    /** Function-valued label-correcting search, valid for shared edge support. */
    private ProfileSearchCounts runProfileLabelCorrecting(
            int source,
            ExactTemporalProfile identity,
            Map<Integer, ExactTemporalProfile> best,
            long initialHeap) {
        best.put(source, identity);
        PriorityQueue<ProfileQueueEntry> queue = new PriorityQueue<>(Comparator
                .comparingDouble(ProfileQueueEntry::minimumArrival)
                .thenComparingInt(ProfileQueueEntry::node));
        Set<Integer> queued = new HashSet<>();
        queue.add(new ProfileQueueEntry(source, identity.minimumValue()));
        queued.add(source);
        long expanded = 0;
        long generated = 1;
        long sampledPeak = initialHeap;

        while (!queue.isEmpty()) {
            ProfileQueueEntry entry = queue.poll();
            queued.remove(entry.node());
            ExactTemporalProfile prefix = best.get(entry.node());
            expanded++;
            for (Edge edge : graph.outgoingEdges(entry.node())) {
                ExactTemporalProfile candidate = prefix.composeAfter(ExactTemporalProfile.edgeArrival(edge));
                if (candidate.isEmpty()) {
                    continue;
                }
                ExactTemporalProfile current = best.get(edge.target());
                ExactTemporalProfile merged = current == null
                        ? candidate
                        : current.pointwiseMinimum(candidate);
                if (current != null && current.sameFunction(merged)) {
                    continue;
                }
                best.put(edge.target(), merged);
                generated++;
                if (queued.add(edge.target())) {
                    queue.add(new ProfileQueueEntry(edge.target(), merged.minimumValue()));
                }
                sampledPeak = Math.max(sampledPeak, usedHeapBytes());
            }
            sampledPeak = Math.max(sampledPeak, usedHeapBytes());
        }
        return new ProfileSearchCounts(expanded, generated, sampledPeak);
    }

    /** Partial-support fallback; every expanded function has a simple route witness. */
    private ProfileSearchCounts runSimpleRouteExpansion(
            int source,
            ExactTemporalProfile identity,
            Map<Integer, ExactTemporalProfile> best,
            long initialHeap) {
        PriorityQueue<RouteLabel> queue = new PriorityQueue<>(LABEL_ORDER);
        RouteLabel initial = new RouteLabel(source, identity, List.of(), List.of(source), Set.of(source));
        queue.add(initial);
        Map<StateKey, List<ExactTemporalProfile>> exactDuplicateStates = new HashMap<>();
        exactDuplicateStates.put(new StateKey(source, initial.visitedNodes()), new ArrayList<>(List.of(identity)));
        long expanded = 0;
        long generated = 1;
        long sampledPeak = initialHeap;

        while (!queue.isEmpty()) {
            RouteLabel label = queue.poll();
            expanded++;
            ExactTemporalProfile prior = best.get(label.node());
            if (prior == null) {
                best.put(label.node(), label.profile());
            } else {
                best.put(label.node(), prior.pointwiseMinimum(label.profile()));
            }
            for (Edge edge : graph.outgoingEdges(label.node())) {
                if (label.visitedNodes().contains(edge.target())) {
                    continue;
                }
                ExactTemporalProfile candidate = label.profile().composeAfter(
                        ExactTemporalProfile.edgeArrival(edge));
                if (candidate.isEmpty()) {
                    continue;
                }
                List<Integer> arcs = append(label.arcIds(), edge.arcId());
                List<Integer> nodes = append(label.nodeSequence(), edge.target());
                Set<Integer> visited = new HashSet<>(label.visitedNodes());
                visited.add(edge.target());
                Set<Integer> immutableVisited = Set.copyOf(visited);
                StateKey state = new StateKey(edge.target(), immutableVisited);
                List<ExactTemporalProfile> equivalent = exactDuplicateStates.computeIfAbsent(
                        state, ignoredState -> new ArrayList<>());
                if (equivalent.stream().anyMatch(candidate::sameFunction)) {
                    continue;
                }
                equivalent.add(candidate);
                queue.add(new RouteLabel(edge.target(), candidate, arcs, nodes, immutableVisited));
                generated++;
                sampledPeak = Math.max(sampledPeak, usedHeapBytes());
            }
            sampledPeak = Math.max(sampledPeak, usedHeapBytes());
        }
        return new ProfileSearchCounts(expanded, generated, sampledPeak);
    }

    private boolean hasCommonSupportDomain() {
        Domain common = null;
        for (Edge edge : graph.edges()) {
            Domain candidate = edge.travelTimeFunction().domain();
            if (common == null) {
                common = candidate;
            } else if (!common.equals(candidate)) {
                return false;
            }
        }
        return true;
    }

    private static List<Integer> append(List<Integer> prefix, int value) {
        List<Integer> result = new ArrayList<>(prefix.size() + 1);
        result.addAll(prefix);
        result.add(value);
        return List.copyOf(result);
    }

    private static long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static void validateFifoGraph(TDGraph graph) {
        for (Edge edge : graph.edges()) {
            PiecewiseLinearFn function = edge.travelTimeFunction();
            function.requireFifo("arc_id " + edge.arcId());
            List<Domain.Interval> intervals = function.domain().intervals();
            for (int index = 1; index < intervals.size(); index++) {
                double priorArrival = function.arrivalTimeAtClosure(intervals.get(index - 1).end());
                double nextArrival = function.arrivalTimeAtClosure(intervals.get(index).start());
                if (priorArrival > nextArrival) {
                    throw new IllegalArgumentException(
                            "arc_id " + edge.arcId()
                                    + ": arrival function is non-FIFO across a domain gap");
                }
            }
        }
    }

    private static final Comparator<RouteLabel> LABEL_ORDER = Comparator
            .comparingDouble((RouteLabel label) -> label.profile().minimumValue())
            .thenComparingInt(label -> label.arcIds().size())
            .thenComparing(RouteLabel::arcIds, FifoFastestPathProfileSearch::compareArcIds)
            .thenComparingInt(RouteLabel::node);

    private static int compareArcIds(List<Integer> left, List<Integer> right) {
        int common = Math.min(left.size(), right.size());
        for (int index = 0; index < common; index++) {
            int comparison = Integer.compare(left.get(index), right.get(index));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.size(), right.size());
    }

    private record ProfileQueueEntry(int node, double minimumArrival) {
    }

    private record ProfileSearchCounts(long expanded, long generated, long sampledPeakUsedHeapBytes) {
    }

    private record StateKey(int node, Set<Integer> visitedNodes) {
    }

    private record RouteLabel(
            int node,
            ExactTemporalProfile profile,
            List<Integer> arcIds,
            List<Integer> nodeSequence,
            Set<Integer> visitedNodes) {
    }

    public record SearchStatistics(
            long elapsedNanos,
            long searchStatesExpanded,
            long searchStatesGenerated,
            long temporalCompositions,
            long envelopeOperations,
            long sampledPeakUsedHeapBytes) {
    }

    /** Immutable all-destination profiles from one source. */
    public static final class SearchResult {
        private final int source;
        private final Domain departureDomain;
        private final Map<Integer, ExactTemporalProfile> profiles;
        private final SearchStatistics statistics;

        private SearchResult(
                int source,
                Domain departureDomain,
                Map<Integer, ExactTemporalProfile> profiles,
                SearchStatistics statistics) {
            this.source = source;
            this.departureDomain = departureDomain;
            this.profiles = Map.copyOf(profiles);
            this.statistics = statistics;
        }

        public int source() {
            return source;
        }

        public Domain departureDomain() {
            return departureDomain;
        }

        public Optional<ExactTemporalProfile> profileTo(int destination) {
            return Optional.ofNullable(profiles.get(destination));
        }

        public Map<Integer, ExactTemporalProfile> profiles() {
            return profiles;
        }

        public SearchStatistics statistics() {
            return statistics;
        }
    }
}

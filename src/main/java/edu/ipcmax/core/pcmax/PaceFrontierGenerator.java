package edu.ipcmax.core.pcmax;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import edu.ipcmax.core.cache.CandidateCache;
import edu.ipcmax.core.cache.MemoKey;
import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.profile.CandidateProfile;
import edu.ipcmax.core.profile.CandidateSet;
import edu.ipcmax.core.profile.ScoreProfile;
import edu.ipcmax.core.profile.TimeProfile;
import edu.ipcmax.core.validate.Path;

/**
 * Correctness-first implementation of the PACE GenerateFrontier recursion.
 */
public final class PaceFrontierGenerator {
    private static final double EPSILON = 1e-9;

    private final TDGraph graph;
    private final CandidateCache memo;
    private final List<Edge> scoreAnchors;

    /**
     * Creates a generator using all score-contributing edges as anchors.
     */
    public PaceFrontierGenerator(TDGraph graph) {
        this.graph = graph;
        this.memo = new CandidateCache();
        this.scoreAnchors = graph.edges().stream()
                .filter(edge -> edge.scoreFunction().maxValue() > 0)
                .toList();
    }

    /**
     * Generates {@code Q_uv^ell(D)} under budget {@code B}.
     */
    public CandidateSet generateFrontier(int source, int destination, Domain domain, double budget, int ell) {
        if (ell < 0) {
            throw new IllegalArgumentException("frontier depth cannot be negative");
        }
        if (domain.isEmpty()) {
            return new CandidateSet();
        }

        MemoKey key = new MemoKey(
                source,
                destination,
            domain,
                "start-domain:" + domain.intervals(),
                "budget:" + budget,
                ell,
                true,
                1,
                false,
                0);
        Optional<CandidateSet> cached = memo.get(key);
        if (cached.isPresent()) {
            return cached.get();
        }

        CandidateSet frontier = connectorFrontier(source, destination, domain, budget);
        if (ell > 0) {
            for (Edge anchor : scoreAnchors) {
                CandidateSet leftFrontier = generateFrontier(source, anchor.source(), domain, budget, ell - 1);
                for (CandidateProfile left : leftFrontier.candidates()) {
                    Domain leftAnchorDomain = leftAnchorDomain(left, anchor, domain);
                    if (leftAnchorDomain.isEmpty()) {
                        continue;
                    }
                    Domain rightDomain = imageDomainAfterAnchor(left, anchor, leftAnchorDomain);
                    if (rightDomain.isEmpty()) {
                        continue;
                    }
                    CandidateSet rightFrontier = generateFrontier(anchor.target(), destination, rightDomain, budget, ell - 1);
                    for (CandidateProfile right : rightFrontier.candidates()) {
                        TemporalStitch.stitch(graph, left, anchor, right, domain, budget)
                                .ifPresent(frontier::add);
                    }
                }
            }
        }

        CandidateSet compressed = compressExactDuplicates(frontier);
        memo.put(key, compressed);
        return compressed;
    }

    /**
     * Computes ImageDomain(A_a composed with A_C_L, D_L^a).
     */
    static Domain imageDomainAfterAnchor(CandidateProfile left, Edge anchor, Domain leftAnchorDomain) {
        TimeProfile anchorArrival = edgeArrivalProfile(anchor);
        TimeProfile composed = left.arrivalProfile().compose(anchorArrival, "image-domain:" + left.pathPointer().arcIds() + ":" + anchor.arcId());
        Domain image = composed.imageDomain(leftAnchorDomain);
        if (image.isEmpty()) {
            return Domain.empty();
        }
        return image;
    }

    private CandidateSet connectorFrontier(int source, int destination, Domain domain, double budget) {
        CandidateSet set = new CandidateSet();
        for (Path path : enumerateLooplessPaths(source, destination)) {
            Domain feasible = feasiblePathDomain(path, domain, budget);
            if (feasible.isEmpty()) {
                continue;
            }
            set.add(candidateForPath(path, feasible));
        }
        return set;
    }

    private List<Path> enumerateLooplessPaths(int source, int destination) {
        List<Path> paths = new ArrayList<>();
        if (source == destination) {
            paths.add(Path.empty());
            return paths;
        }
        dfs(source, destination, new HashSet<>(), new ArrayList<>(), paths);
        return paths;
    }

    private void dfs(int node, int destination, Set<Integer> visited, List<Integer> arcs, List<Path> paths) {
        if (node == destination) {
            paths.add(new Path(arcs));
            return;
        }
        visited.add(node);
        for (Edge edge : graph.outgoingEdges(node)) {
            if (visited.contains(edge.target())) {
                continue;
            }
            arcs.add(edge.arcId());
            dfs(edge.target(), destination, visited, arcs, paths);
            arcs.remove(arcs.size() - 1);
        }
        visited.remove(node);
    }

    private Domain feasiblePathDomain(Path path, Domain domain, double budget) {
        TimeProfile arrival = pathArrivalProfile(path, domain);
        return arrival.domainWhereTravelTimeAtMost(domain, budget);
    }

    private CandidateProfile candidateForPath(Path path, Domain domain) {
        TimeProfile arrival = pathArrivalProfile(path, domain);
        ScoreProfile score = pathScoreProfile(path, domain, arrival);
        Domain feasible = arrival.domainWhereTravelTimeAtMost(domain, Double.POSITIVE_INFINITY).intersection(domain);
        return new CandidateProfile(
                feasible,
                arrival.restrict(feasible),
                score.restrict(feasible),
                () -> path.arcIds(),
                0,
                -1,
                false);
    }

    private TimeProfile pathArrivalProfile(Path path, Domain domain) {
        TimeProfile arrival = TimeProfile.identity(domain);
        for (int arcId : path.arcIds()) {
            Edge edge = graph.edges().get(arcId);
            arrival = arrival.compose(edgeArrivalProfile(edge), "connector-arrival:" + path.arcIds() + ":" + arcId + ":" + domain.intervals());
        }
        return arrival;
    }

    private ScoreProfile pathScoreProfile(Path path, Domain domain, TimeProfile arrival) {
        ScoreProfile score = ScoreProfile.constant(domain, 0);
        TimeProfile prefixArrival = TimeProfile.identity(domain);
        for (int arcId : path.arcIds()) {
            Edge edge = graph.edges().get(arcId);
            ScoreProfile edgeScore = ScoreProfile.compose(prefixArrival, edge.scoreFunction(), domain, "connector-edge-score:" + path.arcIds() + ":" + arcId + ":" + domain.intervals());
            score = score.add(edgeScore, domain, "connector-score:" + path.arcIds() + ":" + arcId + ":" + domain.intervals());
            prefixArrival = prefixArrival.compose(edgeArrivalProfile(edge), "connector-prefix-arrival:" + path.arcIds() + ":" + arcId + ":" + domain.intervals());
        }
        return score;
    }

    private static TimeProfile edgeArrivalProfile(Edge edge) {
        Domain edgeDomain = Domain.closed(edge.travelTimeFunction().firstMinute(), edge.travelTimeFunction().lastMinute());
        List<TimeProfile.Breakpoint> breakpoints = new ArrayList<>();
        for (edu.ipcmax.core.function.PiecewiseLinearFn.Breakpoint breakpoint : edge.travelTimeFunction().breakpoints()) {
            breakpoints.add(new TimeProfile.Breakpoint(breakpoint.minute(), edge.travelTimeFunction().arrivalTimeAt(breakpoint.minute())));
        }
        return TimeProfile.piecewise(edgeDomain, breakpoints, "edge-arrival:" + edge.arcId());
    }

    private Domain leftAnchorDomain(CandidateProfile left, Edge anchor, Domain rootDomain) {
        Domain base = rootDomain.intersection(left.domain());
        return left.arrivalProfile().preimage(anchor.scoreFunction().positiveDomain(), base);
    }

    private CandidateSet compressExactDuplicates(CandidateSet frontier) {
        Map<String, CandidateProfile> unique = new LinkedHashMap<>();
        for (CandidateProfile candidate : frontier.candidates()) {
            String key = candidate.pathPointer().arcIds()
                    + "|" + candidate.domain().intervals()
                    + "|" + candidate.arrivalProfile().fingerprint()
                    + "|" + candidate.scoreProfile().fingerprint();
            unique.putIfAbsent(key, candidate);
        }
        CandidateSet compressed = new CandidateSet();
        unique.values().forEach(compressed::add);
        return compressed;
    }
}

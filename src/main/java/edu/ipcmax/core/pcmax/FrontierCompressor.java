package edu.ipcmax.core.pcmax;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.profile.CandidateProfile;
import edu.ipcmax.core.profile.CandidateSet;
import edu.ipcmax.core.profile.PathPointer;
import edu.ipcmax.core.profile.SafeProfileDominance;
import edu.ipcmax.core.profile.ScoreProfile;
import edu.ipcmax.core.profile.TimeProfile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finalized PACE frontier compression: exact duplicate removal, cell-local
 * extension-safe dominance, and deterministic bounded retention for PACE-B.
 */
public final class FrontierCompressor {
    private FrontierCompressor() {
    }

    /**
     * Compresses a subproblem frontier without introducing a second frontier type.
     * PACE-X ignores {@code k}; PACE-B requires it to be positive and retains at most
     * {@code k} fragments on every temporal cell.
     */
    public static CandidateSet compress(
            TDGraph graph,
            CandidateSet candidateFrontier,
            Domain subproblemDomain,
            double budget,
            int k,
            PaceExecutionPolicy policy,
            int subproblemSource,
            int subproblemDestination) {
        return compress(graph, candidateFrontier, subproblemDomain, budget, k, policy,
                subproblemSource, subproblemDestination, PaceFeatures.defaults());
    }

    /** Compresses with explicit experiment feature switches. */
    public static CandidateSet compress(
            TDGraph graph,
            CandidateSet candidateFrontier,
            Domain subproblemDomain,
            double budget,
            int k,
            PaceExecutionPolicy policy,
            int subproblemSource,
            int subproblemDestination,
            PaceFeatures features) {
        if (graph == null || candidateFrontier == null || subproblemDomain == null || policy == null
                || features == null) {
            throw new IllegalArgumentException("graph, frontier, domain, and policy are required");
        }
        if (budget < 0.0 || !Double.isFinite(budget)) {
            throw new IllegalArgumentException("budget must be finite and non-negative");
        }
        if (policy == PaceExecutionPolicy.PACE_B && k < 1) {
            throw new IllegalArgumentException("PACE-B requires K >= 1");
        }
        if (subproblemDomain.isEmpty() || candidateFrontier.isEmpty()) {
            return new CandidateSet();
        }

        List<CandidateProfile> normalized = normalizeAndDeduplicate(candidateFrontier, subproblemDomain);
        if (normalized.isEmpty()) {
            return new CandidateSet();
        }

        if (!features.compressionEnabled()) {
            CandidateSet exactDuplicatesOnly = new CandidateSet();
            normalized.forEach(exactDuplicatesOnly::add);
            return exactDuplicatesOnly;
        }

        if (policy == PaceExecutionPolicy.PACE_B && !features.perCellRetentionEnabled()) {
            Domain.Interval whole = new Domain.Interval(
                    subproblemDomain.intervals().get(0).start(),
                    subproblemDomain.intervals().get(subproblemDomain.intervals().size() - 1).end());
            List<CandidateProfile> safe = features.safeDominanceEnabled()
                    ? safePrune(graph, normalized, whole, subproblemSource, subproblemDestination)
                    : normalized;
            List<CandidateProfile> selected = boundedRetain(graph, safe, whole, subproblemDomain,
                    budget, k, subproblemSource, subproblemDestination,
                    features.representativeRetentionEnabled());
            CandidateSet global = new CandidateSet();
            selected.forEach(global::add);
            return global;
        }

        List<Domain.Interval> cells = ProfileCellPartition.cells(subproblemDomain, normalized, true);
        List<Fragment> retained = new ArrayList<>();
        for (Domain.Interval cell : cells) {
            double activeSample = cell.start() == cell.end()
                    ? cell.start()
                    : ProfileCellPartition.midpoint(cell);
            List<CandidateProfile> active = normalized.stream()
                    .filter(candidate -> candidate.domain().contains(activeSample))
                    .sorted(CandidateSet.STABLE_ORDER)
                    .toList();
            retainCellExactly(
                    graph,
                    normalized,
                    active,
                    cell,
                    subproblemDomain,
                    budget,
                    k,
                    policy,
                    subproblemSource,
                    subproblemDestination,
                    features,
                    retained);
        }
        return features.adjacentMergeEnabled()
                ? mergeAdjacentFragments(retained)
                : fragmentsWithoutMerge(retained);
    }

    private static void retainCellExactly(
            TDGraph graph,
            List<CandidateProfile> normalized,
            List<CandidateProfile> active,
            Domain.Interval cell,
            Domain subproblemDomain,
            double budget,
            int k,
            PaceExecutionPolicy policy,
            int source,
            int destination,
            PaceFeatures features,
            List<Fragment> output) {
        if (cell.start() == cell.end()) {
            for (CandidateProfile candidate : retainAtPoint(
                    graph, active, cell.start(), subproblemDomain, budget, k, policy, source, destination,
                    features)) {
                output.add(new Fragment(candidate, cell));
            }
            return;
        }

        Domain.Interval interiorCell = new Domain.Interval(
                cell.start(), cell.end(), false, false);
        List<CandidateProfile> safelyRetained = features.safeDominanceEnabled()
                ? safePrune(graph, active, interiorCell, source, destination)
                : active;
        List<CandidateProfile> interior = policy == PaceExecutionPolicy.PACE_B
                ? boundedRetain(
                graph, safelyRetained, interiorCell, subproblemDomain, budget, k, source, destination,
                features.representativeRetentionEnabled())
                : safelyRetained;
        List<CandidateProfile> atStart = cell.startInclusive()
                ? retainAtPoint(
                graph,
                activeAtPoint(normalized, cell.start()),
                cell.start(),
                subproblemDomain,
                budget,
                k,
                policy,
                source,
                destination,
                features)
                : List.of();
        List<CandidateProfile> atEnd = cell.endInclusive()
                ? retainAtPoint(
                graph,
                activeAtPoint(normalized, cell.end()),
                cell.end(),
                subproblemDomain,
                budget,
                k,
                policy,
                source,
                destination,
                features)
                : List.of();

        Set<CandidateProfile> handled = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (CandidateProfile candidate : interior) {
            boolean ownsStart = containsIdentity(atStart, candidate);
            boolean ownsEnd = containsIdentity(atEnd, candidate);
            output.add(new Fragment(candidate, new Domain.Interval(
                    cell.start(),
                    cell.end(),
                    cell.startInclusive() && ownsStart,
                    cell.endInclusive() && ownsEnd)));
            handled.add(candidate);
        }
        for (CandidateProfile candidate : atStart) {
            if (!handled.contains(candidate)) {
                output.add(new Fragment(candidate, new Domain.Interval(cell.start(), cell.start())));
            }
        }
        for (CandidateProfile candidate : atEnd) {
            if (!handled.contains(candidate)) {
                output.add(new Fragment(candidate, new Domain.Interval(cell.end(), cell.end())));
            }
        }
    }

    private static List<CandidateProfile> retainAtPoint(
            TDGraph graph,
            List<CandidateProfile> active,
            double point,
            Domain subproblemDomain,
            double budget,
            int k,
            PaceExecutionPolicy policy,
            int source,
            int destination,
            PaceFeatures features) {
        if (active.isEmpty() || policy == PaceExecutionPolicy.PACE_X) {
            if (active.isEmpty()) {
                return active;
            }
        }
        Domain.Interval singleton = new Domain.Interval(point, point);
        List<CandidateProfile> safelyRetained = features.safeDominanceEnabled()
                ? safePrune(graph, active, singleton, source, destination)
                : active;
        return policy == PaceExecutionPolicy.PACE_B
                ? boundedRetain(
                graph, safelyRetained, singleton, subproblemDomain, budget, k, source, destination,
                features.representativeRetentionEnabled())
                : safelyRetained;
    }

    private static List<CandidateProfile> activeAtPoint(
            List<CandidateProfile> candidates,
            double point) {
        return candidates.stream()
                .filter(candidate -> candidate.domain().contains(point))
                .sorted(CandidateSet.STABLE_ORDER)
                .toList();
    }

    private static boolean containsIdentity(List<CandidateProfile> candidates, CandidateProfile sought) {
        for (CandidateProfile candidate : candidates) {
            if (candidate == sought) {
                return true;
            }
        }
        return false;
    }

    /**
     * Removes only repeated representations of the same path, domain, arrival, and score.
     */
    public static CandidateSet removeExactDuplicates(CandidateSet frontier) {
        CandidateSet result = new CandidateSet();
        List<CandidateProfile> unique = new ArrayList<>();
        for (CandidateProfile candidate : frontier.candidates().stream().sorted(CandidateSet.STABLE_ORDER).toList()) {
            boolean duplicate = unique.stream().anyMatch(existing -> sameRepresentation(existing, candidate));
            if (!duplicate) {
                unique.add(candidate);
                result.add(candidate);
            }
        }
        return result;
    }

    private static List<CandidateProfile> normalizeAndDeduplicate(
            CandidateSet frontier,
            Domain subproblemDomain) {
        CandidateSet restricted = new CandidateSet();
        for (CandidateProfile candidate : frontier.candidates()) {
            Domain domain = candidate.domain().intersection(subproblemDomain);
            if (!domain.isEmpty()) {
                restricted.add(candidate.domain().equals(domain) ? candidate : candidate.restrict(domain));
            }
        }
        return removeExactDuplicates(restricted).candidates();
    }

    private static boolean sameRepresentation(CandidateProfile left, CandidateProfile right) {
        if (!left.stablePathId().equals(right.stablePathId()) || !left.domain().equals(right.domain())) {
            return false;
        }
        for (Domain.Interval component : left.domain().intervals()) {
            if (component.end() <= component.start()) {
                double point = component.start();
                if (!Domain.sameTime(left.arrivalProfile().valueAt(point), right.arrivalProfile().valueAt(point))
                        || left.scoreProfile().valueAt(point) != right.scoreProfile().valueAt(point)) {
                    return false;
                }
                continue;
            }
            List<Domain.Interval> cells = ProfileCellPartition.cells(
                    Domain.of(component), List.of(left, right), false);
            for (Domain.Interval cell : cells) {
                if (!SafeProfileDominance.sameArrival(left, right, cell)
                        || !SafeProfileDominance.sameScore(left, right, cell)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static List<CandidateProfile> safePrune(
            TDGraph graph,
            List<CandidateProfile> active,
            Domain.Interval cell,
            int source,
            int destination) {
        List<CandidateProfile> retained = new ArrayList<>();
        for (int dominatedIndex = 0; dominatedIndex < active.size(); dominatedIndex++) {
            CandidateProfile dominated = active.get(dominatedIndex);
            boolean isDominated = false;
            for (int dominatorIndex = 0; dominatorIndex < active.size(); dominatorIndex++) {
                if (dominatorIndex == dominatedIndex) {
                    continue;
                }
                CandidateProfile dominator = active.get(dominatorIndex);
                boolean forward = SafeProfileDominance.dominates(
                        graph, dominator, dominated, cell, source, destination);
                if (!forward) {
                    continue;
                }
                boolean reverse = SafeProfileDominance.dominates(
                        graph, dominated, dominator, cell, source, destination);
                if (!reverse || dominatorIndex < dominatedIndex) {
                    isDominated = true;
                    break;
                }
            }
            if (!isDominated) {
                retained.add(dominated);
            }
        }
        return retained;
    }

    private static List<CandidateProfile> boundedRetain(
            TDGraph graph,
            List<CandidateProfile> candidates,
            Domain.Interval cell,
            Domain subproblemDomain,
            double budget,
            int k,
            int source,
            int destination,
            boolean representativesEnabled) {
        if (candidates.size() <= k) {
            return candidates;
        }

        Map<CandidateProfile, Metrics> metrics = new IdentityHashMap<>();
        for (CandidateProfile candidate : candidates) {
            metrics.put(candidate, metrics(
                    graph, candidate, cell, subproblemDomain, budget, source, destination));
        }

        List<CandidateProfile> selected = new ArrayList<>(k);
        if (representativesEnabled) {
            addIfNew(selected, minimum(candidates, championComparator(cell)));
        }
        if (representativesEnabled && k >= 2) {
            addIfNew(selected, minimum(candidates, metricComparator(metrics, MetricOrder.EARLIEST)));
        }
        if (representativesEnabled && k >= 3) {
            addIfNew(selected, minimum(candidates, metricComparator(metrics, MetricOrder.LEAST_RESTRICTIVE)));
        }
        if (selected.size() < k) {
            List<CandidateProfile> fill = candidates.stream()
                    .sorted(metricComparator(metrics, MetricOrder.FILL))
                    .toList();
            for (CandidateProfile candidate : fill) {
                addIfNew(selected, candidate);
                if (selected.size() == k) {
                    break;
                }
            }
        }
        return selected.stream().sorted(CandidateSet.STABLE_ORDER).toList();
    }

    private static Comparator<CandidateProfile> championComparator(Domain.Interval cell) {
        double sample = ProfileCellPartition.midpoint(cell);
        return (left, right) -> {
            int comparison = Integer.compare(
                    right.scoreProfile().valueAt(sample), left.scoreProfile().valueAt(sample));
            if (comparison != 0) {
                return comparison;
            }
            comparison = Double.compare(left.travelTimeAt(sample), right.travelTimeAt(sample));
            if (comparison != 0) {
                return comparison;
            }
            comparison = Integer.compare(left.edgeCount(), right.edgeCount());
            if (comparison != 0) {
                return comparison;
            }
            comparison = PathPointer.STABLE_PATH_ORDER.compare(left.stablePathId(), right.stablePathId());
            return comparison != 0 ? comparison : canonicalProfileCompare(left, right);
        };
    }

    private static Comparator<CandidateProfile> metricComparator(
            Map<CandidateProfile, Metrics> metrics,
            MetricOrder order) {
        return (left, right) -> {
            Metrics l = metrics.get(left);
            Metrics r = metrics.get(right);
            int comparison;
            if (order == MetricOrder.EARLIEST) {
                comparison = Double.compare(l.averageArrival(), r.averageArrival());
                if (comparison == 0) {
                    comparison = -Double.compare(l.averageScore(), r.averageScore());
                }
            } else if (order == MetricOrder.LEAST_RESTRICTIVE) {
                comparison = Integer.compare(l.omegaSize(), r.omegaSize());
                if (comparison == 0) {
                    comparison = Double.compare(l.averageArrival(), r.averageArrival());
                }
                if (comparison == 0) {
                    comparison = -Double.compare(l.averageScore(), r.averageScore());
                }
            } else {
                comparison = -Double.compare(l.averageScore(), r.averageScore());
                if (comparison == 0) {
                    comparison = Double.compare(l.averageArrival(), r.averageArrival());
                }
                if (comparison == 0) {
                    comparison = -Double.compare(l.minimumSlack(), r.minimumSlack());
                }
                if (comparison == 0) {
                    comparison = -Double.compare(l.temporalCoverage(), r.temporalCoverage());
                }
                if (comparison == 0) {
                    comparison = Integer.compare(l.omegaSize(), r.omegaSize());
                }
            }
            if (comparison == 0) {
                comparison = Integer.compare(left.edgeCount(), right.edgeCount());
            }
            if (comparison == 0) {
                comparison = PathPointer.STABLE_PATH_ORDER.compare(left.stablePathId(), right.stablePathId());
            }
            return comparison != 0 ? comparison : canonicalProfileCompare(left, right);
        };
    }

    private static int canonicalProfileCompare(CandidateProfile left, CandidateProfile right) {
        int comparison = left.domain().toString().compareTo(right.domain().toString());
        if (comparison == 0) {
            comparison = left.arrivalProfile().fingerprint().compareTo(right.arrivalProfile().fingerprint());
        }
        if (comparison == 0) {
            comparison = left.scoreProfile().fingerprint().compareTo(right.scoreProfile().fingerprint());
        }
        return comparison;
    }

    private static CandidateProfile minimum(
            List<CandidateProfile> candidates,
            Comparator<CandidateProfile> comparator) {
        return candidates.stream().min(comparator).orElseThrow();
    }

    private static void addIfNew(List<CandidateProfile> selected, CandidateProfile candidate) {
        for (CandidateProfile existing : selected) {
            if (existing == candidate) {
                return;
            }
        }
        selected.add(candidate);
    }

    private static Metrics metrics(
            TDGraph graph,
            CandidateProfile candidate,
            Domain.Interval cell,
            Domain subproblemDomain,
            double budget,
            int source,
            int destination) {
        double duration = cell.end() - cell.start();
        double averageArrival;
        double averageScore;
        if (duration <= 0) {
            averageArrival = candidate.arrivalProfile().valueAt(cell.start());
            averageScore = candidate.scoreProfile().valueAt(cell.start());
        } else {
            averageArrival = integrateArrival(candidate.arrivalProfile(), cell) / duration;
            averageScore = integrateScore(candidate.scoreProfile(), cell) / duration;
        }
        double minimumSlack = budget - maximumTravelTime(candidate.arrivalProfile(), cell);
        double denominator = ProfileCellPartition.measure(subproblemDomain);
        double temporalCoverage = denominator <= 0
                ? 1.0
                : ProfileCellPartition.measure(candidate.domain()) / denominator;
        int omegaSize = candidate.internalVertices(graph, source, destination).size();
        return new Metrics(averageScore, averageArrival, minimumSlack, temporalCoverage, omegaSize);
    }

    private static double integrateArrival(TimeProfile profile, Domain.Interval cell) {
        List<Double> cuts = new ArrayList<>();
        cuts.add(cell.start());
        cuts.add(cell.end());
        for (TimeProfile.Breakpoint breakpoint : profile.breakpoints()) {
            if (breakpoint.minute() > cell.start()
                    && breakpoint.minute() < cell.end()) {
                cuts.add(breakpoint.minute());
            }
        }
        List<Double> sorted = ProfileCellPartition.uniqueSorted(cuts);
        double integral = 0.0;
        for (int i = 0; i + 1 < sorted.size(); i++) {
            double start = sorted.get(i);
            double end = sorted.get(i + 1);
            integral += (profile.valueAtClosure(start) + profile.valueAtClosure(end))
                    * (end - start) / 2.0;
        }
        return integral;
    }

    private static double integrateScore(ScoreProfile profile, Domain.Interval cell) {
        List<Double> cuts = new ArrayList<>();
        cuts.add(cell.start());
        cuts.add(cell.end());
        for (double breakpoint : profile.breakpoints()) {
            if (breakpoint > cell.start() && breakpoint < cell.end()) {
                cuts.add(breakpoint);
            }
        }
        List<Double> sorted = ProfileCellPartition.uniqueSorted(cuts);
        double integral = 0.0;
        for (int i = 0; i + 1 < sorted.size(); i++) {
            double start = sorted.get(i);
            double end = sorted.get(i + 1);
            integral += profile.valueAt(start + ((end - start) / 2.0)) * (end - start);
        }
        return integral;
    }

    private static double maximumTravelTime(TimeProfile profile, Domain.Interval cell) {
        List<Double> points = new ArrayList<>();
        points.add(cell.start());
        points.add(cell.end());
        for (TimeProfile.Breakpoint breakpoint : profile.breakpoints()) {
            if (breakpoint.minute() > cell.start()
                    && breakpoint.minute() < cell.end()) {
                points.add(breakpoint.minute());
            }
        }
        double maximum = Double.NEGATIVE_INFINITY;
        for (double point : points) {
            maximum = Math.max(maximum, profile.valueAtClosure(point) - point);
        }
        return maximum;
    }

    private static CandidateSet mergeAdjacentFragments(List<Fragment> fragments) {
        List<CandidateProfile> pieces = new ArrayList<>();
        for (Fragment fragment : fragments) {
            pieces.add(restrictAndMarkCompressed(fragment.source(), Domain.of(fragment.cell())));
        }
        pieces.sort(Comparator
                .comparing(CandidateProfile::stablePathId, PathPointer.STABLE_PATH_ORDER)
                .thenComparingDouble(candidate -> candidate.domain().intervals().get(0).start())
                .thenComparing(candidate -> candidate.domain().toString()));

        List<CandidateProfile> merged = new ArrayList<>();
        for (CandidateProfile piece : pieces) {
            if (!merged.isEmpty()) {
                int lastIndex = merged.size() - 1;
                CandidateProfile joined = mergeAdjacentCompatible(merged.get(lastIndex), piece);
                if (joined != null) {
                    merged.set(lastIndex, joined);
                    continue;
                }
            }
            merged.add(piece);
        }
        CandidateSet result = new CandidateSet();
        merged.stream().sorted(CandidateSet.STABLE_ORDER).forEach(result::add);
        return removeExactDuplicates(result);
    }

    private static CandidateSet fragmentsWithoutMerge(List<Fragment> fragments) {
        CandidateSet result = new CandidateSet();
        for (Fragment fragment : fragments) {
            result.add(restrictAndMarkCompressed(fragment.source(), Domain.of(fragment.cell())));
        }
        return removeExactDuplicates(result);
    }

    /**
     * Merges adjacent compatible restrictions of the same stable path, or returns
     * {@code null} when the fragments must remain separate.
     */
    static CandidateProfile mergeAdjacentCompatible(
            CandidateProfile first,
            CandidateProfile second) {
        if (!first.stablePathId().equals(second.stablePathId())
                || first.explicitAnchorCount() != second.explicitAnchorCount()
                || !sameProfileLineage(
                        first.arrivalProfile().fingerprint(),
                        second.arrivalProfile().fingerprint())
                || !sameProfileLineage(
                        first.scoreProfile().fingerprint(),
                        second.scoreProfile().fingerprint())
                || first.domain().intervals().size() != 1
                || second.domain().intervals().size() != 1) {
            return null;
        }
        CandidateProfile left = first;
        CandidateProfile right = second;
        if (left.domain().intervals().get(0).start() > right.domain().intervals().get(0).start()) {
            left = second;
            right = first;
        }
        Domain.Interval leftInterval = left.domain().intervals().get(0);
        Domain.Interval rightInterval = right.domain().intervals().get(0);
        if (!Domain.sameTime(leftInterval.end(), rightInterval.start())
                || !(leftInterval.endInclusive() || rightInterval.startInclusive())) {
            return null;
        }
        double boundary = leftInterval.end();
        if (!Domain.sameTime(
                left.arrivalProfile().valueAtClosure(boundary),
                right.arrivalProfile().valueAtClosure(boundary))) {
            return null;
        }
        if (leftInterval.endInclusive() && rightInterval.startInclusive()
                && left.scoreProfile().valueAt(boundary) != right.scoreProfile().valueAt(boundary)) {
            return null;
        }

        Domain union = left.domain().union(right.domain());
        TimeProfile arrival = mergeArrivalProfiles(left, right, union);
        ScoreProfile score = mergeScoreProfiles(left, right, union);
        int pivotId = left.pivotId() == right.pivotId() ? left.pivotId() : -1;
        return new CandidateProfile(
                union,
                arrival,
                score,
                left.pathPointer(),
                left.explicitAnchorCount(),
                pivotId,
                true);
    }

    private static TimeProfile mergeArrivalProfiles(
            CandidateProfile left,
            CandidateProfile right,
            Domain union) {
        List<TimeProfile.Breakpoint> points = new ArrayList<>();
        points.addAll(left.arrivalProfile().breakpoints());
        points.addAll(right.arrivalProfile().breakpoints());
        points.sort(Comparator.comparingDouble(TimeProfile.Breakpoint::minute));
        List<TimeProfile.Breakpoint> unique = new ArrayList<>();
        for (TimeProfile.Breakpoint point : points) {
            if (!unique.isEmpty()
                    && Domain.sameTime(unique.get(unique.size() - 1).minute(), point.minute())) {
                if (!Domain.sameTime(unique.get(unique.size() - 1).value(), point.value())) {
                    throw new IllegalArgumentException("incompatible arrival fragments for one path");
                }
                continue;
            }
            unique.add(point);
        }
        return TimeProfile.piecewise(
                union,
                unique,
                profileLineage(left.arrivalProfile().fingerprint())
                        + "|restrict:" + union.intervals());
    }

    private static ScoreProfile mergeScoreProfiles(
            CandidateProfile left,
            CandidateProfile right,
            Domain union) {
        List<Double> cuts = new ArrayList<>(union.breakpoints());
        cuts.addAll(left.scoreProfile().breakpoints());
        cuts.addAll(right.scoreProfile().breakpoints());
        List<ScoreProfile.Interval> pieces = new ArrayList<>();
        for (Domain.Interval cell : union.splitAt(ProfileCellPartition.uniqueSorted(cuts)).intervals()) {
            if (cell.start() == cell.end()) {
                pieces.add(new ScoreProfile.Interval(
                        cell.start(),
                        cell.end(),
                        scoreAt(left, right, cell.start())));
                continue;
            }
            double sample = ProfileCellPartition.midpoint(cell);
            int value = scoreAt(left, right, sample);
            pieces.add(new ScoreProfile.Interval(cell.start(), cell.end(), value));
            if (cell.endInclusive()) {
                int endpointValue = scoreAt(left, right, cell.end());
                if (endpointValue != value) {
                    pieces.add(new ScoreProfile.Interval(cell.end(), cell.end(), endpointValue));
                }
            }
        }
        return ScoreProfile.piecewise(
                union,
                pieces,
                profileLineage(left.scoreProfile().fingerprint())
                        + "|restrict:" + union.intervals());
    }

    private static boolean sameProfileLineage(String left, String right) {
        return profileLineage(left).equals(profileLineage(right));
    }

    private static String profileLineage(String fingerprint) {
        int restriction = fingerprint.indexOf("|restrict:");
        return restriction < 0 ? fingerprint : fingerprint.substring(0, restriction);
    }

    private static int scoreAt(
            CandidateProfile left,
            CandidateProfile right,
            double departure) {
        boolean inLeft = left.domain().contains(departure);
        boolean inRight = right.domain().contains(departure);
        if (inLeft && inRight) {
            int leftValue = left.scoreProfile().valueAt(departure);
            int rightValue = right.scoreProfile().valueAt(departure);
            if (leftValue != rightValue) {
                throw new IllegalArgumentException("incompatible score fragments for one path");
            }
            return leftValue;
        }
        if (inLeft) {
            return left.scoreProfile().valueAt(departure);
        }
        if (inRight) {
            return right.scoreProfile().valueAt(departure);
        }
        throw new IllegalArgumentException("merged profile has an uncovered departure " + departure);
    }

    private static CandidateProfile restrictAndMarkCompressed(CandidateProfile source, Domain domain) {
        CandidateProfile restricted = source.restrict(domain);
        return new CandidateProfile(
                restricted.domain(),
                restricted.arrivalProfile(),
                restricted.scoreProfile(),
                restricted.pathPointer(),
                restricted.explicitAnchorCount(),
                restricted.pivotId(),
                true);
    }

    private enum MetricOrder {
        EARLIEST,
        LEAST_RESTRICTIVE,
        FILL
    }

    private record Metrics(
            double averageScore,
            double averageArrival,
            double minimumSlack,
            double temporalCoverage,
            int omegaSize) {
    }

    private record Fragment(CandidateProfile source, Domain.Interval cell) {
    }
}

package edu.ipcmax.baselines.exact.preference;

import edu.ipcmax.baselines.exact.ExactProfileEnvelope;
import edu.ipcmax.baselines.exact.ExactTemporalDomain;
import edu.ipcmax.baselines.exact.ExactTime;
import edu.ipcmax.core.validate.Path;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/** Complete exact route-selection profile over a supplied candidate registry. */
public final class ExactPreferenceEnvelope {
    public record Cell(
            ExactTemporalDomain.Interval interval,
            Path path,
            BigInteger score,
            ExactTime arrivalSlope,
            ExactTime arrivalIntercept) {
        public Cell {
            Objects.requireNonNull(interval, "interval");
            boolean noPath = path == null;
            if (noPath != (score == null) || noPath != (arrivalSlope == null) || noPath != (arrivalIntercept == null)) {
                throw new IllegalArgumentException("NO_PATH cells have no route value; selected cells require all values");
            }
        }
        public boolean found() { return path != null; }
        public ExactTime arrivalAt(ExactTime departure) {
            if (!found()) throw new IllegalStateException("NO_PATH cell has no arrival");
            if (!interval.contains(departure)) throw new IllegalArgumentException("departure outside cell");
            return arrivalSlope.multiply(departure).add(arrivalIntercept);
        }
        public ExactTime travelTimeAt(ExactTime departure) {
            return arrivalAt(departure).subtract(departure);
        }
    }

    private static final Comparator<Cell> CELL_ORDER = Comparator
            .comparing((Cell cell) -> cell.interval().start())
            .thenComparing(cell -> !cell.interval().startInclusive())
            .thenComparing(cell -> cell.interval().end())
            .thenComparing(cell -> !cell.interval().endInclusive());

    private final ExactTemporalDomain domain;
    private final List<Cell> cells;
    private final long envelopeComparisons;

    private ExactPreferenceEnvelope(ExactTemporalDomain domain, List<Cell> cells, long comparisons) {
        this.domain = Objects.requireNonNull(domain, "domain");
        this.cells = cells.stream().sorted(CELL_ORDER).toList();
        this.envelopeComparisons = comparisons;
        ExactTemporalDomain covered = ExactTemporalDomain.empty();
        for (int i = 0; i < this.cells.size(); i++) {
            Cell current = this.cells.get(i);
            if (domain.intersection(ExactTemporalDomain.of(current.interval())).isEmpty()) {
                throw new IllegalArgumentException("envelope cell lies outside requested domain");
            }
            for (int j = 0; j < i; j++) {
                if (current.interval().intersection(this.cells.get(j).interval()) != null) {
                    throw new IllegalArgumentException("exact envelope cells overlap");
                }
            }
            covered = covered.union(ExactTemporalDomain.of(current.interval()));
        }
        if (!covered.equals(domain)) throw new IllegalArgumentException("exact envelope does not cover its requested domain");
    }

    /** Reload hook used by the deterministic exact profile codec. */
    public static ExactPreferenceEnvelope fromSerialized(
            ExactTemporalDomain domain, List<Cell> cells) {
        return new ExactPreferenceEnvelope(domain, cells, 0);
    }

    public ExactTemporalDomain domain() { return domain; }
    public List<Cell> cells() { return cells; }
    public long envelopeComparisons() { return envelopeComparisons; }

    public int maximumRationalComponentBitLength() {
        int result = 0;
        for (Cell cell : cells) {
            result = Math.max(result, Math.max(
                    bitLength(cell.interval().start()), bitLength(cell.interval().end())));
            if (cell.found()) {
                result = Math.max(result, Math.max(
                        bitLength(cell.arrivalSlope()), bitLength(cell.arrivalIntercept())));
                result = Math.max(result, cell.score().abs().bitLength());
            }
        }
        return result;
    }

    public Optional<Cell> cellAt(ExactTime departure) {
        if (!domain.contains(departure)) return Optional.empty();
        return cells.stream().filter(cell -> cell.interval().contains(departure)).findFirst();
    }

    public boolean hasFeasibleCell() { return cells.stream().anyMatch(Cell::found); }

    /** Exact lower envelope by score, travel time, edge count, then arc-ID sequence. */
    public static ExactPreferenceEnvelope overRetained(
            ExactTemporalDomain requested,
            Map<Path, ExactPreferencePathProfile> profiles) {
        TreeSet<ExactTime> cuts = new TreeSet<>();
        for (ExactTemporalDomain.Interval interval : requested.intervals()) {
            cuts.add(interval.start());
            cuts.add(interval.end());
        }
        List<ExactPreferencePathProfile> usable = profiles.values().stream()
                .filter(profile -> !profile.feasibleDomain().isEmpty()).toList();
        for (ExactPreferencePathProfile profile : usable) {
            addDomainCuts(cuts, profile.feasibleDomain());
            profile.arrival().segments().forEach(segment -> {
                cuts.add(segment.interval().start());
                cuts.add(segment.interval().end());
            });
            profile.score().segments().forEach(segment -> {
                cuts.add(segment.interval().start());
                cuts.add(segment.interval().end());
            });
        }
        long pairComparisons = 0;
        for (int i = 0; i < usable.size(); i++) {
            for (int j = i + 1; j < usable.size(); j++) {
                ExactPreferencePathProfile left = usable.get(i);
                ExactPreferencePathProfile right = usable.get(j);
                ExactTemporalDomain common = left.feasibleDomain().intersection(right.feasibleDomain());
                if (common.isEmpty()) continue;
                for (ExactTime crossing : ExactProfileEnvelope.breakpointCrossings(left.arrival(), right.arrival())) {
                    if (common.contains(crossing)) cuts.add(crossing);
                }
                pairComparisons += (long) left.arrival().segments().size() * right.arrival().segments().size();
            }
        }

        List<ExactTime> ordered = List.copyOf(cuts);
        List<Cell> result = new ArrayList<>();
        long comparisons = pairComparisons;
        for (int i = 0; i + 1 < ordered.size(); i++) {
            ExactTime start = ordered.get(i), end = ordered.get(i + 1);
            if (start.equals(end)) continue;
            ExactTime probe = start.add(end).divide(ExactTime.of(2));
            if (!requested.contains(probe)) continue;
            Chosen chosen = select(usable, probe);
            Selection selection = chosen.selection();
            comparisons += chosen.comparisons();
            ExactTemporalDomain.Interval cell = new ExactTemporalDomain.Interval(start, end, false, false);
            result.add(selection == null ? new Cell(cell, null, null, null, null)
                    : selectedCell(cell, selection, probe));
        }
        for (ExactTime point : ordered) {
            if (!requested.contains(point)) continue;
            Chosen chosen = select(usable, point);
            Selection selection = chosen.selection();
            comparisons += chosen.comparisons();
            ExactTemporalDomain.Interval cell = ExactTemporalDomain.Interval.singleton(point);
            result.add(selection == null ? new Cell(cell, null, null, null, null)
                    : selectedCell(cell, selection, point));
        }
        return new ExactPreferenceEnvelope(requested, result, comparisons);
    }

    private record Selection(ExactPreferencePathProfile profile, BigInteger score, ExactTime arrival) { }

    private record Chosen(Selection selection, long comparisons) { }

    private static Chosen select(List<ExactPreferencePathProfile> profiles, ExactTime departure) {
        Selection best = null;
        long comparisons = 0;
        for (ExactPreferencePathProfile candidate : profiles) {
            if (!candidate.feasibleDomain().contains(departure)) continue;
            BigInteger score = candidate.score().valueAt(departure);
            ExactTime arrival = candidate.arrival().valueAtExact(departure);
            Selection current = new Selection(candidate, score, arrival);
            if (best == null) best = current;
            else {
                comparisons++;
                if (compare(current, best) > 0) best = current;
            }
        }
        return new Chosen(best, comparisons);
    }

    /** Positive means left wins. */
    private static int compare(Selection left, Selection right) {
        int score = left.score().compareTo(right.score());
        if (score != 0) return score;
        int arrival = right.arrival().compareTo(left.arrival());
        if (arrival != 0) return arrival;
        int edges = Integer.compare(right.profile().path().arcIds().size(), left.profile().path().arcIds().size());
        if (edges != 0) return edges;
        return -lexicographic(left.profile().path().arcIds(), right.profile().path().arcIds());
    }

    private static int lexicographic(List<Integer> left, List<Integer> right) {
        for (int i = 0; i < Math.min(left.size(), right.size()); i++) {
            int order = Integer.compare(left.get(i), right.get(i));
            if (order != 0) return order;
        }
        return Integer.compare(left.size(), right.size());
    }

    private static Cell selectedCell(
            ExactTemporalDomain.Interval interval, Selection selection, ExactTime probe) {
        var affine = selection.profile().arrival().segments().stream()
                .filter(segment -> segment.interval().contains(probe))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("selected cell crosses an arrival event"));
        return new Cell(interval, selection.profile().path(), selection.score(),
                affine.slope(), affine.intercept());
    }

    private static void addDomainCuts(TreeSet<ExactTime> cuts, ExactTemporalDomain domain) {
        for (ExactTemporalDomain.Interval interval : domain.intervals()) {
            cuts.add(interval.start());
            cuts.add(interval.end());
        }
    }

    private static int bitLength(ExactTime value) {
        return Math.max(value.numerator().abs().bitLength(), value.denominator().bitLength());
    }
}

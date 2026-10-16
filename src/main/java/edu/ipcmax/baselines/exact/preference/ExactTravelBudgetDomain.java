package edu.ipcmax.baselines.exact.preference;

import edu.ipcmax.baselines.exact.ExactArrivalProfile;
import edu.ipcmax.baselines.exact.ExactTemporalDomain;
import edu.ipcmax.baselines.exact.ExactTime;

import java.util.ArrayList;
import java.util.List;

/** Exact support where the affine travel-time relation is at most a budget. */
public record ExactTravelBudgetDomain(ExactTemporalDomain domain, long rootOperations) {
    public static ExactTravelBudgetDomain compute(ExactArrivalProfile arrival, ExactTime budget) {
        List<ExactTemporalDomain.Interval> result = new ArrayList<>();
        long roots = 0;
        for (var affine : arrival.asTravelTime().segments()) {
            ExactTime slope = affine.slope();
            ExactTime intercept = affine.intercept();
            var source = affine.interval();
            if (slope.signum() == 0) {
                if (intercept.compareTo(budget) <= 0) result.add(source);
                continue;
            }
            roots++;
            ExactTime root = budget.subtract(intercept).divide(slope);
            ExactTemporalDomain.Interval feasible;
            if (slope.signum() > 0) {
                int atStart = root.compareTo(source.start());
                int atEnd = root.compareTo(source.end());
                if (atStart < 0) continue;
                if (atStart == 0) {
                    if (!source.startInclusive()) continue;
                    feasible = ExactTemporalDomain.Interval.singleton(root);
                    result.add(feasible);
                    continue;
                }
                if (atEnd > 0 || atEnd == 0) {
                    feasible = source;
                } else {
                    feasible = new ExactTemporalDomain.Interval(
                            source.start(), root, source.startInclusive(), true);
                }
            } else {
                int atStart = root.compareTo(source.start());
                int atEnd = root.compareTo(source.end());
                if (atEnd > 0) continue;
                if (atStart < 0 || atStart == 0) {
                    feasible = source;
                } else if (atEnd == 0) {
                    if (!source.endInclusive()) continue;
                    feasible = ExactTemporalDomain.Interval.singleton(root);
                } else {
                    feasible = new ExactTemporalDomain.Interval(
                            root, source.end(), true, source.endInclusive());
                }
            }
            if (feasible != null) result.add(feasible);
        }
        return new ExactTravelBudgetDomain(ExactTemporalDomain.of(result), roots);
    }
}

package com.kuronami.musicdiscmaker.client.audio;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.kuronami.musicdiscmaker.network.PlaybackSourceStamp;

/** Selects every distinct request released by one or more close-ACK indexes. */
final class PendingCoreActions {
    record Action(Object route, PlaybackSourceStamp identity, long worldEpoch,
            long order, Runnable action) {}

    private PendingCoreActions() {}

    static boolean mayDrainAfterClose(long barrierWorldEpoch, long currentWorldEpoch) {
        return barrierWorldEpoch == currentWorldEpoch;
    }

    static boolean mayDrainSourceAfterClose(UUID closingSource, Action pending, long currentWorldEpoch) {
        return closingSource != null && pending != null && pending.worldEpoch() == currentWorldEpoch
                && closingSource.equals(sourceId(pending));
    }

    static List<Action> select(Action routePending, Action sourcePending) {
        if (routePending == null && sourcePending == null) return List.of();
        if (routePending == null) return List.of(sourcePending);
        if (sourcePending == null) return List.of(routePending);

        UUID routeSource = sourceId(routePending);
        UUID pendingSource = sourceId(sourcePending);
        if (routeSource != null && routeSource.equals(pendingSource)) {
            Action latest = compareVersion(routePending, sourcePending) >= 0 ? routePending : sourcePending;
            return List.of(latest);
        }

        List<Action> selected = new ArrayList<>(List.of(routePending, sourcePending));
        selected.sort(Comparator.comparingLong(Action::order));
        return List.copyOf(selected);
    }

    private static int compareVersion(Action left, Action right) {
        long leftGeneration = left.identity() == null ? Long.MIN_VALUE : left.identity().generation();
        long rightGeneration = right.identity() == null ? Long.MIN_VALUE : right.identity().generation();
        int generation = Long.compare(leftGeneration, rightGeneration);
        return generation != 0 ? generation : Long.compare(left.order(), right.order());
    }

    private static UUID sourceId(Action action) {
        return action.identity() == null ? null : action.identity().sourceId();
    }
}

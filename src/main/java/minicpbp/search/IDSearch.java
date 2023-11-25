/*
 * mini-cp is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License  v3
 * as published by the Free Software Foundation.
 *
 * mini-cp is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY.
 * See the GNU Lesser General Public License  for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with mini-cp. If not, see http://www.gnu.org/licenses/lgpl-3.0.en.html
 *
 * Copyright (c)  2018. by Laurent Michel, Pierre Schaus, Pascal Van Hentenryck
 */

package minicpbp.search;

import minicpbp.state.StateManager;
import minicpbp.util.exception.InconsistencyException;
import minicpbp.util.exception.NotImplementedException;
import minicpbp.util.Procedure;

import java.util.LinkedList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Depth First Search Branch and Bound implementation
 */
public class IDSearch {

    private Supplier<Procedure[]> branching;
    private StateManager sm;

    private final int maxDepth;

    private List<Procedure> depthListeners = new LinkedList<>();

    public IDSearch(StateManager sm, int maxDepth, Supplier<Procedure[]> branching) {
        this.sm = sm;
        this.branching = branching;
        this.maxDepth = maxDepth;
    }

    public void onMaxDepth(Procedure listener) {
        depthListeners.add(listener);
    }

    private void notifyDepth() {
        depthListeners.forEach(Procedure::call);
    }

    public void solve(SearchStatistics stats) {
        sm.withNewState(() -> {
            try {
                ids(0, stats);
                stats.setCompleted();
            } catch (StopSearchException ignored) {
            } catch (StackOverflowError e) {
                throw new NotImplementedException("dfs with explicit stack needed to pass this test");
            }
        });
    }

    private void ids(int depth, SearchStatistics stats) {
        Procedure[] branches = branching.get();
        if (depth == maxDepth || branches.length == 0) {
            stats.incrSolutions();
            notifyDepth();
            return;
        }
        for (Procedure b : branches) {
            sm.withNewState(() -> {
                try {
                    stats.incrNodes();
                    b.call();
                    ids(depth + 1, stats);
                } catch (InconsistencyException e) {
                    stats.incrFailures();
                }
            });
        }
    }
}

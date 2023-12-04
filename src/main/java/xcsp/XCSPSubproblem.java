package xcsp;

import minicpbp.search.SearchStatistics;

import java.util.List;
import java.util.concurrent.Callable;

public class XCSPSubproblem implements Callable<XCSPSubproblem.Result> {
    private final XCSP parent;
    private final List<List<Integer>> tuples;
    private final int id;

    /*Result*/
    public static class Result {
        Result(int id, SearchStatistics stats, List<String> solutions) {
            this.id = id;
            this.stats = stats;
            this.solutions = solutions;
        }
        int id;
        SearchStatistics stats;
        List<String> solutions;

    }

    public XCSPSubproblem(int id, XCSP parent, List<List<Integer>> tuples) {
        this.parent = parent;
        this.tuples = tuples;
        this.id = id;
    }

    @Override
    public Result call() {
        XCSP subproblem;
        try {
            subproblem = new XCSP(parent);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        /* Add tuples to subproblem */
        subproblem.addTableConstraint(tuples);
		System.out.printf("--- [subp %d] %d tuples: %s%n", id, tuples.size(), tuples);

        /* Solve subproblem */
        subproblem.launchSearch();

        /* Get results */
        Result result = new Result(id, subproblem.getStats(), subproblem.getSolutions());

        subproblem = null;
        System.gc();
        return result;
    }
}

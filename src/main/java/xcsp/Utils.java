package xcsp;

import minicpbp.engine.core.IntVar;
import minicpbp.state.StateStack;

import java.util.List;

public class Utils {
    public static <T> void fillArrayFromStateStack(StateStack<T> vars, int size, T[] array) {
        for (int i = 0; i < size; i++) {
            array[i] = vars.get(i);
        }
    }

    public static <T> void fillArrayFromStateStack(StateStack<T> vars, T[] array) {
        for (int i = 0; i < vars.size(); i++) {
            array[i] = vars.get(i);
        }
    }


    public static int[][] generateTableFromList(List<List<Integer>> tuples) {
        assert !tuples.isEmpty();
        int nTuples = tuples.size();
        int nVars = tuples.get(0).size();

        // one tuple per line
        int[][] table = new int[nTuples][nVars];
        for (int i = 0; i < nTuples; i++) {
            for (int j = 0; j < nVars; j++) {
                table[i][j] = tuples.get(i).get(j);
            }
        }
        return table;
    }

    /**
     * Returns min { l | max(1, nTuples) * P_{i=d+1}^{l} |D(x_i| >= p}
     * @param t number of tuples
     * @param d actual decomposition depth
     * @param x all variables
     * @param p number of subproblems
     * @return new bound for d
     */
    public static int newBound(int t, int d, IntVar[] x, int p) {
        int product = Math.max(1, t);
        for (int l = d+1; l < x.length; l++) {
            product *= x[l].size();
            if (product >= p) {
                return l;
            }
        }
        return -1;
    }
}

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

package xcsp;

import minicpbp.cp.Factory;
import minicpbp.engine.core.*;
import minicpbp.engine.core.Solver.PropaMode;
import minicpbp.search.IDSearch;
import minicpbp.search.LDSearch;
import minicpbp.search.Search;
import minicpbp.search.SearchStatistics;
import minicpbp.util.Procedure;
import minicpbp.util.exception.InconsistencyException;
import minicpbp.util.exception.NotImplementedException;

import launch.SolveXCSPFZN.BranchingHeuristic;
import launch.SolveXCSPFZN.TreeSearchType;

import org.xcsp.common.structures.Transition;
import org.xcsp.parser.callbacks.SolutionChecker;
import org.xcsp.common.Condition;
import org.xcsp.common.Constants;
import org.xcsp.common.Types;
import org.xcsp.common.predicates.XNode;
import org.xcsp.common.predicates.XNodeParent;
import org.xcsp.parser.callbacks.XCallbacks2;
import org.xcsp.parser.entries.XVariables.XVarInteger;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.security.InvalidParameterException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static minicpbp.cp.BranchingScheme.*;
import static minicpbp.cp.Factory.*;
import static java.lang.reflect.Array.newInstance;

public class XCSP implements XCallbacks2, Runnable {

	private Implem implem = new Implem(this);

	private String fileName;
	private final Map<XVarInteger, IntVar> mapVar = new HashMap<>();
	private List<XVarInteger> xVars = new LinkedList<>();
	private List<IntVar> minicpVars = new LinkedList<>();

	private final Set<IntVar> decisionVars = new LinkedHashSet<>();

	private Solver minicp = makeSolver();

	private Optional<IntVar> objectiveMinimize = Optional.empty();
	private Optional<IntVar> realObjective = Optional.empty();

	private boolean hasFailed;

	/* Parallel computation */
	private long t0;
	private int timeout;
	private BranchingHeuristic heuristic;
	private IntVar[] subproblemVars;
	private List<String> subproblemSolutions;
	private SearchStatistics stats;

	@Override
	public Implem implem() {
		return implem;
	}

	public XCSP(String fileName) throws Exception {
		this.fileName = fileName;
		hasFailed = false;

		implem.currParameters.clear();

		implem.currParameters.put(XCallbacksParameters.RECOGNIZE_UNARY_PRIMITIVES, new Object());
		implem.currParameters.put(XCallbacksParameters.RECOGNIZE_BINARY_PRIMITIVES, new Object());
		implem.currParameters.put(XCallbacksParameters.RECOGNIZE_TERNARY_PRIMITIVES, new Object());
		implem.currParameters.put(XCallbacksParameters.RECOGNIZE_NVALUES_CASES, new Object());
		implem.currParameters.put(XCallbacksParameters.RECOGNIZE_COUNT_CASES, Boolean.TRUE);
		implem.currParameters.put(XCallbacksParameters.RECOGNIZING_BEFORE_CONVERTING, Boolean.TRUE);
		implem.currParameters.put(XCallbacksParameters.CONVERT_INTENSION_TO_EXTENSION_ARITY_LIMIT, Integer.MAX_VALUE); // included
		implem.currParameters.put(XCallbacksParameters.CONVERT_INTENSION_TO_EXTENSION_SPACE_LIMIT, Long.MAX_VALUE); // included

		loadInstance(fileName);
	}

	public boolean isCOP() {
		return objectiveMinimize.isPresent();
	}

	public List<String> getViolatedCtrs(String solution) throws Exception {
		return new SolutionChecker(false, fileName, new ByteArrayInputStream(solution.getBytes())).violatedCtrs;
	}

	@Override
	public void buildVarInteger(XVarInteger x, int minValue, int maxValue) {
		IntVar minicpVar = makeIntVar(minicp, minValue, maxValue);
		mapVar.put(x, minicpVar);
		minicpVars.add(minicpVar);
		xVars.add(x);
		minicpVar.setName(x.id());
	}

	@Override
	public void buildVarInteger(XVarInteger x, int[] values) {
		Set<Integer> vals = new LinkedHashSet<>();
		for (int v : values)
			vals.add(v);
		IntVar minicpVar = makeIntVar(minicp, vals);
		mapVar.put(x, minicpVar);
		minicpVars.add(minicpVar);
		xVars.add(x);
		minicpVar.setName(x.id());
	}

	private IntVar[] mapVarArray(Object vars) {
		return Arrays.stream((XVarInteger[]) vars).map(mapVar::get).toArray(IntVar[]::new);
	}
	/* eventually useful?
	private IntVar[][] mapVar2dArray(Object vars) {
		return Arrays.stream((XVarInteger[][]) vars).map(this::mapVarArray).toArray(IntVar[][]::new);
	}
	*/

	@Override
	public void buildCtrExtension(String id, XVarInteger x, int[] values, boolean positive, Set<Types.TypeFlag> flags) {
		if (hasFailed)
			return;
		int[][] table = new int[values.length][1];
		for (int i = 0; i < values.length; i++)
			table[i][0] = values[i];
		buildCtrExtension(id, new XVarInteger[] { x }, table, positive, flags);
	}

	@Override
	public void buildCtrExtension(String id, XVarInteger[] list, int[][] tuples, boolean positive,
			Set<Types.TypeFlag> flags) {
		if (hasFailed)
			return;

		/*
		 * if (flags.contains(Types.TypeFlag.UNCLEAN_TUPLES)) { // You have possibly to
		 * clean tuples here, in order to remove invalid tuples. // A tuple is invalid
		 * if it contains a setValue $a$ for a variable $x$, not present in $dom(x)$ //
		 * Note that most of the time, tuples are already cleaned by the parser }
		 */

		try {

			if (!positive) {
				minicp.post(negTable(mapVarArray(list), tuples));
			} else {
				if (flags.contains(Types.TypeFlag.STARRED_TUPLES)) {
					minicp.post(shortTable(mapVarArray(list), tuples, Constants.STAR_INT));
				} else {
					minicp.post(table(mapVarArray(list), tuples));
				}
			}

		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	private void relConstraintVal(IntVar x, Types.TypeConditionOperatorRel operator, int k) {
		if (hasFailed)
			return;
		try {
			switch (operator) {
			case EQ:
				x.assign(k);
				break;
			case GE:
				x.removeBelow(k);
				break;
			case GT:
				x.removeBelow(k + 1);
				break;
			case LE:
				x.removeAbove(k);
				break;
			case LT:
				x.removeAbove(k - 1);
				break;
			case NE:
				x.remove(k);
				break;
			default:
				throw new InvalidParameterException("unknown condition");
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	private void relConstraintVar(IntVar x, Types.TypeConditionOperatorRel operator, IntVar y) {
		if (hasFailed)
			return;
		try {
			switch (operator) {
			case EQ:
				minicp.post(equal(x, y));
				break;
			case GE:
				minicp.post(largerOrEqual(x, y));
				break;
			case GT:
				minicp.post(larger(x, y));
				break;
			case LE:
				minicp.post(lessOrEqual(x, y));
				break;
			case LT:
				minicp.post(less(x, y));
				break;
			case NE:
				minicp.post(notEqual(x, y));
				break;
			default:
				throw new InvalidParameterException("unknown condition");
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	private void buildCrtWithCondition(String id, IntVar expr, Condition operator) {
		if (hasFailed)
			return;

		if (operator instanceof Condition.ConditionVal) {
			Condition.ConditionVal op = (Condition.ConditionVal) operator;
			relConstraintVal(expr, op.operator, (int) op.k);
		} else if (operator instanceof Condition.ConditionVar) {
			Condition.ConditionVar op = (Condition.ConditionVar) operator;
			relConstraintVar(expr, op.operator, mapVar.get(op.x));
		} else if (operator instanceof Condition.ConditionIntvl) {
			Condition.ConditionIntvl op = (Condition.ConditionIntvl) operator;
			try {
				switch (op.operator) {
				case IN:
					expr.removeAbove((int) op.max);
					expr.removeBelow((int) op.min);
					break;
				case NOTIN:
					BoolVar[] ltOrGt = new BoolVar[2];
					ltOrGt[0] = isLess(expr, (int) op.min);
					ltOrGt[1] = isLarger(expr, (int) op.max);
					minicp.post(or(ltOrGt));
					break;
				default:
					throw new InvalidParameterException("unknown condition");
				}
			} catch (InconsistencyException e) {
				hasFailed = true;
			}
		}
	}

	// UNARY PRIMITIVE CONSTRAINTS

	@Override
	public void buildCtrPrimitive(String id, XVarInteger x, Types.TypeConditionOperatorRel op, int k) {
		if (hasFailed)
			return;
		relConstraintVal(mapVar.get(x), op, k);
	}

	/*TODO
	@Override
	public void buildCtrPrimitive(String id, XVarInteger x, Types.TypeConditionOperatorRel op, int[] t) {
		if (hasFailed)
			return;

	}
	 */

	/*TODO
	@Override
	public void buildCtrPrimitive(String id, XVarInteger x, TypeConditionOperatorSet op, int min, int max) {
		if (hasFailed)
			return;

	}
	 */

	@Override
	public void buildCtrPrimitive(String id, XVarInteger x, Types.TypeArithmeticOperator aop, int p,
								  Types.TypeConditionOperatorRel op, int k) {
		if (hasFailed)
			return;

		try {
			IntVar r = arithmeticOperatorConstraintVal(mapVar.get(x), aop, p);
			relConstraintVal(r, op, k);
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	// BINARY PRIMITIVE CONSTRAINTS

	@Override
	public void buildCtrPrimitive(String id, XVarInteger x, Types.TypeUnaryArithmeticOperator aop, XVarInteger y) {
		if (hasFailed)
			return;
		try {
			IntVar r = unaryArithmeticOperatorConstraint(mapVar.get(y), aop);
			relConstraintVar(mapVar.get(x), Types.TypeConditionOperatorRel.EQ, r);
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrPrimitive(String id, XVarInteger x, Types.TypeArithmeticOperator aop, int p,
			Types.TypeConditionOperatorRel op, XVarInteger y) {
		if (hasFailed)
			return;
		try {
			IntVar r = arithmeticOperatorConstraintVal(mapVar.get(x), aop, p);
			relConstraintVar(r, op, mapVar.get(y));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrPrimitive(String id, XVarInteger x, Types.TypeArithmeticOperator aop, XVarInteger y,
			Types.TypeConditionOperatorRel op, int k) {
		if (hasFailed)
			return;

		IntVar minicpX = mapVar.get(x);
		IntVar minicpY = mapVar.get(y);

		try {
			IntVar r = arithmeticOperatorConstraintVar(minicpX, aop, minicpY);
			relConstraintVal(r, op, k);
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	// TERNARY PRIMITIVE CONSTRAINTS

	@Override
	public void buildCtrPrimitive(String id, XVarInteger x, Types.TypeArithmeticOperator aop, XVarInteger y,
			Types.TypeConditionOperatorRel op, XVarInteger z) {
		if (hasFailed)
			return;

		try {
			IntVar r = arithmeticOperatorConstraintVar(mapVar.get(x), aop, mapVar.get(y));
			relConstraintVar(r, op, mapVar.get(z));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}
	public void buildCtrLogic(String id, XVarInteger x, XVarInteger y, Types.TypeConditionOperatorRel op, int k) {
		if (hasFailed)
			return;
		try {
			minicp.post(equal(mapVar.get(x),reifiedRelOperatorConstraintVal(mapVar.get(y),op,k)));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	public void buildCtrLogic(String id, XVarInteger x, XVarInteger y, Types.TypeConditionOperatorRel op, XVarInteger z) {
		if (hasFailed)
			return;
		try {
			minicp.post(equal(mapVar.get(x),reifiedRelOperatorConstraintVar(mapVar.get(y),op,mapVar.get(z))));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	/* TODO
	public void buildCtrLogic(String id, TypeLogicalOperator lop, XVarInteger[] vars)
	public void buildCtrLogic(String id, XVarInteger x, TypeEqNeOperator op, TypeLogicalOperator lop, XVarInteger[] vars)
	 */

	private IntVar unaryArithmeticOperatorConstraint(IntVar x, Types.TypeUnaryArithmeticOperator aop) {
		switch (aop) {
			case NEG:
				return minus(x);
			case ABS:
				return abs(x);
			case SQR:
				return product(x,x);
			case NOT: //treat X as a Boolean variable
				x.removeBelow(0);
				x.removeAbove(1);
				return plus(minus(x),1); // not(x) = 1-x
			default:
			// Not needed
			throw new IllegalArgumentException("not implemented");
		}
	}

	private IntVar arithmeticOperatorConstraintVal(IntVar x, Types.TypeArithmeticOperator aop, int p) {
		switch (aop) {
		case ADD:
			return plus(x, p);
		case DIST:
			return abs(minus(x, p));
		case SUB:
			return minus(x, p);
		case MUL:
			return mul(x, p);
		case DIV:
			IntVar y = makeIntVar(minicp, (p>=0? (int) Math.floor(x.min()/p): (int) Math.floor(x.max()/p)), (p>=0? (int) Math.ceil(x.max()/p): (int) Math.ceil(x.min()/p)));
			minicp.post(equal(x, mul(y, p)));
			return y;
		case MOD:
			IntVar[] xs = new IntVar[2];
			xs[0] = x;
			IntVar yy = makeIntVar(minicp, 0, p-1);
			xs[1] = minus(yy);
			minicp.post(sumModP(xs, 0, p));
			return yy;
		case POW:
			return pow(x,makeIntVar(minicp,p,p));
		default:
			throw new IllegalArgumentException("Unknown TypeArithmeticOperator");
		}
	}

	private IntVar arithmeticOperatorConstraintVar(IntVar x, Types.TypeArithmeticOperator aop, IntVar y) {
		switch (aop) {
		case ADD:
			return sum(x, y);
		case DIST:
			return abs(sum(x, minus(y)));
		case SUB:
			return sum(x, minus(y));
		case MUL:
			return product(x, y);
		case DIV:
			return quotient(x, y);
		case MOD:
			return modulo(x, y);
		case POW:
			return pow(x, y);
		default:
			throw new IllegalArgumentException("Unknown TypeArithmeticOperator");
		}
	}
	private IntVar reifiedRelOperatorConstraintVar(IntVar x, Types.TypeConditionOperatorRel operator, IntVar y) {
		switch (operator) {
			case EQ:
				return isEqual(x, y);
			case GE:
				return isLargerOrEqual(x, y);
			case GT:
				return isLarger(x, y);
			case LE:
				return isLessOrEqual(x, y);
			case LT:
				return isLess(x, y);
			case NE:
				return isNotEqual(x, y);
			default:
				throw new InvalidParameterException("unknown condition");
		}
	}
	private IntVar reifiedRelOperatorConstraintVal(IntVar x, Types.TypeConditionOperatorRel operator, int y) {
		switch (operator) {
			case EQ:
				return isEqual(x, y);
			case GE:
				return isLargerOrEqual(x, y);
			case GT:
				return isLarger(x, y);
			case LE:
				return isLessOrEqual(x, y);
			case LT:
				return isLess(x, y);
			case NE:
				return isNotEqual(x, y);
			default:
				throw new InvalidParameterException("unknown condition");
		}
	}

	// logic truth tables
	int [][] tableAND = { {0,0,0}, {0,1,0}, {1,0,0}, {1,1,1}};
	int [][] tableOR = { {0,0,0}, {0,1,1}, {1,0,1}, {1,1,1}};
	int [][] tableXOR = { {0,0,0}, {0,1,1}, {1,0,1}, {1,1,0}};
	int [][] tableIFF = { {0,0,1}, {0,1,0}, {1,0,0}, {1,1,1}};
	int [][] tableIMP = { {0,0,1}, {0,1,1}, {1,0,0}, {1,1,1}};

	private IntVar reifiedLogOperatorConstraint(IntVar x, Types.TypeLogicalOperator operator, IntVar y) {
		IntVar z = makeIntVar(minicp,0,1);
		switch (operator) {
			case AND:
				minicp.post(table(new IntVar[]{x,y,z},tableAND));
				return z;
			case OR:
				minicp.post(table(new IntVar[]{x,y,z},tableOR));
				return z;
			case XOR:
				minicp.post(table(new IntVar[]{x,y,z},tableXOR));
				return z;
			case IFF:
				minicp.post(table(new IntVar[]{x,y,z},tableIFF));
				return z;
			case IMP:
				minicp.post(table(new IntVar[]{x,y,z},tableIMP));
				return z;
			default:
				throw new InvalidParameterException("unknown condition");
		}
	}

	private IntVar parseExpr(XNode<XVarInteger> tree) {
//		System.out.println(tree.toString() + " op: " + tree.type + " arity: " + tree.arity());
		Types.TypeExpr type = tree.type;
		switch (tree.arity()) {
			case 0:
				if (type == Types.TypeExpr.VAR) {
//					System.out.println("in var");
					return mapVar.get(tree.var(0));
				} else if (type == Types.TypeExpr.LONG) {
//					System.out.println("in val");
					int val = tree.val(0);
//					System.out.println("had to turn val into var in expression tree (shouldn't happen?)");
					return makeIntVar(minicp, val, val);
				} else
					throw new IllegalArgumentException("expression tree leaf that isn't a var nor a value?");
			case 1:
				return unaryArithmeticOperatorConstraint(parseExpr(tree.sons[0]), type.toUnalop());
			case 2:
//				System.out.println("in binary op");
				if (type.isRelationalOperator()) {
//					System.out.println("in relop");
					if (tree.sons[1].type == Types.TypeExpr.LONG) {
//						System.out.println("in rel with val; type is " + type);
						return reifiedRelOperatorConstraintVal(parseExpr(tree.sons[0]), type.toRelop(), tree.sons[1].val(0));
					} else {
						return reifiedRelOperatorConstraintVar(parseExpr(tree.sons[0]), type.toRelop(), parseExpr(tree.sons[1]));
					}
				} else if (type.isArithmeticOperator()) {
					if (tree.sons[1].type == Types.TypeExpr.LONG) {
//						System.out.println("in arith with val; type is " + type);
						return arithmeticOperatorConstraintVal(parseExpr(tree.sons[0]), type.toAriop(), tree.sons[1].val(0));
					} else {
						return arithmeticOperatorConstraintVar(parseExpr(tree.sons[0]), type.toAriop(), parseExpr(tree.sons[1]));
					}
				} else if (type.isLogicalOperator()) {
					return reifiedLogOperatorConstraint(parseExpr(tree.sons[0]), type.toLogop(), parseExpr(tree.sons[1]));
				} else if (type == Types.TypeExpr.MAX) {
					return maximum(new IntVar[]{parseExpr(tree.sons[0]),parseExpr(tree.sons[1])});
				}else if (type == Types.TypeExpr.MIN) {
					return minimum(new IntVar[]{parseExpr(tree.sons[0]),parseExpr(tree.sons[1])});
				} else
					throw new IllegalArgumentException("unsupported expression-tree binary node");
			default:
				if (type == Types.TypeExpr.MUL) {
					IntVar cumul = parseExpr(tree.sons[0]);
					for (int i = 1; i < tree.arity(); i++) {
						cumul = product(cumul, parseExpr(tree.sons[i]));
					}
					return cumul;
				}
				if (type == Types.TypeExpr.ADD) {
					IntVar[] children = new IntVar[tree.arity()];
					for (int i = 0; i < tree.arity(); i++) {
						children[i] = parseExpr(tree.sons[i]);
					}
					return sum(children);
				}
				if (type == Types.TypeExpr.MAX) {
					IntVar[] children = new IntVar[tree.arity()];
					for (int i = 0; i < tree.arity(); i++) {
						children[i] = parseExpr(tree.sons[i]);
					}
					return maximum(children);
				}
				if (type == Types.TypeExpr.MIN) {
					IntVar[] children = new IntVar[tree.arity()];
					for (int i = 0; i < tree.arity(); i++) {
						children[i] = parseExpr(tree.sons[i]);
					}
					return minimum(children);
				}
				throw new IllegalArgumentException("not implemented");
		}
	}

	@Override
	public void buildCtrSum(String id, XVarInteger[] list, Condition condition) {
		if (hasFailed)
			return;
		try {
			if ((condition instanceof Condition.ConditionVar) &&
					(((Condition.ConditionVar) condition).operator == Types.TypeConditionOperatorRel.EQ)) {
				// special case of equality with a variable: avoid creating an intermediate variable
				minicp.post(sum(mapVarArray(list),mapVar.get(((Condition.ConditionVar) condition).x)));
			} else {
				IntVar s = sum(mapVarArray(list));
				buildCrtWithCondition(id, s, condition);
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrSum(String id, XNode<XVarInteger>[] trees, Condition condition) {
		if (hasFailed)
			return;

		try {
			IntVar[] wx = new IntVar[trees.length];
			for (int i = 0; i < trees.length; i++) {
				wx[i] = parseExpr(trees[i]);
			}
			if ((condition instanceof Condition.ConditionVar) &&
					(((Condition.ConditionVar) condition).operator == Types.TypeConditionOperatorRel.EQ)) {
				// special case of equality with a variable: avoid creating an intermediate variable
				minicp.post(sum(wx,mapVar.get(((Condition.ConditionVar) condition).x)));
			} else {
				IntVar s = sum(wx);
				buildCrtWithCondition(id, s, condition);
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrSum(String id, XVarInteger[] list, int[] coeffs, Condition condition) {
		if (hasFailed)
			return;
		try {
			IntVar[] wx = new IntVar[list.length];
			for (int i = 0; i < list.length; i++) {
				wx[i] = mul(mapVar.get(list[i]), coeffs[i]);
			}
			if ((condition instanceof Condition.ConditionVar) &&
					(((Condition.ConditionVar) condition).operator == Types.TypeConditionOperatorRel.EQ)) {
				// special case of equality with a variable: avoid creating an intermediate variable
				minicp.post(sum(wx,mapVar.get(((Condition.ConditionVar) condition).x)));
			} else {
				IntVar s = sum(wx);
				buildCrtWithCondition(id, s, condition);
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrSum(String id, XNode<XVarInteger>[] trees, int[] coeffs, Condition condition) {
		if (hasFailed)
			return;
		try {
			IntVar[] wx = new IntVar[trees.length];
			for (int i = 0; i < trees.length; i++) {
				wx[i] = mul(parseExpr(trees[i]), coeffs[i]);
			}
			if ((condition instanceof Condition.ConditionVar) &&
					(((Condition.ConditionVar) condition).operator == Types.TypeConditionOperatorRel.EQ)) {
				// special case of equality with a variable: avoid creating an intermediate variable
				minicp.post(sum(wx,mapVar.get(((Condition.ConditionVar) condition).x)));
			} else {
				IntVar s = sum(wx);
				buildCrtWithCondition(id, s, condition);
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrSum(String id, XVarInteger[] list, XVarInteger[] coeffs, Condition condition) {
		if (hasFailed)
			return;
		try {
			IntVar[] wx = new IntVar[list.length];
			for (int i = 0; i < list.length; i++) {
				wx[i] = product(mapVar.get(list[i]), mapVar.get(coeffs[i]));
			}
			if ((condition instanceof Condition.ConditionVar) &&
					(((Condition.ConditionVar) condition).operator == Types.TypeConditionOperatorRel.EQ)) {
				// special case of equality with a variable: avoid creating an intermediate variable
				minicp.post(sum(wx,mapVar.get(((Condition.ConditionVar) condition).x)));
			} else {
				IntVar s = sum(wx);
				buildCrtWithCondition(id, s, condition);
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrSum(String id, XNode<XVarInteger>[] trees, XVarInteger[] coeffs, Condition condition) {
		if (hasFailed)
			return;
		try {
			IntVar[] wx = new IntVar[trees.length];
			for (int i = 0; i < trees.length; i++) {
				wx[i] = product(parseExpr(trees[i]), mapVar.get(coeffs[i]));
			}
			if ((condition instanceof Condition.ConditionVar) &&
					(((Condition.ConditionVar) condition).operator == Types.TypeConditionOperatorRel.EQ)) {
				// special case of equality with a variable: avoid creating an intermediate variable
				minicp.post(sum(wx,mapVar.get(((Condition.ConditionVar) condition).x)));
			} else {
				IntVar s = sum(wx);
				buildCrtWithCondition(id, s, condition);
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrAllDifferent(String id, XVarInteger[] list) {
		if (hasFailed)
			return;
		// Constraints
		try {
			IntVar[] xs = mapVarArray(list);
			minicp.post(allDifferent(xs));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrAllDifferent(String id, XNode<XVarInteger>[] trees) {
		if (hasFailed)
			return;
		try {
			IntVar[] xs = new IntVar[trees.length];
			for (int i=0; i<trees.length; i++) {
				xs[i] = parseExpr(trees[i]);
			}
			minicp.post(allDifferent(xs));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	// TODO: buildCtrAllDifferentExcept

	@Override
	public void buildCtrAllDifferentMatrix(String id, XVarInteger[][] matrix) {
		if (hasFailed)
			return;
		// Constraints
		try {
			for (XVarInteger[] list : matrix) {
				IntVar[] xs = mapVarArray(list);
				minicp.post(allDifferent(xs));
			}
			XVarInteger[][] tmatrix = transpose(matrix);
			for (XVarInteger[] list : tmatrix) {
				IntVar[] xs = mapVarArray(list);
				minicp.post(allDifferent(xs));
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrAllEqual(String id, XVarInteger[] list) {
		if (hasFailed)
			return;
		try {
			IntVar[] xs = mapVarArray(list);
			for (int i = 1; i < list.length; i++) {
				minicp.post(equal(xs[0],xs[i]));
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrAllEqual(String id, XNode<XVarInteger>[] trees) {
		if (hasFailed)
			return;
		try {
			IntVar[] xs = Arrays.stream(trees).map(this::parseExpr).toArray(IntVar[]::new);
			for (int i = 1; i < trees.length; i++) {
				minicp.post(equal(xs[0],xs[i]));
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	// TODO: buildCtrNotAllEqual(String id, XVarInteger[] list)

	@Override
	public void buildCtrOrdered(String id, XVarInteger[] list, Types.TypeOperatorRel operator) {
		if (hasFailed)
			return;
		try {
			IntVar[] xs = mapVarArray(list);
			switch (operator) {
				case GE:
					for (int i = 0; i < xs.length-1; i++)
						minicp.post(lessOrEqual(xs[i+1], xs[i]));
					break;
				case GT:
					for (int i = 0; i < xs.length-1; i++)
						minicp.post(less(xs[i+1], xs[i]));
					break;
				case LE:
					for (int i = 0; i < xs.length-1; i++)
						minicp.post(lessOrEqual(xs[i], xs[i+1]));
					break;
				case LT:
					for (int i = 0; i < xs.length-1; i++)
						minicp.post(less(xs[i], xs[i+1]));
					break;
				default:
					throw new InvalidParameterException("inappropriate condition");
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrOrdered(String id, XVarInteger[] list, int[] lengths, Types.TypeOperatorRel operator) {
		if (hasFailed)
			return;
		try {
			IntVar[] xs = mapVarArray(list);
			switch (operator) {
				case GE:
					for (int i = 0; i < xs.length-1; i++)
						minicp.post(lessOrEqual(xs[i+1], plus(xs[i],lengths[i])));
					break;
				case GT:
					for (int i = 0; i < xs.length-1; i++)
						minicp.post(less(xs[i+1], plus(xs[i],lengths[i])));
					break;
				case LE:
					for (int i = 0; i < xs.length-1; i++)
						minicp.post(lessOrEqual(plus(xs[i],lengths[i]), xs[i+1]));
					break;
				case LT:
					for (int i = 0; i < xs.length-1; i++)
						minicp.post(less(plus(xs[i],lengths[i]), xs[i+1]));
					break;
				default:
					throw new InvalidParameterException("inappropriate condition");
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	// TODO: public void buildCtrOrdered(String id, XVarInteger[] list, XVarInteger[] lengths, TypeOperatorRel operator)

	@Override
	public void buildCtrLex(String id, XVarInteger[][] lists, Types.TypeOperatorRel operator) {
		if (hasFailed)
			return;
		try {
			IntVar[][] xs = new IntVar[lists.length][];
			int i = 0;
			for (XVarInteger[] list : lists) {
				xs[i++] = mapVarArray(list);
			}
			switch (operator) {
				case GE:
					for (i = 0; i < lists.length-1; i++)
						minicp.post(lexLessOrEqual(xs[i+1], xs[i]));
					break;
				case GT:
					for (i = 0; i < lists.length-1; i++)
						minicp.post(lexLess(xs[i+1], xs[i]));
					break;
				case LE:
					for (i = 0; i < lists.length-1; i++)
						minicp.post(lexLessOrEqual(xs[i], xs[i+1]));
					break;
				case LT:
					for (i = 0; i < lists.length-1; i++)
						minicp.post(lexLess(xs[i], xs[i+1]));
					break;
				default:
					throw new InvalidParameterException("inappropriate condition");
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrLexMatrix(String id, XVarInteger[][] matrix, Types.TypeOperatorRel operator) {
		if (hasFailed)
			return;
		try {
			IntVar[][] rows = new IntVar[matrix.length][];
			int i = 0;
			for (XVarInteger[] list : matrix) {
				rows[i++] = mapVarArray(list);
			}
			XVarInteger[][] tmatrix = transpose(matrix);
			IntVar[][] cols = new IntVar[tmatrix.length][];
			i = 0;
			for (XVarInteger[] list : tmatrix) {
				cols[i++] = mapVarArray(list);
			}
			switch (operator) {
				case GE:
					for (i = 0; i < rows.length-1; i++)
						minicp.post(lexLessOrEqual(rows[i+1], rows[i]));
					for (i = 0; i < cols.length-1; i++)
						minicp.post(lexLessOrEqual(cols[i+1], cols[i]));
					break;
				case GT:
					for (i = 0; i < rows.length-1; i++)
						minicp.post(lexLess(rows[i+1], rows[i]));
					for (i = 0; i < cols.length-1; i++)
						minicp.post(lexLess(cols[i+1], cols[i]));
					break;
				case LE:
					for (i = 0; i < rows.length-1; i++)
						minicp.post(lexLessOrEqual(rows[i], rows[i+1]));
					for (i = 0; i < cols.length-1; i++)
						minicp.post(lexLessOrEqual(cols[i], cols[i+1]));
					break;
				case LT:
					for (i = 0; i < rows.length-1; i++)
						minicp.post(lexLess(rows[i], rows[i+1]));
					for (i = 0; i < cols.length-1; i++)
						minicp.post(lexLess(cols[i], cols[i+1]));
					break;
				default:
					throw new InvalidParameterException("inappropriate condition");
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrPrecedence(String id, XVarInteger[] list) {
		if (hasFailed)
			return;
		IntVar[] xs = mapVarArray(list);
		SortedSet<Integer> values = new TreeSet<Integer>();
		for (int i = 0; i < xs.length; i++)
			for (int j = xs[i].min(); j <= xs[i].max(); j++)
				if (xs[i].contains(j)) {
					values.add(j);
				}
		buildCtrPrecedence(id,list,values.stream().mapToInt(i->i).toArray(),false);
	}

	@Override
	public void buildCtrPrecedence(String id, XVarInteger[] list, int[] values, boolean covered) {
		if (hasFailed)
			return;
		try {
			IntVar[] xs = mapVarArray(list);
			int minVal = xs[0].min();
			int maxVal = xs[0].max();
			for (IntVar x : xs) {
				if (x.min() < minVal)
					minVal = x.min();
				if (x.max() > maxVal)
					maxVal = x.max();
			}
			int[][] A = new int[values.length+1][maxVal-minVal+1];
			// TODO: potentially wasteful in terms of space; define A over union of domains only
			for (int i = 0; i <= values.length; i++) {
				for (int j = 0; j <= maxVal-minVal; j++) {
					A[i][j] = i; // loop by default
				}
				if (i < values.length) A[i][values[i]] = i+1; // first occurrence of values[i]
				for (int j = i+1; j < values.length; j++) {
					A[i][values[j]] = -1; // cannot be used yet
				}
			}
			if (covered) {
				List<Integer> f = new ArrayList<Integer>();
				f.add(values.length);
				minicp.post(regular(xs,A,f));
			}else {
				minicp.post(regular(xs,A));
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	// the code of transpose method is from choco solver:
	// https://tinyurl.com/tlgz7mt
	/**
	 * Transposes a matrix M[n][m] in a matrix M<sup>T</sup>[m][n] such that
	 * M<sup>T</sup>[i][j] = M[j][i]
	 * 
	 * @param matrix matrix to transpose
	 * @param        <T> the class of the objects in the input matrix
	 * @return a matrix
	 */
	@SuppressWarnings("unchecked")
	public static <T> T[][] transpose(T[][] matrix) {
		T[][] ret = (T[][]) newInstance(matrix.getClass().getComponentType(), matrix[0].length);
		for (int i = 0; i < ret.length; i++) {
			ret[i] = (T[]) newInstance(matrix[0].getClass().getComponentType(), matrix.length);
		}

		for (int i = 0; i < matrix.length; i++)
			for (int j = 0; j < matrix[i].length; j++)
				ret[j][i] = matrix[i][j];

		return ret;

	}

	@Override
	public void buildCtrElement(String id, XVarInteger[] list, Condition cond) {
		if(hasFailed)
			return;
		try {
			IntVar[] xs = mapVarArray(list);
			if (cond instanceof Condition.ConditionVal) {
				int val = (int) ((Condition.ConditionVal) cond).k;
				minicp.post(atleast(xs, val, 1));
			} else if (cond instanceof Condition.ConditionVar) {
				IntVar z = mapVar.get(((Condition.ConditionVar) cond).x);
				IntVar y = makeIntVar(minicp, 0, xs.length-1);
				minicp.post(element(xs, y, z));
			}
			else {
				System.out.println("c Element constraint with an unsupported condition");
				System.exit(1);
			}
		} catch(InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrElement(String id, XVarInteger[] list, int startIndex, XVarInteger index, Types.TypeRank rank, Condition cond) {
		if (hasFailed)
			return;
		try {
			IntVar[] xs = mapVarArray(list);
			IntVar y = minus(mapVar.get(index),startIndex);
			switch(rank) {
				case ANY:
					if (cond instanceof Condition.ConditionVal) {
						int val = (int) ((Condition.ConditionVal) cond).k;
						Types.TypeConditionOperatorRel op = ((Condition.ConditionVal) cond).operator;
						relConstraintVal(element(xs, y), op, val);
					} else if (cond instanceof Condition.ConditionVar) {
						IntVar z = mapVar.get(((Condition.ConditionVar) cond).x);
						Types.TypeConditionOperatorRel op = ((Condition.ConditionVar) cond).operator;
						relConstraintVar(element(xs, y), op, z);
					}
					else {
						System.out.println("c Element constraint with an unsupported condition");
						System.exit(1);
					}
					break;
				case FIRST:
					System.out.println("c Ranking type for Element Constraint is not supported yet");
					System.exit(1);
					break;
				case LAST:
					System.out.println("c Ranking type for Element Constraint is not supported yet");
					System.exit(1);
					break;
			}
		} catch(InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrElement(String id, int[] list, int startIndex, XVarInteger index, Types.TypeRank rank, Condition cond) {
		if (hasFailed)
			return;
		try {
			IntVar y = minus(mapVar.get(index),startIndex);
			switch(rank) {
				case ANY:
					if (cond instanceof Condition.ConditionVal) {
						int val = (int) ((Condition.ConditionVal) cond).k;
						Types.TypeConditionOperatorRel op = ((Condition.ConditionVal) cond).operator;
						relConstraintVal(element(list, y), op, val);
					} else if (cond instanceof Condition.ConditionVar) {
						IntVar z = mapVar.get(((Condition.ConditionVar) cond).x);
						Types.TypeConditionOperatorRel op = ((Condition.ConditionVar) cond).operator;
						relConstraintVar(element(list, y), op, z);
					}
					else {
						System.out.println("c Element constraint with an unsupported condition");
						System.exit(1);
					}
					break;
				case FIRST:
					System.out.println("c Ranking type for Element Constraint is not supported yet");
					System.exit(1);
					break;
				case LAST:
					System.out.println("c Ranking type for Element Constraint is not supported yet");
					System.exit(1);
					break;
			}
		} catch(InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrElement(String id, int[][] matrix, int startRowIndex, XVarInteger rowIndex, int startColIndex, XVarInteger colIndex,
								 Condition cond) {
		if (hasFailed)
			return;
		try {
			IntVar x = minus(mapVar.get(rowIndex),startRowIndex);
			IntVar y = minus(mapVar.get(colIndex),startColIndex);
			if (cond instanceof Condition.ConditionVal) {
				int val = (int) ((Condition.ConditionVal) cond).k;
				Types.TypeConditionOperatorRel op = ((Condition.ConditionVal) cond).operator;
				relConstraintVal(element(matrix, x, y), op, val);
			} else if (cond instanceof Condition.ConditionVar) {
				IntVar z = mapVar.get(((Condition.ConditionVar) cond).x);
				Types.TypeConditionOperatorRel op = ((Condition.ConditionVar) cond).operator;
				relConstraintVar(element(matrix, x, y), op, z);
			}
			else {
				System.out.println("c Element constraint with an unsupported condition");
				System.exit(1);
			}
		} catch(InconsistencyException e) {
			hasFailed = true;
		}
	}

	private void setObj(IntVar obj, boolean minimization) {
		IntVar minobj = minimization ? obj : minus(obj);

		objectiveMinimize = Optional.of(minobj);
		realObjective = Optional.of(obj);
	}

	@Override
	public void buildObjToMinimize(String id, XVarInteger x) {
		if (hasFailed)
			return;

		setObj(mapVar.get(x), true);
	}

	@Override
	public void buildObjToMinimize(String id, Types.TypeObjective type, XVarInteger[] list) {
		if (hasFailed)
			return;
		try {
			if (type == Types.TypeObjective.MAXIMUM) {
				IntVar[] xs = mapVarArray(list);
				setObj(maximum(xs), true);
			} else if (type == Types.TypeObjective.MINIMUM) {
				IntVar[] xs = mapVarArray(list);
				setObj(minimum(xs), true);
			} else if (type == Types.TypeObjective.SUM) {
				IntVar s = sum(mapVarArray(list));
				setObj(s, true);
			} else {
				throw new NotImplementedException();
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildObjToMinimize(String id, Types.TypeObjective type, XVarInteger[] list, int[] coeffs) {
		if (hasFailed)
			return;

		IntVar[] wx = new IntVar[list.length];
		for (int i = 0; i < list.length; i++) {
			wx[i] = mul(mapVar.get(list[i]), coeffs[i]);
		}
		try {
			IntVar s = sum(wx);
			setObj(s, true);
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildObjToMaximize(String id, XVarInteger x) {
		if (hasFailed)
			return;

		setObj(mapVar.get(x), false);
	}

	@Override
	public void buildObjToMaximize(String id, Types.TypeObjective type, XVarInteger[] list) {
		if (hasFailed)
			return;

		try {
			if (type == Types.TypeObjective.MAXIMUM) {
				IntVar[] xs = mapVarArray(list);
				setObj(maximum(xs), false);
			} else if (type == Types.TypeObjective.MINIMUM) {
				IntVar[] xs = mapVarArray(list);
				setObj(minimum(xs), false);
			} else if (type == Types.TypeObjective.SUM) {
				IntVar s = sum(mapVarArray(list));
				setObj(s, false);
			} else {
				throw new NotImplementedException();
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildObjToMaximize(String id, Types.TypeObjective type, XVarInteger[] list, int[] coeffs) {
		if (hasFailed)
			return;

		IntVar[] wx = new IntVar[list.length];
		for (int i = 0; i < list.length; i++) {
			wx[i] = mul(mapVar.get(list[i]), coeffs[i]);
		}
		try {
			IntVar s = sum(wx);
			setObj(s, false);
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrIntension(String id, XVarInteger[] scope, XNodeParent<XVarInteger> tree) {
		if (hasFailed)
			return;
		// TODO: catch cases not covered by unary/binary/ternary primitives
		throw new NotImplementedException();
	}

	@Override
	public void buildCtrMaximum(String id, XVarInteger[] list, Condition condition) {
		if (hasFailed)
			return;
		try {
			IntVar[] xs = mapVarArray(list);
			if ((condition instanceof Condition.ConditionVar) &&
					(((Condition.ConditionVar) condition).operator == Types.TypeConditionOperatorRel.EQ)) {
				// special case of equality with a variable: avoid creating an intermediate variable
				minicp.post(maximum(xs,mapVar.get(((Condition.ConditionVar) condition).x)));
			} else {
				IntVar s = maximum(xs);
				buildCrtWithCondition(id, s, condition);
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrMaximum(String id, XNode<XVarInteger>[] trees, Condition condition) {
		if (hasFailed)
			return;
		try {
			IntVar[] xs = new IntVar[trees.length];
			for (int i=0; i<trees.length; i++) {
				xs[i] = parseExpr(trees[i]);
			}
			if ((condition instanceof Condition.ConditionVar) &&
					(((Condition.ConditionVar) condition).operator == Types.TypeConditionOperatorRel.EQ)) {
				// special case of equality with a variable: avoid creating an intermediate variable
				minicp.post(maximum(xs,mapVar.get(((Condition.ConditionVar) condition).x)));
			} else {
				IntVar s = maximum(xs);
				buildCrtWithCondition(id, s, condition);
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrMinimum(String id, XVarInteger[] list, Condition condition) {
		if (hasFailed)
			return;
		try {
			IntVar[] xs = mapVarArray(list);
			if ((condition instanceof Condition.ConditionVar) &&
					(((Condition.ConditionVar) condition).operator == Types.TypeConditionOperatorRel.EQ)) {
				// special case of equality with a variable: avoid creating an intermediate variable
				minicp.post(minimum(xs,mapVar.get(((Condition.ConditionVar) condition).x)));
			} else {
				IntVar s = Factory.minimum(xs);
				buildCrtWithCondition(id, s, condition);
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrMinimum(String id, XNode<XVarInteger>[] trees, Condition condition) {
		if (hasFailed)
			return;
		try {
			IntVar[] xs = new IntVar[trees.length];
			for (int i=0; i<trees.length; i++) {
				xs[i] = parseExpr(trees[i]);
			}
			if ((condition instanceof Condition.ConditionVar) &&
					(((Condition.ConditionVar) condition).operator == Types.TypeConditionOperatorRel.EQ)) {
				// special case of equality with a variable: avoid creating an intermediate variable
				minicp.post(minimum(xs,mapVar.get(((Condition.ConditionVar) condition).x)));
			} else {
				IntVar s = minimum(xs);
				buildCrtWithCondition(id, s, condition);
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrCount(String id, XVarInteger[] list, int[] values, Condition condition) {
		if (hasFailed)
			return;
		try {
			Types.TypeConditionOperatorRel operator = null;
			if (condition instanceof Condition.ConditionVar) {
				IntVar countVar = mapVar.get(((Condition.ConditionVar) condition).x);
				operator = ((Condition.ConditionVar) condition).operator;
				IntVar[] xs = mapVarArray(list);
				IntVar occ = makeIntVar(minicp,0,xs.length);
				minicp.post(among(xs,values,occ));
				relConstraintVar(occ,operator,countVar);
			} else if (condition instanceof Condition.ConditionVal) {
				int countVal = (int) ((Condition.ConditionVal) condition).k;
				operator = ((Condition.ConditionVal) condition).operator;
				IntVar[] xs = mapVarArray(list);
				switch (operator) {
					case EQ:
						minicp.post(exactly(xs,values,countVal));
						break;
					case GE:
						minicp.post(atleast(xs,values,countVal));
						break;
					case GT:
						minicp.post(atleast(xs,values,countVal+1));
						break;
					case LE:
						minicp.post(atmost(xs,values,countVal));
						break;
					case LT:
						minicp.post(atmost(xs,values,countVal-1));
						break;
					case NE:
						IntVar occ = makeIntVar(minicp,0,xs.length);
						occ.remove(countVal);
						minicp.post(among(xs,values,occ));
						break;
					default:
						throw new InvalidParameterException("unknown condition");
				}
			} else {
				throw new InvalidParameterException("unknown condition");
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrCount(String id, XNode<XVarInteger>[] trees, int[] values, Condition condition) {
		if (hasFailed)
			return;
		try {
			Types.TypeConditionOperatorRel operator = null;
			if (condition instanceof Condition.ConditionVar) {
				IntVar countVar = mapVar.get(((Condition.ConditionVar) condition).x);
				operator = ((Condition.ConditionVar) condition).operator;
				IntVar[] xs = new IntVar[trees.length];
				for (int i = 0; i < trees.length; i++) {
					xs[i] = parseExpr(trees[i]);
				}
				IntVar occ = makeIntVar(minicp,0,xs.length);
				minicp.post(among(xs,values,occ));
				relConstraintVar(occ,operator,countVar);
			} else if (condition instanceof Condition.ConditionVal) {
				int countVal = (int) ((Condition.ConditionVal) condition).k;
				operator = ((Condition.ConditionVal) condition).operator;
				IntVar[] xs = new IntVar[trees.length];
				for (int i = 0; i < trees.length; i++) {
					xs[i] = parseExpr(trees[i]);
				}
				switch (operator) {
					case EQ:
						minicp.post(exactly(xs,values,countVal));
						break;
					case GE:
						minicp.post(atleast(xs,values,countVal));
						break;
					case GT:
						minicp.post(atleast(xs,values,countVal+1));
						break;
					case LE:
						minicp.post(atmost(xs,values,countVal));
						break;
					case LT:
						minicp.post(atmost(xs,values,countVal-1));
						break;
					case NE:
						IntVar occ = makeIntVar(minicp,0,xs.length);
						occ.remove(countVal);
						minicp.post(among(xs,values,occ));
						break;
					default:
						throw new InvalidParameterException("unknown condition");
				}
			} else {
				throw new InvalidParameterException("unknown condition");
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}
/* TODO?
	@Override
	public void buildCtrCount(String id, XVarInteger[] list, XVarInteger[] values, Condition condition) {
	}
*/
	@Override
	public void buildCtrAtLeast(String id, XVarInteger[] list, int value, int k) {
		if (hasFailed)
			return;
		try {
			IntVar[] xs = mapVarArray(list);
			minicp.post(atleast(xs, value, k));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrAtMost(String id, XVarInteger[] list, int value, int k) {
		if (hasFailed)
			return;
		try {
			IntVar[] xs = mapVarArray(list);
			minicp.post(atmost(xs, value, k));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrExactly(String id, XVarInteger[] list, int value, int k) {
		if (hasFailed)
			return;
		try {
			IntVar[] xs = mapVarArray(list);
			minicp.post(exactly(xs, value, k));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrExactly(String id, XVarInteger[] list, int value, XVarInteger k) {
		if (hasFailed)
			return;
		try {
			IntVar[] xs = mapVarArray(list);
			minicp.post(among(xs, value, mapVar.get(k)));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrAmong(String id, XVarInteger[] list, int[] values, int k) {
		if (hasFailed)
			return;
		try {
			IntVar[] xs = mapVarArray(list);
			minicp.post(exactly(xs, values, k));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrAmong(String id, XVarInteger[] list, int[] values, XVarInteger k) {
		if (hasFailed)
			return;
		try {
			IntVar[] xs = mapVarArray(list);
			minicp.post(among(xs, values, mapVar.get(k)));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrCardinality(String id, XVarInteger[] list, boolean closed, int[] values, XVarInteger[] occurs) {
		if (hasFailed)
			return;
		try {
			if (closed)
				throw new NotImplementedException();
			IntVar[] xs = mapVarArray(list);
			IntVar[] os = mapVarArray(occurs);
			minicp.post(cardinality(xs, values, os));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrCardinality(String id, XVarInteger[] list, boolean closed, int[] values, int[] occurs) {
		if (hasFailed)
			return;
		try {
			if (closed)
				throw new NotImplementedException();
			IntVar[] xs = mapVarArray(list);
			minicp.post(cardinality(xs, values, occurs));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrCardinality(String id, XVarInteger[] list, boolean closed, int[] values, int[] occursMin,
			int[] occursMax) {
		if (hasFailed)
			return;
		try {
			if (closed)
				throw new NotImplementedException();
			IntVar[] xs = mapVarArray(list);
			minicp.post(cardinality(xs, values, occursMin, occursMax));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrNValues(String id, XVarInteger[] list, Condition condition) {
		if (hasFailed)
			return;
		try {
			IntVar[] xs = mapVarArray(list);
			if ((condition instanceof Condition.ConditionVar) &&
					(((Condition.ConditionVar) condition).operator == Types.TypeConditionOperatorRel.EQ)) {
				// special case of equality with a variable: avoid creating an intermediate variable
				minicp.post(nValues(xs,mapVar.get(((Condition.ConditionVar) condition).x)));
			} else {
				IntVar nDistinct = makeIntVar(minicp, 0, xs.length);
				minicp.post(nValues(xs, nDistinct));
				buildCrtWithCondition(id, nDistinct, condition);
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	/* TODO?
	@Override
	public void buildCtrNValuesExcept(String id, XVarInteger[] list, int[] except, Condition condition) {
	}
*/

	@Override
	public void buildCtrNValues(String id, XNode<XVarInteger>[] trees, Condition condition) {
		if (hasFailed)
			return;
		try {
			IntVar[] xs = new IntVar[trees.length];
			for (int i=0; i<trees.length; i++) {
				xs[i] = parseExpr(trees[i]);
			}
			if ((condition instanceof Condition.ConditionVar) &&
					(((Condition.ConditionVar) condition).operator == Types.TypeConditionOperatorRel.EQ)) {
				// special case of equality with a variable: avoid creating an intermediate variable
				minicp.post(nValues(xs,mapVar.get(((Condition.ConditionVar) condition).x)));
			} else {
				IntVar nDistinct = makeIntVar(minicp, 0, xs.length);
				minicp.post(nValues(xs, nDistinct));
				buildCrtWithCondition(id, nDistinct, condition);
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrInstantiation(String id, XVarInteger[] list, int[] values) {
		if (hasFailed)
			return;
		try {
			IntVar[] xs = mapVarArray(list);
			assert (xs.length == values.length);
			for (int i = 0; i < xs.length; i++)
				xs[i].assign(values[i]);
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrClause(String id, XVarInteger[] pos, XVarInteger[] neg) {
		if (hasFailed)
			return;
		try {
			IntVar[] xpos = mapVarArray(pos);
			IntVar[] xneg = mapVarArray(neg);
			BoolVar[] literals = new BoolVar[xpos.length + xneg.length];
			for (int i = 0; i < xpos.length; i++) {
				literals[i] = makeBoolVar(minicp);
				minicp.post(equal(literals[i],xpos[i]));
			}
			for (int i = 0; i < xneg.length; i++) {
				literals[xpos.length + i] = makeBoolVar(minicp);
				minicp.post(equal(not(literals[xpos.length + i]),xneg[i]));
			}
			minicp.post(or(literals));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrChannel(String id, XVarInteger[] list, int startIndex) {
		if (hasFailed)
			return;
		try {
			IntVar[] x = mapVarArray(list);
			minicp.post(inverse(x,x,startIndex));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrChannel(String id, XVarInteger[] list1, int startIndex1, XVarInteger[] list2, int startIndex2) {
		if (hasFailed)
			return;
		if (startIndex1 != startIndex2) {
			System.out.println("c Channel constraint with two different start indices?!?");
			System.exit(1);
		}
		try {
			IntVar[] x1 = mapVarArray(list1);
			IntVar[] x2 = mapVarArray(list2);
			minicp.post(inverse(x1,x2,startIndex1));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrChannel(String id, XVarInteger[] list, int startIndex, XVarInteger value) {
		if (hasFailed)
			return;
		try {
			IntVar[] x = mapVarArray(list); // assumes this is an array of 0-1 vara
			IntVar v = (startIndex == 0 ? mapVar.get(value) : new IntVarViewOffset(mapVar.get(value), -startIndex));
			minicp.post(element(x,v,1));
			minicp.post(sum(x,1));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrRegular(String id, XVarInteger[] list, Transition[] transitions, String startState,
								String[] finalStates) {
		if (hasFailed)
			return;
		try {
			IntVar[] x = mapVarArray(list);

			int minVal = x[0].min();
			int maxVal = x[0].max();
			for (IntVar y : x) {
				if (y.min() < minVal)
					minVal = y.min();
				if (y.max() > maxVal)
					maxVal = y.max();
			}

			// create var view of x with domain starting at zero (regular constraint currently requires this)
			IntVar[] x_withDomStartingAtZero = new IntVar[x.length];
			for (int i = 0; i < x.length; i++) {
				x_withDomStartingAtZero[i] = minus(x[i], minVal);
			}

			Map<String, Integer> stateMap = new HashMap<String, Integer>();

			ArrayList<int[]> A0 = new ArrayList<int[]>();
			for (Transition tr : transitions) {
				String from = (String) tr.start;
				if (!stateMap.containsKey(from)) {
					stateMap.put(from, stateMap.size());
					// create an empty entry for that new state in the transition table
					int[] no_outgoing_arcs = new int[maxVal-minVal+1];
					for (int i = 0; i < maxVal-minVal+1; i++) {
						no_outgoing_arcs[i] = -1;
					}
					A0.add(no_outgoing_arcs);
				}

				int value = ((Long) tr.value).intValue();

				String to = (String) tr.end;
				if (!stateMap.containsKey(to)) {
					stateMap.put(to, stateMap.size());
					// create an empty entry for that new state in the transition table
					int[] no_outgoing_arcs = new int[maxVal-minVal+1];
					for (int i = 0; i < maxVal-minVal+1; i++) {
						no_outgoing_arcs[i] = -1;
					}
					A0.add(no_outgoing_arcs);
				}

				int fromIndex = stateMap.get(from);
				int toIndex = stateMap.get(to);
				if (value>=minVal && value<=maxVal) // otherwise it will never be used
					A0.get(fromIndex)[value-minVal] = toIndex;
			}

			int[][] A = new int[A0.size()][maxVal-minVal+1];
			for (int i = 0; i < A0.size(); i++)
				A[i] = A0.get(i);

			int s = stateMap.get(startState);
			List<Integer> f = Arrays.stream(finalStates).map(stateMap::get).collect(Collectors.toList());

			minicp.post(regular(x_withDomStartingAtZero, A, s, f));

		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	// TODO: buildCtrStretch(String id, XVarInteger[] list, int[] values, int[] widthsMin, int[] widthsMax);
	// TODO: buildCtrStretch(String id, XVarInteger[] list, int[] values, int[] widthsMin, int[] widthsMax, int[][] patterns);

	// TODO: buildCtrMDD(String id, XVarInteger[] list, Transition[] transitions) http://xcsp.org/format3.pdf

	@Override
	public void buildCtrCircuit(String id, XVarInteger[] list, int startIndex) {
		// TODO this signature (without size parameter) should allow self-loops but it currently won't...
		if (hasFailed)
			return;
		try {
			IntVar[] x = mapVarArray(list);
			minicp.post(circuit(x, startIndex));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrBinPacking(String id, XVarInteger[] list, int[] sizes, Condition condition) {
		if (hasFailed)
			return;
		try {
			IntVar[] b = mapVarArray(list);
			if (!(condition instanceof Condition.ConditionVal)) {
				throw new InvalidParameterException("bin packing constraint with inappropriate condition");
			}
			int	capacity = (int) ((Condition.ConditionVal) condition).k;
			Types.TypeConditionOperatorRel operator = ((Condition.ConditionVal) condition).operator;
			int lastBin = 0;
			for (int i=0; i < b.length; i++) {
				if (b[i].max() > lastBin)
					lastBin = b[i].max();
			}
			IntVar[] l = new IntVar[lastBin+1];
			// restrict the capacity of bins
			switch (operator) {
				case LE:
					for (int j = 0; j <= lastBin; j++) {
						l[j] = makeIntVar(minicp,0, capacity);
					}
					break;
				case LT:
					for (int j = 0; j <= lastBin; j++) {
						l[j] = makeIntVar(minicp,0, capacity-1);
					}
					break;
				case EQ:
					for (int j = 0; j <= lastBin; j++) {
						l[j] = makeIntVar(minicp, capacity, capacity);
					}
					break;
				default:
					throw new InvalidParameterException("bin packing constraint with inappropriate condition");
			}
			minicp.post(binPacking(b, sizes, l));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrBinPacking(String id, XVarInteger[] list, int[] sizes, int[] capacities, boolean loads) {
		// If loads is true then these capacities correspond to the exact load. Difference between <limits> and <loads>
		if (hasFailed)
			return;
		try {
			IntVar[] b = mapVarArray(list);
			IntVar[] l = new IntVar[capacities.length];
			// restrict the capacity of bins
			if (loads) {
				for (int j = 0; j < l.length; j++) {
					l[j] = makeIntVar(minicp, capacities[j], capacities[j]);
				}
			} else {
				for (int j = 0; j < l.length; j++) {
					l[j] = makeIntVar(minicp, 0, capacities[j]);
				}
			}
			minicp.post(binPacking(b, sizes, l));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrBinPacking(String id, XVarInteger[] list, int[] sizes, XVarInteger[] capacities, boolean loads) {
		// If loads is true than these capacities correspond to the exact load. Difference between <limits> and <loads>
		if (hasFailed)
			return;
		try {
			IntVar[] b = mapVarArray(list);
			IntVar[] caps = mapVarArray(capacities);
			// restrict the capacity of bins
			if (loads) {
				minicp.post(binPacking(b, sizes, caps));
			} else {
				IntVar[] l = new IntVar[capacities.length];
				for (int j = 0; j < l.length; j++) {
					l[j] = makeIntVar(minicp, 0, caps[j].max());
					minicp.post(lessOrEqual(l[j], caps[j]));
				}
				minicp.post(binPacking(b, sizes, l));
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrBinPacking(String id, XVarInteger[] list, int[] sizes, Condition[] conditions, int startIndex) {
		if (hasFailed)
			return;
		try {
			IntVar[] b;
			if (startIndex == 0) {
				b = mapVarArray(list);
			} else {
				b = new IntVar[list.length];
				for (int i=0; i<list.length; i++) {
					b[i] = new IntVarViewOffset(mapVar.get(list[i]), -startIndex);
				}
			}
			IntVar[] l = new IntVar[conditions.length];
			for (int j = 0; j < l.length; j++) {
				if (!(conditions[j] instanceof Condition.ConditionVal)) {
					throw new InvalidParameterException("bin packing constraint with inappropriate condition");
				}
				int capacity = (int) ((Condition.ConditionVal) conditions[j]).k;
				Types.TypeConditionOperatorRel operator = ((Condition.ConditionVal) conditions[j]).operator;
				// restrict the capacity of bins
				switch (operator) {
					case LE:
						l[j] = makeIntVar(minicp, 0, capacity);
						break;
					case LT:
						l[j] = makeIntVar(minicp, 0, capacity - 1);
						break;
					case EQ:
						l[j] = makeIntVar(minicp, capacity, capacity);
						break;
					default:
						throw new InvalidParameterException("bin packing constraint with inappropriate condition");
				}
			}
			minicp.post(binPacking(b, sizes, l));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrKnapsack(String id, XVarInteger[] list, int[] weights, Condition wcondition, int[] profits, Condition pcondition) {
		if (hasFailed)
			return;
		try {
			IntVar[] xs = mapVarArray(list);
			int maxWeight=0, minProfit=0;
			Types.TypeConditionOperatorRel woperator=null, poperator=null;
			if (wcondition instanceof Condition.ConditionVal) {
				maxWeight = (int) ((Condition.ConditionVal) wcondition).k;
					woperator = ((Condition.ConditionVal) wcondition).operator;
			} else {
				System.out.println("c Knapsack constraint with an unsupported wcondition");
				System.exit(1);
			}
			if ((woperator != Types.TypeConditionOperatorRel.EQ) && (woperator != Types.TypeConditionOperatorRel.LE) && (woperator != Types.TypeConditionOperatorRel.LT)) {
				System.out.println("c Knapsack constraint with an unsupported wcondition");
				System.exit(1);
			}
			if (pcondition instanceof Condition.ConditionVal) {
				minProfit = (int) ((Condition.ConditionVal) pcondition).k;
				poperator = ((Condition.ConditionVal) pcondition).operator;
			} else {
				System.out.println("c Knapsack constraint with an unsupported pcondition");
				System.exit(1);
			}
			if ((poperator != Types.TypeConditionOperatorRel.EQ) && (poperator != Types.TypeConditionOperatorRel.GE) && (poperator != Types.TypeConditionOperatorRel.GT)) {
				System.out.println("c Knapsack constraint with an unsupported pcondition");
				System.exit(1);
			}
			relConstraintVal(sum(weights,xs), woperator, maxWeight);
			relConstraintVal(sum(profits,xs), poperator, minProfit);
			} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrNoOverlap(String id, XVarInteger[] origins, int[] lengths, boolean zeroIgnored) {
		if (hasFailed)
			return;
		if (!zeroIgnored) {
			System.out.println("c NoOverlap constraint with unsupported zeroIgnored=false");
			System.exit(1);
		}
		try {
			IntVar[] starts = mapVarArray(origins);
			minicp.post(disjunctive(starts,lengths));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrCumulative(String id, XVarInteger[] origins, int[] lengths, int[] heights, Condition condition) {
		if (hasFailed)
			return;
		try {
			IntVar[] starts = mapVarArray(origins);
			if (!(condition instanceof Condition.ConditionVal) || (((Condition.ConditionVal) condition).operator != Types.TypeConditionOperatorRel.LE)) {
				System.out.println("c Cumulative constraint with an unsupported condition");
				System.exit(1);
			}
			minicp.post(cumulative(starts,lengths,heights,(int) ((Condition.ConditionVal) condition).k));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	static class EntryComparator implements Comparator<Map.Entry<XVarInteger, IntVar>> {
		@Override
		public int compare(Map.Entry<XVarInteger, IntVar> o1, Map.Entry<XVarInteger, IntVar> o2) {
			return o1.getKey().id.compareTo(o2.getKey().id);
		}
	}

	public String solve(int nSolution, int timeOut) {
		AtomicReference<String> lastSolution = new AtomicReference<>("");
		Long t0 = System.currentTimeMillis();

		solve((solution, value) -> {
			System.out.println("solfound " + (value == Integer.MAX_VALUE ? value : "solution"));
			lastSolution.set(solution);
		}, ss -> {
			int nSols = isCOP() ? nSolution : 1;
			return (System.currentTimeMillis() - t0 >= timeOut * 1000 || ss.numberOfSolutions() >= nSols);
		});

		return lastSolution.get();
	}

	public void buildAnnotationDecision(XVarInteger[] list) {
		decisionVars.clear();
		Arrays.stream(list).map(mapVar::get).forEach(decisionVars::add);
	}

	/**
	 * @param onSolution: void onSolution(solution, obj). If not a COP, obj =
	 *        Integer.MAXVALUE
	 * @param shouldStop: boolean shouldStop(stats, isCOP).
	 * @return Stats
	 */
	public SearchStatistics solve(BiConsumer<String, Integer> onSolution,
			Function<SearchStatistics, Boolean> shouldStop) {

		IntVar[] vars = mapVar.entrySet().stream().sorted(new EntryComparator()).map(Map.Entry::getValue)
				.toArray(IntVar[]::new);
		LDSearch search;
		// TODO change firstfail to maxMarginalStrength
		if (decisionVars.isEmpty()) {
			search = makeLds(minicp, firstFail(vars));
		} else {
			search = makeLds(minicp, and(firstFail(decisionVars.toArray(new IntVar[0])), firstFail(vars)));
		}

		if (objectiveMinimize.isPresent()) {
			try {
				minicp.minimize(objectiveMinimize.get());
			} catch (InconsistencyException e) {
				hasFailed = true;
			}
		}

		if (hasFailed) {
			throw InconsistencyException.INCONSISTENCY;
		}

		search.onSolution(() -> {
			StringBuilder sol = new StringBuilder("<instantiation>\n\t<list>\n\t\t");
			for (XVarInteger x : xVars)
				sol.append(x.id()).append(" ");
			sol.append("\n\t</list>\n\t<values>\n\t\t");
			for (IntVar x : minicpVars)
				sol.append(x.min()).append(" ");
			sol.append("\n\t</values>\n</instantiation>");
			onSolution.accept(sol.toString(), realObjective.map(IntVar::min).orElse(Integer.MAX_VALUE));
		});

		return search.solve(shouldStop::apply);
	}

	private String solutionStr = null;
	private boolean extractSolutionStr = false;
	private boolean foundSolution = false;

	private static boolean checkSolution = false;

	public void checkSolution(boolean checkSolution) {
		XCSP.checkSolution = checkSolution;
	}

	private static boolean traceBP = false;

	public void traceBP(boolean traceBP) {
		XCSP.traceBP = traceBP;
	}

	private static boolean traceSearch = false;

	public void traceSearch(boolean traceSearch) {
		XCSP.traceSearch = traceSearch;
	}

	private static boolean traceEntropy = false;

	public void traceEntropy(boolean traceEntropy) {
		XCSP.traceEntropy = traceEntropy;
	}

	private static int maxIter = 5;

	public void maxIter(int maxIter) {
		XCSP.maxIter = maxIter;
	}

	private static boolean damp = false;

	public void damp(boolean damp) {
		XCSP.damp = damp;
	}

	private static double dampingFactor = 0.5;

	public void dampingFactor(double dampingFactor) {
		XCSP.dampingFactor = dampingFactor;
	}

	private static boolean restart = false;
	
	public void restart(boolean restart) {
		XCSP.restart = restart;
	}

	private static int nbFailCutof = 100;

	public void nbFailCutof(int nbFailCutof) {
		XCSP.nbFailCutof = nbFailCutof;
	} 

	private static double restartFactor = 1.5;

	public void restartFactor(double restartFactor) {
		XCSP.restartFactor = restartFactor;
	}

	private static double variationThreshold = -Double.MAX_VALUE;

	public void variationThreshold(double variationThreshold) {
		XCSP.variationThreshold = variationThreshold;
	}

	private static TreeSearchType searchType = TreeSearchType.DFS;

	public void searchType(TreeSearchType searchType) {
		XCSP.searchType = searchType;
	}

	private static boolean initImpact = false;

	public void initImpact(boolean initImpact) {
		XCSP.initImpact = initImpact;
	}

	private static boolean dynamicStopBP = false;

	public void dynamicStopBP(boolean dynamicStopBP) {
		XCSP.dynamicStopBP = dynamicStopBP;
	}

	private static boolean traceNbIter = false;

	public void traceNbIter(boolean traceNbIter) {
		XCSP.traceNbIter = traceNbIter;
	}
	
	private static boolean competitionOutput = false;

	public void competitionOutput(boolean competitionOutput) {
		XCSP.competitionOutput = competitionOutput;
	}

	private Search makeSearch(Supplier<Procedure[]> branching) {
		Search search = null;
		switch (searchType) {
		case DFS:
			search = makeDfs(minicp, branching);
			break;
		case LDS:
			search = makeLds(minicp, branching);
			break;
		default:
			System.out.println("unknown search type");
			System.exit(1);
		}
		return search;
	}

	public void solve(BranchingHeuristic heuristic, int timeout, String statsFileStr, String solFileStr) {
		solve(heuristic, timeout, statsFileStr, solFileStr, 1);
	}

	private IntVar[] getVars() {
		return mapVar.values().toArray(new IntVar[0]);
	}

	private XCSP getNewProblem() {
		XCSP xcsp;
		try {
			xcsp = new XCSP(this.fileName);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		return xcsp;
	}

	public void solve(BranchingHeuristic heuristic, int timeout, String statsFileStr, String solFileStr, int instances) {
		this.t0 = System.currentTimeMillis();

		if (hasFailed) {
			if (!competitionOutput) {
				System.out.println("problem failed before initiating the search");
				throw InconsistencyException.INCONSISTENCY;
			} else {
				System.out.println("s UNSATISFIABLE");
				System.out.println("c problem failed before initiating the search");
				return;
			}
		}

		/* Instantiate the workers */
		XCSP[] xcsps = new XCSP[instances];
		for (int i = 0; i < instances; i++) {
			XCSP subproblem = getNewProblem();
			subproblem.t0 = t0;
			subproblem.timeout = timeout;
			subproblem.heuristic = heuristic;
			subproblem.subproblemSolutions = new ArrayList<>();
			xcsps[i] = subproblem;

			Solver subproblemSolver = xcsps[i].minicp;
			subproblemSolver.setTraceBPFlag(traceBP);
			subproblemSolver.setTraceSearchFlag(traceSearch);
//			subproblemSolver.setTraceNbIterFlag(traceNbIter);
			subproblemSolver.setTraceEntropyFlag(traceEntropy);
			subproblemSolver.setMaxIter(maxIter);
//			subproblemSolver.setDynamicStopBP(dynamicStopBP);
			subproblemSolver.setDamp(damp);
			subproblemSolver.setDampingFactor(dampingFactor);
//			subproblemSolver.setVariationThreshold(variationThreshold);
		}

		/* Top-Down Decomposition */
		int d = 0;
		final List<List<Integer>> tuples = new ArrayList<>();
		Solver decompositionSolver = this.minicp;

		// variables
		int nVar = decompositionSolver.getVariables().size();
		IntVar[] vars = new IntVar[nVar];
		Utils.fillArrayFromStateStack(decompositionSolver.getVariables(), vars);

		while (tuples.size() < instances) {

			/* Determine a lower bound */
//			d++; // too easy :-)
			d = Utils.newBound(tuples.size(), d, vars, instances);
			if (d == -1) {
				System.err.println("No more solutions, max depth attained");
				break;
			}
//			System.out.println("d = " + d);
			IntVar[] q = Arrays.copyOfRange(vars, 0, d);

			/* Extend current search */
			IDSearch search = makeIds(decompositionSolver, d, topDownStaticOrdering(q));

			search.onMaxDepth(() -> {
                for (IntVar intVar : q) assert intVar.isBound();
				List<Integer> newTuple = Arrays.stream(q).map(IntVar::min).toList();
				tuples.add(newTuple);
			});

			tuples.clear();
			search.solve();

			// add computed tuples as constraints and propagate them
			decompositionSolver.post(table(q, Utils.generateTableFromList(tuples)));
			decompositionSolver.propagateSolver();

			System.out.println(tuples);
			System.out.println(tuples.size());
		}

		System.out.println("decomposition done into " + tuples.size() + " subproblems in " + (System.currentTimeMillis() - t0) + " ms");


		/* Send subproblems */
		int nStart = tuples.size() / instances, nEnd = nStart;
		int nLastChild = tuples.size() / instances + tuples.size() % instances;
		assert nStart * (instances - 1) + nLastChild == tuples.size();
		int t = tuples.get(0).size();
		List<String> solutions = new ArrayList<>();

		ExecutorService executor = Executors.newFixedThreadPool(instances);
		Future<?>[] futures = new Future<?>[instances];
		for (int i = 0; i < instances; i++) {
			System.out.println("\n === NEW CHILD ===");
			XCSP subproblem = xcsps[i];
			Solver solver = xcsps[i].minicp;

			IntVar[] subproblemVars = new IntVar[solver.getVariables().size()];
			Utils.fillArrayFromStateStack(solver.getVariables(), subproblemVars); // static ordering
			subproblem.subproblemVars = subproblemVars;

			/* Add tuple constraint */
			if (i == instances - 1)
				nEnd = nLastChild;

			// tuples from i*nStart to (i+1)*nEnd
			List<List<Integer>> currentTuples = new ArrayList<>();
			for (int j = i * nStart; j < i * nStart + nEnd; j++) {
				System.out.println(tuples.get(j));
				currentTuples.add(tuples.get(j));
			}
			solver.post(table(Arrays.copyOf(subproblemVars, t), Utils.generateTableFromList(currentTuples)));
			solver.propagateSolver();

			futures[i] = executor.submit(xcsps[i]);
		}

		if (checkSolution || (solFileStr != ""))
			extractSolutionStr = true;

		for (int i = 0; i < instances; i++) {
			try {
				futures[i].get();
			} catch (InterruptedException | ExecutionException e) {
				System.err.println("Error while waiting for child " + i);
				throw new RuntimeException(e);
			}
			System.out.println("\n === CHILD " + i + " DONE ===");
			System.out.println(xcsps[i].stats);
			solutions.addAll(xcsps[i].subproblemSolutions);
		}

		executor.shutdown();

		/* Aggregate solutions to verify if needed */
		foundSolution = !solutions.isEmpty();
		if(!competitionOutput) {
			if (foundSolution) {
				System.out.printf("%d solutions found%n", solutions.size());
				for (String values : solutions) {
					StringBuilder sol = new StringBuilder("<instantiation>\n\t<list>\n\t\t");
					for (XVarInteger x : xVars)
						sol.append(x.id()).append(" ");
					sol.append("\n\t</list>\n\t<values>\n\t\t");
					sol.append(values);
					sol.append("\n\t</values>\n</instantiation>");
					solutionStr = sol.toString();

					if (checkSolution)
						verifySolution();
//					printSolution(solFileStr);

				}
			} else
				System.out.println("no solution was found");

			long runtime = System.currentTimeMillis() - t0;
			System.out.println("runtime: " + runtime + " ms");
//			printStats(stats, statsFileStr, runtime);
		}
//		else {
//			if(foundSolution) {
//				System.out.println("s SATISFIABLE");
//				System.out.println(solutionStr);
//				System.out.println("c "+stats.numberOfFailures()+" backtracks");
//			}
//			else if(stats.isCompleted()) {
//				System.out.println("s UNSATISFIABLE");
//				System.out.println("c "+stats.numberOfFailures()+" backtracks");
//			}
//			else {
//				System.out.println("s UNKNOWN");
//			}
//		}

	}

	public void run() {
		Search search;
		foundSolution = false;
		long childT0 = System.currentTimeMillis();
		switch (heuristic) {
			case FFRV:
				minicp.setMode(PropaMode.SP);
				search = makeSearch(firstFailRandomVal(subproblemVars));
				break;
			case MXMS:
				search = makeSearch(maxMarginalStrength(subproblemVars));
				break;
			case MXM:
				search = makeSearch(maxMarginal(subproblemVars));
				break;
			case MNMS:
				search = makeSearch(minMarginalStrength(subproblemVars));
				break;
			case MNM:
				search = makeSearch(minMarginal(subproblemVars));
				break;
			case MNE:
				search = makeSearch(minEntropy(subproblemVars));
				break;
			case IE:
				search = makeSearch(impactEntropy(subproblemVars));
				if(XCSP.initImpact)
					search.initializeImpact(subproblemVars);
				break;
			case IBS:
				minicp.setMode(PropaMode.SP);
				search = makeSearch(impactBasedSearch(subproblemVars));
				search.initializeImpactDomains(subproblemVars);
				nbFailCutof = nbFailCutof*subproblemVars.length;
				break;
			case MIE:
				search = makeDfs(minicp, minEntropyRegisterImpact(subproblemVars),impactEntropy(subproblemVars));
				if(XCSP.initImpact)
					search.initializeImpact(subproblemVars);
				break;
			case MNEBW:
				search = makeSearch(minEntropyBiasedWheelSelectVal(subproblemVars));
				break;
			case WDEG:
				minicp.setMode(PropaMode.SP);
				search = makeSearch(domWdeg(subproblemVars));
				nbFailCutof = nbFailCutof*subproblemVars.length;
				break;
			default:
				System.out.println("unknown search strategy");
				throw new RuntimeException();
		}

		search.onSolution(() -> {
			foundSolution = true;
			StringBuilder sol = new StringBuilder();
			for (IntVar x : minicpVars) {
				sol.append(x.min()).append(" ");
			}
			subproblemSolutions.add(sol.toString());
//			extractSolutionStr = true;
//			if (extractSolutionStr) {
//				StringBuilder sol = new StringBuilder("<instantiation>\n\t<list>\n\t\t");
//				for (XVarInteger x : xVars)
//					sol.append(x.id()).append(" ");
//				sol.append("\n\t</list>\n\t<values>\n\t\t");
//				for (IntVar x : minicpVars) {
//					sol.append(x.min()).append(" ");
//				}
//				sol.append("\n\t</values>\n</instantiation>");
//				solutionStr = sol.toString();
//				solutions.add(solutionStr);
//			}
//			if(competitionOutput) {
//				StringBuilder sol = new StringBuilder("v <instantiation>\nv <list> ");
//				for (XVarInteger x : xVars)
//					sol.append(x.id()).append(" ");
//				sol.append("</list>\nv <values> ");
//				for (IntVar x : minicpVars) {
//					sol.append(x.min()).append(" ");
//				}
//				sol.append("</values>\nv </instantiation>");
//				solutionStr = sol.toString();
//			}
//			// GP: printing each solution
//			System.out.println("SOLN:"+solutionStr);
		});

		if (!restart) {
			stats = search.solve(ss -> (System.currentTimeMillis() - t0 >= timeout * 1000L));
		} else {
			stats = search.solveRestarts(ss -> (System.currentTimeMillis() - t0 >= timeout * 1000L), nbFailCutof, restartFactor);
		}

		System.out.println("Found " + subproblemSolutions.size() + " solutions in " + (System.currentTimeMillis() - childT0) + " ms");

//		if(!competitionOutput) {
//			if (foundSolution) {
//				if(competitionOutput) {}
//				System.out.println("solution found");
//				if (checkSolution)
//					verifySolution();
//				printSolution(solFileStr);
//			} else
//				System.out.println("no solution was found");
//
//			Long runtime = System.currentTimeMillis() - t0;
//			printStats(stats, statsFileStr, runtime);
//		}
//		else {
//			if(foundSolution) {
//				System.out.println("s SATISFIABLE");
//				System.out.println(solutionStr);
//				System.out.println("c "+stats.numberOfFailures()+" backtracks");
//			}
//			else if(stats.isCompleted()) {
//				System.out.println("s UNSATISFIABLE");
//				System.out.println("c "+stats.numberOfFailures()+" backtracks");
//			}
//			else {
//				System.out.println("s UNKNOWN");
//			}
//		}

	}

	private void verifySolution() {
		System.out.println("verifying the solution (begin)");
		try {
			SolutionChecker checker = new SolutionChecker(false, fileName,
					new ByteArrayInputStream(solutionStr.getBytes()));
			if (checker.violatedCtrs.size() > 0)
				System.out.println("INVALID SOLUTION");
			else
				System.out.println("VALID SOLUTION");
		} catch (Exception e) {
			e.printStackTrace();
			System.out.println("unable to verify the solution");
		}
		System.out.println("verifying the solution (end)");
	}

	private void printSolution(String solFileStr) {
		if (solFileStr != "")
			try {
				PrintWriter out = new PrintWriter(new File(solFileStr));
				out.print(solutionStr);
				out.close();
			} catch (FileNotFoundException e) {
				e.printStackTrace();
				System.out.println("unable to create file " + solFileStr);
				System.exit(1);
			}
		else {
			System.out.println(solutionStr);
		}
	}

	private void printStats(SearchStatistics stats, String statsFileStr, Long runtime) {
		System.out.println(stats);
		PrintStream out = null;
		if (statsFileStr == "")
			out = System.out;
		else
			try {
				out = new PrintStream(new File(statsFileStr));
			} catch (FileNotFoundException e) {
				e.printStackTrace();
				System.out.println("unable to create file " + statsFileStr);
				System.exit(1);
			}

		String statusStr;
		if (foundSolution)
			statusStr = "SAT";
		else if (stats.isCompleted())
			statusStr = "UNSAT";
		else
			statusStr = "TIMEOUT";

		out.println("status: " + statusStr);
		out.println("failures: " + stats.numberOfFailures());
		out.println("nodes: " + stats.numberOfNodes());
		out.println("runtime (ms): " + runtime);

//		out.close();

	}

	public static void main(String[] args) {
		try {
			XCSP xcsp = new XCSP(args[0]);
			String solution = xcsp.solve(Integer.MAX_VALUE, 100);
			List<String> violatedCtrs = xcsp.getViolatedCtrs(solution);
			System.out.println(violatedCtrs);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}

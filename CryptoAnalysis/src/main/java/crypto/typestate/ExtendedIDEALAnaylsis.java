package crypto.typestate;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.beust.jcommander.internal.Lists;
import com.beust.jcommander.internal.Sets;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;

import boomerang.BackwardQuery;
import boomerang.Boomerang;
import boomerang.debugger.Debugger;
import boomerang.jimple.Statement;
import boomerang.jimple.Val;
import crypto.analysis.AnalysisSeedWithSpecification;
import crypto.analysis.CrySLAnalysisResultsAggregator;
import crypto.rules.StateMachineGraph;
import heros.utilities.DefaultValueMap;
import ideal.IDEALAnalysis;
import ideal.IDEALAnalysisDefinition;
import ideal.PerSeedAnalysisContext;
import ideal.ResultReporter;
import soot.Local;
import soot.SootMethod;
import soot.Unit;
import soot.Value;
import soot.jimple.Stmt;
import soot.jimple.toolkits.ide.icfg.BiDiInterproceduralCFG;
import sync.pds.solver.WeightFunctions;
import sync.pds.solver.nodes.Node;
import typestate.TransitionFunction;

public abstract class ExtendedIDEALAnaylsis {

	private FiniteStateMachineToTypestateChangeFunction changeFunction;
	private Multimap<CallSiteWithParamIndex, Unit> collectedValues = HashMultimap.create();
	private Set<Unit> invokedMethodsOnInstance = Sets.newHashSet();
	private DefaultValueMap<AdditionalBoomerangQuery, AdditionalBoomerangQuery> additionalBoomerangQuery = new DefaultValueMap<AdditionalBoomerangQuery, AdditionalBoomerangQuery>() {
		@Override
		protected AdditionalBoomerangQuery createItem(AdditionalBoomerangQuery key) {
			return key;
		}
	};
	private final IDEALAnalysis<TransitionFunction> analysis;
	private PerSeedAnalysisContext<TransitionFunction> seedAnalysis;
	
	public ExtendedIDEALAnaylsis(){
		analysis = new IDEALAnalysis<TransitionFunction>(new IDEALAnalysisDefinition<TransitionFunction>() {
			@Override
			public Collection<Val> generate(SootMethod method, Unit stmt, Collection<SootMethod> calledMethod) {
				return null;
			}

			@Override
			public WeightFunctions<Statement, Val, Statement, TransitionFunction> weightFunctions() {
				return getOrCreateTypestateChangeFunction();
			}

			@Override
			public ResultReporter<TransitionFunction> resultReporter() {
				return null;
			}

			@Override
			public BiDiInterproceduralCFG<Unit, SootMethod> icfg() {
				return ExtendedIDEALAnaylsis.this.icfg();
			}

			@Override
			public boolean enableAliasing() {
				return true;
			}

			@Override
			public long analysisBudgetInSeconds() {
				return 0;
			}

			@Override
			public boolean enableNullPointOfAlias() {
				return true;
			}

			@Override
			public boolean enableStrongUpdates() {
				return true;
			}

			@Override
			public Debugger<TransitionFunction> debugger() {
				return new Debugger<>();
			}
		});
	}

	public FiniteStateMachineToTypestateChangeFunction getOrCreateTypestateChangeFunction() {
		if (this.changeFunction == null)
			this.changeFunction = new FiniteStateMachineToTypestateChangeFunction(getStateMachine(), this);
		return this.changeFunction;
	}

	public abstract StateMachineGraph getStateMachine();

	public void run(Node<Statement, Val> query, final ResultReporter<TransitionFunction> resultReporter) {
		getOrCreateTypestateChangeFunction().injectQueryForSeed(query.stmt().getUnit().get());

		seedAnalysis = analysis.run(query);
		CrySLAnalysisResultsAggregator reports = analysisListener();
		for (AdditionalBoomerangQuery q : additionalBoomerangQuery.keySet()) {
			if (reports != null) {
				reports.boomerangQueryStarted(query, q);
			}
			q.solve();
			if (reports != null) {
				reports.boomerangQueryFinished(query, q);
			}
		}
	}


	public void addQueryAtCallsite(final String varNameInSpecification, final Stmt stmt, final int index) {
		Value parameter = stmt.getInvokeExpr().getArg(index);
		SootMethod method = icfg().getMethodOf(stmt);
		Statement s = new Statement(stmt, method);
		if (!(parameter instanceof Local)) {
			collectedValues.put(
					new CallSiteWithParamIndex(s, new Val(parameter, method), index, varNameInSpecification), stmt);
			return;
		}
		AdditionalBoomerangQuery query = additionalBoomerangQuery
				.getOrCreate(new AdditionalBoomerangQuery(s, new Val((Local) parameter, method)));
		query.addListener(new QueryListener() {
			@Override
			public void solved(AdditionalBoomerangQuery q, Set<Node<Statement, Val>> res) {
				for (Node<Statement, Val> v : res) {
					collectedValues.put(new CallSiteWithParamIndex(s, v.fact(), index, varNameInSpecification),
							v.stmt().getUnit().get());
				}
			}
		});
	}

	protected abstract BiDiInterproceduralCFG<Unit, SootMethod> icfg();

	public void addAdditionalBoomerangQuery(AdditionalBoomerangQuery q, QueryListener listener) {
		AdditionalBoomerangQuery query = additionalBoomerangQuery.getOrCreate(q);
		query.addListener(listener);
	}

	public class AdditionalBoomerangQuery extends BackwardQuery {
		public AdditionalBoomerangQuery(Statement stmt, Val variable) {
			super(stmt, variable);
		}

		protected boolean solved;
		private List<QueryListener> listeners = Lists.newLinkedList();
		private Set<Node<Statement, Val>> res;

		public void solve() {
			Boomerang boomerang = new Boomerang() {
				@Override
				public BiDiInterproceduralCFG<Unit, SootMethod> icfg() {
					return ExtendedIDEALAnaylsis.this.icfg();
				}
			};
			boomerang.solve(this);
			// log("Solving query "+ accessGraph + " @ " + stmt);
			res = boomerang.getResults(this);
			for (QueryListener l : Lists.newLinkedList(listeners)) {
				l.solved(this, res);
			}
			solved = true;
		}

		public void addListener(QueryListener q) {
			if (solved) {
				q.solved(this, res);
				return;
			}
			listeners.add(q);
		}

		private ExtendedIDEALAnaylsis getOuterType() {
			return ExtendedIDEALAnaylsis.this;
		}
	}

	public static interface QueryListener {
		public void solved(AdditionalBoomerangQuery q, Set<Node<Statement, Val>> res);
	}

	public Multimap<CallSiteWithParamIndex, Unit> getCollectedValues() {
		return collectedValues;
	}

	public void log(String string) {
		// System.out.println(string);
	}

	public Collection<Unit> getInvokedMethodOnInstance() {
		return invokedMethodsOnInstance;
	}

	public void methodInvokedOnInstance(Unit method) {
		invokedMethodsOnInstance.add(method);
	}

	public abstract CrySLAnalysisResultsAggregator analysisListener();

	public Map<Node<Statement, Val>, TransitionFunction> getObjectDestructingStatements(
			AnalysisSeedWithSpecification analysisSeedWithSpecification) {
		return null;
	}

	public Set<Node<Statement, Val>> computeInitialSeeds() {
		return analysis.computeSeeds();
	}

	public Map<Node<Statement, Val>, TransitionFunction> getResults() {
		return seedAnalysis.getResults();
	}

}
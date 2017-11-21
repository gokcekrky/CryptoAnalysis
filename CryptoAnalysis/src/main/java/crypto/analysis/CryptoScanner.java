package crypto.analysis;

import java.util.AbstractMap.SimpleEntry;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

import com.beust.jcommander.internal.Sets;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Lists;
import com.google.common.collect.Table;
import com.google.common.collect.Table.Cell;

import boomerang.AliasFinder;
import boomerang.AliasResults;
import boomerang.BackwardQuery;
import boomerang.Boomerang;
import boomerang.BoomerangOptions;
import boomerang.DefaultBoomerangOptions;
import boomerang.accessgraph.AccessGraph;
import boomerang.cfg.IExtendedICFG;
import boomerang.context.AllCallersRequester;
import boomerang.jimple.Statement;
import boomerang.jimple.Val;
import boomerang.pointsofindirection.AllocationSiteHandlers;
import crypto.analysis.util.StmtWithMethod;
import crypto.rules.CryptSLPredicate;
import crypto.rules.CryptSLRule;
import crypto.rules.StateNode;
import crypto.typestate.CryptSLMethodToSootMethod;
import heros.fieldsens.Debugger.NullDebugger;
import heros.fieldsens.structs.FactAtStatement;
import heros.solver.Pair;
import heros.utilities.DefaultValueMap;
import ideal.IFactAtStatement;
import ideal.debug.IDebugger;
import soot.Local;
import soot.SootMethod;
import soot.Unit;
import soot.Value;
import soot.jimple.InstanceInvokeExpr;
import soot.jimple.InvokeExpr;
import soot.jimple.Stmt;
import soot.jimple.toolkits.ide.icfg.BiDiInterproceduralCFG;
import sync.pds.solver.nodes.Node;
import typestate.TypestateDomainValue;

public abstract class CryptoScanner {

	private final LinkedList<IAnalysisSeed> worklist = Lists.newLinkedList();
	private final List<ClassSpecification> specifications = Lists.newLinkedList();
	private Table<Unit, Val, Set<EnsuredCryptSLPredicate>> existingPredicates = HashBasedTable.create();
	private Table<Unit, IAnalysisSeed, Set<EnsuredCryptSLPredicate>> existingPredicatesObjectBased = HashBasedTable.create();
	private Table<Unit, IAnalysisSeed, Set<CryptSLPredicate>> expectedPredicateObjectBased = HashBasedTable.create();
	private Set<Entry<CryptSLPredicate, CryptSLPredicate>> disallowedPredPairs = new HashSet<Entry<CryptSLPredicate, CryptSLPredicate>>();
	private CrySLAnalysisResultsAggregator resultsAggregator = new CrySLAnalysisResultsAggregator(icfg(), null);

	private DefaultValueMap<Node<Statement,Val>, AnalysisSeedWithEnsuredPredicate> seedsWithoutSpec = new DefaultValueMap<Node<Statement,Val>, AnalysisSeedWithEnsuredPredicate>() {

		@Override
		protected AnalysisSeedWithEnsuredPredicate createItem(Node<Statement,Val> key) {
			return new AnalysisSeedWithEnsuredPredicate(CryptoScanner.this, key);
		}
	};
	private DefaultValueMap<AnalysisSeedWithSpecification, AnalysisSeedWithSpecification> seedsWithSpec = new DefaultValueMap<AnalysisSeedWithSpecification, AnalysisSeedWithSpecification>() {

		@Override
		protected AnalysisSeedWithSpecification createItem(AnalysisSeedWithSpecification key) {
			return new AnalysisSeedWithSpecification(CryptoScanner.this, key.asNode(), key.getSpec());
		}
	};

	public abstract BiDiInterproceduralCFG<Unit, SootMethod> icfg();

	public CrySLAnalysisResultsAggregator getAnalysisListener() {
		return resultsAggregator;
	};

	public abstract boolean isCommandLineMode();

	/**
	 * @return the existingPredicates
	 */
	public Set<EnsuredCryptSLPredicate> getExistingPredicates(Unit stmt, Val seed) {
		Set<EnsuredCryptSLPredicate> set = existingPredicates.get(stmt, seed);
		if (set == null) {
			set = Sets.newHashSet();
			existingPredicates.put(stmt, seed, set);
		}
		return set;
	}

	public CryptoScanner(List<CryptSLRule> specs) {
		CryptSLMethodToSootMethod.reset();
		for (CryptSLRule rule : specs) {
			specifications.add(new ClassSpecification(rule, this));
		}
	}

	public boolean addNewPred(IAnalysisSeed seedObj, Unit stmt, Val seed, EnsuredCryptSLPredicate ensPred) {
		Set<EnsuredCryptSLPredicate> set = getExistingPredicates(stmt, seed);
		boolean added = set.add(ensPred);
		assert existingPredicates.get(stmt, seed).contains(ensPred);
		if (added) {
			onPredicateAdded(stmt, seed, ensPred);
		}

		Set<EnsuredCryptSLPredicate> predsObjBased = existingPredicatesObjectBased.get(stmt, seedObj);
		if (predsObjBased == null)
			predsObjBased = Sets.newHashSet();
		predsObjBased.add(ensPred);
		existingPredicatesObjectBased.put(stmt, seedObj, predsObjBased);
		return added;
	}

	private void onPredicateAdded(Unit stmt, Val seed, EnsuredCryptSLPredicate ensPred) {
		if (stmt instanceof Stmt && ((Stmt) stmt).containsInvokeExpr()) {
			InvokeExpr ivexpr = ((Stmt) stmt).getInvokeExpr();
			if (ivexpr instanceof InstanceInvokeExpr) {
				InstanceInvokeExpr iie = (InstanceInvokeExpr) ivexpr;
				SootMethod method = iie.getMethod();
				Value base = iie.getBase();
				boolean paramMatch = false;
				for (Value arg : iie.getArgs()) {
					if (seed.value() != null && seed.value().equals(arg))
						paramMatch = true;
				}
				if (paramMatch) {
					for (final ClassSpecification specification : specifications) {
						if (specification.getAnalysisProblem().getOrCreateTypestateChangeFunction().getEdgeLabelMethods().contains(method)) {
							Boomerang boomerang = new Boomerang(new DefaultBoomerangOptions() {
								//TODO
//								@Override
//								public AllocationSiteHandlers allocationSiteHandlers() {
//									return new PrimitiveTypeAndReferenceForCryptoType();
//								}
							}){
								@Override
								public BiDiInterproceduralCFG<Unit, SootMethod> icfg() {
									return CryptoScanner.this.icfg();
								}
							};
							Val val = new Val(base, method);
							BackwardQuery backwardQuery = new BackwardQuery(new Statement((Stmt) stmt, method), val);
							boomerang.solve(backwardQuery);
							Set<Node<Statement, Val>> results = boomerang.getResults(backwardQuery);
							for (Node<Statement, Val> p : results) {
								AnalysisSeedWithSpecification seedWithSpec = getOrCreateSeedWithSpec(new AnalysisSeedWithSpecification(CryptoScanner.this, p, specification));
								seedWithSpec.addEnsuredPredicate(ensPred);
							}
						}
					}
				}
			}

		}
	}

	public boolean deleteNewPred(Unit stmt, Val seed, EnsuredCryptSLPredicate ensPred) {
		Set<EnsuredCryptSLPredicate> set = getExistingPredicates(stmt, seed);
		boolean deleted = set.remove(ensPred);
		assert !existingPredicates.get(stmt, seed).contains(ensPred);
		return deleted;
	}

	public void scan() {
		getAnalysisListener().beforeAnalysis();
		initialize();
		while (!worklist.isEmpty()) {
			IAnalysisSeed curr = worklist.poll();
			getAnalysisListener().discoveredSeed(curr);
			curr.execute();
		}
		IDebugger<TypestateDomainValue<StateNode>> debugger = debugger();
		if (debugger instanceof CryptoVizDebugger) {
			CryptoVizDebugger ideVizDebugger = (CryptoVizDebugger) debugger;
			ideVizDebugger.addEnsuredPredicates(this.existingPredicates);
		}
		checkForContradictions();
		for (AnalysisSeedWithSpecification seed : seedsWithSpec.values()) {
			Set<CryptSLPredicate> missingPredicates = seed.getMissingPredicates();
			getAnalysisListener().missingPredicates(seed, missingPredicates);
		}
		getAnalysisListener().ensuredPredicates(this.existingPredicates, expectedPredicateObjectBased, computeMissingPredicates());
		getAnalysisListener().afterAnalysis();
		debugger().afterAnalysis();
	}

	private Table<Unit, IAnalysisSeed, Set<CryptSLPredicate>> computeMissingPredicates() {
		Table<Unit, IAnalysisSeed, Set<CryptSLPredicate>> res = HashBasedTable.create();
		for (Cell<Unit, IAnalysisSeed, Set<CryptSLPredicate>> c : expectedPredicateObjectBased.cellSet()) {
			Set<EnsuredCryptSLPredicate> exPreds = existingPredicatesObjectBased.get(c.getRowKey(), c.getColumnKey());
			if (c.getValue() == null)
				continue;
			HashSet<CryptSLPredicate> expectedPreds = new HashSet<>(c.getValue());
			if (exPreds == null) {
				exPreds = Sets.newHashSet();
			}
			for (EnsuredCryptSLPredicate p : exPreds) {
				expectedPreds.remove(p.getPredicate());
			}
			if (!expectedPreds.isEmpty()) {
				res.put(c.getRowKey(), c.getColumnKey(), expectedPreds);
			}
		}
		return res;
	}

	private void checkForContradictions() {
		for (Unit generatingPredicateStmt : expectedPredicateObjectBased.rowKeySet()) {
			for (Entry<AccessGraph, Set<EnsuredCryptSLPredicate>> exPredCell : existingPredicates.row(generatingPredicateStmt).entrySet()) {
				Set<String> preds = new HashSet<String>();
				for (EnsuredCryptSLPredicate exPred : exPredCell.getValue()) {
					preds.add(exPred.getPredicate().getPredName());
				}
				for (Entry<CryptSLPredicate, CryptSLPredicate> disPair : disallowedPredPairs) {
					if (preds.contains(disPair.getKey().getPredName()) && preds.contains(disPair.getValue().getPredName())) {
						getAnalysisListener().predicateContradiction(new StmtWithMethod(generatingPredicateStmt, icfg().getMethodOf(generatingPredicateStmt)), exPredCell.getKey(), disPair);
					}
				}
			}

		}

	}

	private void initialize() {
		for (ClassSpecification spec : getClassSpecifictions()) {
			spec.checkForForbiddenMethods();
			if (!isCommandLineMode() && !spec.isLeafRule())
				continue;

			for (IFactAtStatement seed : spec.getInitialSeeds()) {
				addToWorkList(getOrCreateSeedWithSpec(new AnalysisSeedWithSpecification(this, seed, spec)));
			}
		}
	}

	public List<ClassSpecification> getClassSpecifictions() {
		return specifications;
	}

	protected void addToWorkList(IAnalysisSeed analysisSeedWithSpecification) {
		worklist.add(analysisSeedWithSpecification);
	}

	public IDebugger<TypestateDomainValue<StateNode>> debugger() {
		return new NullDebugger<>();
	}

	public AnalysisSeedWithEnsuredPredicate getOrCreateSeed(Node<Statement,Val> factAtStatement) {
		boolean addToWorklist = false;
		if (!seedsWithoutSpec.containsKey(factAtStatement))
			addToWorklist = true;
		AnalysisSeedWithEnsuredPredicate seed = seedsWithoutSpec.getOrCreate(factAtStatement);
		if (addToWorklist)
			addToWorkList(seed);
		return seed;
	}

	public AnalysisSeedWithSpecification getOrCreateSeedWithSpec(AnalysisSeedWithSpecification factAtStatement) {
		boolean addToWorklist = false;
		if (!seedsWithSpec.containsKey(factAtStatement))
			addToWorklist = true;
		AnalysisSeedWithSpecification seed = seedsWithSpec.getOrCreate(factAtStatement);
		if (addToWorklist)
			addToWorkList(seed);
		return seed;
	}

	public void addDisallowedPredicatePair(CryptSLPredicate cryptSLPredicate, CryptSLPredicate cons) {
		disallowedPredPairs.add(new SimpleEntry<CryptSLPredicate, CryptSLPredicate>(cryptSLPredicate, cons));
	}

	public void expectPredicate(IAnalysisSeed object, Unit stmt, CryptSLPredicate predToBeEnsured) {
		for (Unit succ : icfg().getSuccsOf(stmt)) {
			Set<CryptSLPredicate> set = expectedPredicateObjectBased.get(succ, object);
			if (set == null)
				set = Sets.newHashSet();
			set.add(predToBeEnsured);
			expectedPredicateObjectBased.put(succ, object, set);
		}
	}
	
	public StmtWithMethod getMethodFromUnit(Unit unit) {
		return new StmtWithMethod(unit, icfg().getMethodOf(unit));
	}
}

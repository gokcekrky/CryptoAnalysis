package crypto.analysis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.beust.jcommander.internal.Lists;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.common.collect.Table.Cell;

import boomerang.WeightedBoomerang;
import boomerang.accessgraph.AccessGraph;
import boomerang.debugger.Debugger;
import boomerang.jimple.Statement;
import boomerang.jimple.Val;
import crypto.analysis.util.StmtWithMethod;
import crypto.rules.CryptSLCondPredicate;
import crypto.rules.CryptSLMethod;
import crypto.rules.CryptSLObject;
import crypto.rules.CryptSLPredicate;
import crypto.rules.CryptSLRule;
import crypto.rules.StateMachineGraph;
import crypto.rules.StateNode;
import crypto.typestate.CallSiteWithParamIndex;
import crypto.typestate.CryptSLMethodToSootMethod;
import crypto.typestate.CryptoTypestateAnaylsisProblem;
import crypto.typestate.ErrorStateNode;
import crypto.typestate.ExtendedIDEALAnaylsis;
import ideal.IDEALAnalysis;
import ideal.IDEALAnalysisDefinition;
import ideal.ResultReporter;
import soot.IntType;
import soot.Local;
import soot.RefType;
import soot.SootMethod;
import soot.Type;
import soot.Unit;
import soot.Value;
import soot.ValueBox;
import soot.jimple.AssignStmt;
import soot.jimple.Constant;
import soot.jimple.IntConstant;
import soot.jimple.InvokeExpr;
import soot.jimple.Stmt;
import soot.jimple.StringConstant;
import soot.jimple.toolkits.ide.icfg.BiDiInterproceduralCFG;
import sync.pds.solver.WeightFunctions;
import sync.pds.solver.nodes.Node;
import typestate.TransitionFunction;
import typestate.TypestateDomainValue;
import typestate.finiteautomata.ITransition;
import typestate.interfaces.ICryptSLPredicateParameter;
import typestate.interfaces.ISLConstraint;

public class AnalysisSeedWithSpecification extends IAnalysisSeed {

	private final ClassSpecification spec;
	private ExtendedIDEALAnaylsis analysis;
	private Multimap<CallSiteWithParamIndex, Unit> parametersToValues = HashMultimap.create();
	private HashBasedTable<Unit, Val, TransitionFunction> results;
	private Collection<EnsuredCryptSLPredicate> ensuredPredicates = Sets.newHashSet();
	private Multimap<Unit, StateNode> typeStateChange = HashMultimap.create();
	private Collection<EnsuredCryptSLPredicate> indirectlyEnsuredPredicates = Sets.newHashSet();
	private Set<CryptSLPredicate> missingPredicates = Sets.newHashSet();
	private ConstraintSolver constraintSolver;
	private boolean internalConstraintSatisfied;
	protected Collection<Unit> allCallsOnObject = Sets.newHashSet();

	public AnalysisSeedWithSpecification(CryptoScanner cryptoScanner, Node<Statement,Val> factAtStmt, ClassSpecification spec) {
		super(cryptoScanner,factAtStmt.stmt(),factAtStmt.fact());
		this.spec = spec;
		analysis = new ExtendedIDEALAnaylsis(){

			@Override
			public StateMachineGraph getStateMachine() {
				return spec.getRule().getUsagePattern();
			}

			@Override
			protected BiDiInterproceduralCFG<Unit, SootMethod> icfg() {
				return cryptoScanner.icfg();
			}

			@Override
			public CrySLAnalysisResultsAggregator analysisListener() {
				return null;
			}};
	}


	@Override
	public String toString() {
		return "AnalysisSeed [" + super.toString() + " with spec " + spec.getRule().getClassName() + "]";
	}

	public void execute() {
		cryptoScanner.getAnalysisListener().seedStarted(this);
		analysis.run(this.asNode(), resultReporter);
		parametersToValues = analysis.getCollectedValues();
		allCallsOnObject = analysis.getInvokedMethodOnInstance();
		cryptoScanner.getAnalysisListener().onSeedFinished(this, solver);
		AnalysisSeedWithSpecification.this.onSeedFinished(this, solver);
		cryptoScanner.getAnalysisListener().seedFinished(this);
		cryptoScanner.getAnalysisListener().collectedValues(this, problem.getCollectedValues());
		final CryptSLRule rule = spec.getRule();
		for (ISLConstraint cons : rule.getConstraints()) {
			if (cons instanceof CryptSLPredicate && ((CryptSLPredicate) cons).isNegated()) {
				cryptoScanner.addDisallowedPredicatePair(rule.getPredicates().get(0), ((CryptSLPredicate) cons).setNegated(false));
			}
		}
	}

	public void onSeedFinished(Node<Statement,Val> seed, ExtendedIDEALAnaylsis solver) {
		// Merge all information (all access graph here point to the seed
		// object)
		cryptoScanner.getAnalysisListener().beforeConstraintCheck(this);
		constraintSolver = new ConstraintSolver(cryptoScanner, spec, parametersToValues, allCallsOnObject, new ConstraintReporter() {
			@Override
			public void constraintViolated(ISLConstraint con, StmtWithMethod unit) {
				cryptoScanner.getAnalysisListener().constraintViolation(AnalysisSeedWithSpecification.this, con, unit);
			}

			@Override
			public void callToForbiddenMethod(ClassSpecification classSpecification, Unit callSite) {
				cryptoScanner.getAnalysisListener().callToForbiddenMethod(classSpecification, new StmtWithMethod(callSite, cryptoScanner.icfg().getMethodOf(callSite)), Lists.newLinkedList());
			}
		});
		cryptoScanner.getAnalysisListener().checkedConstraints(this,constraintSolver.getRelConstraints());
		internalConstraintSatisfied = (0 == constraintSolver.evaluateRelConstraints());
		cryptoScanner.getAnalysisListener().afterConstraintCheck(this);
		results = transform(analysis.getResults());
		Multimap<Unit, StateNode> unitToStates = HashMultimap.create();
		for (Cell<Unit, Val, TransitionFunction> c : results.cellSet()) {
			unitToStates.putAll(c.getRowKey(), c.getValue().getStates());
			for (EnsuredCryptSLPredicate pred : indirectlyEnsuredPredicates) {
				//TODO only maintain indirectly ensured predicate as long as they are not killed by the rule
				cryptoScanner.addNewPred(this, c.getRowKey(), c.getColumnKey(), pred);
			}
		}
		
		computeTypestateErrorUnits(unitToStates);
		computeTypestateErrorsForEndOfObjectLifeTime(solver);
	}

	private HashBasedTable<Unit, Val, TransitionFunction> transform(
			Map<Node<Statement, Val>, TransitionFunction> in) {

		HashBasedTable<Unit, Val, TransitionFunction> out = HashBasedTable.create();
		for(Entry<Node<Statement, Val>, TransitionFunction> e : in.entrySet()){
			out.put(e.getKey().stmt().getUnit().get(), e.getKey().fact(), e.getValue());
		}
		return out;
	}


	private void computeTypestateErrorUnits(Multimap<Unit, StateNode> unitToStates) {
		for (Unit curr : unitToStates.keySet()) {
			Collection<StateNode> stateAtCurrMinusPred = Sets.newHashSet(unitToStates.get(curr));
			for (Unit pred : cryptoScanner.icfg().getPredsOf(curr)) {
				Collection<StateNode> stateAtPred = unitToStates.get(pred);
				stateAtCurrMinusPred.removeAll(stateAtPred);
				for (StateNode newStateAtCurr : stateAtCurrMinusPred) {
					typeStateChangeAtStatement(pred, newStateAtCurr);
					if(newStateAtCurr.equals(ErrorStateNode.v())){
						Set<SootMethod> expectedMethodCalls = expectedMethodsCallsFor(stateAtPred);
						cryptoScanner.getAnalysisListener().typestateErrorAt(this, new StmtWithMethod(pred, cryptoScanner.icfg().getMethodOf(pred)), expectedMethodCalls);
					}
				}
			}
		}
	}


	private Set<SootMethod> expectedMethodsCallsFor(Collection<StateNode> stateAtPred) {
		Set<SootMethod> res = Sets.newHashSet();
		for(StateNode s : stateAtPred){
			res.addAll(problem.getOrCreateTypestateChangeFunction().getEdgesOutOf(s));
		}
		return res;
	}


	private void computeTypestateErrorsForEndOfObjectLifeTime(ExtendedIDEALAnaylsis solver) {
		Map<Node<Statement, Val>, TransitionFunction> endPathOfPropagation = solver.getObjectDestructingStatements(this);
		for (Entry<Node<Statement, Val>, TransitionFunction> c : endPathOfPropagation.entrySet()) {
			for (ITransition n : c.getValue().values()) {
				if (!n.to().isAccepting()) {
					Node<Statement, Val> key = c.getKey();
					Statement s = key.stmt();
					cryptoScanner.getAnalysisListener().typestateErrorEndOfLifeCycle(this, new StmtWithMethod(s.getUnit().get(), s.getMethod()));
				}
			}
		}
	}

	private void typeStateChangeAtStatement(Unit curr, StateNode stateNode) {
		typeStateChange.put(curr, stateNode);
		onAddedTypestateChange(curr, stateNode);
	}

	private void onAddedTypestateChange(Unit curr, StateNode stateNode) {
		for (CryptSLPredicate predToBeEnsured : spec.getRule().getPredicates()) {
			if (predToBeEnsured.isNegated()) {
				continue;
			}

			if (isPredicateGeneratingState(predToBeEnsured, stateNode)) {
				ensuresPred(predToBeEnsured, curr, stateNode);
			}
		}
	}

	private void ensuresPred(CryptSLPredicate predToBeEnsured, Unit currStmt, StateNode stateNode) {
		if (predToBeEnsured.isNegated()) {
			return;
		}
		boolean satisfiesConstraintSytem = checkConstraintSystem();

		for (ICryptSLPredicateParameter predicateParam : predToBeEnsured.getParameters()) {
			if (predicateParam.getName().equals("this")) {
				expectPredicateWhenThisObjectIsInState(stateNode, currStmt, predToBeEnsured, satisfiesConstraintSytem);
			}
		}
		if (currStmt instanceof Stmt && ((Stmt) currStmt).containsInvokeExpr()) {
			InvokeExpr ie = ((Stmt) currStmt).getInvokeExpr();
			SootMethod invokedMethod = ie.getMethod();
			Collection<CryptSLMethod> convert = CryptSLMethodToSootMethod.v().convert(invokedMethod);

			for (CryptSLMethod cryptSLMethod : convert) {
				Entry<String, String> retObject = cryptSLMethod.getRetObject();
				if (!retObject.getKey().equals("_") && currStmt instanceof AssignStmt && predicateParameterEquals(predToBeEnsured.getParameters(),retObject.getKey())) {
					AssignStmt as = (AssignStmt) currStmt;
					Value leftOp = as.getLeftOp();
					Val val = new Val(leftOp, cryptoScanner.icfg().getMethodOf(currStmt));
					expectPredicateOnOtherObject(predToBeEnsured, currStmt, val, satisfiesConstraintSytem);
				}
				int i = 0;
				for (Entry<String, String> p : cryptSLMethod.getParameters()) {
					if(predicateParameterEquals(predToBeEnsured.getParameters(),p.getKey())){
						Value param = ie.getArg(i);
						if (param instanceof Local) {
							Val val = new Val(param, cryptoScanner.icfg().getMethodOf(currStmt));
							expectPredicateOnOtherObject(predToBeEnsured, currStmt, val, satisfiesConstraintSytem);
						}
					}
					i++;
				}

			}

		}
	}

	private boolean predicateParameterEquals(List<ICryptSLPredicateParameter> parameters, String key) {
		for (ICryptSLPredicateParameter predicateParam :parameters) {
			if (key.equals(predicateParam.getName())){
				return true;
			}
		}
		return false;
	}

	private void expectPredicateOnOtherObject(CryptSLPredicate predToBeEnsured, Unit currStmt, Val accessGraph, boolean satisfiesConstraintSytem) {
		boolean matched = false;
		for (ClassSpecification spec : cryptoScanner.getClassSpecifictions()) {
			if(accessGraph.value() == null){
				continue;
			}
			Type baseType = accessGraph.value().getType();
			if (baseType instanceof RefType) {
				RefType refType = (RefType) baseType;
				if (spec.getRule().getClassName().equals(refType.getSootClass().getShortName())) {
					AnalysisSeedWithSpecification seed = cryptoScanner.getOrCreateSeedWithSpec(
						new AnalysisSeedWithSpecification(cryptoScanner, new Node<Statement,Val>(new Statement((Stmt)currStmt,cryptoScanner.icfg().getMethodOf(currStmt)), accessGraph), spec));
					matched = true;
					if (satisfiesConstraintSytem)
						seed.addEnsuredPredicateFromOtherRule(new EnsuredCryptSLPredicate(predToBeEnsured, parametersToValues));
				}
			}
		}
		if (matched)
			return;
		AnalysisSeedWithEnsuredPredicate seed = cryptoScanner.getOrCreateSeed(new Node<Statement,Val>(new Statement((Stmt)currStmt,cryptoScanner.icfg().getMethodOf(currStmt)), accessGraph));
		cryptoScanner.expectPredicate(seed, currStmt, predToBeEnsured);
		if (satisfiesConstraintSytem) {
			seed.addEnsuredPredicate(new EnsuredCryptSLPredicate(predToBeEnsured, parametersToValues));
		}
	}

	private void addEnsuredPredicateFromOtherRule(EnsuredCryptSLPredicate ensuredCryptSLPredicate) {
		indirectlyEnsuredPredicates.add(ensuredCryptSLPredicate);
		if (results == null)
			return;
		for (Cell<Unit, AccessGraph, TypestateDomainValue<StateNode>> c : results.cellSet()) {
			for (EnsuredCryptSLPredicate pred : indirectlyEnsuredPredicates) {
				cryptoScanner.addNewPred(this, c.getRowKey(), c.getColumnKey(), pred);
			}
		}
	}

	private void expectPredicateWhenThisObjectIsInState(StateNode stateNode, Unit currStmt, CryptSLPredicate predToBeEnsured, boolean satisfiesConstraintSytem) {
		cryptoScanner.expectPredicate(this, currStmt, predToBeEnsured);

		if (!satisfiesConstraintSytem)
			return;
		for (Cell<Unit, AccessGraph, TypestateDomainValue<StateNode>> e : results.cellSet()) {
			// TODO check for any reachable state that don't kill
			// predicates.
			if (e.getValue().contains(stateNode)) {
				cryptoScanner.addNewPred(this, e.getRowKey(), e.getColumnKey(), new EnsuredCryptSLPredicate(predToBeEnsured, parametersToValues));
			}
		}
	}


	private boolean checkConstraintSystem() {
		cryptoScanner.getAnalysisListener().beforePredicateCheck(this);
		List<ISLConstraint> relConstraints = constraintSolver.getRelConstraints();
		boolean checkPredicates = checkPredicates(relConstraints);
		cryptoScanner.getAnalysisListener().afterPredicateCheck(this);
		if (!checkPredicates)
			return false;
		return internalConstraintSatisfied;
	}

	private boolean checkPredicates(List<ISLConstraint> relConstraints) {
		List<CryptSLPredicate> requiredPredicates = Lists.newArrayList();
		for (ISLConstraint con : relConstraints) {
			if (con instanceof CryptSLPredicate && !ConstraintSolver.predefinedPreds.contains(((CryptSLPredicate) con).getPredName())) {
				requiredPredicates.add((CryptSLPredicate) con);
			}
		}
		Set<CryptSLPredicate> remainingPredicates = Sets.newHashSet(requiredPredicates);
		for (CryptSLPredicate pred : requiredPredicates) {
			if (pred.isNegated()) {
				for (EnsuredCryptSLPredicate ensPred : ensuredPredicates) {
					if (ensPred.getPredicate().equals(pred))
						return false;
				}
				remainingPredicates.remove(pred);
			} else {
				for (EnsuredCryptSLPredicate ensPred : ensuredPredicates) {
					if (ensPred.getPredicate().equals(pred) && doPredsMatch(pred, ensPred)) {
						remainingPredicates.remove(pred);
					}
				}
			}
		}
		for (CryptSLPredicate rem : Lists.newArrayList(remainingPredicates)) {
			final ISLConstraint conditional = rem.getConstraint();
			if (conditional != null) {
				if (constraintSolver.evaluate(conditional) != null) {
					remainingPredicates.remove(rem);
				}
			}
		}
		
		this.missingPredicates  = Sets.newHashSet(remainingPredicates);
		return remainingPredicates.isEmpty();
	}

	private boolean doPredsMatch(CryptSLPredicate pred, EnsuredCryptSLPredicate ensPred) {
		boolean requiredPredicatesExist = true;
		for (int i = 0; i < pred.getParameters().size(); i++) {
			String var = pred.getParameters().get(i).getName();
			if (isOfNonTrackableType(var)) {
				continue;
			} else if (pred.getInvolvedVarNames().contains(var)) {

				final String parameterI = ensPred.getPredicate().getParameters().get(i).getName();
				Collection<String> actVals = null;
				Collection<String> expVals = null;

				for (CallSiteWithParamIndex cswpi : ensPred.getParametersToValues().keySet()) {
					if (cswpi.getVarName().equals(parameterI)) {
						actVals = retrieveValueFromUnit(cswpi, ensPred.getParametersToValues().get(cswpi));
					}
				}
				for (CallSiteWithParamIndex cswpi : parametersToValues.keySet()) {
					if (cswpi.getVarName().equals(var)) {
						expVals = retrieveValueFromUnit(cswpi, parametersToValues.get(cswpi));
					}
				}

				String splitter = "";
				int index = -1;
				if (pred.getParameters().get(i) instanceof CryptSLObject) {
					CryptSLObject obj = (CryptSLObject) pred.getParameters().get(i);
					if (obj.getSplitter() != null) {
						splitter = obj.getSplitter().getSplitter();
						index = obj.getSplitter().getIndex();
					}
				}
				for (String foundVal : expVals) {
					if (index > -1) {
						foundVal = foundVal.split(splitter)[index];
					}
					requiredPredicatesExist &= actVals.contains(foundVal);
				}
			} else {
				requiredPredicatesExist = false;
			}
		}
		return pred.isNegated() != requiredPredicatesExist;
	}

	private Collection<String> retrieveValueFromUnit(CallSiteWithParamIndex cswpi, Collection<Unit> collection) {
		Collection<String> values = new ArrayList<String>();
		for (Unit u : collection) {
			if (cswpi.stmt().getUnit().get().equals(u)) {
				if (u instanceof AssignStmt) {
					values.add(retrieveConstantFromValue(((AssignStmt) u).getRightOp().getUseBoxes().get(cswpi.getIndex()).getValue()));
				} else {
					values.add(retrieveConstantFromValue(u.getUseBoxes().get(cswpi.getIndex()).getValue()));
				}
			} else if (u instanceof AssignStmt) {
				final Value rightSide = ((AssignStmt) u).getRightOp();
				if (rightSide instanceof Constant) {
					values.add(retrieveConstantFromValue(rightSide));
				} else {
					final List<ValueBox> useBoxes = rightSide.getUseBoxes();

					//					varVal.put(callSite.getVarName(), retrieveConstantFromValue(useBoxes.get(callSite.getIndex()).getValue()));
				}
			}
			//			if (u instanceof AssignStmt) {
			//				final List<ValueBox> useBoxes = ((AssignStmt) u).getRightOp().getUseBoxes();
			//				if (!(useBoxes.size() <= cswpi.getIndex())) {
			//					values.add(retrieveConstantFromValue(useBoxes.get(cswpi.getIndex()).getValue()));
			//				} 
			//			} else 	if (cswpi.getStmt().equals(u)) {
			//				values.add(retrieveConstantFromValue(cswpi.getStmt().getUseBoxes().get(cswpi.getIndex()).getValue()));
			//			}
		}
		return values;
	}

	private String retrieveConstantFromValue(Value val) {
		if (val instanceof StringConstant) {
			return ((StringConstant) val).value;
		} else if (val instanceof IntConstant || val.getType() instanceof IntType) {
			return val.toString();
		} else {
			return "";
		}
	}

	private final static List<String> trackedTypes = Arrays.asList("java.lang.String", "int", "java.lang.Integer");

	private boolean isOfNonTrackableType(String varName) {
		for (Entry<String, String> object : spec.getRule().getObjects()) {
			if (object.getValue().equals(varName) && trackedTypes.contains(object.getKey())) {
				return false;
			}
		}
		return true;
	}

	public ClassSpecification getSpec() {
		return spec;
	}

	public void addEnsuredPredicate(EnsuredCryptSLPredicate ensPred) {
		if (ensuredPredicates.add(ensPred)) {
			for (Entry<Unit, StateNode> e : typeStateChange.entries())
				onAddedTypestateChange(e.getKey(), e.getValue());
		}
	}

	private boolean isPredicateGeneratingState(CryptSLPredicate ensPred, StateNode stateNode) {
		return ensPred instanceof CryptSLCondPredicate && ((CryptSLCondPredicate) ensPred).getConditionalMethods()
			.contains(stateNode) || (!(ensPred instanceof CryptSLCondPredicate) && stateNode.getAccepting());
	}

	@Override
	public boolean contradictsNegations() {
		return false;
	}
	
	public Set<CryptSLPredicate> getMissingPredicates() {
		return missingPredicates;
	}
	
	public Multimap<CallSiteWithParamIndex, Unit> getExtractedValues(){
		return parametersToValues;
	}


	@Override
	public int hashCode() {
		final int prime = 31;
		int result = super.hashCode();
		result = prime * result + ((spec == null) ? 0 : spec.hashCode());
		return result;
	}


	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (!super.equals(obj))
			return false;
		if (getClass() != obj.getClass())
			return false;
		AnalysisSeedWithSpecification other = (AnalysisSeedWithSpecification) obj;
		if (spec == null) {
			if (other.spec != null)
				return false;
		} else if (!spec.equals(other.spec))
			return false;
		return true;
	}
	
}

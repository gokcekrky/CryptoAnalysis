package crypto.boomerang;

import com.google.common.base.Optional;

import boomerang.IntAndStringBoomerangOptions;
import boomerang.jimple.AllocVal;
import boomerang.jimple.Statement;
import boomerang.jimple.Val;
import soot.SootMethod;
import soot.Unit;
import soot.Value;
import soot.jimple.AssignStmt;
import soot.jimple.InstanceInvokeExpr;
import soot.jimple.IntConstant;
import soot.jimple.LengthExpr;
import soot.jimple.NewExpr;
import soot.jimple.NullConstant;
import soot.jimple.ReturnStmt;
import soot.jimple.Stmt;
import soot.jimple.StringConstant;
import soot.jimple.toolkits.ide.icfg.BiDiInterproceduralCFG;

/**
 * Created by johannesspath on 23.12.17.
 */
public class CogniCryptIntAndStringBoomerangOptions extends IntAndStringBoomerangOptions {
    @Override
    public Optional<AllocVal> getAllocationVal(SootMethod m, Stmt stmt, Val fact, BiDiInterproceduralCFG<Unit, SootMethod> icfg) {
        if (stmt.containsInvokeExpr() && stmt instanceof AssignStmt) {
            AssignStmt as = (AssignStmt) stmt;
            if (as.getLeftOp().equals(fact.value())) {
                if (icfg.getCalleesOfCallAt(stmt).isEmpty())
                    return Optional.of(new AllocVal(as.getLeftOp(), m, as.getRightOp(),new Statement(as, m)));
            }
        }
        if(stmt.containsInvokeExpr()){
        		if(stmt.getInvokeExpr().getMethod().isConstructor() && (stmt.getInvokeExpr() instanceof InstanceInvokeExpr)) {
        			InstanceInvokeExpr iie = (InstanceInvokeExpr) stmt.getInvokeExpr();
        			Value base = iie.getBase();
        			if (base.equals(fact.value())) {
        				return Optional.of(new AllocVal(base,m,base,new Statement(stmt,m)));
        			}
        		}
		}

        if (!(stmt instanceof AssignStmt)) {
			return Optional.absent();
		}
		AssignStmt as = (AssignStmt) stmt;
		if (!as.getLeftOp().equals(fact.value())) {
			return Optional.absent();
		}
		if(as.getRightOp() instanceof LengthExpr){
			return Optional.of(new AllocVal(as.getLeftOp(), m,as.getRightOp(), new Statement(stmt,m)));
		}
		if(as.containsInvokeExpr()){
			for(SootMethod callee : icfg.getCalleesOfCallAt(as)){
				for(Unit u : icfg.getEndPointsOf(callee)){
					if(u instanceof ReturnStmt && isAllocationVal(((ReturnStmt) u).getOp())){
						return Optional.of(new AllocVal(as.getLeftOp(), m,((ReturnStmt) u).getOp(),new Statement((Stmt) u,m)));
					}
				}
			}
		}
		if(isAllocationVal(as.getRightOp())) {
			return Optional.of(new AllocVal(as.getLeftOp(), m,as.getRightOp(),new Statement(stmt,m)));
		}

		return Optional.absent();
    }

    
    @Override
	public boolean isAllocationVal(Value val) {
		if(val instanceof IntConstant){
			return true;
		}
		if (!trackStrings() && isStringAllocationType(val.getType())) {
			return false;
		}
		if(trackNullAssignments() && val instanceof NullConstant){
			return true;
		}
		if(arrayFlows() && isArrayAllocationVal(val)){
			return true;
		}
		if(trackStrings() && val instanceof StringConstant){
			return true;
		}	
		if (!trackAnySubclassOfThrowable() && isThrowableAllocationType(val.getType())) {
			return false;
		}
		
		return false;
	}

    @Override
    public boolean onTheFlyCallGraph() {
        return false;
    }

    @Override
    public boolean arrayFlows() {
        return true;
    }

    @Override
    public int analysisTimeoutMS() {
    	return 5000;
    }
}

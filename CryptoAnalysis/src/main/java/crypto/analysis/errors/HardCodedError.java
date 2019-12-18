package crypto.analysis.errors;

import crypto.analysis.IAnalysisSeed;
import crypto.extractparameter.CallSiteWithExtractedValue;
import de.darmstadt.tu.crossing.interfaces.ISLConstraint;
import de.darmstadt.tu.crossing.rules.CrySLRule;

public class HardCodedError extends ConstraintError {

	public HardCodedError(CallSiteWithExtractedValue cs, CrySLRule rule, IAnalysisSeed objectLocation, ISLConstraint con) {
		super(cs, rule, objectLocation, con);
	}

}

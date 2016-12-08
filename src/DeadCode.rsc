module DeadCode
import lang::java::\syntax::Java18;
import ParseTree; 
import IO;
import Set; 

/*
Deteccao e remocao de atribuicoes que não usadas.
Base: Live variables do livro Principles of Progam Analysis

*/

bool checkExpUse(Identifier left, CompilationUnit unit) {
  bool res = false;
  top-down-break visit(unit) {
    case (Assignment)`<LeftHandSide dst> <AssignmentOperator op> <Identifier y>`: { 
    	res = (left == y);
    }
    
    case (Assignment)`<LeftHandSide dst> <AssignmentOperator op> <MethodInvocation m>`:{
    	res = checkMethodArgs(m, left);
    }
  }
  return res; 
}

bool checkMethodArgs(MethodInvocation m, Identifier left){
	bool res = false;
	top-down-break visit(m){
		case (MethodInvocation)`<MethodName mName>(<Identifier arg>)`: {
			res = (arg == left);
		}
	}
	return res;
}


CompilationUnit removeDeadAssignment(CompilationUnit unit) =  visit(unit){
        
    case (Assignment) `<Identifier leftId> <AssignmentOperator s> <Expression rightExp>`  => 
    (Assignment) `<Identifier leftId> <AssignmentOperator s> eliminado` when !checkExpUse(leftId, unit)
    
    case (PostfixExpression) `<Identifier pExp>`  => 
    (PostfixExpression) `eliminado` when !checkExpUse(pExp, unit)
};


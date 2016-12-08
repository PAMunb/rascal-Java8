module DeadCode
import lang::java::\syntax::Java18;
import ParseTree; 
import IO;
import Set; 

/*
Deteccao e remocao de atribuicoes que não usadas.
Base: Live variables do livro Principles of Progam Analysis

*/

bool CheckExpUse(Identifier left, CompilationUnit unit) {
  bool res = false;
  visit(unit) {
    case (Assignment)`<LeftHandSide dst> <AssignmentOperator op> <Identifier y>`: { 
    	println("*********");
    	print(left); print("--"); 	
    	print(y); print("===");
    	res = (left == y);
    }   
  }
  return res; 
}

/*
CompilationUnit removeDeadAssignment(CompilationUnit unit) =  visit(unit){
    case (Assignment) `<LeftHandSide leftExp> <AssignmentOperator s> <Expression rightExp>`  => 
    (Assignment) `<LeftHandSide leftExp> <AssignmentOperator s> 22` when CheckExpUse(leftExp, rightExp)  
};
*/

CompilationUnit removeDeadAssignment(CompilationUnit unit) =  visit(unit){
    case (Assignment) `<Identifier leftId> <AssignmentOperator s> <Expression rightExp>`  => 
    (Assignment) `<Identifier leftId> <AssignmentOperator s> <Expression rightExp>` when CheckExpUse(leftId, unit)
    
    case (Assignment) `<Identifier leftId> <AssignmentOperator s> <Expression rightExp>`  => 
    (Assignment) `<Identifier leftId> <AssignmentOperator s> eliminado` when !CheckExpUse(leftId, unit)
};


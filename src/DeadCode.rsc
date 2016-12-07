module DeadCode
import lang::java::\syntax::Java18;
import ParseTree; 
import IO;
import Set; 

/*
Deteccao e remocao de atribuicoes que não usadas.
Base: Live variables do livro Principles of Progam Analysis

*/

int countAssignment(CompilationUnit unit) {
  int res = 0;
  
  visit(unit) {
    case (Assignment) `<Identifier id> <AssignmentOperator s> <Expression e>` : { res += 1; }  
   
  }
  return res; 
}

CompilationUnit removeDeadAssignment(CompilationUnit unit) =  visit(unit){
    case (Assignment) `<Identifier id> <AssignmentOperator s> <Expression e>`  => 
    (Assignment) `<Identifier id> <AssignmentOperator s> 22`   
   
}; 
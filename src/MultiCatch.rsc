module MultiCatch

import lang::java::\syntax::Java18;
import ParseTree; 
import IO;
import Map;
import Type; 
import List;
import Set;

// sure, I don't like global variables. 
//
// However I could not find a way to perform both 
// a replacement and count the number of times 
// it was applied in the same compilation unit. 
//int numberOfOccurences = 0; 

/**
 * Refactor a try-catch statement to use the 
 * MultiCatch construct of Java 7. 
 */
public tuple[int, CompilationUnit]  refactorMultiCatch(CompilationUnit unit) { 
  int numberOfOccurences = 0;  
  CompilationUnit cu =  visit(unit) {
   case (TryStatement)`try <Block b1> <Catches c1>` : { 
     <app, mc> = computeMultiCatches(c1);
     if(app) numberOfOccurences += 1;
     insert (TryStatement)`try <Block b1><Catches mc>`;
   }
  };
  return <numberOfOccurences, cu>;
}

/*
 * Based on a simple notion of similarity, 
 * this function calculates the possible 
 * occurences of MultiCatch. 
 */ 
private tuple[bool, Catches] computeMultiCatches(cs){
   map [Block, tuple[list[CatchType], VariableDeclaratorId, Block] ] mCatches = ();
   app = false;
   visit(cs){
      case(CatchClause)`catch (<CatchType t> <VariableDeclaratorId vId>) <Block b>` :{
         if (b in mCatches){
            <ts, vId, blk> = mCatches[b];
            ts += t;
            mCatches[b] = <ts, vId, blk>;
            app = true;
         }
         else{
            mCatches[b] = <[t], vId, b>;
         }
      }
   }
   if(app) {
      return <app, generateMultiCatches([mCatches[b] | b <- mCatches])>; 
   }
   return <false, cs>; // this return statement occurs when we find a try ... finally, without catch!
}

/*
 * Creates a syntactic catch clause (either a simple one or 
 * a multicatch). 
 * 
 * This is a recursive definition. The base case expects only 
 * one tuple, and than it returns a single catch clause. In the 
 * recursive definition, at least two tuples must be passed as 
 * arguments, and thus it returns at least two catches clauses 
 * (actually, one catch clause for each element in the list)
 */
private Catches generateMultiCatches([<ts, vId, b>]) = {
  types = parse(#CatchType, intercalate("| ", ts));
  return (Catches)`catch(<CatchType types> <VariableDeclaratorId vId>) /*multi-catch refactor*/ <Block b>`; 
};
private Catches generateMultiCatches([<ts, vId, b>, C*]) = {
  catches = generateMultiCatches(C);
  types = parse(#CatchType, intercalate("| ", ts));
  return (Catches)`catch(<CatchType types> <VariableDeclaratorId vId>) <Block b> <CatchClause+ catches>`;
};


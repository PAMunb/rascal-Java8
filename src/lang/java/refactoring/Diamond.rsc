module lang::java::refactoring::Diamond 

import lang::java::\syntax::Java18;
import ParseTree; 
import IO;
import List;
import Node;

/**
 * Refactor a compilation unit to use Diamond. 
 */
public tuple[int, CompilationUnit] refactorDiamond(CompilationUnit unit) {
  int numberOfOccurences = 0;
  CompilationUnit cu = visit(unit) 
  {
    //case (FieldDeclaration)`<FieldModifier fm> <Identifier idt><TypeArguments tas><VariableDeclaratorId vdId>= new <{AnnotatedType "."}* aType>(<ArgumentList? al>);` : {
    //  numberOfOccurences += 1;  
    //  insert((FieldDeclaration)`<FieldModifier fm> <Identifier idt><TypeArguments tas> <VariableDeclaratorId vdId>= new <{AnnotatedType "."}* aType>\<\>(<ArgumentList? al>);`);
    //}
    //case (FieldDeclaration)`<Identifier idt><TypeArguments tas><VariableDeclaratorId vdId>= new <{AnnotatedType "."}* aType>(<ArgumentList? al>);` : {
    //  numberOfOccurences += 1;  
    //  insert((FieldDeclaration)`<Identifier idt><TypeArguments tas> <VariableDeclaratorId vdId>= new <{AnnotatedType "."}* aType>\<\>(<ArgumentList? al>);`);
    //}  
    case (FieldDeclaration)`<FieldModifier fm> <Identifier idt><TypeArguments tas><VariableDeclaratorId vdId> = new <{AnnotatedType "."}* aType><TypeArguments args>(<ArgumentList? al>);` : {
      if(args !:= (TypeArguments)`\<\>`){
        numberOfOccurences += 1;
        insert((FieldDeclaration)`<FieldModifier fm> <Identifier idt><TypeArguments tas> <VariableDeclaratorId vdId>= new <{AnnotatedType "."}* aType>\<\>(<ArgumentList? al>);`);
      }
    }
    case (FieldDeclaration)`<Identifier idt><TypeArguments tas><VariableDeclaratorId vdId> = new <{AnnotatedType "."}* aType><TypeArguments args>(<ArgumentList? al>);` : {
      if(args !:= (TypeArguments)`\<\>`){
        numberOfOccurences += 1;
        insert((FieldDeclaration)`<Identifier idt><TypeArguments tas> <VariableDeclaratorId vdId>= new <{AnnotatedType "."}* aType>\<\>(<ArgumentList? al>);`);
      }
    }    
    //case (LocalVariableDeclaration)`<VariableModifier vm> <Identifier idt><TypeArguments tas><VariableDeclaratorId vdId> = new <{AnnotatedType "."}* aType>(<ArgumentList? al>)` : {
    //  numberOfOccurences += 1;  
    //  insert((LocalVariableDeclaration)`<VariableModifier vm> <Identifier idt><TypeArguments tas> <VariableDeclaratorId vdId>= new <{AnnotatedType "."}* aType>\<\>(<ArgumentList? al>)`);
    //}
    //case (LocalVariableDeclaration)`<Identifier idt><TypeArguments tas><VariableDeclaratorId vdId> = new <{AnnotatedType "."}* aType>(<ArgumentList? al>)` : {
    //  numberOfOccurences += 1;  
    //  insert((LocalVariableDeclaration)`<Identifier idt><TypeArguments tas> <VariableDeclaratorId vdId>= new <{AnnotatedType "."}* aType>\<\>(<ArgumentList? al>)`);
    //}    
    case (LocalVariableDeclaration)      `<VariableModifier vm> <Identifier idt><TypeArguments tas><VariableDeclaratorId vdId> = new <{AnnotatedType "."}* aType><TypeArguments args>(<ArgumentList? al>)` : {
       if(args !:= (TypeArguments)`\<\>`){
        numberOfOccurences += 1;
        insert((LocalVariableDeclaration)`<VariableModifier vm> <Identifier idt><TypeArguments tas> <VariableDeclaratorId vdId>= new <{AnnotatedType "."}* aType>\<\>(<ArgumentList? al>)`);
      }
    }
    case (LocalVariableDeclaration)`<Identifier idt><TypeArguments tas><VariableDeclaratorId vdId> = new <{AnnotatedType "."}* aType><TypeArguments args>(<ArgumentList? al>)` : {
      if(args !:= (TypeArguments)`\<\>`){
        numberOfOccurences += 1;
        insert((LocalVariableDeclaration)`<Identifier idt><TypeArguments tas> <VariableDeclaratorId vdId>= new <{AnnotatedType "."}* aType>\<\>(<ArgumentList? al>)`);
      }
    }    
  };
  return <numberOfOccurences, cu>;
}
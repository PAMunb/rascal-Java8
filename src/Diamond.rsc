module Diamond 

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
  CompilationUnit cu = visit(unit) {
    //Case where generics isn't especified
    case (ClassOrInterfaceTypeToInstantiate)`<{AnnotatedType "."}* aType>` : {
      numberOfOccurences += 1; 
      insert (ClassOrInterfaceTypeToInstantiate)`<{AnnotatedType "."}* aType>\<\>`;
    }
    //Case where generics may be simplified to <>
    case (ClassOrInterfaceTypeToInstantiate)`<{AnnotatedType "."}* aType> <TypeArguments args>` : {
      numberOfOccurences += 1; 
      print("**args: " + args + "\n");
      if (/`\<\>`/ !:= toString(args))
      {
        print("passou\n");	
      	insert (ClassOrInterfaceTypeToInstantiate)`<{AnnotatedType "."}* aType>\<\>`;
      }
    }    
        
  };
  return <numberOfOccurences, cu>;
}

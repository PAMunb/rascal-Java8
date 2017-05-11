module refactor::forloop::ProspectiveOperationTestResources

import IO;
import lang::java::\syntax::Java18;
import ParseTree;
import MethodVar;
import LocalVariablesFinder;

public tuple [set[MethodVar] vars, EnhancedForStatement loop] simpleShort() {
	fileLoc = |project://rascal-Java8//testes/ProspectiveOperation/SimpleShortEnhancedLoop|;
	enhancedForLoop = parse(#EnhancedForStatement, readFile(fileLoc));
	return <{}, enhancedForLoop>; 
}

public tuple [set[MethodVar] vars, EnhancedForStatement loop] continueAndReturn() {
	fileLoc = |project://rascal-Java8//testes/ProspectiveOperation/ContinueAndReturnEnhancedLoop|;
	enhancedForLoop = parse(#EnhancedForStatement, readFile(fileLoc));
	return <continueAndReturnVars(), enhancedForLoop>; 
}

private set[MethodVar] continueAndReturnVars() {
	methodHeader = parse(#MethodHeader, "boolean isEngineExisting(String grammarName)");
	methodBody = parse(#MethodBody, "{\n for(GrammarEngine e : importedEngines) { \n if(e.getGrammarName() == null) continue; \n if(e.getGrammarName().equals(grammarName))\n return true; \n } \n return false; \n}" );
	return findLocalVariables(methodHeader, methodBody);
}

public tuple [set[MethodVar] vars, EnhancedForStatement loop] filterMapReduce() {
	fileLoc = |project://rascal-Java8//testes/ProspectiveOperation/FilterMapReduceEnhancedLoop|;
	enhancedForLoop = parse(#EnhancedForStatement, readFile(fileLoc));
	return <filterMapReduceVars(), enhancedForLoop>; 
}

private set[MethodVar] filterMapReduceVars() {
	methodHeader = parse(#MethodHeader, "int getNumberOfErrors()");
	methodBody = parse(#MethodBody, "{\n    int count = 0;\n    for (ElementRule rule : getRules()) {\n      if(rule.hasErrors())\n        count += rule.getErrors().size();\n    }\n    return count;\n  }");
	return findLocalVariables(methodHeader, methodBody);
}

public tuple [set[MethodVar] vars, EnhancedForStatement loop] filterAndMergedForEach() {
	fileLoc = |project://rascal-Java8//testes/ProspectiveOperation/FilterAndMergedForEach|;
	enhancedForLoop = parse(#EnhancedForStatement, readFile(fileLoc));
	return <filterAndMergedForEachVars(), enhancedForLoop>; 
}

private set[MethodVar] filterAndMergedForEachVars() {
	methodHeader = parse(#MethodHeader, "List\<String\> findReloadedContextMemoryLeaks()");
	methodBody = parse(#MethodBody, "{\n    List\<String\> result = new ArrayList\<String\>();\n    for (Map.Entry\<ClassLoader, String\> entry :\n        childClassLoaders.entrySet())\n      if(isValid(entry)) {\n        ClassLoader cl = entry.getKey();\n        if (!((WebappClassLoader)cl).isStart())\n          result.add(entry.getValue());\n      }\n   return result;\n  }");
	return findLocalVariables(methodHeader, methodBody);
}

public tuple [set[MethodVar] vars, EnhancedForStatement loop] multipleMapsAndEndingReducer() {
	fileForLoc = |project://rascal-Java8//testes/ForLoopToFunctional/T2For2.java|;
	EnhancedForStatement forStmt = parse(#EnhancedForStatement, readFile(fileForLoc));
	return <multipleMapsAndEndingReducerVars(), forStmt>;
}

private set[MethodVar] multipleMapsAndEndingReducerVars() {
	fileLoc = |project://rascal-Java8//testes/ForLoopToFunctional/T2.java|;
	methodBody = parse(#MethodBody, readFile(fileLoc));
	methodHeader = parse(#MethodHeader, "void assertInvariants(Map\<K, V\> map)");
	return findLocalVariables(methodHeader, methodBody);
}

public tuple [set[MethodVar] vars, EnhancedForStatement loop] innerLoop1() {
	fileForLoc = |project://rascal-Java8//testes/ForLoopToFunctional/InnerLoop1.java|;
	EnhancedForStatement forStmt = parse(#EnhancedForStatement, readFile(fileForLoc));
	return <innerLoop1Vars(), forStmt>;
}

private set[MethodVar] innerLoop1Vars() {
	fileLoc = |project://rascal-Java8//testes/ForLoopToFunctional/MethodBodyInnerLoop1.java|;
	methodBody = parse(#MethodBody, readFile(fileLoc));
	methodHeader = parse(#MethodHeader, "\<N\> Graph\<N\> transitiveClosure(Graph\<N\> graph)");
	return findLocalVariables(methodHeader, methodBody);
}

public tuple [set[MethodVar] vars, EnhancedForStatement loop] innerLoop2() {
	fileForLoc = |project://rascal-Java8//testes/ForLoopToFunctional/InnerLoop2.java|;
	EnhancedForStatement forStmt = parse(#EnhancedForStatement, readFile(fileForLoc));
	return <innerLoop2Vars(), forStmt>;
}

private set[MethodVar] innerLoop2Vars() {
	fileLoc = |project://rascal-Java8//testes/ForLoopToFunctional/MethodBodyInnerLoop2.java|;
	methodBody = parse(#MethodBody, readFile(fileLoc));
	methodHeader = parse(#MethodHeader, "ImmutableList\<Method\> getAnnotatedMethodsNotCached(Class\<?\> clazz)");
	return findLocalVariables(methodHeader, methodBody);
}

public tuple [set[MethodVar] vars, EnhancedForStatement loop] loopWithInnerWhile() {
	fileForLoc = |project://rascal-Java8//testes/ForLoopToFunctional/LoopWithInnerWhile.java|;
	EnhancedForStatement forStmt = parse(#EnhancedForStatement, readFile(fileForLoc));
	return <loopWithInnerWhileVars(), forStmt>;
}

private set[MethodVar] loopWithInnerWhileVars() {
	fileLoc = |project://rascal-Java8//testes/ForLoopToFunctional/MethodBodyLoopWithInnerWhile.java|;
	methodBody = parse(#MethodBody, readFile(fileLoc));
	methodHeader = parse(#MethodHeader, "ImmutableList\<Method\> getAnnotatedMethodsNotCached(Class\<?\> clazz)");
	return findLocalVariables(methodHeader, methodBody);
}

public tuple [set[MethodVar] vars, EnhancedForStatement loop] loopReduceWithPostIncrement() {
	forStmt = parse(#EnhancedForStatement, "for (Entry\<E\> entry : entries) {\n      elementsBuilder.add(entry.getElement());\n      // cumulativeCounts[i + 1] = cumulativeCounts[i] + entry.getCount();\n      i++;\n    }");
	return <loopReduceWithPostIncrementVars(), forStmt>;
}

private set[MethodVar] loopReduceWithPostIncrementVars() {
	methodHeader = parse(#MethodHeader, "\<E\> ImmutableSortedMultiset\<E\> copyOfSortedEntries(Comparator\<? super E\> comparator, Collection\<Entry\<E\>\> entries)");
	methodBodyLoc = |project://rascal-Java8/testes/localVariables/MethodBodyReduceWithPostIncrement|;
  	methodBody = parse(#MethodBody, readFile(methodBodyLoc));
	return findLocalVariables(methodHeader, methodBody);
}

public tuple [set[MethodVar] vars, EnhancedForStatement loop] loopWithThrowStatement() {
	forStmt = parse(#EnhancedForStatement, "for (K key : keysToLoad) {\n            V value = newEntries.get(key);\n            if (value == null) {\n              throw new InvalidCacheLoadException(\"loadAll failed to return a value for \" + key);\n            }\n            result.put(key, value);\n          }");
	return <loopWithThrowStatementVars(), forStmt>;
}

private set[MethodVar] loopWithThrowStatementVars() {
	methodHeader = parse(#MethodHeader, "ImmutableMap\<K, V\> getAll(Iterable\<? extends K\> keys) throws ExecutionException");
	methodBodyLoc = |project://rascal-Java8//testes/localVariables/MethodBodyPostDecrementedVar|;
  	methodBody = parse(#MethodBody, readFile(methodBodyLoc));
	return findLocalVariables(methodHeader, methodBody);
}

public tuple [set[MethodVar] vars, EnhancedForStatement loop] loopWithIfWithTwoStatementsInsideBlock() {
	fileForLoc = |project://rascal-Java8//testes/ForLoopToFunctional/ForIfWithTwoStmtsInsideAndStmtAfterBlock.java|;
	EnhancedForStatement forStmt = parse(#EnhancedForStatement, readFile(fileForLoc));
	return <loopWithIfWithTwoStatementsInsideBlockVars(), forStmt>;
}

private set[MethodVar] loopWithIfWithTwoStatementsInsideBlockVars() {
	methodHeader = parse(#MethodHeader, "String[] getPaths(EndpointHandlerMapping endpointHandlerMapping)");
	methodBodyLoc = |project://rascal-Java8//testes/ForLoopToFunctional/MethodBodyIfWithTwoStmtsInsideAndStmtAfterBlock.java|;
  	methodBody = parse(#MethodBody, readFile(methodBodyLoc));
	return findLocalVariables(methodHeader, methodBody);
}
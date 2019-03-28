class DeadCodeExamples {
	int teste(int var)
	{
		return var;
	}
	
	void casos()
	{
		int x,y,z;
		
		//Teste 1 - eliminando atribuicoes
		/*
		y = 4;
		x = 2;
		z = 4;
		*/
		
		//Teste 2 - preservando alguma coisa
		/*
		y = 4;
		x = 2;
		z = y;
		*/
		
		//Teste 3 - variavel usada como parametro
		/*
		y = 4;
		x = 2;
		z = teste(y);
		*/
		
		//Teste 4 - PostfixExpression
		
		y = 4;
		x = 2;
		z = y;
		y++;
		x++;
		
	}
}
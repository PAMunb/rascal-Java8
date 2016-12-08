class DeadCodeExamples {
	int teste()
	{
		int x = 1;
		if(x == 1)
		{return true;}
		else{return false;}
	}
	
	void deadCode()
	{
		int x = 1,y;
		y = 4;
		x = 2;
		x = y;
		
	}
}
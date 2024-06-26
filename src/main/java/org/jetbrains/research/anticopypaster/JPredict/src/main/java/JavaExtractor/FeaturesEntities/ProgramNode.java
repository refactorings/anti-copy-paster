package org.jetbrains.research.anticopypaster.JPredict.src.main.java.JavaExtractor.FeaturesEntities;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import org.jetbrains.research.anticopypaster.JPredict.src.main.java.JavaExtractor.Common.Common;

public class ProgramNode {
	public int Id;
	public String Type;
	public String Name;
	public boolean IsMethodDeclarationName;
	
	public ProgramNode(String name) {
		Name = name;
		try {
			Name = URLEncoder.encode(name, Common.UTF8);
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}
	}
}

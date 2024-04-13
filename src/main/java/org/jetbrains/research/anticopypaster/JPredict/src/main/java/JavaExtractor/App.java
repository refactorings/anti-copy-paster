package org.jetbrains.research.anticopypaster.JPredict.src.main.java.JavaExtractor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

import org.jetbrains.research.anticopypaster.JPredict.src.main.java.JavaExtractor.FeaturesEntities.ProgramFeatures;
import org.kohsuke.args4j.CmdLineException;

import org.jetbrains.research.anticopypaster.JPredict.src.main.java.JavaExtractor.Common.CommandLineValues;
import org.jetbrains.research.anticopypaster.JPredict.src.main.java.JavaExtractor.FeaturesEntities.ProgramRelation;

public class App {
	private static CommandLineValues s_CommandLineValues;

	public static ArrayList<ProgramFeatures> execute(String[] args) {
		try {
			s_CommandLineValues = new CommandLineValues(args);
		} catch (CmdLineException e) {
			e.printStackTrace();
			return null;
		}

		if (s_CommandLineValues.NoHash) {
			ProgramRelation.setNoHash();
		}

		if (s_CommandLineValues.File != null) {
			ExtractFeaturesTask extractFeaturesTask = new ExtractFeaturesTask(s_CommandLineValues,
					s_CommandLineValues.File);
			return extractFeaturesTask.processFile();
		} else if (s_CommandLineValues.Dir != null) {
			extractDir();
		}
		return null;
	}

	private static void extractDir() {
		ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(s_CommandLineValues.NumThreads);
		LinkedList<ExtractFeaturesTask> tasks = new LinkedList<>();
		try {
			Files.walk(Paths.get(s_CommandLineValues.Dir)).filter(Files::isRegularFile)
					.filter(p -> p.toString().toLowerCase().endsWith(".java")).forEach(f -> {
						ExtractFeaturesTask task = new ExtractFeaturesTask(s_CommandLineValues, f.toString());
						tasks.add(task);
					});
		} catch (IOException e) {
			e.printStackTrace();
			return;
		}
		try {
			executor.invokeAll(tasks);
		} catch (InterruptedException e) {
			e.printStackTrace();
		} finally {
			executor.shutdown();
		}
	}
}

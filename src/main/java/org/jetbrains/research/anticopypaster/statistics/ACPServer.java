
package org.jetbrains.research.anticopypaster.statistics;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.extensions.PluginId;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.research.anticopypaster.JPredict.src.main.java.JavaExtractor.App;
import org.jetbrains.research.anticopypaster.JPredict.src.main.java.JavaExtractor.FeaturesEntities.ProgramFeatures;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;


public class ACPServer implements Runnable{
    @Override
    public void run() {
        try{
            String os = System.getProperty("os.name").toLowerCase();
            String pythonPath = getPythonPath(os);
            String pluginId = "org.jetbrains.research.anticopypaster";
            String pluginPath = PluginManagerCore.getPlugin(PluginId.getId(pluginId)).getPluginPath().toString();
            pluginPath = pluginPath.replace("\\", "/");
            if (pythonPath != null && !pythonPath.isEmpty()) {
                ProcessBuilder builderTest = new ProcessBuilder();
                builderTest.command(pythonPath, pluginPath+"/code2vec/code2vec-master/test.py");
                Process processTest = builderTest.start();
                int exitCode = processTest.waitFor();
                if(exitCode == 0){
                    ServerSocket server = new ServerSocket(8081);
                    ProcessBuilder builder = new ProcessBuilder();
                    builder.command(pythonPath,
                            pluginPath+"/code2vec/code2vec-master/code2vec.py", "--load", pluginPath+"/code2vec/java14m_model/models/java14_model/saved_model_iter8.release", "--predict");
                    Process process = builder.start();
                    Socket py_client = server.accept();
                    PrintWriter out_py = new PrintWriter(py_client.getOutputStream(),true);
                    BufferedReader in_py = new BufferedReader(new InputStreamReader(py_client.getInputStream()));
                    String msg = ("void f(int arr[])\n" +
                            "    {\n" +
                            "        int n = arr.length;\n" +
                            "        for (int i = 1; i < n; ++i) {\n" +
                            "            int key = arr[i];\n" +
                            "            int j = i - 1;\n" +
                            "            while (j >= 0 && arr[j] > key) {\n" +
                            "                arr[j + 1] = arr[j];\n" +
                            "                j = j - 1;\n" +
                            "            }\n" +
                            "            arr[j + 1] = key;\n" +
                            "        }\n" +
                            "    }");
                    String[] args = {
                            "--max_path_length",
                            "8",
                            "--max_path_width",
                            "2",
                            "--file",
                            msg,
                            "--no_hash"
                    };
                    ArrayList<ProgramFeatures> extracted = App.execute(args);
                    out_py.println(extracted);
                    msg = in_py.readLine();
                    while(true){
                        Socket jv_client = server.accept();
                        PrintWriter out_jv = new PrintWriter(jv_client.getOutputStream(),true);
                        BufferedReader in_jv = new BufferedReader(new InputStreamReader(jv_client.getInputStream()));
                        msg = in_jv.readLine();
                        out_py.println(msg);
                        msg = in_py.readLine();
                        out_jv.println(msg);
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Nullable
    private static String getPythonPath(String os) {
        String pythonPath = null;
        if(os.contains("windows")){
            String[] versions = {"38", "39", "310", "311", "312"};

            for (String version : versions) {
                String inC = "C:\\Python"+version+"\\python3.exe";
                String progF = "C:\\Program Files\\Python"+version+"\\python3.exe";
                if (new File(inC).exists()) {
                    pythonPath = inC;
                    break;
                } else if (new File(progF).exists()) {
                    pythonPath = progF;
                    break;
                }
            }
            if (pythonPath == null){
                for (String version : versions){
                    String inC = "C:\\Python"+version+"\\python.exe";
                    String progF = "C:\\Program Files\\Python"+version+"\\python.exe";
                    if (new File(inC).exists()) {
                        pythonPath = inC;
                        break;
                    } else if (new File(progF).exists()) {
                        pythonPath = progF;
                        break;
                    }
                }
            }
        } else if (os.contains("mac")){
            String[] macLocations = {"/Library/Frameworks/Python.framework/Versions/Current/bin/python3", "/usr/bin/python3", "/usr/local/bin/python3"};
            for (String dir : macLocations) {
                if (new File(dir).exists()) {
                    pythonPath = dir;
                    break;
                }
            }
        }
        else{
            String[] otherLocations = {"/usr/bin/python3", "/usr/local/bin/python3"};
            for (String dir : otherLocations) {
                if (new File(dir).exists()) {
                    pythonPath = dir;
                    break;
                }
            }
            String[] versions = {".8", ".9", ".10", ".11", ".12"};
        }
        return pythonPath;
    }
}

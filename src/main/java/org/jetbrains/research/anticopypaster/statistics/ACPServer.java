
package org.jetbrains.research.anticopypaster.statistics;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.extensions.PluginId;
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
            String pythonPath = null;
            if(os.contains("windows")){
                String[] windowsLocations = {"C:\\Python312\\python3.exe", "C:\\Program Files\\Python312\\python3.exe",
                                             "C:\\Python311\\python3.exe", "C:\\Program Files\\Python311\\python3.exe",
                                             "C:\\Python310\\python3.exe", "C:\\Program Files\\Python310\\python3.exe",
                                             "C:\\Python38\\python3.exe", "C:\\Program Files\\Python38\\python3.exe",
                                             "C:\\Python37\\python3.exe", "C:\\Program Files\\Python37\\python3.exe",
                                             "C:\\Python36\\python3.exe", "C:\\Program Files\\Python36\\python3.exe"};
                for (String dir : windowsLocations) {
                    if (new File(dir).exists()) {
                        pythonPath = dir;
                        break;
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
                String[] otherLocations = {"/Library/Frameworks/Python.framework/Versions/Current/bin/python3", "/usr/bin/python3", "/usr/local/bin/python3"};
                for (String dir : otherLocations) {
                    if (new File(dir).exists()) {
                        pythonPath = dir;
                        break;
                    }
                }
            }
            if (pythonPath != null && !pythonPath.isEmpty()) {
                ServerSocket server = new ServerSocket(8081);
                String pluginId = "org.jetbrains.research.anticopypaster";
                String pluginPath = String.valueOf(PluginManagerCore.getPlugin(PluginId.getId(pluginId)).getPluginPath());
                pluginPath = pluginPath.replace("\\", "/");
                //String pluginPath = "/Users/squir/Library/Application Support/JetBrains/IdeaIC2023.2/plugins/AntiCopyPaster";
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
                        " \n" +
                        "            /* Move elements of arr[0..i-1], that are\n" +
                        "               greater than key, to one position ahead\n" +
                        "               of their current position*/\n" +
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
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

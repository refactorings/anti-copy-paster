
package org.jetbrains.research.anticopypaster.statistics;
import com.intellij.ide.plugins.PluginManager;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.extensions.PluginId;
import org.jetbrains.research.anticopypaster.JPredict.src.main.java.JavaExtractor.App;
import org.jetbrains.research.anticopypaster.JPredict.src.main.java.JavaExtractor.FeaturesEntities.ProgramFeatures;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.jetbrains.research.anticopypaster.ide.ExtractionTask.extractEncasedText;

public class ACPServer implements Runnable{
    @Override
    public void run() {
        try{
            ServerSocket server = new ServerSocket(8081);
            String pluginId = "org.jetbrains.research.anticopypaster";
            String pluginPath = String.valueOf(PluginManagerCore.getPlugin(PluginId.getId(pluginId)).getPluginPath());
            pluginPath = pluginPath.replace("\\", "/");
            //String pluginPath = "/Users/squir/Library/Application Support/JetBrains/IdeaIC2023.2/plugins/AntiCopyPaster";
            ProcessBuilder builder = new ProcessBuilder();
            builder.command(pluginPath+"/venv/bin/python3",
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
            /*try (PrintWriter writer = new PrintWriter(new FileWriter("/Users/squir/Documents/GitHub/anti-copy-paster/demofile.txt"))) {
                writer.println(msg);
            } catch (IOException e) {
                e.printStackTrace();
            }*/
            while(true){
                Socket jv_client = server.accept();
                PrintWriter out_jv = new PrintWriter(jv_client.getOutputStream(),true);
                BufferedReader in_jv = new BufferedReader(new InputStreamReader(jv_client.getInputStream()));
                msg = in_jv.readLine();
                out_py.println(msg);
                msg = in_py.readLine();
                out_jv.println(msg);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

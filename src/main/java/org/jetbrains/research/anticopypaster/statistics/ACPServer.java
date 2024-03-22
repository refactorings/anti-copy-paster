
package org.jetbrains.research.anticopypaster.statistics;
import com.intellij.ide.plugins.PluginManager;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.extensions.PluginId;
import org.jetbrains.research.anticopypaster.JPredict.src.main.java.JavaExtractor.App;
import org.jetbrains.research.anticopypaster.JPredict.src.main.java.JavaExtractor.FeaturesEntities.ProgramFeatures;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class ACPServer implements Runnable{
    @Override
    public void run() {
        try{
            String FILE_PATH2 = "C:/Users/squir/OneDrive/Desktop/sem6/extract.txt";
            new FileWriter(FILE_PATH2, false).close();
            FileWriter fileWriter2 = new FileWriter(FILE_PATH2);
            String pluginId = "org.jetbrains.research.anticopypaster";
            String pluginPath = String.valueOf(PluginManagerCore.getPlugin(PluginId.getId(pluginId)).getPluginPath());
            pluginPath = pluginPath.replace("\\", "/");
            ServerSocket server = new ServerSocket(8081);
            ProcessBuilder builder = new ProcessBuilder("python3",
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
            fileWriter2.write(msg);
            fileWriter2.close();
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
    /*private static List<List<String>> extractEncasedText(String input) {
        List<List<String>> result = new ArrayList<>();

        // Regular expressions to match text inside the predictions
        String regexParentheses = "\\(([^)]*)\\)";
        String regexSquareBrackets = "\\[([^\\]]*)\\]";

        // Find matches using the regular expressions
        java.util.regex.Pattern patternParentheses = java.util.regex.Pattern.compile(regexParentheses);
        java.util.regex.Pattern patternSquareBrackets = java.util.regex.Pattern.compile(regexSquareBrackets);
        java.util.regex.Matcher matcherParentheses = patternParentheses.matcher(input);
        java.util.regex.Matcher matcherSquareBrackets = patternSquareBrackets.matcher(input);

        // Extract and store the matches in the result list
        while (matcherParentheses.find() && matcherSquareBrackets.find() && result.size() <= 3) {
            String textInParentheses = matcherParentheses.group(1);
            String textInSquareBrackets = matcherSquareBrackets.group(1);
            textInSquareBrackets = textInSquareBrackets.replaceAll(" ", "");
            textInSquareBrackets = textInSquareBrackets.replaceAll(",", "_");
            textInSquareBrackets = textInSquareBrackets.replaceAll("'", "");
            if(textInSquareBrackets.length() >= 3){
                // Add the new pair to the result list
                List<String> pair = new ArrayList<>();
                pair.add(textInParentheses);
                pair.add(textInSquareBrackets);
                result.add(pair);
            }
        }

        return result;
    }
    public static void main(String[] args) throws IOException {
        String code = "int f(int arr[], int x)\n" +
                "    {\n" +
                "        int l = 0, r = arr.length - 1;\n" +
                "        while (l <= r) {\n" +
                "            int m = l + (r - l) / 2;\n" +
                " \n" +
                "            // Check if x is present at mid\n" +
                "            if (arr[m] == x)\n" +
                "                return m;\n" +
                " \n" +
                "            // If x greater, ignore left half\n" +
                "            if (arr[m] < x)\n" +
                "                l = m + 1;\n" +
                " \n" +
                "            // If x is smaller, ignore right half\n" +
                "            else\n" +
                "                r = m - 1;\n" +
                "        }\n" +
                " \n" +
                "        // If we reach here, then element was\n" +
                "        // not present\n" +
                "        return -1;\n" +
                "    }";
        String[] args2 = {
                "--max_path_length",
                "8",
                "--max_path_width",
                "2",
                "--file",
                code,
                "--no_hash"
        };
        ArrayList<ProgramFeatures> extracted = App.execute(args2);
        List<List<String>> extractedText = null;
        try{
            Socket socket = new Socket("localhost", 8081);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter out = new PrintWriter(socket.getOutputStream(),true);
            out.println(extracted);
            String predictions = in.readLine();
            socket.close();
            extractedText = extractEncasedText(predictions);
        }catch(Exception e){
            System.out.println(e);
        }
        System.out.println(extractedText);
    }*/
}

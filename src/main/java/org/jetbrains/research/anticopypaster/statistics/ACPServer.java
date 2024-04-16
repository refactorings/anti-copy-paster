
package org.jetbrains.research.anticopypaster.statistics;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.extensions.PluginId;
import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.utils.IOUtils;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.research.anticopypaster.JPredict.src.main.java.JavaExtractor.App;
import org.jetbrains.research.anticopypaster.JPredict.src.main.java.JavaExtractor.FeaturesEntities.ProgramFeatures;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.zip.GZIPInputStream;


public class ACPServer implements Runnable{
    @Override
    public void run() {
        try{
            String os = System.getProperty("os.name").toLowerCase();
            String pythonPath = getPythonPath(os);
            String pluginId = "org.jetbrains.research.anticopypaster";
            String pluginPath = PluginManagerCore.getPlugin(PluginId.getId(pluginId)).getPluginPath().toString();
            pluginPath = pluginPath.replace("\\", "/");
            File modelpath = new File(pluginPath+"/code2vec/java14m_model/models/java14_model/dictionaries.bin");
            if(!modelpath.exists()){
                downloadModel(pluginPath);
            }
            if (pythonPath != null && !pythonPath.isEmpty()) {
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
        } catch (Exception e) {
            e.printStackTrace();
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
        }
        return pythonPath;
    }

    private static void downloadModel(String pluginPath){
        try {
            URL website = new URL("https://s3.amazonaws.com/code2vec/model/java14m_model.tar.gz");
            ReadableByteChannel rbc = Channels.newChannel(website.openStream());
            FileOutputStream fos = new FileOutputStream(pluginPath+"/code2vec/java14m_model/java14m_model.tar.gz");
            fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
            File inputFile = new File(pluginPath+"/code2vec/java14m_model/java14m_model.tar.gz");
            File outputFile = new File(pluginPath+"/code2vec/java14m_model", inputFile.getName().substring(0, inputFile.getName().lastIndexOf(".")));
            GZIPInputStream in = new GZIPInputStream(new FileInputStream(pluginPath+"/code2vec/java14m_model/java14m_model.tar.gz"));
            FileOutputStream out = new FileOutputStream(outputFile);
            IOUtils.copy(in, out);
            in.close();
            out.close();
            File inputFileTar = new File(pluginPath+"/code2vec/java14m_model/java14m_model.tar");
            inputFile.delete();
            File outputDir = new File(pluginPath+"/code2vec/java14m_model");
            final List<File> untaredFiles = new LinkedList<File>();
            final InputStream is = new FileInputStream(inputFileTar);
            final TarArchiveInputStream debInputStream = (TarArchiveInputStream) new ArchiveStreamFactory().createArchiveInputStream("tar", is);
            TarArchiveEntry entry = null;
            while ((entry = (TarArchiveEntry)debInputStream.getNextEntry()) != null) {
                final File outputFileTar = new File(outputDir, entry.getName());
                final OutputStream outputFileStream = new FileOutputStream(outputFileTar);
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = debInputStream.read(buffer)) != -1) {
                    outputFileStream.write(buffer, 0, bytesRead);
                }
                outputFileStream.close();
                untaredFiles.add(outputFileTar);
            }
            debInputStream.close();
            inputFileTar.delete();
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (ArchiveException e) {
            throw new RuntimeException(e);
        }
    }
}

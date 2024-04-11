package org.jetbrains.research.anticopypaster.ide;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

public class predHolder implements Runnable{
    public void run(){
        try {
            ServerSocket server = new ServerSocket(8082);
            String msg;
            while(true){
                Socket extraction_task = server.accept();
                BufferedReader in_ex = new BufferedReader(new InputStreamReader(extraction_task.getInputStream()));
                msg = in_ex.readLine();
                Socket jnsp = server.accept();
                PrintWriter out_jnsp = new PrintWriter(jnsp.getOutputStream(),true);
                out_jnsp.println(msg);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}

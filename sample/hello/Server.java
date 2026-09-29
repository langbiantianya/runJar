package hello;

import java.net.URL;
import java.net.URLStreamHandler;

/**
 * Claims the JVM's one URL stream handler factory and then leaves a non-daemon
 * thread behind, so a run whose main has returned still holds its process.
 */
public class Server {
    public static void main(String[] args) throws Exception {
        URL.setURLStreamHandlerFactory(host -> (URLStreamHandler) null);
        System.out.println("claimed the URL stream handler factory");
        Thread worker = new Thread(() -> {
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "server-worker");
        worker.setDaemon(false);
        worker.start();
        System.out.println("worker started; main is returning");
    }
}

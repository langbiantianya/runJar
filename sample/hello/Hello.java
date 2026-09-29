package hello;

public class Hello {
    public static void main(String[] args) {
        System.out.println("Hello World from a JAR running on Android!");
        System.out.println("java.version=" + System.getProperty("java.version"));
        System.out.println("java.home=" + System.getProperty("java.home"));
        System.out.println("os.arch=" + System.getProperty("os.arch"));
        System.out.println("user.dir=" + System.getProperty("user.dir"));
        for (String arg : args) {
            System.out.println("arg: " + arg);
        }
    }
}

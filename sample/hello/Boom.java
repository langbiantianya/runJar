package hello;

public class Boom {
    public static void main(String[] args) {
        System.out.println("about to fail");
        throw new IllegalStateException("deliberate failure from the JAR");
    }
}

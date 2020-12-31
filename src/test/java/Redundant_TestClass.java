public class Redundant_TestClass {

    public static void main(String[] args) {
        t.start();
    }

    static Thread t = new Thread() {
        @Override
        public void run() {
            super.run();
        }
    };
}





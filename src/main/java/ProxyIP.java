public class ProxyIP {
    String host;
    int port;

    ProxyIP(String host, int port) {
        this.host = host;
        this.port = port;
    }

    ProxyIP(String ...array) {
        host = array[0];
        port = Integer.parseInt(array[1]);
    }
}
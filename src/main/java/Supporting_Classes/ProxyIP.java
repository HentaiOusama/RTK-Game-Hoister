package Supporting_Classes;

public class ProxyIP {
    public String host;
    public int port;

    public ProxyIP(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public ProxyIP(String... array) {
        host = array[0];
        port = Integer.parseInt(array[1]);
    }
}
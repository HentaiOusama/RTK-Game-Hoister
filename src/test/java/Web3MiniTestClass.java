import Supporting_Classes.WebSocketService;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.DefaultBlockParameterNumber;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.Web3ClientVersion;
import org.web3j.protocol.websocket.WebSocketClient;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Scanner;

/**
 * Notes :-
 * 1) If Web3j is used, then after shutdown, it will take around 30 seconds to close off properly.
 * 2) Shutting Down web3j will automatically close the WebSocket Client/Service so no need to close manually.
 * 3) Sending and Getting Logs can cause error, but disposable is not causing Error
 * */

public class Web3MiniTestClass {
    public static Web3j web3j = null;

    public static void main(String[] args) {
        WebSocketService webSocketService = null;
        String val = "mumbai";
        ArrayList<String> webSocketUrls = new ArrayList<>();
        webSocketUrls.add("wss://rpc-" + val + ".maticvigil.com/ws/v1/a149b4ed97ba55c6edad87c488229015d3d7124a");
        webSocketUrls.add("wss://rpc-" + val + ".maticvigil.com/ws/v1/871bf83249074ca466951d0e573cae6397033c0a");
        webSocketUrls.add("wss://rpc-" + val + ".maticvigil.com/ws/v1/56aa369ae7fd09b65b52f932d7410d38ba287d07");


        System.out.println("Connecting to Web3");
        try {
            WebSocketClient webSocketClient = new WebSocketClient(new URI(webSocketUrls.get(0))) {
                @Override
                public void onClose(int code, String reason, boolean remote) {
                    super.onClose(code, reason, remote);
                    System.out.println("(onClose) : WebSocket connection to " + uri + " closed successfully. Code : " + code);
                }

                @Override
                public void onError(Exception e) {
                    super.onError(e);
                    System.out.println("XXXXX\nXXXXX\n" + "(onError) : WebSocket connection to " + uri + " failed.... \n\nLine No. : "
                            + e.getStackTrace()[0].getLineNumber() + "\nXXXXX\nXXXXX");
                }
            };
            webSocketService = new WebSocketService(webSocketClient, true);
            webSocketService.connect();
            web3j = Web3j.build(webSocketService);
            Thread.sleep(2500);
            Web3ClientVersion web3ClientVersion = web3j.web3ClientVersion().send();
            System.out.println("Web3ClientVersion Result : " + web3ClientVersion.getResult());
            System.out.println("Web3ClientVersion : " + web3ClientVersion.getWeb3ClientVersion());
            System.out.println("Latest Block Number : " + web3j.ethBlockNumber().send().getBlockNumber());
            EthFilter RTKContractFilter = new org.web3j.protocol.core.methods.request.EthFilter(new DefaultBlockParameterNumber(
                    Long.parseLong("11480000")),
                    DefaultBlockParameterName.LATEST, Arrays.asList("", ""));
            Thread.sleep(3000);
            System.out.println("Shutting Down....");
            web3j.shutdown();
            Thread.sleep(4000);
            System.out.println("Web3j Shutdown Complete");
            webSocketClient.close();
        } catch (Exception e) {
            e.printStackTrace();
        }

        System.out.println("Closing WebSocket");
        assert webSocketService != null;
        webSocketService.close();
        Scanner scanner = new Scanner(System.in);
        char ip = 'n';
        while (ip == 'y') {
            System.out.print("Continue (Y/N)? : ");
            ip = scanner.nextLine().charAt(0);
        }
        System.out.println("End of Main");
    }
}

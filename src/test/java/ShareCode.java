import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.DefaultBlockParameterNumber;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.websocket.WebSocketClient;

import java.net.URI;
import java.util.Arrays;

public class ShareCode {
    public static Web3j web3j = null;

    public static void main(String[] args) {

        WebSocketService webSocketService = null;
        String[] RTKContractAddresses = new String[]{"0x38332D8671961aE13d0BDe040d536eB336495eEA"};;
        String startBlockNumber = "11461568";
        System.out.println("Connecting to Web3");
        try {
            WebSocketClient webSocketClient = new WebSocketClient(new URI(
                    "wss://rpc-mainnet.maticvigil.com/ws/v1/a149b4ed97ba55c6edad87c488229015d3d7124a")) {
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
        } catch (Exception e) {
            e.printStackTrace();
        }


        try {
            web3j = Web3j.build(webSocketService);
            EthFilter RTKContractFilter = new EthFilter(new DefaultBlockParameterNumber(Long.parseLong(startBlockNumber)),
                    DefaultBlockParameterName.LATEST, Arrays.asList(RTKContractAddresses));

            System.out.println("Setting Filter...");
            org.web3j.protocol.core.methods.response.EthFilter ethFilter = web3j.ethNewFilter(RTKContractFilter).send();
            System.out.println("Filter Id : " + ethFilter.getFilterId());

            System.out.println("Web3ClientVersion : " + web3j.web3ClientVersion().send().getWeb3ClientVersion());
            System.out.println("\nLast Checked Block Number : " + startBlockNumber);
            System.out.println("Latest Block Number : " + web3j.ethBlockNumber().send().getBlockNumber());
            System.out.println("Gas Price : " + web3j.ethGasPrice().send().getGasPrice());
            System.out.println("Wallet Balance : " + web3j.ethGetBalance("0xdcCF6EE3977903d541B47F31D5bfD3AED3511C62",
                    DefaultBlockParameterName.LATEST).send().getBalance());

            // Below Line Causes Error
            System.out.println(web3j.ethGetLogs(RTKContractFilter).send().getLogs());


            System.out.println("\n");
            Thread.sleep(2500);
        } catch (Exception e) {
            System.out.println("Disposable Build Error.");
            e.printStackTrace();
        }


        System.out.println("Shutdown");
        web3j.shutdown();
        assert webSocketService != null;
        webSocketService.close();
    }
}
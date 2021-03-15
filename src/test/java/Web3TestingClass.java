import io.reactivex.disposables.Disposable;
import org.web3j.abi.TypeDecoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.DefaultBlockParameterNumber;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.Transaction;
import org.web3j.protocol.websocket.WebSocketClient;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;
import java.util.Scanner;


public class Web3TestingClass {
    public static String prevHash = null;
    public static Web3j web3j = null;

    public static void main(String[] args) {

        WebSocketService webSocketService = null;
        Disposable disposable = null;
        String val = "mainnet", EthNetworkType = "ropsten";
        ArrayList<String> webSocketUrls = new ArrayList<>();
        String[] RTKContractAddresses;
        String startBlockNumber;
        // Url Setter and Connect to WebSocket
        if (true) {
            webSocketUrls.add("wss://rpc-" + val + ".maticvigil.com/ws/v1/b59593f7317289035dee5b626e6d3d6dd95c4c91");
            webSocketUrls.add("wss://rpc-" + val + ".maticvigil.com/ws/v1/a149b4ed97ba55c6edad87c488229015d3d7124a");
            webSocketUrls.add("wss://rpc-" + val + ".maticvigil.com/ws/v1/871bf83249074ca466951d0e573cae6397033c0a");
            webSocketUrls.add("wss://rpc-" + val + ".maticvigil.com/ws/v1/56aa369ae7fd09b65b52f932d7410d38ba287d07");
            webSocketUrls.add("wss://rpc-" + val + ".maticvigil.com/ws/v1/3b0b0d6046e7da8da765b05296085f8c97753c61");
            webSocketUrls.add("wss://rpc-" + val + ".maticvigil.com/ws/v1/d02da2509d64cf806714e1ddcd54e4c179c13d4e");
            webSocketUrls.add("wss://rpc-" + val + ".maticvigil.com/ws/v1/d8fdfd183f6bc45dd2ad4809f22687b29ca4b85c");
            webSocketUrls.add("wss://rpc-" + val + ".maticvigil.com/ws/v1/c2f20b22705f9c45d1337380a28d6613e08310d6");
            webSocketUrls.add("wss://rpc-" + val + ".maticvigil.com/ws/v1/94ef8862aaa7f832ca421d4e01da3fb5a5313969");
            webSocketUrls.add("wss://rpc-" + val + ".maticvigil.com/ws/v1/e247aacac4d9d2cc83a8e81cd51c3ec36a2f5a93");
            webSocketUrls.add("wss://rpc-" + val + ".maticvigil.com/ws/v1/eee88d447ebc33a64f5acf891270517ff506330b");
            webSocketUrls.add("wss://rpc-" + val + ".maticvigil.com/ws/v1/d2b3d15442e3631d4a11324eda64d05a6404a2e8");
            if (val.equals("mumbai")) {
                RTKContractAddresses = new String[]{"0x54320152Eb8889a9b28048280001549FAC3E26F5",
                        "0xc21af68636B79A9F12C11740B81558Ad27C038a6", "0x9D27dE8369fc977a3BcD728868984CEb19cF7F66",
                        "0xc21EE7D6eF734dc00B3c535dB658Ef85720948d3", "0x39b892Cf8238736c038B833d88B8C91B1D5C8158"};
            } else {
                RTKContractAddresses = new String[]{"0x38332D8671961aE13d0BDe040d536eB336495eEA",
                        "0x136A5c9B9965F3827fbB7A9e97E41232Df168B08", "0xfB8C59fe95eB7e0a2fA067252661687df67d87b8",
                        "0x99afe8FDEd0ef57845F126eEFa945d687CdC052d", "0x88dD15CEac31a828e06078c529F5C1ABB214b6E8"};
            }
            startBlockNumber = "11461568";
        } else {
            webSocketUrls.add("wss://" + EthNetworkType + ".infura.io/ws/v3/04009a10020d420bbab54951e72e23fd");
            webSocketUrls.add("wss://" + EthNetworkType + ".infura.io/ws/v3/94fead43844d49de833adffdf9ff3993");
            webSocketUrls.add("wss://" + EthNetworkType + ".infura.io/ws/v3/b8440ab5890a4d539293994119b36893");
            webSocketUrls.add("wss://" + EthNetworkType + ".infura.io/ws/v3/b05a1fe6f7b64750a10372b74dec074f");
            webSocketUrls.add("wss://" + EthNetworkType + ".infura.io/ws/v3/2e98f2588f85423aa7bced2687b8c2af");
            RTKContractAddresses = new String[]{"0x38332D8671961aE13d0BDe040d536eB336495eEA",
                    "0x9C72573A47b0d81Ef6048c320bF5563e1606A04C", "0x136A5c9B9965F3827fbB7A9e97E41232Df168B08",
                    "0xfB8C59fe95eB7e0a2fA067252661687df67d87b8", "0x99afe8FDEd0ef57845F126eEFa945d687CdC052d"};
            startBlockNumber = "8000000";
        }
        System.out.println("Connecting to Web3");
        try {

            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("45.95.99.20", 7580));
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
            webSocketClient.setProxy(proxy);
            final String authUser = System.getenv("proxyUsername");
            final String authPassword = System.getenv("proxyPassword");
            Authenticator.setDefault(
                    new Authenticator() {
                        @Override
                        public PasswordAuthentication getPasswordAuthentication() {
                            return new PasswordAuthentication(authUser, authPassword.toCharArray());
                        }
                    }
            );

            System.setProperty("http.proxyUser", authUser);
            System.setProperty("http.proxyPassword", authPassword);

            System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
            webSocketService = new WebSocketService(webSocketClient, true);
            webSocketService.connect();
        } catch (Exception e) {
            e.printStackTrace();
        }


        EthFilter RTKContractFilter = null;
        try {
            web3j = Web3j.build(webSocketService);
            System.out.println("Web3ClientVersion : " + web3j.web3ClientVersion().send().getWeb3ClientVersion());
            System.out.println("\nLast Checked Block Number : " + startBlockNumber);
            System.out.println("Latest Block Number : " + web3j.ethBlockNumber().send().getBlockNumber());
            RTKContractFilter = new EthFilter(new DefaultBlockParameterNumber(Long.parseLong(startBlockNumber)),
                    DefaultBlockParameterName.LATEST, Arrays.asList(RTKContractAddresses));
            //System.out.println("Logs de Gozaru : " + web3j.ethGetLogs(RTKContractFilter).send().getLogs());

            disposable = web3j.ethLogFlowable(RTKContractFilter).subscribe(log -> {
                String hash = log.getTransactionHash();
                if ((prevHash == null) || (!prevHash.equalsIgnoreCase(hash))) {
                    Optional<Transaction> trx = web3j.ethGetTransactionByHash(hash).send().getTransaction();
                    if (trx.isPresent()) {
                        TransactionData currentTrxData = splitInputData(log, trx.get());
                        currentTrxData.X = 0;
                        System.out.println(currentTrxData);
                    }
                }
                prevHash = hash;
            }, throwable -> {
                System.out.println("Disposal Internal Error");
                throwable.printStackTrace();
            });
            System.out.println("Gas Price : " + web3j.ethGasPrice().send().getGasPrice());
            System.out.println("Wallet Balance : " + web3j.ethGetBalance("0xdcCF6EE3977903d541B47F31D5bfD3AED3511C62", DefaultBlockParameterName.LATEST).send().getBalance());
            System.out.println("\n");
            Thread.sleep(2500);
        } catch (Exception e) {
            System.out.println("Disposable Build Error.");
            e.printStackTrace();
        }
        System.out.println("Disposing....");
        assert disposable != null;
        disposable.dispose();
        while (!disposable.isDisposed()) {
            System.out.println("Disposable not disposed...");
            try {
                Thread.sleep(200);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        web3j.shutdown();
        assert webSocketService != null;
        webSocketService.close();


        Scanner scanner = new Scanner(System.in);
        char ip = 'y';
        while (ip == 'y') {
            System.out.print("Continue (Y/N)? : ");
            ip = scanner.nextLine().charAt(0);
        }
        System.out.println("End of Main");
    }








    /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////


    public static void sleep() {
        try {
            Thread.sleep(2500);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @SuppressWarnings("SpellCheckingInspection")
    private static TransactionData splitInputData(Log log, Transaction transaction) throws Exception {
        String inputData = transaction.getInput();
        TransactionData currentTransactionData = new TransactionData();
        String method = inputData.substring(0, 10);
        currentTransactionData.methodName = method;
        currentTransactionData.trxHash = transaction.getHash();
        currentTransactionData.blockNumber = transaction.getBlockNumber();
        currentTransactionData.trxIndex = transaction.getTransactionIndex();

        // If method is transfer method
        if (method.equalsIgnoreCase("0xa9059cbb")) {
            currentTransactionData.fromAddress = transaction.getFrom().toLowerCase();
            String topic = log.getTopics().get(0);
            if (topic.equalsIgnoreCase("0x897c6a07c341708f5a14324ccd833bbf13afacab63b30bbd827f7f1d29cfdff4")) {
                currentTransactionData.didBurn = true;
            } else if (topic.equalsIgnoreCase("0xe7d849ade8c22f08229d6eec29ca84695b8f946b0970558272215552d79076e6")) {
                currentTransactionData.didBurn = false;
            }
            Method refMethod = TypeDecoder.class.getDeclaredMethod("decode", String.class, int.class, Class.class);
            refMethod.setAccessible(true);
            Address toAddress = (Address) refMethod.invoke(null, inputData.substring(10, 74), 0, Address.class);
            Uint256 amount = (Uint256) refMethod.invoke(null, inputData.substring(74), 0, Uint256.class);
            currentTransactionData.toAddress = toAddress.toString().toLowerCase();
            currentTransactionData.value = amount.getValue();
        } else {
            currentTransactionData.methodName = "Useless";
        }
        return currentTransactionData;
    }
}

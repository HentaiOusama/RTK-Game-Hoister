import Supporting_Classes.WebSocketService;
import io.reactivex.Flowable;
import io.reactivex.disposables.Disposable;
import org.web3j.contracts.eip20.generated.ERC20;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterNumber;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.websocket.WebSocketClient;
import org.web3j.tx.RawTransactionManager;
import org.web3j.tx.gas.ContractGasProvider;

import java.math.BigInteger;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;

public class Test {
    
    static Web3j web3j;
    static List<String> RTKContractAddresses;

    public static void main(String[] args) {
        try {
            RTKContractAddresses = Arrays.asList("0x38332D8671961aE13d0BDe040d536eB336495eEA",
                    "0x136A5c9B9965F3827fbB7A9e97E41232Df168B08", "0xfB8C59fe95eB7e0a2fA067252661687df67d87b8",
                    "0x99afe8FDEd0ef57845F126eEFa945d687CdC052d", "0x88dD15CEac31a828e06078c529F5C1ABB214b6E8");

            String uri = System.getenv("tempUri1");
            System.out.println("Uri Used : " + uri);
            WebSocketService webSocketService = new WebSocketService(new WebSocketClient(
                    new URI(uri)), true);
            webSocketService.connect();
            web3j = Web3j.build(webSocketService);

            if (false) {
                RawTransactionManager rawTransactionManager = new RawTransactionManager(web3j, Credentials.create(
                        System.getenv("PrivateKey")), web3j.ethChainId().send().getChainId().longValue());
                BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
                System.out.println("GasPrice : " + gasPrice);
                TransactionReceipt trxReceipt = ERC20.load(RTKContractAddresses.get(0), web3j, rawTransactionManager,
                        new ContractGasProvider() {
                            @Override
                            public BigInteger getGasPrice(String s) {
                                return gasPrice;
                            }

                            @Override
                            public BigInteger getGasPrice() {
                                return gasPrice;
                            }

                            @Override
                            public BigInteger getGasLimit(String s) {
                                return BigInteger.valueOf(65000L);
                            }

                            @Override
                            public BigInteger getGasLimit() {
                                return BigInteger.valueOf(65000L);
                            }
                        }).transfer("0xf7C1f4cA54D64542061E6f53A9D38E2f5A6A4Ecc",
                        new BigInteger("1000000000000000")).sendAsync().get();
                System.out.println("TrxHash : " + trxReceipt.getTransactionHash());
                System.exit(0);
            }

            BigInteger latestBlock = web3j.ethBlockNumber().send().getBlockNumber();
            EthFilter ethFilter = new EthFilter(new DefaultBlockParameterNumber(new BigInteger("14443714")), new DefaultBlockParameterNumber(latestBlock),
                    RTKContractAddresses);
            ethFilter.addOptionalTopics("0x897c6a07c341708f5a14324ccd833bbf13afacab63b30bbd827f7f1d29cfdff4",
                    "0xe7d849ade8c22f08229d6eec29ca84695b8f946b0970558272215552d79076e6");

            Flowable<Log> flowable = web3j.ethLogFlowable(ethFilter);

            Disposable disposable = flowable.subscribe(log -> System.out.println(log.toString()));

            Scanner scanner = new Scanner(System.in);
            System.out.println("Wanna Exit ?");
            scanner.nextLine();

            disposable.dispose();
            web3j.shutdown();
            webSocketService.close();
            System.out.println("Exit Success");
            System.exit(0);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

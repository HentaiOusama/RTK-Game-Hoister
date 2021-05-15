import io.reactivex.Flowable;
import io.reactivex.disposables.Disposable;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterNumber;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.websocket.WebSocketClient;
import Supporting_Classes.WebSocketService;

import java.math.BigInteger;
import java.net.URI;
import java.util.Collections;
import java.util.Scanner;

public class Test {

    public static void main(String[] args) {
        try {
            WebSocketService webSocketService = new WebSocketService(new WebSocketClient(
                    new URI(System.getenv("tempUri"))), true);
            webSocketService.connect();
            Web3j web3j = Web3j.build(webSocketService);

            BigInteger latestBlock = web3j.ethBlockNumber().send().getBlockNumber();
            EthFilter ethFilter = new EthFilter(new DefaultBlockParameterNumber(new BigInteger("14443714")), new DefaultBlockParameterNumber(latestBlock),
                    Collections.singletonList("0x88dD15CEac31a828e06078c529F5C1ABB214b6E8"));
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

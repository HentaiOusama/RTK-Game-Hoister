import org.web3j.protocol.Web3j;
import org.web3j.protocol.Web3jService;
import org.web3j.protocol.core.BatchRequest;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.DefaultBlockParameterNumber;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthLog;
import org.web3j.protocol.core.methods.response.Transaction;
import org.web3j.protocol.core.methods.response.Web3ClientVersion;
import org.web3j.protocol.http.HttpService;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

public class TempTest {
    public static void main(String[] args) {
        HttpService httpService = new HttpService("https://matic-mainnet--jsonrpc.datahub.figment.io/apikey/3c3bfe53467bb818a9edfd9323b1ec30/");
        Web3j web3j = Web3j.build(httpService);

        try {

            System.out.println("Client Version : " + web3j.web3ClientVersion().send().getWeb3ClientVersion()); // Get Client Version
            BigInteger blockNumber = web3j.ethBlockNumber().send().getBlockNumber();
            System.out.println("Latest Block Number : " + blockNumber); // Get Block Number
            System.out.println("Matic Balance of Addy : " + web3j.ethGetBalance("0xdcCF6EE3977903d541B47F31D5bfD3AED3511C62",
                    DefaultBlockParameterName.LATEST).send().getBalance()); // Get Balance of the mentioned wallet

            BatchRequest batchRequest = new BatchRequest(httpService);
            batchRequest.add(web3j.web3ClientVersion());
            batchRequest.add(web3j.ethBlockNumber());
            batchRequest.add(web3j.ethGetBalance("0xdcCF6EE3977903d541B47F31D5bfD3AED3511C62",
                    DefaultBlockParameterName.LATEST));
            batchRequest.add(web3j.ethChainId());

            System.out.println("Batch Request : " + ((Web3ClientVersion) batchRequest.send().getResponses().get(0)).getWeb3ClientVersion());


            EthFilter filter = new EthFilter(new DefaultBlockParameterNumber(new BigInteger("14451593")), DefaultBlockParameterName.LATEST,
                    Arrays.asList("0x38332D8671961aE13d0BDe040d536eB336495eEA","0x136A5c9B9965F3827fbB7A9e97E41232Df168B08",
                            "0xfB8C59fe95eB7e0a2fA067252661687df67d87b8", "0x99afe8FDEd0ef57845F126eEFa945d687CdC052d",
                            "0x88dD15CEac31a828e06078c529F5C1ABB214b6E8"));
            System.out.println("Filter : " + filter); // Setting filter data

            BigInteger filterId =  web3j.ethNewFilter(filter).send().getFilterId();
            if (filterId != null) {
                System.out.println("Filter Id : " + filterId);
                System.out.println("Sleeping for 5 seconds");
                Thread.sleep(5000);
                System.out.println("Sleep Over");

                List<EthLog.LogResult> logResults = web3j.ethGetFilterLogs(filterId).send().getLogs();
                if (logResults != null) {
                    System.out.println("Log Results Size : " + logResults.size());
                    System.out.println("Log Result (0) : " + logResults.get(0).get());
                } else {
                    System.out.println("LogResults are null");
                }

                for (int i = 0; i < 5; i++) {
                    logResults = web3j.ethGetFilterChanges(filterId).send().getLogs();
                    if (logResults != null) {
                        System.out.println("Log Results Size : " + logResults.size());
                        System.out.println("Log Result (0) : " + logResults.get(0).get());
                    } else {
                        System.out.println("LogResults are null. Try No. " + i);
                    }
                }

                web3j.ethUninstallFilter(filterId);
            } else {
                System.out.println("Filter is Null");
            }

            //Disposable disposable = web3j.ethLogFlowable(filter).subscribe(log -> System.out.println("Log : " + log), Throwable::printStackTrace);

            System.out.println("Waiting 4 seconds");
            Thread.sleep(4000);
            System.out.println("Wait over");

            web3j.shutdown();
            System.exit(0);
        } catch (Exception e) {
            System.out.println("Main Catch Block...");
            e.printStackTrace();
            System.exit(1);
        }
    }
}

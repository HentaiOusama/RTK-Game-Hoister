import io.reactivex.disposables.Disposable;
import org.apache.log4j.Logger;
import org.web3j.abi.TypeDecoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.Transaction;
import org.web3j.protocol.core.methods.response.Web3ClientVersion;
import org.web3j.protocol.websocket.WebSocketClient;
import org.web3j.protocol.websocket.WebSocketService;
import java.io.IOException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Optional;

public class Game implements Runnable {

    // Managing Variables
    private final Deadline_Chaser_Bot deadline_chaser_bot;
    private int roundCount;
    private final long chat_id;
    Logger logger = Logger.getLogger(Game.class);
    Instant currentRoundStartTime, currentRoundHalfTime, currentRoundQuarterTime, currentRoundEndTime;
    private volatile BigInteger finalBlockNumber;

    // Blockchain Related Stuff
    private final String shotWallet;
    private final String[] RTKContractAddresses;
    private final BigInteger shotCost;
    private final Disposable[] disposable;
    private final ArrayList<String> webSocketUrls = new ArrayList<>();
    private WebSocketService webSocketService;
    private Web3j web3j;
    private ArrayList<TransactionData> validTransactions = new ArrayList<>();
    private final String[] prevHash = {null, null, null, null, null};
    private boolean shouldTryToEstablishConnection = true;
    private final Thread finalBlockRecorder;

    // Constructor
    Game(Deadline_Chaser_Bot deadline_chaser_bot, long chat_id, String EthNetworkType, String shotWallet, String[] RTKContractAddresses,
         BigInteger shotCost) {
        this.deadline_chaser_bot = deadline_chaser_bot;
        this.chat_id = chat_id;
        this.shotWallet = shotWallet;
        this.RTKContractAddresses = RTKContractAddresses;
        this.shotCost = shotCost;
        roundCount = 1;
        disposable = new Disposable[5];

        ///// Setting web3 data
        // Connecting to web3 client
        webSocketUrls.add("wss://" + EthNetworkType + ".infura.io/ws/v3/04009a10020d420bbab54951e72e23fd");
        webSocketUrls.add("wss://" + EthNetworkType + ".infura.io/ws/v3/94fead43844d49de833adffdf9ff3993");
        webSocketUrls.add("wss://" + EthNetworkType + ".infura.io/ws/v3/b8440ab5890a4d539293994119b36893");
        webSocketUrls.add("wss://" + EthNetworkType + ".infura.io/ws/v3/b05a1fe6f7b64750a10372b74dec074f");
        webSocketUrls.add("wss://" + EthNetworkType + ".infura.io/ws/v3/2e98f2588f85423aa7bced2687b8c2af");
        /////

        finalBlockRecorder = new Thread() {
            @Override
            public void run() {
                super.run();
                try {
                    while(!Thread.interrupted() && Instant.now().compareTo(currentRoundEndTime) <= 0) {
                        if(Instant.now().compareTo(currentRoundEndTime) > 0) {
                            return;
                        }
                        finalBlockNumber = web3j.ethBlockNumber().send().getBlockNumber();
                        performProperWait(0.8);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        };
    }

    @Override
    public void run() {
        ArrayList<TransactionData> transactionsUnderReview;
        String finalSender = null;
        boolean halfWarn, quarterWarn;
        int halfValue, quarterValue;

        // Not yet complete....
        // Send some msg to chat regarding some wait before the game start.

        buildCustomBlockchainReader(true);

        for(; roundCount <= 3; roundCount++) {
            currentRoundStartTime = Instant.now();
            if(roundCount == 1) {
                currentRoundHalfTime = currentRoundStartTime.plus(15, ChronoUnit.MINUTES);
                currentRoundQuarterTime = currentRoundHalfTime.plus(22, ChronoUnit.MINUTES);
                currentRoundEndTime = currentRoundStartTime.plus(30, ChronoUnit.MINUTES);
                halfValue = 15;
                quarterValue = 8;
            } else if(roundCount == 2) {
                currentRoundHalfTime = currentRoundStartTime.plus(10, ChronoUnit.MINUTES);
                currentRoundQuarterTime = currentRoundHalfTime.plus(15, ChronoUnit.MINUTES);
                currentRoundEndTime = currentRoundStartTime.plus(20, ChronoUnit.MINUTES);
                halfValue = 10;
                quarterValue = 5;
            } else if (roundCount == 3) {
                currentRoundHalfTime = currentRoundStartTime.plus(5, ChronoUnit.MINUTES);
                currentRoundQuarterTime = currentRoundHalfTime.plus(7, ChronoUnit.MINUTES);
                currentRoundEndTime = currentRoundStartTime.plus(10, ChronoUnit.MINUTES);
                halfValue = 5;
                quarterValue = 3;
            } else {
                halfValue = 0;
                quarterValue = 0;
            }
            transactionsUnderReview = new ArrayList<>();
            halfWarn = true;
            quarterWarn = true;
            finalBlockNumber = null;
            finalBlockRecorder.start();
            boolean furtherCountNecessary = true;
            TransactionData transactionData = null;

            MID :
            while(Instant.now().compareTo(currentRoundEndTime) <= 0) {
                if(halfWarn) {
                    if(Instant.now().compareTo(currentRoundHalfTime) >= 0) {
                        deadline_chaser_bot.sendMessage(chat_id, "Half Time crossed. LESS THAN " + halfValue + " minutes " +
                                "remaining for the current round.");
                        halfWarn = false;
                    }
                } else if(quarterWarn) {
                    if(Instant.now().compareTo(currentRoundQuarterTime) >= 0) {
                        deadline_chaser_bot.sendMessage(chat_id, "3/4th Time crossed. LESS THAN " + quarterValue + " minutes " +
                                "remaining for the current round.");
                        quarterWarn = false;
                    }
                }

                while(!validTransactions.isEmpty()) {
                    transactionsUnderReview.add(validTransactions.remove(0));
                }
                Collections.sort(transactionsUnderReview);

                while (transactionsUnderReview.size() > 0) {
                    transactionData = transactionsUnderReview.remove(0);
                    addRTKToPot(transactionData.value, transactionData.X);
                    if(finalBlockNumber == null || transactionData.compareBlock(finalBlockNumber) <= 0) {
                        if(transactionData.didBurn) {
                            finalSender = transactionData.fromAddress;
                            deadline_chaser_bot.sendMessage(chat_id, "Someone got shot.\nTrx Hash : " + transactionData.trxHash +
                                    "\nCurrent pot holder : " + finalSender);
                            performProperWait(1);
                            if(roundCount != 3) {
                                furtherCountNecessary = false;
                                break MID;
                            }
                        }
                    } else {
                        furtherCountNecessary = false;
                        transactionsUnderReview.add(0, transactionData);
                        addRTKToPot(transactionData.value.multiply(new BigInteger("-1")), transactionData.X);
                        break MID;
                    }
                }
            }
            if(!finalBlockRecorder.isInterrupted()) {
                finalBlockRecorder.interrupt();
            }

            if(furtherCountNecessary) {
                boolean didSomeoneBurned = false;
                while(!validTransactions.isEmpty()) {
                    transactionsUnderReview.add(validTransactions.remove(0));
                }
                Collections.sort(transactionsUnderReview);

                while (transactionsUnderReview.size() > 0) {
                    transactionData = transactionsUnderReview.remove(0);
                    addRTKToPot(transactionData.value, transactionData.X);
                    if(finalBlockNumber == null || transactionData.compareBlock(finalBlockNumber) <= 0) {
                        if(transactionData.didBurn) {
                            finalSender = transactionData.fromAddress;
                            didSomeoneBurned = true;
                        }
                    } else {
                        break;
                    }
                }
                if(didSomeoneBurned) {
                    deadline_chaser_bot.sendMessage(chat_id, "Final valid burn :-\nTrx Hash : " + transactionData.trxHash +
                            "\nFinal pot holder : " + finalSender);
                    performProperWait(1);
                }
            }

            if(roundCount != 3) {
                // Not yet Complete
                // Send a message to the chat notifying that next round will now start...
            }

        }

        for(int i = 0; i < 5; i++) {
            if(!disposable[i].isDisposed()) {
                disposable[i].dispose();
            }
        }

        deadline_chaser_bot.deleteGame(chat_id);
    }


    public void performProperWait(double seconds) {
        try {
            Thread.sleep((long)(seconds * 1000));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    private void addRTKToPot(BigInteger amount, int X) {
        // Not yet complete...
    }

    // Related to Blockchain Communication
    private TransactionData splitInputData(Log log, Transaction transaction) throws Exception {
        String inputData = transaction.getInput();
        TransactionData currentTransactionData = new TransactionData();
        String method = inputData.substring(0, 10);
        currentTransactionData.methodName = method;
        currentTransactionData.trxHash = transaction.getHash();
        currentTransactionData.blockNumber = transaction.getBlockNumber();
        currentTransactionData.trxIndex = transaction.getTransactionIndex();

        // If method is transfer method
        if(method.equalsIgnoreCase("0xa9059cbb")) {
            currentTransactionData.fromAddress = transaction.getFrom().toLowerCase();
            String topic = log.getTopics().get(0);
            if(topic.equalsIgnoreCase("0x897c6a07c341708f5a14324ccd833bbf13afacab63b30bbd827f7f1d29cfdff4")) {
                currentTransactionData.didBurn = true;
            } else if(topic.equalsIgnoreCase("0xe7d849ade8c22f08229d6eec29ca84695b8f946b0970558272215552d79076e6")) {
                currentTransactionData.didBurn = false;
            }
            Method refMethod = TypeDecoder.class.getDeclaredMethod("decode",String.class,int.class,Class.class);
            refMethod.setAccessible(true);
            Address toAddress = (Address) refMethod.invoke(null,inputData.substring(10, 74),0,Address.class);
            Uint256 amount = (Uint256) refMethod.invoke(null,inputData.substring(74),0,Uint256.class);
            currentTransactionData.toAddress = toAddress.toString().toLowerCase();
            currentTransactionData.value = amount.getValue();
        } else {
            currentTransactionData.methodName = "Useless";
        }
        return currentTransactionData;
    }

    private boolean buildCustomBlockchainReader(boolean shouldSendMessage) {

        int count = 0;
        if(shouldSendMessage) {
            deadline_chaser_bot.sendMessage(chat_id, "Connecting to Ethereum Network to read transactions. Please be patient. " +
                    "This can take from few seconds to few minutes");
        }
        System.out.println("Connecting to Web3");
        validTransactions = new ArrayList<>();
        shouldTryToEstablishConnection = true;
        while (shouldTryToEstablishConnection && count < 6) {
            count++;
            try {
                Collections.shuffle(webSocketUrls);
                WebSocketClient webSocketClient = new WebSocketClient(new URI(webSocketUrls.get(0))) {
                    @Override
                    public void onClose(int code, String reason, boolean remote) {
                        super.onClose(code, reason, remote);
                        logger.info(chat_id + " : WebSocket connection to " + uri + " closed successfully " + reason);
                    }

                    @Override
                    public void onError(Exception e) {
                        super.onError(e);
                        e.printStackTrace();
                        setShouldTryToEstablishConnection();
                        logger.error(chat_id + " : WebSocket connection to " + uri + " failed with error");
                        System.out.println("Trying again");
                    }
                };
                webSocketService = new WebSocketService(webSocketClient, true);
                webSocketService.connect();
                shouldTryToEstablishConnection = false;
            } catch (Exception e) {
                e.printStackTrace();
            }
            performProperWait(2);
        }

        try {
            System.out.println("\n\n\n\n\n\n");
            web3j =  Web3j.build(webSocketService);
            Web3ClientVersion web3ClientVersion = web3j.web3ClientVersion().send();
            System.out.println("Game's Chat ID : " + chat_id + "\nWeb3ClientVersion : " + web3ClientVersion.getWeb3ClientVersion());

            EthFilter[] RTKContractFilter = new EthFilter[5];
            for(int i = 0; i < 5; i++) {
                RTKContractFilter[i] = new EthFilter(DefaultBlockParameterName.LATEST, DefaultBlockParameterName.LATEST, RTKContractAddresses[i]);
                int finalI = i;
                disposable[i] = web3j.ethLogFlowable(RTKContractFilter[i]).subscribe(log -> {
                    String hash = log.getTransactionHash();
                    if((prevHash[finalI] == null) || (!prevHash[finalI].equalsIgnoreCase(hash))) {
                        System.out.println("Chat ID : " + chat_id + " - Trx :  " + log.getTransactionHash() + ", X = " + (finalI + 1));
                        Optional<Transaction> trx = web3j.ethGetTransactionByHash(hash).send().getTransaction();
                        if(trx.isPresent()) {
                            TransactionData currentTrxData = splitInputData(log, trx.get());
                            currentTrxData.X = finalI + 1;
                            if(currentTrxData.toAddress.equalsIgnoreCase(shotWallet) && currentTrxData.value.compareTo(shotCost) >= 0) {
                                validTransactions.add(currentTrxData);
                            }
                        }
                    }
                    prevHash[finalI] = hash;
                }, throwable -> {
                    throwable.printStackTrace();
                    webSocketService.close();
                    webSocketService.connect();
                });
            }
            System.out.println("\n\n\n\n\n\n");
        } catch (IOException e) {
            e.printStackTrace();
        }
        return !shouldTryToEstablishConnection;
    }

    private void setShouldTryToEstablishConnection() {
        shouldTryToEstablishConnection = true;
    }
}

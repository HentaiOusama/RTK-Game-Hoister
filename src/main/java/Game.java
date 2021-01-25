import io.reactivex.disposables.Disposable;
import org.apache.log4j.Logger;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.contracts.eip20.generated.ERC20;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.DefaultBlockParameterNumber;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.Transaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.core.methods.response.Web3ClientVersion;
import org.web3j.protocol.websocket.WebSocketClient;
import org.web3j.protocol.websocket.WebSocketService;
import org.web3j.tx.gas.ContractGasProvider;
import java.io.IOException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Game implements Runnable {

    // Managing Variables
    private final Last_Bounty_Hunter_Bot last_bounty_hunter_bot;
    private final long chat_id;
    Logger logger = Logger.getLogger(Game.class);
    public volatile boolean isGameRunning = false;
    private volatile boolean shouldContinueGame = true;
    private volatile Instant currentRoundEndTime;
    private volatile BigInteger finalLatestBlockNumber = null;
    private volatile TransactionData lastCheckedTransactionData = null;

    private class finalBlockRecorder implements Runnable {
        @Override
        public void run() {
            if (Instant.now().compareTo(currentRoundEndTime) <= 0) {
                try {
                    finalLatestBlockNumber = web3j.ethBlockNumber().send().getBlockNumber();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    // Blockchain Related Stuff
    private final String shotWallet;
    private final String[] RTKContractAddresses;
    private final BigInteger shotCost;
    private final BigInteger decimals = new BigInteger("1000000000000000000");
    private final Disposable[] disposable;
    private final ArrayList<String> webSocketUrls = new ArrayList<>();
    private WebSocketService webSocketService;
    private Web3j web3j;
    private volatile ArrayList<TransactionData> validTransactions = new ArrayList<>();
    private final String[] prevHash = {null, null, null, null, null};
    private boolean shouldTryToEstablishConnection = true;
    private BigInteger gasPrice, minGasFees;
    private final String EthNetworkType;
    private BigDecimal rewardWalletBalance;
    private BigInteger netCurrentPool = BigInteger.valueOf(0), prizePool = BigInteger.valueOf(0);

    // Constructor
    @SuppressWarnings("SpellCheckingInspection")
    Game(Last_Bounty_Hunter_Bot last_bounty_hunter_bot, long chat_id, String EthNetworkType, String shotWallet, String[] RTKContractAddresses,
         BigInteger shotCost) {
        this.last_bounty_hunter_bot = last_bounty_hunter_bot;
        this.chat_id = chat_id;
        this.EthNetworkType = EthNetworkType;
        this.shotWallet = shotWallet;
        this.RTKContractAddresses = RTKContractAddresses;
        this.shotCost = shotCost;
        disposable = new Disposable[5];

        ///// Setting web3 data
        // Connecting to web3 client
        webSocketUrls.add("wss://" + EthNetworkType + ".infura.io/ws/v3/04009a10020d420bbab54951e72e23fd");
        webSocketUrls.add("wss://" + EthNetworkType + ".infura.io/ws/v3/94fead43844d49de833adffdf9ff3993");
        webSocketUrls.add("wss://" + EthNetworkType + ".infura.io/ws/v3/b8440ab5890a4d539293994119b36893");
        webSocketUrls.add("wss://" + EthNetworkType + ".infura.io/ws/v3/b05a1fe6f7b64750a10372b74dec074f");
        webSocketUrls.add("wss://" + EthNetworkType + ".infura.io/ws/v3/2e98f2588f85423aa7bced2687b8c2af");
        /////

        if (EthNetworkType.equalsIgnoreCase("ropsten")) {
            last_bounty_hunter_bot.sendMessage(chat_id, "Warning! The bot is running on Ropsten network and not on Mainnet.");
        }
    }

    @Override
    public void run() {
        lastCheckedTransactionData = last_bounty_hunter_bot.getLastCheckedTransactionDetails();

        ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
        scheduledExecutorService.scheduleAtFixedRate(new finalBlockRecorder(), 0, 800, TimeUnit.MILLISECONDS);

        last_bounty_hunter_bot.sendMessage(chat_id, "Welcome to the Last Bounty Hunter game. The bot will now start reading transactions.");
        performProperWait(1.5);
        last_bounty_hunter_bot.sendMessage(chat_id, "Note :- \n\nEach shot is counted as valid IF\n1) Shot cost is " +
                shotCost.divide(decimals) + " RTK or RTKLX\n2) It is sent to the below address :-");
        performProperWait(0.5);
        last_bounty_hunter_bot.sendMessage(chat_id, shotWallet);
        performProperWait(1.5);

        ArrayList<TransactionData> transactionsUnderReview = new ArrayList<>();
        String finalSender = null;
        boolean halfWarn, quarterWarn;
        int halfValue, quarterValue;

        if (!buildCustomBlockchainReader(true)) {
            last_bounty_hunter_bot.sendMessage(chat_id, "Error encountered while trying to connect to ethereum network. Cancelling the" +
                    "game.");
            last_bounty_hunter_bot.deleteGame(chat_id);
            return;
        }

        if (notHasEnoughBalance()) {
            last_bounty_hunter_bot.sendMessage(chat_id, "Rewards Wallet " + shotWallet + " doesn't have enough eth for transactions. " +
                    "Please contact admins. Closing Game\n\nMinimum eth required : " + new BigDecimal(minGasFees).divide(
                    new BigDecimal("1000000000000000000"), 5, RoundingMode.HALF_EVEN) + ". Actual Balance = " + rewardWalletBalance +
                    "\n\n\nThe bot will not read any transactions till the balances is updated by admins.");
            last_bounty_hunter_bot.deleteGame(chat_id);
            return;
        }

        last_bounty_hunter_bot.sendMessage(chat_id, "Connection Successful... Keep Shooting....");

        while (shouldContinueGame) {

            if (validTransactions.size() == 0 && transactionsUnderReview.size() == 0) {
                performProperWait(2);
                continue;
            }

            isGameRunning = true;
            netCurrentPool = new BigInteger(last_bounty_hunter_bot.getTotalRTKForPoolInWallet());
            prizePool = netCurrentPool.divide(BigInteger.valueOf(2));

            for (int roundCount = 1; roundCount <= 3; roundCount++) {
                Instant currentRoundHalfTime, currentRoundQuarterTime;
                Instant currentRoundStartTime = Instant.now();
                if (roundCount == 1) {
                    currentRoundHalfTime = currentRoundStartTime.plus(15, ChronoUnit.MINUTES);
                    currentRoundQuarterTime = currentRoundHalfTime.plus(22, ChronoUnit.MINUTES);
                    currentRoundEndTime = currentRoundStartTime.plus(30, ChronoUnit.MINUTES);
                    halfValue = 15;
                    quarterValue = 8;
                } else if (roundCount == 2) {
                    currentRoundHalfTime = currentRoundStartTime.plus(10, ChronoUnit.MINUTES);
                    currentRoundQuarterTime = currentRoundHalfTime.plus(15, ChronoUnit.MINUTES);
                    currentRoundEndTime = currentRoundStartTime.plus(20, ChronoUnit.MINUTES);
                    halfValue = 10;
                    quarterValue = 5;
                } else {
                    currentRoundHalfTime = currentRoundStartTime.plus(5, ChronoUnit.MINUTES);
                    currentRoundQuarterTime = currentRoundHalfTime.plus(7, ChronoUnit.MINUTES);
                    currentRoundEndTime = currentRoundStartTime.plus(10, ChronoUnit.MINUTES);
                    halfValue = 5;
                    quarterValue = 3;
                }
                halfWarn = true;
                quarterWarn = true;
                boolean furtherCountNecessary = true;
                TransactionData transactionData = null;
                last_bounty_hunter_bot.sendMessage(chat_id, "Round " + roundCount + " Started!!!! \nTime Limit : " +
                        halfValue * 2 + " minutes");
                performProperWait(2);

                MID:
                while (Instant.now().compareTo(currentRoundEndTime) <= 0) {
                    if (halfWarn) {
                        if (Instant.now().compareTo(currentRoundHalfTime) >= 0) {
                            last_bounty_hunter_bot.sendMessage(chat_id, "Half Time crossed. LESS THAN " + halfValue + " minutes " +
                                    "remaining for the current round.");
                            halfWarn = false;
                        }
                    } else if (quarterWarn) {
                        if (Instant.now().compareTo(currentRoundQuarterTime) >= 0) {
                            last_bounty_hunter_bot.sendMessage(chat_id, "3/4th Time crossed. LESS THAN " + quarterValue + " minutes " +
                                    "remaining for the current round.");
                            quarterWarn = false;
                        }
                    }

                    while (!validTransactions.isEmpty()) {
                        transactionsUnderReview.add(validTransactions.remove(0));
                    }
                    Collections.sort(transactionsUnderReview);
                    performProperWait(1.5);
                    while (transactionsUnderReview.size() > 0) {
                        transactionData = transactionsUnderReview.remove(0);
                        lastCheckedTransactionData = transactionData;
                        if (finalLatestBlockNumber == null || transactionData.compareBlock(finalLatestBlockNumber) <= 0) {
                            if (transactionData.didBurn) {
                                finalSender = transactionData.fromAddress;
                                last_bounty_hunter_bot.sendMessage(chat_id, "Someone got shot.\nTrx Hash : " + transactionData.trxHash +
                                        "\nCurrent pot holder : " + finalSender + "\n\n\nCurrent Prize Pool Reward : " + getPrizePool());
                                performProperWait(1);
                                if (roundCount != 3) {
                                    furtherCountNecessary = false;
                                    break MID;
                                }
                            } else {
                                addRTKToPot(transactionData.value, transactionData.fromAddress);
                            }
                        } else {
                            furtherCountNecessary = false;
                            transactionsUnderReview.add(0, transactionData);
                            break MID;
                        }
                    }
                }
                if (!scheduledExecutorService.isShutdown()) {
                    scheduledExecutorService.shutdownNow();
                }

                if (furtherCountNecessary) {
                    boolean didSomeoneBurned = false;
                    while (!validTransactions.isEmpty()) {
                        transactionsUnderReview.add(validTransactions.remove(0));
                    }
                    Collections.sort(transactionsUnderReview);

                    while (transactionsUnderReview.size() > 0) {
                        transactionData = transactionsUnderReview.remove(0);
                        lastCheckedTransactionData = transactionData;
                        if (finalLatestBlockNumber == null || transactionData.compareBlock(finalLatestBlockNumber) <= 0) {
                            if (transactionData.didBurn) {
                                finalSender = transactionData.fromAddress;
                                didSomeoneBurned = true;
                            } else {
                                addRTKToPot(transactionData.value, transactionData.fromAddress);
                            }
                        } else {
                            break;
                        }
                    }
                    if (didSomeoneBurned) {
                        last_bounty_hunter_bot.sendMessage(chat_id, "Final valid burn :-\nTrx Hash : " + transactionData.trxHash +
                                "\nFinal pot holder : " + finalSender);
                        performProperWait(1);
                    }
                }
            }
            last_bounty_hunter_bot.sendMessage(chat_id, "All rounds have finished. Winner of the game is : " + finalSender +
                    "\nYou have won " + getPrizePool() + " RTK");
            performProperWait(2);
            sendRewardToWinner(prizePool, finalSender);

            last_bounty_hunter_bot.setTotalRTKForPoolInWallet((netCurrentPool.multiply(BigInteger.valueOf(2))).divide(BigInteger.valueOf(5)).toString());
            last_bounty_hunter_bot.addAmountToWalletFeesBalance(netCurrentPool.divide(BigInteger.valueOf(10)).toString());
            last_bounty_hunter_bot.setLastCheckedTransactionDetails(lastCheckedTransactionData);
            isGameRunning = false;
            if (notHasEnoughBalance()) {
                last_bounty_hunter_bot.sendMessage(chat_id, "Rewards Wallet " + shotWallet + " doesn't have enough eth for transactions. " +
                        "Please contact admins. Closing Game\n\nMinimum eth required : " + new BigDecimal(minGasFees).divide(
                        new BigDecimal("1000000000000000000"), 5, RoundingMode.HALF_EVEN) + ". Actual Balance = " + rewardWalletBalance +
                        "\n\n\nThe bot will not read any transactions till the balances is updated by admins.");
                break;
            }
        }

        for (int i = 0; i < 5; i++) {
            if (!disposable[i].isDisposed()) {
                disposable[i].dispose();
            }
        }
        try {
            web3j.shutdown();
            webSocketService.close();
        } catch (Exception e) {
            e.printStackTrace();
        }

        last_bounty_hunter_bot.deleteGame(chat_id);
    }


    public void performProperWait(double seconds) {
        try {
            Thread.sleep((long) (seconds * 1000));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    private void addRTKToPot(BigInteger amount, String sender) {
        if (!sender.equalsIgnoreCase(last_bounty_hunter_bot.topUpWalletAddress)) {
            netCurrentPool = netCurrentPool.add(amount);
            prizePool = netCurrentPool.divide(BigInteger.valueOf(2));
        }
    }

    private String getPrizePool() {
        return new BigDecimal(prizePool).divide(new BigDecimal(decimals), 3, RoundingMode.HALF_EVEN).toString();
    }

    // Related to Blockchain Communication
    @SuppressWarnings("SpellCheckingInspection")
    private TransactionData splitInputData(Log log, Transaction transaction) throws Exception {
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

    private boolean buildCustomBlockchainReader(boolean shouldSendMessage) {

        int count = 0;
        if (shouldSendMessage) {
            last_bounty_hunter_bot.sendMessage(chat_id, "Connecting to Ethereum Network to read transactions. Please be patient. " +
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
            web3j = Web3j.build(webSocketService);
            Web3ClientVersion web3ClientVersion = web3j.web3ClientVersion().send();
            System.out.println("Game's Chat ID : " + chat_id + "\nWeb3ClientVersion : " + web3ClientVersion.getWeb3ClientVersion());

            EthFilter[] RTKContractFilter = new EthFilter[5];
            for (int i = 0; i < 5; i++) {
                RTKContractFilter[i] = new EthFilter(new DefaultBlockParameterNumber(lastCheckedTransactionData.blockNumber), DefaultBlockParameterName.LATEST, RTKContractAddresses[i]);
                int finalI = i;
                disposable[i] = web3j.ethLogFlowable(RTKContractFilter[i]).subscribe(log -> {
                    String hash = log.getTransactionHash();
                    if ((prevHash[finalI] == null) || (!prevHash[finalI].equalsIgnoreCase(hash))) {
                        Optional<Transaction> trx = web3j.ethGetTransactionByHash(hash).send().getTransaction();
                        if (trx.isPresent()) {
                            TransactionData currentTrxData = splitInputData(log, trx.get());
                            currentTrxData.X = finalI + 1;
                            System.out.println("Chat ID : " + chat_id + " - Trx :  " + log.getTransactionHash() + ", X = " + (finalI + 1) +
                                    ", Did Burn = " + currentTrxData.didBurn);
                            if (!currentTrxData.methodName.equals("Useless") && currentTrxData.toAddress.equalsIgnoreCase(shotWallet) && currentTrxData.value.compareTo(shotCost) >= 0) {
                                if (currentTrxData.compareTo(lastCheckedTransactionData) > 0) {
                                    validTransactions.add(currentTrxData);
                                }
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

    // Not yet complete. This has to be changed and replace with a checker for minimum balance of RTK.
    private BigInteger getNetRTKWalletBalance() {
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
            web3j = Web3j.build(webSocketService);
            BigInteger finalValue = new BigInteger("0");
            for (int i = 0; i < 5; i++) {
                Function function = new Function("balanceOf",
                        Collections.singletonList(new Address(shotWallet)),
                        Collections.singletonList(new TypeReference<Uint256>() {
                        }));

                String encodedFunction = FunctionEncoder.encode(function);
                org.web3j.protocol.core.methods.response.EthCall response = web3j.ethCall(
                        org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction(shotWallet, RTKContractAddresses[i], encodedFunction),
                        DefaultBlockParameterName.LATEST).send();
                List<Type> balances = FunctionReturnDecoder.decode(
                        response.getValue(), function.getOutputParameters());
                finalValue = finalValue.add(new BigInteger(balances.get(0).getValue().toString()));
            }
            web3j.shutdown();
            webSocketService.close();
            return finalValue;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private boolean notHasEnoughBalance() {
        boolean retVal = false;

        try {
            gasPrice = web3j.ethGasPrice().send().getGasPrice();
            BigInteger balance = web3j.ethGetBalance(shotWallet, DefaultBlockParameterName.LATEST).send().getBalance();
            minGasFees = gasPrice.multiply(new BigInteger("195000"));
            System.out.println("Network type = " + EthNetworkType + ", Wallet Balance = " + balance + ", Required Balance = " + minGasFees +
                    ", gasPrice = " + gasPrice);
            rewardWalletBalance = new BigDecimal(balance).divide(new BigDecimal("1000000000000000000"), 5, RoundingMode.HALF_EVEN);
            if (balance.compareTo(minGasFees) > 0) {
                retVal = true;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return !retVal;
    }

    private void sendRewardToWinner(BigInteger amount, String toAddress) {
        while (shouldTryToEstablishConnection) {
            try {
                Collections.shuffle(webSocketUrls);
                shouldTryToEstablishConnection = false;
                WebSocketClient webSocketClient = new WebSocketClient(new URI(webSocketUrls.get(0))) {
                    @Override
                    public void onClose(int code, String reason, boolean remote) {
                        super.onClose(code, reason, remote);
                        logger.info("WebSocket connection to " + uri + " closed successfully " + reason);
                    }

                    @Override
                    public void onError(Exception e) {
                        super.onError(e);
                        logger.error("WebSocket connection to " + uri + " failed with error");
                        e.printStackTrace();
                        System.out.println("Trying again");
                        setShouldTryToEstablishConnection();
                    }
                };
                webSocketService = new WebSocketService(webSocketClient, true);

                webSocketService.connect();
            } catch (Exception e) {
                e.printStackTrace();
            }
            performProperWait(2);
        }

        try {
            web3j = Web3j.build(webSocketService);
            Web3ClientVersion web3ClientVersion = web3j.web3ClientVersion().send();
            System.out.println("Game's Chat ID : " + chat_id + "\nWeb3ClientVersion : " + web3ClientVersion.getWeb3ClientVersion());
            TransactionReceipt trxReceipt = ERC20.load(RTKContractAddresses[0], web3j, Credentials.create(System.getenv("PrivateKey")), new ContractGasProvider() {
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
            }).transfer(toAddress, amount).sendAsync().get();
            System.out.println(trxReceipt.getTransactionHash());
            last_bounty_hunter_bot.sendMessage(chat_id, "Reward is being sent. Trx id :- " + trxReceipt.getTransactionHash() + "\n\n\n" +
                    "Code by : @OreGaZembuTouchiSuru");
            web3j.shutdown();
            webSocketService.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setShouldContinueGame(boolean shouldContinueGame) {
        this.shouldContinueGame = shouldContinueGame;
    }
}
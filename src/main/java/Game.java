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
import java.time.Duration;
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
    private class webSocketReconnect implements Runnable {
        @Override
        public void run() {
            if(shouldTryToEstablishConnection && transactionsUnderReview.size() == 0 && validTransactions.size() == 0 && webSocketService != null) {
                for (int i = 0; i < 5; i++) {
                    try {
                        if (!disposable[i].isDisposed()) {
                            disposable[i].dispose();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                try {
                    web3j.shutdown();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                try {
                    webSocketService.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                try {
                    buildCustomBlockchainReader(false);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }


    // Managing Variables
    Logger logger = Logger.getLogger(Game.class);
    private final Last_Bounty_Hunter_Bot last_bounty_hunter_bot;
    private final long chat_id;
    public volatile boolean isGameRunning = false, shouldContinueGame = true, didSomeoneGotShot = false, hasGameClosed = false;
    private volatile Instant currentRoundEndTime;
    private volatile BigInteger finalLatestBlockNumber = null;
    private volatile TransactionData lastCheckedTransactionData = null;
    private final ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService scheduledExecutorService2 = Executors.newSingleThreadScheduledExecutor();

    // Blockchain Related Stuff
    private final String EthNetworkType, shotWallet;
    private final BigInteger shotCost, decimals = new BigInteger("1000000000000000000");
    private final String[] RTKContractAddresses, prevHash = {null, null, null, null, null};
    private final ArrayList<String> webSocketUrls = new ArrayList<>();
    private WebSocketService webSocketService;
    private Web3j web3j;
    private final Disposable[] disposable;
    private final ArrayList<TransactionData> validTransactions = new ArrayList<>(), transactionsUnderReview = new ArrayList<>();
    private boolean shouldTryToEstablishConnection = true;
    private BigDecimal rewardWalletBalance;
    private BigInteger gasPrice, minGasFees, netCurrentPool = BigInteger.valueOf(0), prizePool = BigInteger.valueOf(0);

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

        scheduledExecutorService.scheduleWithFixedDelay(new finalBlockRecorder(), 0, 3000, TimeUnit.MILLISECONDS);

        netCurrentPool = new BigInteger(last_bounty_hunter_bot.getTotalRTKForPoolInWallet());
        prizePool = netCurrentPool.divide(BigInteger.valueOf(2));

        last_bounty_hunter_bot.enqueueMessageForSend(chat_id, String.format("""
                Welcome to the Last Bounty Hunter game.
                Do you have what it takes to be the Last Bounty Hunter?
                
                Latest Prize Pool : %s
                
                Note :- Each shot is considered to be valid ONLY IF :-
                1) Shot amount is at least %s RTK or RTKLX
                2) It is sent to the below address :-""", getPrizePool(), shotCost.divide(decimals)), 0,
                "https://media.giphy.com/media/UNBtv83uhrDrqShIhX/giphy.gif");
        last_bounty_hunter_bot.enqueueMessageForSend(chat_id, shotWallet, 0);

        String finalSender = null;
        boolean halfWarn, quarterWarn;
        int halfValue, quarterValue;

        if (!buildCustomBlockchainReader(true)) {
            last_bounty_hunter_bot.sendMessage(chat_id, "Error encountered while trying to connect to ethereum network. Cancelling the" +
                    "game.");

            getCurrentGameDeleted();
            return;
        }

        if (notHasEnoughBalance()) {
            last_bounty_hunter_bot.sendMessage(chat_id, String.format("""
                    Rewards Wallet %s doesn't have enough eth for transactions. Please contact admins. Closing Game...
                                                
                    Minimum eth required : %s. Actual Balance = %s
                                                
                    The bot will not read any transactions till the balances is updated by admins.""", shotWallet,
                    new BigDecimal(minGasFees).divide(new BigDecimal("1000000000000000000"), 5, RoundingMode.HALF_EVEN), rewardWalletBalance));
            getCurrentGameDeleted();
            return;
        }

        scheduledExecutorService2.scheduleWithFixedDelay(new webSocketReconnect(), 0, 1000, TimeUnit.MILLISECONDS);

        checkForStatus(1);
        last_bounty_hunter_bot.sendMessage(chat_id, "Connection Successful... Keep Shooting....");
        performProperWait(1.5);

        String finalBurnHash = null;
        try {
            while (shouldContinueGame) {

                if (validTransactions.size() == 0 && transactionsUnderReview.size() == 0) {
                    performProperWait(2);
                    continue;
                }

                // Check for initial Burned Transaction to start the game.
                didSomeoneGotShot = false;
                TransactionData transactionData;
                while (!validTransactions.isEmpty()) {
                    transactionsUnderReview.add(validTransactions.remove(0));
                }
                Collections.sort(transactionsUnderReview);

                while (transactionsUnderReview.size() > 0 && !didSomeoneGotShot) {
                    transactionData = transactionsUnderReview.remove(0);
                    lastCheckedTransactionData = transactionData;
                    if (transactionData.didBurn) {
                        finalSender = transactionData.fromAddress;
                        finalBurnHash = transactionData.trxHash;
                        last_bounty_hunter_bot.enqueueMessageForSend(chat_id, String.format("""
                            💥🔫 First blood!!!
                            Hunter %s has the bounty. Shoot him down before he claims it.
                            ⏱ Time limit: 30 minutes
                            💰Bounty: %s""", finalSender, getPrizePool()), 3,
                                "https://media.giphy.com/media/xaMURZrCVsFZzK6DnP/giphy.gif",
                                "https://media.giphy.com/media/UtXbAXl8Pt4Kr0f02Q/giphy.gif");
                        didSomeoneGotShot = true;
                    } else {
                        addRTKToPot(transactionData.value, transactionData.fromAddress);
                        last_bounty_hunter_bot.enqueueMessageForSend(chat_id, String.format("""
                            \uD83D\uDD2B Close shot! Hunter %s tried to get the bounty, but missed their shot.

                            Updated Bounty : %s""", transactionData.fromAddress, getPrizePool()), 2, "https://media.giphy.com/media/N4qR246iV3fVl2PwoI/giphy.gif");
                    }
                }
                if (didSomeoneGotShot) {
                    checkForStatus(3);
                } else {
                    continue;
                }


                isGameRunning = true;
                for (int roundCount = 1; roundCount <= 3; roundCount++) {
                    didSomeoneGotShot = false;
                    Instant currentRoundHalfTime, currentRoundQuarterTime;
                    Instant currentRoundStartTime = Instant.now();
                    String msgString;
                    if (roundCount == 1) {
                        currentRoundHalfTime = currentRoundStartTime.plus(15, ChronoUnit.MINUTES);
                        currentRoundQuarterTime = currentRoundHalfTime.plus(22, ChronoUnit.MINUTES);
                        currentRoundEndTime = currentRoundStartTime.plus(30, ChronoUnit.MINUTES);
                        halfValue = 15;
                        quarterValue = 8;
                        msgString = null;
                        last_bounty_hunter_bot.lastSendStatus = 4;
                    } else if (roundCount == 2) {
                        currentRoundHalfTime = currentRoundStartTime.plus(10, ChronoUnit.MINUTES);
                        currentRoundQuarterTime = currentRoundHalfTime.plus(15, ChronoUnit.MINUTES);
                        currentRoundEndTime = currentRoundStartTime.plus(20, ChronoUnit.MINUTES);
                        halfValue = 10;
                        quarterValue = 5;
                        msgString = String.format("""
                            💥🔫 Gotcha! Round 2 started
                            Hunter %s has the bounty now. Shoot him down before he claims it.
                            ⏱ Time limit: 20 minutes
                            💰Bounty: %s""", finalSender, getPrizePool());
                    } else {
                        currentRoundHalfTime = currentRoundStartTime.plus(5, ChronoUnit.MINUTES);
                        currentRoundQuarterTime = currentRoundHalfTime.plus(7, ChronoUnit.MINUTES);
                        currentRoundEndTime = currentRoundStartTime.plus(10, ChronoUnit.MINUTES);
                        halfValue = 5;
                        quarterValue = 3;
                        msgString = String.format("""
                            💥🔫 Gotcha! Round 3 started
                            Hunter %s has the bounty now. Shoot him down before he claims it.
                            ⏱ Time limit: 10 minutes
                            💰Bounty: %s""", finalSender, getPrizePool());
                    }
                    halfWarn = true;
                    quarterWarn = true;
                    boolean furtherCountNecessary = true;
                    if (msgString != null) {
                        last_bounty_hunter_bot.enqueueMessageForSend(chat_id, msgString, 4,
                                "https://media.giphy.com/media/RLAcIMgQ43fu7NP29d/giphy.gif",
                                "https://media.giphy.com/media/OLhBtlQ8Sa3V5j6Gg9/giphy.gif",
                                "https://media.giphy.com/media/2GkMCHQ4iz7QxlcRom/giphy.gif");
                    }
                    checkForStatus(4);


                    MID:
                    while (Instant.now().compareTo(currentRoundEndTime) <= 0) {
                        if (halfWarn) {
                            if (Instant.now().compareTo(currentRoundHalfTime) >= 0) {
                                last_bounty_hunter_bot.sendMessage(chat_id, "Hurry up! Half Time crossed. LESS THAN " + halfValue + " minutes " +
                                        "remaining for the current round. Shoot hunter " + finalSender + " down before he claims the bounty!");
                                halfWarn = false;
                            }
                        } else if (quarterWarn) {
                            if (Instant.now().compareTo(currentRoundQuarterTime) >= 0) {
                                last_bounty_hunter_bot.sendMessage(chat_id, "Hurry up! 3/4th Time crossed. LESS THAN " + quarterValue + " minutes " +
                                        "remaining for the current round. Shoot hunter " + finalSender + " down before he claims the bounty!");
                                quarterWarn = false;
                            }
                        }

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
                                    finalBurnHash = transactionData.trxHash;
                                    if (roundCount != 3) {
                                        furtherCountNecessary = false;
                                        break MID;
                                    } else {
                                        last_bounty_hunter_bot.enqueueMessageForSend(chat_id, String.format("""
                                            💥🔫 Gotcha! Hunter %s has the bounty now. Shoot 'em down before they claim it.
                                            ⏱ Remaining time: LESS THAN %d minutes
                                            💰Bounty: %s""", finalSender, Duration.between(Instant.now(), currentRoundEndTime).toMinutes(),
                                                getPrizePool()), 5,"https://media.giphy.com/media/RLAcIMgQ43fu7NP29d/giphy.gif",
                                                "https://media.giphy.com/media/OLhBtlQ8Sa3V5j6Gg9/giphy.gif",
                                                "https://media.giphy.com/media/2GkMCHQ4iz7QxlcRom/giphy.gif");
                                    }
                                    didSomeoneGotShot = true;
                                } else {
                                    addRTKToPot(transactionData.value, transactionData.fromAddress);
                                    last_bounty_hunter_bot.enqueueMessageForSend(chat_id, String.format("""
                                        🔫 Close shot! Hunter %s tried to get the bounty, but missed their shot.
                                        The bounty will be claimed in LESS THAN %s minutes.
                                        💰Updated bounty: %s""", transactionData.fromAddress,
                                            Duration.between(Instant.now(), currentRoundEndTime).toMinutes(),
                                            getPrizePool()), 5,
                                            "https://media.giphy.com/media/N4qR246iV3fVl2PwoI/giphy.gif");
                                }
                            } else {
                                furtherCountNecessary = false;
                                transactionsUnderReview.add(0, transactionData);
                                break MID;
                            }
                        }
                        performProperWait(0.7);
                    }

                    if (!scheduledExecutorService.isShutdown()) {
                        scheduledExecutorService.shutdownNow();
                    }

                    if (furtherCountNecessary) {
                        last_bounty_hunter_bot.enqueueMessageForSend(chat_id, "All rounds have ended. Checking for final desperate " +
                                "attempts of hunters...(Don't try to hunt now. Results are already set in stone)", 5);
                        didSomeoneGotShot = false;
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
                                    finalBurnHash = transactionData.trxHash;
                                    didSomeoneGotShot = true;
                                } else {
                                    addRTKToPot(transactionData.value, transactionData.fromAddress);
                                    last_bounty_hunter_bot.enqueueMessageForSend(chat_id, String.format("""
                                        🔫 Close shot! Hunter %s tried to get the bounty, but missed their shot.
                                        💰Updated bounty: %s""", transactionData.fromAddress, getPrizePool()), 5,
                                            "https://media.giphy.com/media/N4qR246iV3fVl2PwoI/giphy.gif");
                                }
                            } else {
                                transactionsUnderReview.add(0, transactionData);
                                break;
                            }
                        }
                        if (didSomeoneGotShot) {

                            performProperWait(1);
                        }
                    }
                }



                last_bounty_hunter_bot.enqueueMessageForSend(chat_id, String.format("""
                    Final valid burn :-
                    Trx Hash :%s
                    Final pot holder : %s""", finalBurnHash, finalSender), 6);
                last_bounty_hunter_bot.enqueueMessageForSend(chat_id, String.format("""
                    “Ever notice how you come across somebody once in a while you should not have messed with? That’s me.” 
                    %s – The Last Bounty Hunter – claimed the bounty and won %s.""", finalSender, getPrizePool()), 49,
                        "https://media.giphy.com/media/5obMzX3pRnSSundkPw/giphy.gif", "https://media.giphy.com/media/m3Su0jtjGHMRMnlC7L/giphy.gif");
                sendRewardToWinner(prizePool, finalSender);

                last_bounty_hunter_bot.setTotalRTKForPoolInWallet((netCurrentPool.multiply(BigInteger.valueOf(2))).divide(BigInteger.valueOf(5)).toString());
                last_bounty_hunter_bot.addAmountToWalletFeesBalance(netCurrentPool.divide(BigInteger.valueOf(10)).toString());
                last_bounty_hunter_bot.setLastCheckedTransactionDetails(lastCheckedTransactionData);
                isGameRunning = false;

                checkForStatus(50);
                last_bounty_hunter_bot.lastSendStatus = 1;
                if (notHasEnoughBalance()) {
                    last_bounty_hunter_bot.sendMessage(chat_id, "Rewards Wallet " + shotWallet + " doesn't have enough eth for transactions. " +
                            "Please contact admins. Closing Game\n\nMinimum eth required : " + new BigDecimal(minGasFees).divide(
                            new BigDecimal("1000000000000000000"), 5, RoundingMode.HALF_EVEN) + ". Actual Balance = " + rewardWalletBalance +
                            "\n\n\nThe bot will not read any transactions till the balances is updated by admins.");
                    break;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            last_bounty_hunter_bot.sendMessage(chat_id, "The bot encountered Fatal Error.\nReference : " + e.getMessage() +
                    "\n\nPlease Contact @OreGaZembuTouchiSuru");
        }

        getCurrentGameDeleted();
    }


    private void performProperWait(double seconds) {
        try {
            Thread.sleep((long) (seconds * 1000));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void checkForStatus(int sendStatus) {
        while (last_bounty_hunter_bot.lastSendStatus != sendStatus) {
            performProperWait(1);
        }
    }

    private void addRTKToPot(BigInteger amount, String sender) {
        if (!sender.equalsIgnoreCase(last_bounty_hunter_bot.topUpWalletAddress)) {
            netCurrentPool = netCurrentPool.add(amount);
            prizePool = netCurrentPool.divide(BigInteger.valueOf(2));
        }
    }

    private String getPrizePool() {
        return new BigDecimal(prizePool).divide(new BigDecimal(decimals), 3, RoundingMode.HALF_EVEN).toString() + " RTK";
    }

    private void getCurrentGameDeleted() {
        while (!last_bounty_hunter_bot.deleteGame(chat_id, this)) {
            performProperWait(1.5);
        }
        if(!scheduledExecutorService.isShutdown()) {
            scheduledExecutorService.shutdownNow();
        }
        if (!scheduledExecutorService2.isShutdown()) {
            scheduledExecutorService2.shutdownNow();
        }
        performProperWait(1);
        for (int i = 0; i < 5; i++) {
            try {
                if (!disposable[i].isDisposed()) {
                    disposable[i].dispose();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        try {
            web3j.shutdown();
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            webSocketService.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        hasGameClosed = true;
        last_bounty_hunter_bot.sendMessage(chat_id, "The bot has been shut down. Please don't send any transactions now.");
        System.out.println("Game Closed...");
    }

    public void setShouldContinueGame(boolean shouldContinueGame) {
        this.shouldContinueGame = shouldContinueGame;
    }


    // Related to Blockchain Communication
    private boolean buildCustomBlockchainReader(boolean shouldSendMessage) {

        int count = 0;
        if(shouldSendMessage) {
            last_bounty_hunter_bot.enqueueMessageForSend(chat_id, "Connecting to Ethereum Network to read transactions. Please be patient. " +
                    "This can take from few seconds to few minutes", 1);
        }
        System.out.println("Connecting to Web3");
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
                        setShouldTryToEstablishConnection();
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
            web3j = Web3j.build(webSocketService);
            Web3ClientVersion web3ClientVersion = web3j.web3ClientVersion().send();
            System.out.println("\n\n\nGame's Chat ID : " + chat_id + "\nWeb3ClientVersion : " + web3ClientVersion.getWeb3ClientVersion());

            EthFilter[] RTKContractFilter = new EthFilter[5];
            for (int i = 0; i < 5; i++) {
                RTKContractFilter[i] = new EthFilter(new DefaultBlockParameterNumber(lastCheckedTransactionData.blockNumber),
                        DefaultBlockParameterName.LATEST, RTKContractAddresses[i]);
                int finalI = i;
                disposable[i] = web3j.ethLogFlowable(RTKContractFilter[i]).subscribe(log -> {
                    String hash = log.getTransactionHash();
                    if ((prevHash[finalI] == null) || (!prevHash[finalI].equalsIgnoreCase(hash))) {
                        Optional<Transaction> trx = web3j.ethGetTransactionByHash(hash).send().getTransaction();
                        if (trx.isPresent()) {
                            TransactionData currentTrxData = splitInputData(log, trx.get());
                            currentTrxData.X = finalI + 1;
                            System.out.print("Chat ID : " + chat_id + " - Trx :  " + log.getTransactionHash() + ", X = " + (finalI + 1) +
                                    ", Did Burn = " + currentTrxData.didBurn + ", Was Counted = ");
                            if (!currentTrxData.methodName.equals("Useless") && currentTrxData.toAddress.equalsIgnoreCase(shotWallet)
                                    && currentTrxData.value.compareTo(shotCost) >= 0 && currentTrxData.compareTo(lastCheckedTransactionData) > 0) {
                                validTransactions.add(currentTrxData);
                                System.out.println("Yes");
                            } else {
                                System.out.println("No");
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
            System.out.println("\n\n\n");
        } catch (IOException e) {
            e.printStackTrace();
        }
        return !shouldTryToEstablishConnection;
    }

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
        try {
            TransactionReceipt trxReceipt = ERC20.load(RTKContractAddresses[0], web3j, Credentials.create(System.getenv("PrivateKey")),
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
                    }).transfer(toAddress, amount).sendAsync().get();
            System.out.println(trxReceipt.getTransactionHash());
            last_bounty_hunter_bot.enqueueMessageForSend(chat_id, "Reward is being sent. Trx id :- " + trxReceipt.getTransactionHash() +
                    "\n\n\nCode by : @OreGaZembuTouchiSuru", 50);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setShouldTryToEstablishConnection() {
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
}
import Supporting_Classes.PSB_LastGameState;
import Supporting_Classes.ProxyIP;
import Supporting_Classes.TransactionData;
import Supporting_Classes.WebSocketService;
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
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.DefaultBlockParameterNumber;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.Transaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.websocket.WebSocketClient;
import org.web3j.tx.gas.ContractGasProvider;

import java.io.IOException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.net.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class PotShotBotGame implements Runnable {

    private class webSocketReconnect implements Runnable {
        @Override
        public void run() {
            if (allowConnector && shouldTryToEstablishConnection) {

                if(webSocketService != null) {
                    try {
                        if (!disposable.isDisposed()) {
                            disposable.dispose();
                        }
                    } catch (Exception e) {
                        e.printStackTrace(pot_shot_bot.logsPrintStream);
                    }
                    try {
                        web3j.shutdown();
                    } catch (Exception e) {
                        e.printStackTrace(pot_shot_bot.logsPrintStream);
                    }
                    try {
                        webSocketService.close();
                    } catch (Exception e) {
                        e.printStackTrace(pot_shot_bot.logsPrintStream);
                    }
                }
                try {
                    if(connectionCount == 2) {
                        getCurrentGameDeleted("Deleter-WebSocket-Reconnect-Executor");
                    }
                    if(!buildCustomBlockchainReader(false)) {
                        connectionCount++;
                    } else {
                        connectionCount = 0;
                    }
                } catch (Exception e) {
                    e.printStackTrace(pot_shot_bot.logsPrintStream);
                }
            }
        }
    }

    // Managing Variables
    Logger logger = Logger.getLogger(PotShotBotGame.class);
    volatile boolean shouldContinueGame = true, hasGameClosed = false;
    volatile TransactionData lastCheckedTransactionData = null;
    private final Pot_Shot_Bot pot_shot_bot;
    private final String chat_id;
    private final ScheduledExecutorService webSocketReconnectExecutorService = Executors.newSingleThreadScheduledExecutor();
    private int connectionCount = 0;
    private volatile boolean allowConnector = true;
    private final boolean shouldSendNotificationToMainRTKChat;
    private boolean isBalanceEnough = false;

    private final String EthNetworkType, shotWallet;
    private final BigInteger shotCost, decimals = new BigInteger("1000000000000000000");
    private final List<String> RTKContractAddresses;
    private String prevHash;
    private Supporting_Classes.WebSocketService webSocketService;
    private Web3j web3j;
    private Disposable disposable;
    private final ArrayList<TransactionData> validTransactions = new ArrayList<>(), transactionsUnderReview = new ArrayList<>();
    private boolean shouldTryToEstablishConnection = true;
    private BigDecimal rewardWalletBalance;
    private BigInteger gasPrice, minGasFees, netCurrentPool = BigInteger.valueOf(0), prizePool = BigInteger.valueOf(0);
    ArrayList<String> last3CountedHash = new ArrayList<>();
    
    PotShotBotGame(Pot_Shot_Bot pot_shot_bot, String chat_id, String EthNetworkType, String shotWallet, String[] RTKContractAddresses,
                   BigInteger shotCost) {
        this.pot_shot_bot = pot_shot_bot;
        this.chat_id = chat_id;
        this.EthNetworkType = EthNetworkType;
        this.shotWallet = shotWallet;
        this.RTKContractAddresses = Arrays.asList(RTKContractAddresses);
        this.shotCost = shotCost;
        shouldSendNotificationToMainRTKChat = EthNetworkType.toLowerCase().contains("mainnet");
    }

    @Override
    public void run() {
        netCurrentPool = new BigInteger(pot_shot_bot.getTotalRTKForPoolInWallet());
        prizePool = netCurrentPool.divide(BigInteger.valueOf(2));

        try {
            PSB_LastGameState psb_lastGameState = pot_shot_bot.getLastGameState();
            lastCheckedTransactionData = psb_lastGameState.lastCheckedTransactionData;
            prevHash = lastCheckedTransactionData.trxHash;
            last3CountedHash = psb_lastGameState.last3CountedHash;
        } catch (Exception e) {
            e.printStackTrace(pot_shot_bot.logsPrintStream);
        }

        pot_shot_bot.logsPrintStream.println("Last Game Last Checked TrxData ===>> " + lastCheckedTransactionData);

        if(!buildCustomBlockchainReader(true)) {
            pot_shot_bot.sendMessage(chat_id, "Error encountered while trying to connect to ethereum network. Cancelling the " +
                    "lastBountyHunterGame.");

            getCurrentGameDeleted("Deleter-Failed-Initial-Blockchain-Connect-Attempt");
            return;
        }

        if (!isBalanceEnough) {
            pot_shot_bot.enqueueMessageForSend(chat_id, String.format("""
                            Rewards Wallet %s doesn't have enough eth for transactions. Please contact admins. Closing LastBountyHunterGame...
                                                        
                            Minimum eth required : %s. Actual Balance = %s
                                                        
                            The bot will not read any transactions till the balances are updated by admins.""", shotWallet,
                    new BigDecimal(minGasFees).divide(new BigDecimal("1000000000000000000"), 5, RoundingMode.HALF_EVEN), rewardWalletBalance),
                    null);
            getCurrentGameDeleted("Deleter-ETH-Insufficient-Balance");
            return;
        }

        webSocketReconnectExecutorService.scheduleWithFixedDelay(new PotShotBotGame.webSocketReconnect(), 0, 5000, TimeUnit.MILLISECONDS);
        
        try {
            String mainRuletkaChatID = "-1001303208172";
            
            while (shouldContinueGame) {

                if (validTransactions.size() == 0 && transactionsUnderReview.size() == 0) {
                    performProperWait(2);
                    continue;
                }

                TransactionData transactionData;
                while (!validTransactions.isEmpty()) {
                    transactionsUnderReview.add(validTransactions.remove(0));
                }
                Collections.sort(transactionsUnderReview);

                while (transactionsUnderReview.size() > 0) {
                    transactionData = transactionsUnderReview.remove(0);
                    lastCheckedTransactionData = transactionData;
                    if (transactionData.didBurn) {
                        pot_shot_bot.enqueueMessageForSend(chat_id, String.format("""
                                        Hash :- %s, X = %s
                                        Hunter %s got shot and claimed the pot.
                                        💰 Prize Amount : %s""", trimHashAndAddy(transactionData.trxHash), transactionData.X,
                                trimHashAndAddy(transactionData.fromAddress),
                                getPrizePool()), transactionData, "https://media.giphy.com/media/RLAcIMgQ43fu7NP29d/giphy.gif",
                                "https://media.giphy.com/media/OLhBtlQ8Sa3V5j6Gg9/giphy.gif",
                                "https://media.giphy.com/media/2GkMCHQ4iz7QxlcRom/giphy.gif");

                        if (shouldSendNotificationToMainRTKChat) {
                            pot_shot_bot.enqueueMessageForSend(mainRuletkaChatID, String.format("""
                                        Hunter %s got shot and claimed : %s
                                        Check out the group @%s""", trimHashAndAddy(transactionData.fromAddress), getPrizePool(),
                                    pot_shot_bot.getBotUsername()), transactionData);
                        }

                        sendRewardToWinner(prizePool, transactionData.fromAddress);
                        tryToSaveState();
                    } else {
                        addRTKToPot(transactionData.value, transactionData.fromAddress);
                        pot_shot_bot.enqueueMessageForSend(chat_id, String.format("""
                                        Hash :- %s, X = %s
                                        🔫 Close shot! Hunter %s tried to get the bounty, but missed their shot.
                                        💰 Updated bounty: %s""", trimHashAndAddy(transactionData.trxHash), transactionData.X,
                                trimHashAndAddy(transactionData.fromAddress), getPrizePool()), transactionData,
                                "https://media.giphy.com/media/N4qR246iV3fVl2PwoI/giphy.gif");
                    }
                }
                performProperWait(0.7);
            }
        } catch (Exception e) {
            e.printStackTrace(pot_shot_bot.logsPrintStream);
        }

        tryToSaveState();
        getCurrentGameDeleted("Deleter-Run-END");
    }
    

    // Fixed
    @SuppressWarnings({"SpellCheckingInspection", "BooleanMethodIsAlwaysInverted"})
    private boolean buildCustomBlockchainReader(boolean shouldSendMessage) {

        int count = 0;
        if (shouldSendMessage) {
            pot_shot_bot.enqueueMessageForSend(chat_id, "Connecting to Blockchain Network to read transactions. Please be patient. " +
                    "This can take from few seconds to few minutes", null);
        }
        pot_shot_bot.logsPrintStream.println("Connecting to Web3");
        shouldTryToEstablishConnection = true;


        // Url + WebSocketClient + Supporting_Classes.WebSocketService  <--- Build + Connect
        while (shouldTryToEstablishConnection && count < 2) {
            pot_shot_bot.logsPrintStream.println("Connecting to Blockchain... Attempt : " + (count + 1));
            // Pre Disposer
            if(count != 0) {
                pot_shot_bot.logsPrintStream.println("XXXXX\nXXXXX\nDisposer Before Re-ConnectionBuilder\nXXXXX\nXXXXX");
                try {
                    if (!disposable.isDisposed()) {
                        disposable.dispose();
                    }
                } catch (Exception e) {
                    e.printStackTrace(pot_shot_bot.logsPrintStream);
                }
                try {
                    web3j.shutdown();
                } catch (Exception e) {
                    e.printStackTrace(pot_shot_bot.logsPrintStream);
                }
                try {
                    webSocketService.close();
                } catch (Exception e) {
                    e.printStackTrace(pot_shot_bot.logsPrintStream);
                }
            }
            count++;

            // Building Urls, WebSocketClient and Supporting_Classes.WebSocketService
            ArrayList<String> webSocketUrls;
            String prefix, infix;
            if(EthNetworkType.startsWith("matic")) {
                infix = EthNetworkType.toLowerCase().substring(5);
                if(infix.equals("mainnet") && pot_shot_bot.shouldUseQuickNode) {
                    webSocketUrls = pot_shot_bot.quickNodeWebSocketUrls;
                    prefix = "";
                    infix = "";
                } else {
                    webSocketUrls = pot_shot_bot.maticWebSocketUrls;
                    prefix = pot_shot_bot.maticPrefix;
                }
            } else {
                webSocketUrls = pot_shot_bot.etherWebSocketUrls;
                prefix = pot_shot_bot.etherPrefix;
                infix = EthNetworkType;
            }
            Collections.shuffle(webSocketUrls);
            URI uri = null;
            try {
                uri = new URI(prefix + infix + webSocketUrls.get(0));
            } catch (Exception e) {
                e.printStackTrace(pot_shot_bot.logsPrintStream);
            }
            assert uri != null;
            WebSocketClient webSocketClient = new WebSocketClient(uri) {
                @Override
                public void onClose(int code, String reason, boolean remote) {
                    super.onClose(code, reason, remote);
                    logger.info("(onClose) : " + chat_id + " : WebSocket connection to " + uri + " closed successfully " + reason);
                    setShouldTryToEstablishConnection();
                }

                @Override
                public void onError(Exception e) {
                    super.onError(e);
                    setShouldTryToEstablishConnection();
                    logger.error("XXXXX\nXXXXX\n" + "(onError) : " + chat_id + " : WebSocket connection to " + uri + " failed.... \n" +
                            "Class : LastBountyHunterGame.java\nLine No. : " + e.getStackTrace()[0].getLineNumber() + "\nTrying For Reconnect...\nXXXXX\nXXXXX");
                }
            };
            // Setting up Proxy
            if(pot_shot_bot.shouldUseProxy) {
                ProxyIP proxyIP = pot_shot_bot.getProxyIP();
                Authenticator.setDefault(
                        new Authenticator() {
                            @Override
                            public PasswordAuthentication getPasswordAuthentication() {
                                return new PasswordAuthentication(pot_shot_bot.proxyUsername,
                                        pot_shot_bot.proxyPassword.toCharArray());
                            }
                        }
                );
                System.setProperty("http.proxyUser", pot_shot_bot.proxyUsername);
                System.setProperty("http.proxyPassword", pot_shot_bot.proxyPassword);
                System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
                webSocketClient.setProxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyIP.host, proxyIP.port)));
            }
            webSocketService = new WebSocketService(webSocketClient, true);
            pot_shot_bot.logsPrintStream.println("Connect Url : " + prefix + infix + webSocketUrls.get(0));


            // Connecting to WebSocket
            try {
                webSocketService.connect();
                pot_shot_bot.logsPrintStream.println("Connection Successful");
                shouldTryToEstablishConnection = false;
            } catch (Exception e) {
                pot_shot_bot.logsPrintStream.println("External Error While Connect to Supporting_Classes.WebSocketService... Entered Catch Block");
                e.printStackTrace(pot_shot_bot.logsPrintStream);
                setShouldTryToEstablishConnection();
            }


            performProperWait(2);
        }


        // Building Web3j over Connected Supporting_Classes.WebSocketService
        web3j = Web3j.build(webSocketService);
        try {
            pot_shot_bot.logsPrintStream.println("Game's Chat ID : " + chat_id + "\nWeb3ClientVersion : " +
                    web3j.web3ClientVersion().send().getWeb3ClientVersion());
        } catch (IOException e) {
            pot_shot_bot.logsPrintStream.println("Unable to fetch Client Version... In Catch Block");
            e.printStackTrace(pot_shot_bot.logsPrintStream);
            setShouldTryToEstablishConnection();
        }
        EthFilter RTKContractFilter;
        BigInteger latestBlockNumber = null;
        try {
            latestBlockNumber = web3j.ethBlockNumber().send().getBlockNumber();
        } catch (Exception e) {
            e.printStackTrace(pot_shot_bot.logsPrintStream);
        }
        BigInteger startBlock = lastCheckedTransactionData.blockNumber;
        if(EthNetworkType.startsWith("maticMainnet") && !pot_shot_bot.shouldUseQuickNode && latestBlockNumber != null) {
            startBlock = (startBlock.compareTo(latestBlockNumber.subtract(BigInteger.valueOf(850))) >= 0) ? startBlock : latestBlockNumber;
        }
        pot_shot_bot.logsPrintStream.println("Building Filter\nLast Checked Block Number : " + lastCheckedTransactionData.blockNumber
                + "\nStart Block For Filter : " + startBlock);
        RTKContractFilter = new EthFilter(new DefaultBlockParameterNumber(startBlock), DefaultBlockParameterName.LATEST, RTKContractAddresses);
        RTKContractFilter.addOptionalTopics("0x897c6a07c341708f5a14324ccd833bbf13afacab63b30bbd827f7f1d29cfdff4",
                "0xe7d849ade8c22f08229d6eec29ca84695b8f946b0970558272215552d79076e6");
        isBalanceEnough = hasEnoughBalance();
        try {
            disposable = web3j.ethLogFlowable(RTKContractFilter).subscribe(log -> {
                String hash = log.getTransactionHash();
                if ((prevHash == null) || (!prevHash.equalsIgnoreCase(hash))) {
                    Optional<Transaction> trx = web3j.ethGetTransactionByHash(hash).send().getTransaction();
                    if (trx.isPresent()) {
                        TransactionData currentTrxData = splitInputData(log, trx.get());

                        if (currentTrxData.containsBuildError) {
                            pot_shot_bot.logsPrintStream.println("Error Trx Data : " + currentTrxData);
                            return;
                        }

                        boolean f1, f2, f3, f4, f5;

                        if (currentTrxData.methodName != null) {
                            f1 = !currentTrxData.methodName.equals("Useless");
                        } else {
                            f1 = false;
                        }
                        if (currentTrxData.toAddress != null) {
                            f2 = currentTrxData.toAddress.equalsIgnoreCase(shotWallet);
                        } else {
                            f2 = false;
                        }
                        if (currentTrxData.value != null) {
                            f3 = currentTrxData.value.compareTo(shotCost) >= 0;
                        } else {
                            f3 = false;
                        }

                        f4 = currentTrxData.compareTo(lastCheckedTransactionData) > 0;
                        f5 = isNotOldHash(currentTrxData.trxHash);

                        boolean counted = f1 && f2 && f3 && f4 && f5;
                        
                        if (counted) {
                            pot_shot_bot.logsPrintStream.println("Chat ID : " + chat_id + " ===>> " + currentTrxData +
                                    ", PrevHash : " + prevHash + ", Was Counted = " + true);
                            validTransactions.add(currentTrxData);
                            pushTransaction(currentTrxData.trxHash);
                            prevHash = hash;
                        } else {
                            pot_shot_bot.logsPrintStream.println("Ignored Incoming Hash : " + currentTrxData.trxHash + ", Reason : \n" +
                                    "Useless : " + !f1 + ", Our Wallet : " + f2 +  ", Valid Amount : " + f3 + ", Is Successor : " + f4 +
                                    ", Not old : " + f5);
                        }
                    }
                }
                pot_shot_bot.lastMomentWhenTrxWasRead = Instant.now();
            }, throwable -> {
                pot_shot_bot.logsPrintStream.println("Disposable Internal Error (Throwable)");
                throwable.printStackTrace(pot_shot_bot.logsPrintStream);
                setShouldTryToEstablishConnection();
            });
        } catch (Exception e) {
            pot_shot_bot.logsPrintStream.println("Error while creating disposable");
            e.printStackTrace(pot_shot_bot.logsPrintStream);
        }
        if(disposable == null) {
            setShouldTryToEstablishConnection();
            return false;
        }
        pot_shot_bot.logsPrintStream.println("\n\n");


        return !shouldTryToEstablishConnection;
    }
    
    @SuppressWarnings("SpellCheckingInspection")
    private TransactionData splitInputData(Log log, Transaction transaction) throws Exception {
        String inputData = transaction.getInput();
        TransactionData currentTransactionData = new TransactionData();
        String method = inputData.substring(0, 10);
        currentTransactionData.methodName = method;
        currentTransactionData.trxHash = transaction.getHash();
        try {
            currentTransactionData.blockNumber = transaction.getBlockNumber();
        } catch (Exception e) {
            currentTransactionData.methodName = "Useless";
            return currentTransactionData;
        }
        currentTransactionData.trxIndex = transaction.getTransactionIndex();
        currentTransactionData.X = RTKContractAddresses.indexOf(log.getAddress().toLowerCase());

        // If method is transfer method
        if (method.equalsIgnoreCase("0xa9059cbb")) {
            currentTransactionData.fromAddress = transaction.getFrom().toLowerCase();
            String topic = log.getTopics().get(0);
            if (topic.equalsIgnoreCase("0x897c6a07c341708f5a14324ccd833bbf13afacab63b30bbd827f7f1d29cfdff4")) {
                currentTransactionData.didBurn = true;
            } else if (topic.equalsIgnoreCase("0xe7d849ade8c22f08229d6eec29ca84695b8f946b0970558272215552d79076e6")) {
                currentTransactionData.didBurn = false;
            } else {
                currentTransactionData.containsBuildError = true;
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

    private void sendRewardToWinner(BigInteger amount, String toAddress) {
        try {
            TransactionReceipt trxReceipt = ERC20.load(RTKContractAddresses.get(0), web3j, Credentials.create(
                    System.getenv("PSB" + pot_shot_bot.botType.charAt(0) + "PrivateKey")),
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
            String rewardMsg = "Reward is being sent. Trx id :- " + trxReceipt.getTransactionHash() +
                    "\n\n\nCode by : @OreGaZembuTouchiSuru";
            pot_shot_bot.logsPrintStream.println("Reward Sender Success. Msg :-\n" + rewardMsg);
            pot_shot_bot.enqueueMessageForSend(chat_id, rewardMsg, null);
        } catch (Exception e) {
            e.printStackTrace(pot_shot_bot.logsPrintStream);
        }
    }
    
    private void performProperWait(double seconds) {
        try {
            Thread.sleep((long) (seconds * 1000));
        } catch (Exception e) {
            e.printStackTrace(pot_shot_bot.logsPrintStream);
        }
    }

    public void sendBountyUpdateMessage(BigInteger amount) {
        pot_shot_bot.sendMessage(chat_id, "Bounty Increased...LastBountyHunterGame Host added " + getPrizePool(amount.divide(BigInteger.valueOf(2)))
                + " to the current Bounty");
    }
    
    private String trimHashAndAddy(String string) {
        if(string != null && string.length() >= 12) {
            int len = string.length();
            return string.substring(0, 6) + "...." + string.substring(len - 6, len);
        } else {
            return "None";
        }
    }

    private boolean isNotOldHash(String hash) {
        return !last3CountedHash.contains(hash);
    }

    private void pushTransaction(String hash) {
        if(last3CountedHash.size() == 3) {
            last3CountedHash.remove(0);
        }
        last3CountedHash.add(hash);
    }

    public void setShouldTryToEstablishConnection() {
        shouldTryToEstablishConnection = true;
    }

    private boolean hasEnoughBalance() {
        boolean retVal = false;

        try {
            gasPrice = web3j.ethGasPrice().send().getGasPrice();
            BigInteger balance = web3j.ethGetBalance(shotWallet, DefaultBlockParameterName.LATEST).send().getBalance();
            minGasFees = gasPrice.multiply(new BigInteger("195000"));
            pot_shot_bot.logsPrintStream.println("Network type = " + EthNetworkType + ", Wallet Balance = " + balance + ", Required Balance = " + minGasFees +
                    ", gasPrice = " + gasPrice);
            rewardWalletBalance = new BigDecimal(balance).divide(new BigDecimal("1000000000000000000"), 5, RoundingMode.HALF_EVEN);
            if (balance.compareTo(minGasFees) > 0) {
                retVal = true;
            }
        } catch (Exception e) {
            pot_shot_bot.logsPrintStream.println("Error when trying to get Wallet Balance");
            e.printStackTrace(pot_shot_bot.logsPrintStream);
        }

        return retVal;
    }

    private BigInteger getNetRTKWalletBalance(int X) {
        assert (X >= 1 && X <= 5);
        try {
            BigInteger finalValue = new BigInteger("0");
            Function function = new Function("balanceOf",
                    Collections.singletonList(new Address(shotWallet)),
                    Collections.singletonList(new TypeReference<Uint256>() {
                    }));

            String encodedFunction = FunctionEncoder.encode(function);
            org.web3j.protocol.core.methods.response.EthCall response = web3j.ethCall(
                    org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction(shotWallet, RTKContractAddresses.get(X - 1),
                            encodedFunction), DefaultBlockParameterName.LATEST).send();
            List<Type> balances = FunctionReturnDecoder.decode(response.getValue(), function.getOutputParameters());
            finalValue = finalValue.add(new BigInteger(balances.get(0).getValue().toString()));
            pot_shot_bot.logsPrintStream.println("RTKL" + X + " Balance of the Wallet : " + finalValue);
            return finalValue;
        } catch (Exception e) {
            e.printStackTrace(pot_shot_bot.logsPrintStream);
            return null;
        }
    }

    private void getCurrentGameDeleted(String deleterId) {
        allowConnector = false;
        while (!pot_shot_bot.deleteGame(chat_id, this, deleterId)) {
            performProperWait(1.5);
        }
        if (!webSocketReconnectExecutorService.isShutdown()) {
            webSocketReconnectExecutorService.shutdownNow();
        }
        hasGameClosed = true;
        pot_shot_bot.sendMessage(chat_id, "The bot has been shut down. Please don't send any transactions now.");
        pot_shot_bot.logsPrintStream.println("XXXXX\nXXXXX\nGetGameDeletedDisposer\nXXXXX\nXXXXX");
        try {
            if (!disposable.isDisposed()) {
                disposable.dispose();
            }
        } catch (Exception e) {
            e.printStackTrace(pot_shot_bot.logsPrintStream);
        }
        try {
            web3j.shutdown();
        } catch (Exception e) {
            e.printStackTrace(pot_shot_bot.logsPrintStream);
        }
        try {
            webSocketService.close();
        } catch (Exception e) {
            e.printStackTrace(pot_shot_bot.logsPrintStream);
        }
        pot_shot_bot.decreaseUndisposedGameCount();
        pot_shot_bot.logsPrintStream.println("Game Closed...");
    }
    
    public void tryToSaveState() {
        pot_shot_bot.setTotalRTKForPoolInWallet(netCurrentPool.toString());
        pot_shot_bot.setLastCheckedTransactionDetails(lastCheckedTransactionData, last3CountedHash);
    }


    // Un-Fixed
    public boolean addRTKToPot(BigInteger amount, String sender) {
        boolean allow = false;
        if(sender.equalsIgnoreCase("Override")) {
            BigInteger rtkBal = getNetRTKWalletBalance(1);
            allow = (rtkBal != null) && (rtkBal.compareTo(amount.add(new BigInteger("500000000000000000000"))) >= 0);
        } else if (!sender.equalsIgnoreCase(pot_shot_bot.topUpWalletAddress)) {
            allow = true;
        }

        if(allow) {
            netCurrentPool = netCurrentPool.add(amount);
            prizePool = netCurrentPool.divide(BigInteger.valueOf(2));
        }

        return allow;
    }

    private String getPrizePool() {
        return new BigDecimal(prizePool).divide(new BigDecimal(decimals), 3, RoundingMode.HALF_EVEN) + " RTK";
    }

    private String getPrizePool(BigInteger amount) {
        return new BigDecimal(amount).divide(new BigDecimal(decimals), 3, RoundingMode.HALF_EVEN) + " RTK";
    }
}

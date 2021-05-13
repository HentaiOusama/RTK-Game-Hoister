import Supporting_Classes.LBH_LastGameState;
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
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.contracts.eip20.generated.ERC20;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.DefaultBlockParameterNumber;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.*;
import org.web3j.protocol.websocket.WebSocketClient;
import org.web3j.tx.gas.ContractGasProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.net.*;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class LastBountyHunterGame implements Runnable {

    private class finalBlockRecorder implements Runnable {
        @Override
        public void run() {
            if (Instant.now().compareTo(currentRoundEndTime) <= 0) {
                try {
                    finalLatestBlockNumber = web3j.ethBlockNumber().send().getBlockNumber();
                } catch (IOException e) {
                    e.printStackTrace(last_bounty_hunter_bot.logsPrintStream);
                }
            }
        }
    }

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
                        e.printStackTrace(last_bounty_hunter_bot.logsPrintStream);
                    }
                    try {
                        web3j.shutdown();
                    } catch (Exception e) {
                        e.printStackTrace(last_bounty_hunter_bot.logsPrintStream);
                    }
                    try {
                        webSocketService.close();
                    } catch (Exception e) {
                        e.printStackTrace(last_bounty_hunter_bot.logsPrintStream);
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
                    e.printStackTrace(last_bounty_hunter_bot.logsPrintStream);
                }
            }
        }
    }

    // Managing Variables
    Logger logger = Logger.getLogger(LastBountyHunterGame.class);
    volatile boolean isGameRunning = false, shouldContinueGame = true, didSomeoneGotShot = false, hasGameClosed = false;
    volatile TransactionData lastCheckedTransactionData = null;
    volatile boolean shouldRecoverFromAbruptInterruption = false;
    volatile boolean abruptRecoveryComplete = true;
    private final Last_Bounty_Hunter_Bot last_bounty_hunter_bot;
    private final String chat_id;
    private volatile Instant currentRoundEndTime = null;
    private volatile BigInteger finalLatestBlockNumber = null;
    private final ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService scheduledExecutorService2 = Executors.newSingleThreadScheduledExecutor();
    private int connectionCount = 0;
    private volatile boolean allowConnector = true;
    private final boolean shouldSendNotificationToMainRTKChat;
    private boolean isBalanceEnough = false;
    private final SimpleDateFormat simpleDateFormat = new SimpleDateFormat("dd/MM/yyyy hh:mm:ss a");
    private final String zone;

    // Blockchain Related Stuff
    private final String EthNetworkType, shotWallet;
    private final BigInteger shotCost, decimals = new BigInteger("1000000000000000000");
    private final List<String> RTKContractAddresses;
    private String prevHash;
    private Supporting_Classes.WebSocketService webSocketService; // Custom Supporting_Classes.WebSocketService Used. (Do not Import Web3j....Supporting_Classes.WebSocketService)
    private Web3j web3j;
    private Disposable disposable;
    private final ArrayList<TransactionData> validTransactions = new ArrayList<>(), transactionsUnderReview = new ArrayList<>();
    private boolean shouldTryToEstablishConnection = true;
    private BigDecimal rewardWalletBalance;
    private BigInteger gasPrice, minGasFees, netCurrentPool = BigInteger.valueOf(0), prizePool = BigInteger.valueOf(0);
    ArrayList<String> last3CountedHash = new ArrayList<>();

    // Constructor
    LastBountyHunterGame(Last_Bounty_Hunter_Bot last_bounty_hunter_bot, String chat_id, String EthNetworkType, String shotWallet, String[] RTKContractAddresses,
                         BigInteger shotCost) {
        this.last_bounty_hunter_bot = last_bounty_hunter_bot;
        this.chat_id = chat_id;
        this.EthNetworkType = EthNetworkType;
        this.shotWallet = shotWallet;
        for(int i = 0; i < RTKContractAddresses.length; i++) {
            RTKContractAddresses[i] = RTKContractAddresses[i].toLowerCase();
        }
        this.RTKContractAddresses = Arrays.asList(RTKContractAddresses);
        this.shotCost = shotCost;
        shouldSendNotificationToMainRTKChat = EthNetworkType.toLowerCase().contains("mainnet");
        long millis = TimeZone.getDefault().getRawOffset();
        int minutes = (int) ((millis / (1000*60)) % 60);
        int hours   = (int) ((millis / (1000*60*60)) % 24);
        zone = "Time Zone - UTC : " + ((millis < 0) ? "-" : "+") + hours + ":" + minutes;
    }

    @Override
    public void run() {
        try {
            netCurrentPool = new BigInteger(last_bounty_hunter_bot.getTotalRTKForPoolInWallet());
            prizePool = netCurrentPool.divide(BigInteger.valueOf(2));

            lastCheckedTransactionData = last_bounty_hunter_bot.getLastCheckedTransactionDetails();

            scheduledExecutorService.scheduleWithFixedDelay(new finalBlockRecorder(), 0, 3000, TimeUnit.MILLISECONDS);

            last_bounty_hunter_bot.logsPrintStream.println("Last Game Last Checked TrxData ===>> " + lastCheckedTransactionData);
            shouldRecoverFromAbruptInterruption = !last_bounty_hunter_bot.getWasGameEndMessageSent();
            abruptRecoveryComplete = !shouldRecoverFromAbruptInterruption;
            last_bounty_hunter_bot.logsPrintStream.println("Was Game End Message sent : " + !shouldRecoverFromAbruptInterruption);
            Instant lastGameEndTime = Instant.now();
            if (shouldRecoverFromAbruptInterruption) {
                last_bounty_hunter_bot.makeChecks = true;
                LBH_LastGameState LBHLastGameState = last_bounty_hunter_bot.getLastGameState();
                last3CountedHash = LBHLastGameState.last3CountedHash;
                lastGameEndTime = LBHLastGameState.lastGameEndTime;
            } else {
                last_bounty_hunter_bot.makeChecks = false;
            }
            if (EthNetworkType.equalsIgnoreCase("ropsten")) {
                last_bounty_hunter_bot.enqueueMessageForSend(chat_id, "Warning! The bot is running on Ethereum Ropsten network and not on Mainnet.", -1, null);
            } else if (EthNetworkType.equalsIgnoreCase("mainnet")) {
                last_bounty_hunter_bot.enqueueMessageForSend(chat_id, "The bot is running on Ethereum Mainnet network.", -1, null);
            } else if (EthNetworkType.equalsIgnoreCase("maticMainnet")) {
                last_bounty_hunter_bot.enqueueMessageForSend(chat_id, "The bot is running on MATIC Mainnet network", -1, null);
            } else if (EthNetworkType.equalsIgnoreCase("maticMumbai")) {
                last_bounty_hunter_bot.enqueueMessageForSend(chat_id, "Warning! The bot is running on MATIC Testnet network and not on Mainnet", -1, null);
            }
            last_bounty_hunter_bot.enqueueMessageForSend(chat_id, String.format("""
                        Welcome to the Last Bounty Hunter lastBountyHunterGame.
                        Do you have what it takes to be the Last Bounty Hunter?
                                        
                        Latest Prize Pool : %s
                                        
                        Note :- Each shot is considered to be valid ONLY IF :-
                        1) Shot amount is at least %s RTK or RTKLX
                        2) It is sent to the below address :-""", getPrizePool(), shotCost.divide(decimals)), 0, null,
                    "https://media.giphy.com/media/UNBtv83uhrDrqShIhX/giphy.gif");
            last_bounty_hunter_bot.enqueueMessageForSend(chat_id, shotWallet, 0, null);
            last_bounty_hunter_bot.resetWasGameEndMessageSent();

            String finalSender = null, finalBurnHash = null;
            boolean halfWarn, quarterWarn;
            int halfValue, quarterValue;

            if (!buildCustomBlockchainReader(true)) {
                last_bounty_hunter_bot.sendMessage(chat_id, "Error encountered while trying to connect to ethereum network. Cancelling the " +
                        "lastBountyHunterGame.");

                getCurrentGameDeleted("Deleter-Failed-Initial-Blockchain-Connect-Attempt");
                return;
            }

            if (!isBalanceEnough) {
                last_bounty_hunter_bot.enqueueMessageForSend(chat_id, String.format("""
                            Rewards Wallet %s doesn't have enough eth for transactions. Please contact admins. Closing Game...
                                                        
                            Minimum eth required : %s. Actual Balance = %s
                                                        
                            The bot will not read any transactions till the balances are updated by admins.""", shotWallet,
                        new BigDecimal(minGasFees).divide(new BigDecimal("1000000000000000000"), 5, RoundingMode.HALF_EVEN), rewardWalletBalance),
                        -2, null);
                getCurrentGameDeleted("Deleter-ETH-Insufficient-Balance");
                return;
            }

            BigInteger RTKBalance = getNetRTKWalletBalance(1);
            if(RTKBalance == null || !(RTKBalance.compareTo(netCurrentPool.add(new BigInteger("500000000000000000000"))) >= 0)) {
                last_bounty_hunter_bot.sendMessage(chat_id, "LastBountyHunterGame Wallet RTK Balance too Low. (Min. Requirements : poolSize + 500). " +
                        "Please Contact admins. Closing the Game...");
                getCurrentGameDeleted("Deleter-RTK-Insufficient-Balance");
                return;
            }

            scheduledExecutorService2.scheduleWithFixedDelay(new webSocketReconnect(), 0, 5000, TimeUnit.MILLISECONDS);

            checkForStatus(1);
            if (!last_bounty_hunter_bot.makeChecks) {
                last_bounty_hunter_bot.sendMessage(chat_id, "Connection Successful... Keep Shooting....");
                performProperWait(1.5);
            }

            try {
                while (shouldContinueGame) {

                    if (validTransactions.size() == 0 && transactionsUnderReview.size() == 0) {
                        performProperWait(2);
                        continue;
                    }

                    // Check for initial Burned Transaction to start the lastBountyHunterGame.
                    didSomeoneGotShot = false;
                    TransactionData transactionData;
                    while (!validTransactions.isEmpty()) {
                        transactionsUnderReview.add(validTransactions.remove(0));
                    }
                    Collections.sort(transactionsUnderReview);

                    String mainRuletkaChatID = "-1001303208172";
                    while (transactionsUnderReview.size() > 0 && !didSomeoneGotShot) {
                        transactionData = transactionsUnderReview.remove(0);
                        lastCheckedTransactionData = transactionData;
                        if (transactionData.didBurn) {
                            finalSender = transactionData.fromAddress;
                            finalBurnHash = transactionData.trxHash;
                            last_bounty_hunter_bot.enqueueMessageForSend(chat_id, String.format("""
                                        💥🔫 First blood!!!
                                        Hash :- %s
                                        Hunter %s has the bounty. Shoot him down before he claims it.
                                        ⏱ Time limit: 30 minutes
                                        💰 Bounty: %s""", trimHashAndAddy(finalBurnHash), trimHashAndAddy(finalSender), getPrizePool()),
                                    3, transactionData,"https://media.giphy.com/media/xaMURZrCVsFZzK6DnP/giphy.gif",
                                    "https://media.giphy.com/media/UtXbAXl8Pt4Kr0f02Q/giphy.gif");
                            if(shouldSendNotificationToMainRTKChat) {
                                last_bounty_hunter_bot.enqueueMessageForSend(mainRuletkaChatID, String.format("""
                                        💥🔫 First blood!!!
                                        Hash :- %s
                                        Hunter %s has the bounty. Shoot him down before he claims it.
                                        ⏱ Time limit: 30 minutes
                                        💰 Bounty: %s
                                        
                                        Checkout @Last_Bounty_Hunter_RTK group now and grab that bounty""",  trimHashAndAddy(finalBurnHash),
                                        trimHashAndAddy(finalSender), getPrizePool()),3, transactionData);
                            }
                            didSomeoneGotShot = true;
                        } else {
                            addRTKToPot(transactionData.value, transactionData.fromAddress);
                            last_bounty_hunter_bot.enqueueMessageForSend(chat_id, String.format("""
                                        Hash :- %s
                                        \uD83D\uDD2B Close shot! Hunter %s tried to get the bounty, but missed their shot.

                                        Updated Bounty : %s""", trimHashAndAddy(transactionData.trxHash),
                                    trimHashAndAddy(transactionData.fromAddress), getPrizePool()), 2, transactionData,
                                    "https://media.giphy.com/media/N4qR246iV3fVl2PwoI/giphy.gif");
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
                        halfWarn = true;
                        quarterWarn = true;
                        if (roundCount == 1) {
                            if (shouldRecoverFromAbruptInterruption && lastGameEndTime != null && Instant.now().compareTo(lastGameEndTime) <= 0) {
                                currentRoundEndTime = lastGameEndTime;
                                currentRoundHalfTime = currentRoundEndTime.minus(15, ChronoUnit.MINUTES);
                                currentRoundQuarterTime = currentRoundEndTime.minus(8, ChronoUnit.MINUTES);
                                halfWarn = Instant.now().compareTo(currentRoundHalfTime) < 0;
                                quarterWarn = Instant.now().compareTo(currentRoundQuarterTime) < 0;
                            } else {
                                currentRoundHalfTime = currentRoundStartTime.plus(15, ChronoUnit.MINUTES);
                                currentRoundQuarterTime = currentRoundStartTime.plus(22, ChronoUnit.MINUTES);
                                currentRoundEndTime = currentRoundStartTime.plus(30, ChronoUnit.MINUTES);
                            }
                            halfValue = 15;
                            quarterValue = 8;
                            msgString = null;
                            last_bounty_hunter_bot.lastSendStatus = 4;
                        } else if (roundCount == 2) {
                            if (shouldRecoverFromAbruptInterruption && lastGameEndTime != null && Instant.now().compareTo(lastGameEndTime) <= 0) {
                                currentRoundEndTime = lastGameEndTime;
                                currentRoundHalfTime = currentRoundEndTime.minus(10, ChronoUnit.MINUTES);
                                currentRoundQuarterTime = currentRoundEndTime.minus(5, ChronoUnit.MINUTES);
                                halfWarn = Instant.now().compareTo(currentRoundHalfTime) < 0;
                                quarterWarn = Instant.now().compareTo(currentRoundQuarterTime) < 0;
                            } else {
                                currentRoundHalfTime = currentRoundStartTime.plus(10, ChronoUnit.MINUTES);
                                currentRoundQuarterTime = currentRoundStartTime.plus(15, ChronoUnit.MINUTES);
                                currentRoundEndTime = currentRoundStartTime.plus(20, ChronoUnit.MINUTES);
                            }
                            halfValue = 10;
                            quarterValue = 5;
                            msgString = String.format("""
                                💥🔫 Gotcha! Round 2 started
                                Hash :- %s
                                Hunter %s has the bounty now. Shoot him down before he claims it.
                                ⏱ Time limit: 20 minutes
                                💰 Bounty: %s""", trimHashAndAddy(finalBurnHash), trimHashAndAddy(finalSender), getPrizePool());
                        } else {
                            if (shouldRecoverFromAbruptInterruption && lastGameEndTime != null && Instant.now().compareTo(lastGameEndTime) <= 0) {
                                currentRoundEndTime = lastGameEndTime;
                                currentRoundHalfTime = currentRoundEndTime.minus(5, ChronoUnit.MINUTES);
                                currentRoundQuarterTime = currentRoundEndTime.minus(3, ChronoUnit.MINUTES);
                                halfWarn = Instant.now().compareTo(currentRoundHalfTime) < 0;
                                quarterWarn = Instant.now().compareTo(currentRoundQuarterTime) < 0;
                            } else {
                                currentRoundHalfTime = currentRoundStartTime.plus(5, ChronoUnit.MINUTES);
                                currentRoundQuarterTime = currentRoundStartTime.plus(7, ChronoUnit.MINUTES);
                                currentRoundEndTime = currentRoundStartTime.plus(10, ChronoUnit.MINUTES);
                            }
                            halfValue = 5;
                            quarterValue = 3;
                            msgString = String.format("""
                                💥🔫 Gotcha! Round 3 started
                                Hash :- %s
                                Hunter %s has the bounty now. Shoot him down before he claims it.
                                ⏱ Time limit: 10 minutes
                                💰 Bounty: %s""", trimHashAndAddy(finalBurnHash), trimHashAndAddy(finalSender), getPrizePool());
                        }
                        boolean furtherCountNecessary = true;
                        if (msgString != null) {
                            last_bounty_hunter_bot.enqueueMessageForSend(chat_id, msgString, 4, null,
                                    "https://media.giphy.com/media/RLAcIMgQ43fu7NP29d/giphy.gif",
                                    "https://media.giphy.com/media/OLhBtlQ8Sa3V5j6Gg9/giphy.gif",
                                    "https://media.giphy.com/media/2GkMCHQ4iz7QxlcRom/giphy.gif");
                            if(shouldSendNotificationToMainRTKChat) {
                                last_bounty_hunter_bot.enqueueMessageForSend(mainRuletkaChatID, msgString + """
                                                                                        
                                            Checkout @Last_Bounty_Hunter_RTK group now and grab that bounty""", 4, null);
                            }
                        }
                        checkForStatus(4);

                        last_bounty_hunter_bot.logsPrintStream.println("RoundCount : " + roundCount + "\n" + zone + "\nStartTime : " +
                                simpleDateFormat.format(Date.from(currentRoundStartTime)) + "\nHalfTime : " + simpleDateFormat.format(
                                Date.from(currentRoundHalfTime)) + "\nQuarterTime : " + simpleDateFormat.format(Date.from(currentRoundQuarterTime))
                                + "\nEndTime : " + simpleDateFormat.format(Date.from(currentRoundEndTime)) + "\nHalfWarn : " + halfWarn +
                                "\nQuarterWarn : " + quarterWarn + "\nShouldRecoverFromAbruptInterruption : " + shouldRecoverFromAbruptInterruption);

                        MID:
                        while (Instant.now().compareTo(currentRoundEndTime) <= 0) {
                            if (halfWarn) {
                                if (Instant.now().compareTo(currentRoundHalfTime) >= 0) {
                                    last_bounty_hunter_bot.logsPrintStream.println("Round " + roundCount + " Half Time");
                                    last_bounty_hunter_bot.enqueueMessageForSend(chat_id, "Hurry up! Half Time crossed. LESS THAN " + halfValue + " minutes " +
                                                    "remaining for the current round. Shoot hunter " + finalSender + " down before he claims the bounty!",
                                            -2, null);
                                    halfWarn = false;
                                }
                            } else if (quarterWarn) {
                                if (Instant.now().compareTo(currentRoundQuarterTime) >= 0) {
                                    last_bounty_hunter_bot.logsPrintStream.println("Round " + roundCount + " 3-Quarter Time");
                                    last_bounty_hunter_bot.enqueueMessageForSend(chat_id, "Hurry up! 3/4th Time crossed. LESS THAN " + quarterValue + " minutes " +
                                                    "remaining for the current round. Shoot hunter " + finalSender + " down before he claims the bounty!",
                                            -2, null);
                                    if(shouldSendNotificationToMainRTKChat) {
                                        last_bounty_hunter_bot.enqueueMessageForSend(mainRuletkaChatID, "Hurry up! 3/4th Time crossed. LESS THAN "
                                                        + quarterValue + " minutes remaining for the current round. Shoot hunter " +
                                                        trimHashAndAddy(finalSender) + " down before he claims the bounty!\n\nCheckout " +
                                                        "@Last_Bounty_Hunter_RTK group now and grab that bounty", -2, null);
                                    }
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
                                                        Hash :- %s
                                                        💥🔫 Gotcha! Hunter %s has the bounty now. Shoot 'em down before they claim it.
                                                        ⏱ Remaining time: LESS THAN %d minutes
                                                        💰 Bounty: %s""", trimHashAndAddy(finalBurnHash), trimHashAndAddy(finalSender),
                                                    Duration.between(Instant.now(), currentRoundEndTime).toMinutes(),
                                                    getPrizePool()), 5, transactionData,
                                                    "https://media.giphy.com/media/RLAcIMgQ43fu7NP29d/giphy.gif",
                                                    "https://media.giphy.com/media/OLhBtlQ8Sa3V5j6Gg9/giphy.gif",
                                                    "https://media.giphy.com/media/2GkMCHQ4iz7QxlcRom/giphy.gif");
                                        }
                                        didSomeoneGotShot = true;
                                    } else {
                                        addRTKToPot(transactionData.value, transactionData.fromAddress);
                                        last_bounty_hunter_bot.enqueueMessageForSend(chat_id, String.format("""
                                                    Hash :- %s
                                                    🔫 Close shot! Hunter %s tried to get the bounty, but missed their shot.
                                                    The bounty will be claimed in LESS THAN %s minutes.
                                                    💰 Updated bounty: %s""", trimHashAndAddy(transactionData.trxHash),
                                                trimHashAndAddy(transactionData.fromAddress),
                                                Duration.between(Instant.now(), currentRoundEndTime).toMinutes(), getPrizePool()), 5,
                                                transactionData,"https://media.giphy.com/media/N4qR246iV3fVl2PwoI/giphy.gif");
                                    }
                                } else {
                                    furtherCountNecessary = false;
                                    transactionsUnderReview.add(0, transactionData);
                                    break MID;
                                }
                            }
                            performProperWait(0.7);
                        }

                        last_bounty_hunter_bot.logsPrintStream.println("End of Round " + roundCount);

                        if (!scheduledExecutorService.isShutdown()) {
                            scheduledExecutorService.shutdownNow();
                        }

                        if (furtherCountNecessary) {
                            String midMsg = (roundCount == 3) ? "All rounds have ended. " : "";
                            last_bounty_hunter_bot.enqueueMessageForSend(chat_id, midMsg + "Checking for final desperate " +
                                    "attempts of hunters...(Don't try to hunt now. Results are already set in stone)", 5, null);
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
                                                    Hash :- %s
                                                    🔫 Close shot! Hunter %s tried to get the bounty, but missed their shot.
                                                    💰 Updated bounty: %s""", trimHashAndAddy(transactionData.trxHash),
                                                trimHashAndAddy(transactionData.fromAddress), getPrizePool()), 5, transactionData,
                                                "https://media.giphy.com/media/N4qR246iV3fVl2PwoI/giphy.gif");
                                    }
                                } else {
                                    transactionsUnderReview.add(0, transactionData);
                                    break;
                                }
                            }
                            currentRoundEndTime = null;
                            if (!didSomeoneGotShot) {
                                break;
                            }
                            if (shouldRecoverFromAbruptInterruption) {
                                shouldRecoverFromAbruptInterruption = lastCheckedTransactionData.compareTo(
                                        last_bounty_hunter_bot.lastSavedStateTransactionData) < 0;
                            }
                        }
                    }


                    last_bounty_hunter_bot.enqueueMessageForSend(chat_id, String.format("""
                        Final valid burn :-
                        Trx Hash : %s
                        Final pot holder : %s""", finalBurnHash, finalSender), 6, null);
                    last_bounty_hunter_bot.enqueueMessageForSend(chat_id, String.format("""
                                “Ever notice how you come across somebody once in a while you should not have messed with? That’s me.”
                                %s – The Last Bounty Hunter – claimed the bounty and won %s.""", finalSender, getPrizePool()), 49, null,
                            "https://media.giphy.com/media/5obMzX3pRnSSundkPw/giphy.gif", "https://media.giphy.com/media/m3Su0jtjGHMRMnlC7L/giphy.gif");
                    if(shouldSendNotificationToMainRTKChat) {
                        last_bounty_hunter_bot.enqueueMessageForSend(mainRuletkaChatID, String.format("""
                                %s – The Last Bounty Hunter – claimed the bounty and won %s.
                                
                                Checkout @Last_Bounty_Hunter_RTK group now to take part in new Bounty Hunting Round""", trimHashAndAddy(finalSender),
                                getPrizePool()), 49, null);
                    }
                    sendRewardToWinner(prizePool, finalSender);

                    last_bounty_hunter_bot.setTotalRTKForPoolInWallet((netCurrentPool.multiply(BigInteger.valueOf(2))).divide(BigInteger.valueOf(5)).toString());
                    last_bounty_hunter_bot.addAmountToWalletFeesBalance(netCurrentPool.divide(BigInteger.valueOf(10)).toString());
                    last_bounty_hunter_bot.setLastCheckedTransactionDetails(lastCheckedTransactionData);
                    netCurrentPool = new BigInteger(last_bounty_hunter_bot.getTotalRTKForPoolInWallet());
                    prizePool = netCurrentPool.divide(BigInteger.valueOf(2));
                    if (shouldRecoverFromAbruptInterruption) {
                        shouldRecoverFromAbruptInterruption = false;
                        last_bounty_hunter_bot.makeChecks = false;
                    }
                    isGameRunning = false;
                    last_bounty_hunter_bot.enqueueMessageForSend(chat_id, "Updated Bounty Available for Hunters to Grab : " + getPrizePool(),
                            51, null);

                    checkForStatus(51);
                    last_bounty_hunter_bot.lastSendStatus = 1;
                    if (!hasEnoughBalance()) {
                        last_bounty_hunter_bot.enqueueMessageForSend(chat_id, "Rewards Wallet " + shotWallet + " doesn't have enough currency for transactions. " +
                                "Please contact admins. Closing Game\n\nMinimum currency required : " + new BigDecimal(minGasFees).divide(
                                new BigDecimal("1000000000000000000"), 5, RoundingMode.HALF_EVEN) + ". Actual Balance = " + rewardWalletBalance +
                                "\n\n\nThe bot will not read any transactions till the balances is updated by admins.", -2, null);
                        break;
                    }
                    RTKBalance = getNetRTKWalletBalance(1);
                    if(RTKBalance == null || !(RTKBalance.compareTo(netCurrentPool.add(new BigInteger("500000000000000000000"))) >= 0)) {
                        last_bounty_hunter_bot.enqueueMessageForSend(chat_id, "LastBountyHunterGame Wallet RTK Balance too Low. (Min. Requirements : poolSize + 500). " +
                                "Please Contact admins. Closing the rGame...", -2, null);
                        break;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace(last_bounty_hunter_bot.logsPrintStream);
                last_bounty_hunter_bot.enqueueMessageForSend(chat_id, "The bot encountered Fatal Error.\nReference : " + e.getMessage() +
                        "\n\nPlease Contact @OreGaZembuTouchiSuru", -2, null);
            }
        } catch (Exception e) {
            e.printStackTrace(last_bounty_hunter_bot.logsPrintStream);
        }

        last_bounty_hunter_bot.setTotalRTKForPoolInWallet(netCurrentPool.toString());
        last_bounty_hunter_bot.setLastCheckedTransactionDetails(lastCheckedTransactionData);

        getCurrentGameDeleted("Deleter-Run-END");
    }


    private void performProperWait(double seconds) {
        try {
            Thread.sleep((long) (seconds * 1000));
        } catch (Exception e) {
            e.printStackTrace(last_bounty_hunter_bot.logsPrintStream);
        }
    }

    private void checkForStatus(int sendStatus) {
        while (last_bounty_hunter_bot.lastSendStatus != sendStatus) {
            performProperWait(1);
        }
    }

    public boolean addRTKToPot(BigInteger amount, String sender) {
        boolean allow = false;
        if(sender.equalsIgnoreCase("Override")) {
            BigInteger rtkBal = getNetRTKWalletBalance(1);
            allow = (rtkBal != null) && (rtkBal.compareTo(amount.add(new BigInteger("500000000000000000000"))) >= 0);
        } else if (!sender.equalsIgnoreCase(last_bounty_hunter_bot.topUpWalletAddress)) {
            allow = true;
        }

        if(allow) {
            netCurrentPool = netCurrentPool.add(amount);
            prizePool = netCurrentPool.divide(BigInteger.valueOf(2));
        }

        return allow;
    }

    public void sendBountyUpdateMessage(BigInteger amount) {
        last_bounty_hunter_bot.sendMessage(chat_id, "Bounty Increased...LastBountyHunterGame Host added " + getPrizePool(amount.divide(BigInteger.valueOf(2)))
                + " to the current Bounty");
    }

    private String getPrizePool() {
        return new BigDecimal(prizePool).divide(new BigDecimal(decimals), 3, RoundingMode.HALF_EVEN) + " RTK";
    }

    private String getPrizePool(BigInteger amount) {
        return new BigDecimal(amount).divide(new BigDecimal(decimals), 3, RoundingMode.HALF_EVEN) + " RTK";
    }

    public Instant getCurrentRoundEndTime() {
        return currentRoundEndTime;
    }

    private void getCurrentGameDeleted(String deleterId) {
        allowConnector = false;
        while (!last_bounty_hunter_bot.deleteGame(chat_id, this, deleterId)) {
            performProperWait(1.5);
        }
        if (!scheduledExecutorService.isShutdown()) {
            scheduledExecutorService.shutdownNow();
        }
        if (!scheduledExecutorService2.isShutdown()) {
            scheduledExecutorService2.shutdownNow();
        }
        hasGameClosed = true;
        last_bounty_hunter_bot.sendMessage(chat_id, "The bot has been shut down. Please don't send any transactions now.");
        last_bounty_hunter_bot.logsPrintStream.println("XXXXX\nXXXXX\nGetGameDeletedDisposer\nXXXXX\nXXXXX");
        try {
            if (!disposable.isDisposed()) {
                disposable.dispose();
            }
        } catch (Exception e) {
            e.printStackTrace(last_bounty_hunter_bot.logsPrintStream);
        }
        try {
            web3j.shutdown();
        } catch (Exception e) {
            e.printStackTrace(last_bounty_hunter_bot.logsPrintStream);
        }
        try {
            webSocketService.close();
        } catch (Exception e) {
            e.printStackTrace(last_bounty_hunter_bot.logsPrintStream);
        }
        last_bounty_hunter_bot.decreaseUndisposedGameCount();
        last_bounty_hunter_bot.logsPrintStream.println("Game Closed...");
    }

    public void setShouldContinueGame(boolean shouldContinueGame) {
        this.shouldContinueGame = shouldContinueGame;
    }

    public void tryToSaveState() {
        if(!isGameRunning) {
            last_bounty_hunter_bot.setTotalRTKForPoolInWallet(netCurrentPool.toString());
            last_bounty_hunter_bot.setLastCheckedTransactionDetails(lastCheckedTransactionData);
        }
    }


    // Related to Blockchain Communication
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean buildCustomBlockchainReader(boolean shouldSendMessage) {

        int count = 0;
        if (shouldSendMessage) {
            last_bounty_hunter_bot.enqueueMessageForSend(chat_id, "Connecting to Blockchain Network to read transactions. Please be patient. " +
                    "This can take from few seconds to few minutes", 1, null);
        }
        shouldTryToEstablishConnection = true;


        // Url + WebSocketClient + Supporting_Classes.WebSocketService  <--- Build + Connect
        while (shouldTryToEstablishConnection && count < 2) {
            last_bounty_hunter_bot.logsPrintStream.println("Connecting to Blockchain... Attempt : " + (count + 1));
            // Pre Disposer
            if(count != 0) {
                last_bounty_hunter_bot.logsPrintStream.println("XXXXX\nXXXXX\nDisposer Before Re-ConnectionBuilder\nXXXXX\nXXXXX");
                try {
                    if (!disposable.isDisposed()) {
                        disposable.dispose();
                    }
                } catch (Exception e) {
                    e.printStackTrace(last_bounty_hunter_bot.logsPrintStream);
                }
                try {
                    web3j.shutdown();
                } catch (Exception e) {
                    e.printStackTrace(last_bounty_hunter_bot.logsPrintStream);
                }
                try {
                    webSocketService.close();
                } catch (Exception e) {
                    e.printStackTrace(last_bounty_hunter_bot.logsPrintStream);
                }
            }
            count++;

            // Building Urls, WebSocketClient and Supporting_Classes.WebSocketService
            ArrayList<String> webSocketUrls;
            String prefix, infix;
            if(EthNetworkType.startsWith("matic")) {
                infix = EthNetworkType.toLowerCase().substring(5);
                if(infix.equals("mainnet") && last_bounty_hunter_bot.shouldUseQuickNode) {
                    webSocketUrls = last_bounty_hunter_bot.quickNodeWebSocketUrls;
                    prefix = "";
                    infix = "";
                } else {
                    webSocketUrls = last_bounty_hunter_bot.maticWebSocketUrls;
                    prefix = last_bounty_hunter_bot.maticPrefix;
                }
            } else {
                webSocketUrls = last_bounty_hunter_bot.etherWebSocketUrls;
                prefix = last_bounty_hunter_bot.etherPrefix;
                infix = EthNetworkType;
            }
            Collections.shuffle(webSocketUrls);
            URI uri = null;
            try {
                uri = new URI(prefix + infix + webSocketUrls.get(0));
            } catch (Exception e) {
                e.printStackTrace(last_bounty_hunter_bot.logsPrintStream);
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
            if(last_bounty_hunter_bot.shouldUseProxy) {
                ProxyIP proxyIP = last_bounty_hunter_bot.getProxyIP();
                Authenticator.setDefault(
                        new Authenticator() {
                            @Override
                            public PasswordAuthentication getPasswordAuthentication() {
                                return new PasswordAuthentication(last_bounty_hunter_bot.proxyUsername,
                                        last_bounty_hunter_bot.proxyPassword.toCharArray());
                            }
                        }
                );
                System.setProperty("http.proxyUser", last_bounty_hunter_bot.proxyUsername);
                System.setProperty("http.proxyPassword", last_bounty_hunter_bot.proxyPassword);
                System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
                webSocketClient.setProxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyIP.host, proxyIP.port)));
            }
            webSocketService = new WebSocketService(webSocketClient, true);
            last_bounty_hunter_bot.logsPrintStream.println("Connect Url : " + prefix + infix + webSocketUrls.get(0));


            // Connecting to WebSocket
            try {
                webSocketService.connect();
                last_bounty_hunter_bot.logsPrintStream.println("Connection Successful");
                shouldTryToEstablishConnection = false;
            } catch (Exception e) {
                last_bounty_hunter_bot.logsPrintStream.println("External Error While Connect to Supporting_Classes.WebSocketService... Entered Catch Block");
                e.printStackTrace(last_bounty_hunter_bot.logsPrintStream);
                setShouldTryToEstablishConnection();
            }


            performProperWait(2);
        }


        // Building Web3j over Connected Supporting_Classes.WebSocketService
        web3j = Web3j.build(webSocketService);
        try {
            last_bounty_hunter_bot.logsPrintStream.println("Game's Chat ID : " + chat_id + "\nWeb3ClientVersion : " +
                    web3j.web3ClientVersion().send().getWeb3ClientVersion());
        } catch (IOException e) {
            last_bounty_hunter_bot.logsPrintStream.println("Unable to fetch Client Version... In Catch Block");
            e.printStackTrace(last_bounty_hunter_bot.logsPrintStream);
            setShouldTryToEstablishConnection();
        }
        EthFilter RTKContractFilter;
        BigInteger latestBlockNumber = null;
        try {
            latestBlockNumber = web3j.ethBlockNumber().send().getBlockNumber();
        } catch (Exception e) {
            e.printStackTrace(last_bounty_hunter_bot.logsPrintStream);
        }
        BigInteger startBlock = lastCheckedTransactionData.blockNumber;
        if(EthNetworkType.startsWith("maticMainnet") && !last_bounty_hunter_bot.shouldUseQuickNode && latestBlockNumber != null) {
            startBlock = (startBlock.compareTo(latestBlockNumber.subtract(BigInteger.valueOf(850))) >= 0) ? startBlock : latestBlockNumber;
        }
        last_bounty_hunter_bot.logsPrintStream.println("Building Filter\nLast Checked Block Number : " + lastCheckedTransactionData.blockNumber
                + "\nStart Block For Filter : " + startBlock);
        RTKContractFilter = new EthFilter(new DefaultBlockParameterNumber(startBlock), DefaultBlockParameterName.LATEST, RTKContractAddresses);
        isBalanceEnough = hasEnoughBalance();
        try {
            disposable = web3j.ethLogFlowable(RTKContractFilter).subscribe(log -> {
                String hash = log.getTransactionHash();
                if ((prevHash == null) || (!prevHash.equalsIgnoreCase(hash))) {
                    Optional<Transaction> trx = web3j.ethGetTransactionByHash(hash).send().getTransaction();
                    if (trx.isPresent()) {
                        TransactionData currentTrxData = splitInputData(log, trx.get());
                        boolean counted = !currentTrxData.methodName.equals("Useless") && currentTrxData.toAddress.equalsIgnoreCase(shotWallet)
                                && currentTrxData.value.compareTo(shotCost) >= 0 && currentTrxData.compareTo(lastCheckedTransactionData) > 0;

                        if(abruptRecoveryComplete) {
                            counted = counted && isNotOldHash(currentTrxData.trxHash);
                        } else {
                            if(currentTrxData.compareTo(last_bounty_hunter_bot.lastSavedStateTransactionData) == 0) {
                                abruptRecoveryComplete = true;
                            } else if (currentTrxData.compareTo(last_bounty_hunter_bot.lastSavedStateTransactionData) > 0) {
                                abruptRecoveryComplete = true;
                                counted = counted && isNotOldHash(currentTrxData.trxHash);
                            }
                        }
                        if (counted) {
                            last_bounty_hunter_bot.logsPrintStream.println("Chat ID : " + chat_id + " ===>> " + currentTrxData +
                                    ", PrevHash : " + prevHash + ", Was Counted = " + true);
                            validTransactions.add(currentTrxData);
                            pushTransaction(currentTrxData.trxHash);
                        } else {
                            last_bounty_hunter_bot.logsPrintStream.println("Ignored Incoming Hash : " + currentTrxData.trxHash);
                        }
                    }
                }
                prevHash = hash;
            }, throwable -> {
                last_bounty_hunter_bot.logsPrintStream.println("Disposable Internal Error (Throwable)");
                throwable.printStackTrace(last_bounty_hunter_bot.logsPrintStream);
                setShouldTryToEstablishConnection();
            });
        } catch (Exception e) {
            last_bounty_hunter_bot.logsPrintStream.println("Error while creating disposable");
            e.printStackTrace(last_bounty_hunter_bot.logsPrintStream);
        }
        if(disposable == null) {
            setShouldTryToEstablishConnection();
            return false;
        }
        last_bounty_hunter_bot.logsPrintStream.println("\n\n");


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


    private String trimHashAndAddy(String string) {
        if(string != null) {
            int len = string.length();
            return string.substring(0, 6) + "...." + string.substring(len - 6, len);
        } else {
            return "None";
        }
    }

    private boolean hasEnoughBalance() {
        boolean retVal = false;

        try {
            gasPrice = web3j.ethGasPrice().send().getGasPrice();
            BigInteger balance = web3j.ethGetBalance(shotWallet, DefaultBlockParameterName.LATEST).send().getBalance();
            minGasFees = gasPrice.multiply(new BigInteger("195000"));
            last_bounty_hunter_bot.logsPrintStream.println("Network type = " + EthNetworkType + ", Wallet Balance = " + balance + ", Required Balance = " + minGasFees +
                    ", gasPrice = " + gasPrice);
            rewardWalletBalance = new BigDecimal(balance).divide(new BigDecimal("1000000000000000000"), 5, RoundingMode.HALF_EVEN);
            if (balance.compareTo(minGasFees) > 0) {
                retVal = true;
            }
        } catch (Exception e) {
            last_bounty_hunter_bot.logsPrintStream.println("Error when trying to get Wallet Balance");
            e.printStackTrace(last_bounty_hunter_bot.logsPrintStream);
        }

        return retVal;
    }

    private void sendRewardToWinner(BigInteger amount, String toAddress) {
        try {
            TransactionReceipt trxReceipt = ERC20.load(RTKContractAddresses.get(0), web3j, Credentials.create(System.getenv("LBHPrivateKey")),
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
            last_bounty_hunter_bot.logsPrintStream.println("Reward Sender Success. Msg :-\n" + rewardMsg);
            last_bounty_hunter_bot.enqueueMessageForSend(chat_id, rewardMsg, 50, null);
        } catch (Exception e) {
            e.printStackTrace(last_bounty_hunter_bot.logsPrintStream);
        }
    }

    public void setShouldTryToEstablishConnection() {
        shouldTryToEstablishConnection = true;
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
            last_bounty_hunter_bot.logsPrintStream.println("RTKL" + X + " Balance of the Wallet : " + finalValue);
            return finalValue;
        } catch (Exception e) {
            e.printStackTrace(last_bounty_hunter_bot.logsPrintStream);
            return null;
        }
    }

    private void pushTransaction(String hash) {
        if(last3CountedHash.size() == 3) {
            last3CountedHash.remove(0);
        }
        last3CountedHash.add(hash);
    }

    private boolean isNotOldHash(String hash) {
        return !last3CountedHash.contains(hash);
    }

    public void getAllPreviousTrx(String chatId, int X, String blockNumber) {
        try{
            last_bounty_hunter_bot.logsPrintStream.println("Request to Get Prev Trx CSV");
            class CSVMaker implements Runnable {
                private final String chatId;
                private final List<EthLog.LogResult> tempLogs;

                CSVMaker(List<EthLog.LogResult> tempLogs, String chatId) {
                    this.tempLogs = tempLogs;
                    this.chatId = chatId;
                }

                @Override
                public void run() {
                    try {
                        Thread.sleep(3000);
                    } catch (Exception e) {
                        e.printStackTrace(last_bounty_hunter_bot.logsPrintStream);
                    }
                    last_bounty_hunter_bot.logsPrintStream.println("CSVMaker - ChatID : " + chatId + ", TRX List Size : " + tempLogs.size());
                    StringBuilder result = new StringBuilder();
                    String prevHash = null;
                    for(EthLog.LogResult logResult : tempLogs) {
                        Log log = (Log) logResult.get();
                        String hash = log.getTransactionHash();
                        if ((prevHash == null) || (!prevHash.equalsIgnoreCase(hash))) {
                            try {
                                Optional<Transaction> trx = web3j.ethGetTransactionByHash(hash).send().getTransaction();
                                if (trx.isPresent()) {
                                    TransactionData currentTrxData = splitInputData(log, trx.get());
                                    if (!currentTrxData.methodName.equals("Useless") && currentTrxData.didBurn) {
                                        result.append(currentTrxData.trxHash).append(",").append(currentTrxData.fromAddress).append(",")
                                                .append(currentTrxData.toAddress).append(",").append(currentTrxData.blockNumber).append(",")
                                                .append(currentTrxData.value).append("\n");
                                    }
                                }
                            } catch (Exception e) {
                                e.printStackTrace(last_bounty_hunter_bot.logsPrintStream);
                            }
                        }
                        prevHash = hash;
                    }
                    try {
                        File file = new File("list.csv");
                        if(!file.exists()) {
                            if(!file.createNewFile()) {
                                last_bounty_hunter_bot.sendMessage(chatId, "Failure while creating the file. Closing the CSV Maker");
                                return;
                            }
                        }
                        FileOutputStream fileOutputStream = new FileOutputStream("list.csv");
                        PrintStream printStream = new PrintStream(fileOutputStream);
                        printStream.println(result);
                        printStream.close();
                        fileOutputStream.close();
                        last_bounty_hunter_bot.sendFile(chatId, "list.csv");
                    } catch (Exception e) {
                        e.printStackTrace(last_bounty_hunter_bot.logsPrintStream);
                    }
                }
            }

            EthFilter tempFilter = new EthFilter(new DefaultBlockParameterNumber(new BigInteger(blockNumber)),
                    new DefaultBlockParameterNumber(web3j.ethBlockNumber().send().getBlockNumber()), RTKContractAddresses.get(X-1));

            CSVMaker csvMaker = new CSVMaker(web3j.ethGetLogs(tempFilter).send().getLogs(), chatId);
            csvMaker.run();
        } catch (Exception e) {
            e.printStackTrace(last_bounty_hunter_bot.logsPrintStream);
        }
    }

    public void convertRTKLXIntoRTK(int X, String _amount, String chat_id) {
        BigInteger balance = getNetRTKWalletBalance(X);
        if (_amount.equalsIgnoreCase("Max")) {
            assert balance != null;
            _amount = balance.toString();
        }
        BigInteger amount = new BigInteger(_amount);
        assert (X >= 1 && X <= 5);
        assert balance != null;
        assert balance.compareTo(amount) >= 0;

        Function function = new Function("approve",
                Arrays.asList(new Address(last_bounty_hunter_bot.swapContractAddress), new Uint256(amount)),
                Collections.singletonList(new TypeReference<Bool>() {
                }));
        String approveFunction = FunctionEncoder.encode(function);
        EthCall response = null;

        try {
            response = web3j.ethCall(
                    org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction(shotWallet, RTKContractAddresses.get(X-1),
                            approveFunction), DefaultBlockParameterName.LATEST).send();
        } catch (Exception e) {
            last_bounty_hunter_bot.logsPrintStream.println("Error during approve");
            e.printStackTrace(last_bounty_hunter_bot.logsPrintStream);
        }
        assert response != null;
        List<Type> operationResult = FunctionReturnDecoder.decode(response.getValue(), function.getOutputParameters());
        boolean result = Boolean.parseBoolean(operationResult.get(0).getValue().toString());

        if (result) {
            function = new Function("convertRTKLXIntoRTK",
                    Arrays.asList(new Address(shotWallet), new Uint256(amount), new Uint256(X)),
                    Collections.singletonList(new TypeReference<Bool>() {
                    }));
            String convertFunction = FunctionEncoder.encode(function);
            response = null;

            try {
                response = web3j.ethCall(org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction(shotWallet,
                        last_bounty_hunter_bot.swapContractAddress, convertFunction), DefaultBlockParameterName.LATEST).send();
            } catch (Exception e) {
                last_bounty_hunter_bot.logsPrintStream.println("Error during conversion");
                e.printStackTrace(last_bounty_hunter_bot.logsPrintStream);
            }
            assert response != null;
            operationResult = FunctionReturnDecoder.decode(
                    response.getValue(), function.getOutputParameters());
            result = Boolean.parseBoolean(operationResult.get(0).getValue().toString());
            if(result) {
                last_bounty_hunter_bot.sendMessage(chat_id, "Operation Successful");
                return;
            } else {
                last_bounty_hunter_bot.logsPrintStream.println("Result of Convert Function Was False..");
            }
        } else {
            last_bounty_hunter_bot.logsPrintStream.println("Result of Approve was False...");
        }

        last_bounty_hunter_bot.sendMessage(chat_id, "Operation Unsuccessful. Check Logs");
    }
}
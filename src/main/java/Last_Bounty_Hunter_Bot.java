import Supporting_Classes.LBH_LastGameState;
import Supporting_Classes.ProxyIP;
import Supporting_Classes.TelegramMessage;
import Supporting_Classes.TransactionData;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.jetbrains.annotations.Nullable;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendAnimation;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.*;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;


public class Last_Bounty_Hunter_Bot extends TelegramLongPollingBot {

    private class MessageSender implements Runnable {
        @Override
        public void run() {
            try {
                TelegramMessage currentMessage = allPendingMessages.take();
                if(currentMessage.sendStatus != -2) {
                    lastSendStatus = currentMessage.sendStatus;
                }
                if(makeChecks) {
                    if(currentMessage.hasTransactionData) {
                        makeChecks = lastSavedStateTransactionData != null &&
                                currentMessage.transactionData.compareTo(lastSavedStateTransactionData) <= 0;
                    } else if (currentMessage.sendStatus == 6) {
                        makeChecks = false;
                    }
                    if(!makeChecks) {
                        Set<String> keys = currentlyActiveGames.keySet();
                        for(String key : keys) {
                            currentlyActiveGames.get(key).shouldRecoverFromAbruptInterruption = false;
                        }
                    }
                }
                String msg = null;
                if(makeChecks) {
                    msg = ((currentMessage.isMessage) ? currentMessage.sendMessage.getText() : currentMessage.sendAnimation.getCaption());
                    logsPrintStream.println("Msg Failed Check Before Dispatch. Text :-\n" + (msg.substring(0, Math.min(msg.length(), 20))));
                    return;
                }
                if ((currentMessage.isMessage)) {
                    if (currentMessage.sendMessage.getChatId().equals(actualGameChatId) ||
                            currentMessage.sendMessage.getChatId().equals(testingChatId)) {
                        msg = currentMessage.sendMessage.getText();
                        msg = msg.substring(0, Math.min(msg.length(), 80));
                    }
                    execute(currentMessage.sendMessage);
                } else {
                    if (currentMessage.sendAnimation.getChatId().equals(actualGameChatId) ||
                        currentMessage.sendAnimation.getChatId().equals(testingChatId)) {
                        msg =  currentMessage.sendAnimation.getCaption();
                        msg = msg.substring(0, Math.min(msg.length(), 80));
                    }
                    execute(currentMessage.sendAnimation);
                }
                if (msg != null) {
                    logsPrintStream.println("Msg Sender (Executor):\n" + msg);
                }
                if(currentMessage.hasTransactionData) {
                    lastSavedStateTransactionData = currentMessage.transactionData;
                }
            } catch (Exception e) {
                e.printStackTrace(logsPrintStream);
            }
        }
    }

    // Game manager variable
    private boolean shouldRunGame;
    private ArrayList<Long> allAdmins = new ArrayList<>();
    private final String testingChatId = "-1001477389485", actualGameChatId = "-1001275436629";
    private boolean shouldAllowMessageFlow = true;
    volatile String topUpWalletAddress;
    volatile boolean makeChecks = false;
    volatile TransactionData lastSavedStateTransactionData = null;
    volatile int lastSendStatus = -1;
    FileOutputStream fileOutputStream;
    PrintStream logsPrintStream;
    int undisposedGameCount = 0;
    String proxyUsername, proxyPassword;
    boolean shouldUseProxy, shouldUseQuickNode;
    private final ArrayList<ProxyIP> allProxies = new ArrayList<>();
    private Instant lastGameEndTime = Instant.now();
    volatile Instant lastMomentWhenTrxWasRead = Instant.now(), lastMomentFromCommandUse = Instant.now().minus(55, ChronoUnit.MINUTES);

    // Blockchain Related Stuff
    private String EthNetworkType;
    private final String shotWallet;
    private String[] RTKContractAddresses;
    private BigInteger shotCost;
    final String swapContractAddress = "0x3CCB85af88DE1A148CC942eA9065c2E8b470cf11";
    String maticPrefix;
    String etherPrefix;
    ArrayList<String> maticWebSocketUrls = new ArrayList<>();
    ArrayList<String> quickNodeWebSocketUrls = new ArrayList<>();
    ArrayList<String> etherWebSocketUrls = new ArrayList<>();

    // MongoDB Related Stuff
    private final String botName = "Last Bounty Hunter Bot";
    private final ClientSession clientSession;
    private final MongoCollection botControlCollection, walletDistributionCollection;

    // All Data Holders
    private final HashMap<String, LastBountyHunterGame> currentlyActiveGames = new HashMap<>();
    private final LinkedBlockingDeque<TelegramMessage> allPendingMessages = new LinkedBlockingDeque<>();
    private ExecutorService gameRunningExecutorService = Executors.newCachedThreadPool();
    private ScheduledExecutorService messageSendingExecutor = Executors.newSingleThreadScheduledExecutor();


    @SuppressWarnings("SpellCheckingInspection")
    Last_Bounty_Hunter_Bot(String shotWallet) {
        this.shotWallet = shotWallet;
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                super.run();
                logsPrintStream.println("\n...Shutdown Handler Called ---> Initiating Graceful Shutdown...\n");
                gameRunningExecutorService.shutdownNow();
                messageSendingExecutor.shutdownNow();
                if(lastSavedStateTransactionData != null) {
                    Set<String> keys = currentlyActiveGames.keySet();
                    Document tempDoc = new Document();
                    for(String key : keys) {
                        LastBountyHunterGame lastBountyHunterGame = currentlyActiveGames.get(key);

                        tempDoc.append("trxHash", lastSavedStateTransactionData.trxHash)
                                .append("from", lastSavedStateTransactionData.fromAddress)
                                .append("to", lastSavedStateTransactionData.toAddress)
                                .append("X", lastSavedStateTransactionData.X)
                                .append("didBurn", lastSavedStateTransactionData.didBurn)
                                .append("block", lastSavedStateTransactionData.blockNumber.toString())
                                .append("trxIndex", lastSavedStateTransactionData.trxIndex.toString())
                                .append("value", lastSavedStateTransactionData.value.toString());
                        Instant instant = lastBountyHunterGame.getCurrentRoundEndTime();
                        tempDoc.append("endTime", (instant == null) ? "NULL" : instant.toString());
                        for(int i = 0; i < 3; i++) {
                            if(i < lastBountyHunterGame.last3CountedHash.size())
                                tempDoc.append("lastCountedHash" + i, lastBountyHunterGame.last3CountedHash.get(i));
                            else
                                tempDoc.append("lastCountedHash" + i, "NULL");
                        }
                        lastBountyHunterGame.tryToSaveState();
                    }

                    Document botNameDoc = new Document("botName", botName);
                    Document foundBotNameDoc = (Document) botControlCollection.find(botNameDoc).first();
                    Bson updateAddyDocOperation = new Document("$set", tempDoc);
                    assert foundBotNameDoc != null;
                    botControlCollection.updateOne(foundBotNameDoc, updateAddyDocOperation);

                    // Retry for surity
                    foundBotNameDoc = (Document) botControlCollection.find(botNameDoc).first();
                    updateAddyDocOperation = new Document("$set", tempDoc);
                    assert foundBotNameDoc != null;
                    botControlCollection.updateOne(foundBotNameDoc, updateAddyDocOperation);

                    logsPrintStream.println("\nAttempted to save the Game State :-\n" + tempDoc);
                    logsPrintStream.println("\nActual Saved Game State :-\n" + foundBotNameDoc);
                } else {
                    logsPrintStream.println("Last Saved State Trx Data is NULL. Retaining old value.");
                }
                logsPrintStream.println("...Graceful Shutdown successful...");
                logsPrintStream.flush();
                logsPrintStream.close();
                sendLogs(allAdmins.get(0).toString());
                System.out.println("\n...Graceful Shutdown Successful...\n");
            }
        });

        if(logsPrintStream != null) {
            logsPrintStream.flush();
        }
        try {
            fileOutputStream = new FileOutputStream("LBH_OutPutLogs.txt");
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        logsPrintStream = new PrintStream(fileOutputStream) {

            @Override
            public void println(@Nullable String x) {
                super.println(x);
                super.println("-----------------------------");
            }

            @Override
            public void close() {
                try {
                    fileOutputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                super.close();
            }
        };

        // Mongo Stuff
        ConnectionString connectionString = new ConnectionString(
                "mongodb+srv://" + System.getenv("mongoID") + ":" +
                        System.getenv("mongoPass") + "@hellgatesbotcluster.zm0r5.mongodb.net/test" +
                        "?keepAlive=true&poolSize=30&autoReconnect=true&socketTimeoutMS=360000&connectTimeoutMS=360000"
        );
        MongoClientSettings mongoClientSettings = MongoClientSettings.builder()
                .applyConnectionString(connectionString).retryWrites(true).build();
        MongoClient mongoClient = MongoClients.create(mongoClientSettings);
        clientSession = mongoClient.startSession();
        botControlCollection = mongoClient.getDatabase("All-Bots-Command-Centre").getCollection("MemberValues");
        walletDistributionCollection = mongoClient.getDatabase("Last-Bounty-Hunter-Bot-Database").getCollection("ManagingData");

        try {
            Document walletDetailDoc = new Document("identifier", "adminDetails");
            Document foundWalletDetailDoc = (Document) walletDistributionCollection.find(walletDetailDoc).first();
            assert foundWalletDetailDoc != null;
            topUpWalletAddress = (String) foundWalletDetailDoc.get("topUpWalletAddress");
            if (foundWalletDetailDoc.get("adminID") instanceof List) {
                for (int i = 0; i < (((List<?>) foundWalletDetailDoc.get("adminID")).size()); i++) {
                    Object item = ((List<?>) foundWalletDetailDoc.get("adminID")).get(i);
                    if (item instanceof Long) {
                        allAdmins.add((Long) item);
                    }
                }
            }
            logsPrintStream.println("TopUpWalletAddress = " + topUpWalletAddress + "\nAdmins = " + allAdmins);

            Document botIndependentDoc = new Document("botName", "Bot Independent Data");
            Document foundBotIndependentDoc = (Document) botControlCollection.find(botIndependentDoc).first();
            assert foundBotIndependentDoc != null;
            proxyUsername = (String) foundBotIndependentDoc.get("proxyUsername");
            proxyPassword = (String) foundBotIndependentDoc.get("proxyPassword");
            if (foundBotIndependentDoc.get("proxyIP") instanceof List) {
                for (int i = 0; i < (((List<?>) foundBotIndependentDoc.get("proxyIP")).size()); i++) {
                    Object item = ((List<?>) foundBotIndependentDoc.get("proxyIP")).get(i);
                    if (item instanceof String) {
                        allProxies.add(new ProxyIP(((String) item).trim().split(":")));
                    }
                }
            }
            String[] linkCounts = ((String) foundBotIndependentDoc.get("urlCounts")).trim().split(" ");
            int maticCount = Integer.parseInt(linkCounts[0]);
            int QuickNodeCount = Integer.parseInt(linkCounts[1]);
            if (foundBotIndependentDoc.get("urlList") instanceof List) {
                for (int i = 0; i < (((List<?>) foundBotIndependentDoc.get("urlList")).size()); i++) {
                    Object item = ((List<?>) foundBotIndependentDoc.get("urlList")).get(i);
                    if (item instanceof String) {
                        if(i == 0) {
                            maticPrefix = (String) item;
                        } else if(i == 1) {
                            etherPrefix = (String) item;
                        } else if(i < maticCount + 2) {
                            maticWebSocketUrls.add((String) item);
                        } else if(i < maticCount + QuickNodeCount + 2) {
                            quickNodeWebSocketUrls.add((String) item);
                        } else {
                            etherWebSocketUrls.add((String) item);
                        }
                    }
                }
            }

            Document botNameDoc = new Document("botName", botName);
            Document foundBotNameDoc = (Document) botControlCollection.find(botNameDoc).first();
            assert foundBotNameDoc != null;
            shouldRunGame = (boolean) foundBotNameDoc.get("shouldRunGame");
            this.EthNetworkType = (String) foundBotNameDoc.get("EthNetworkType");
            this.shotCost = new BigInteger((String) foundBotNameDoc.get("shotCost"));
            shouldUseProxy = (boolean) foundBotNameDoc.get("shouldUseProxy");
            shouldUseQuickNode = (boolean) foundBotNameDoc.get("shouldUseQuickNode");

            switch (EthNetworkType) {
                case "mainnet" -> RTKContractAddresses = new String[]{"0x1F6DEADcb526c4710Cf941872b86dcdfBbBD9211",
                        "0x66bc87412a2d92a2829137ae9dd0ee063cd0f201", "0xb0f87621a43f50c3b7a0d9f58cc804f0cdc5c267",
                        "0x4a1c95097473c54619cb0e22c6913206b88b9a1a", "0x63b9713df102ea2b484196a95cdec5d8af278a60"};
                case "ropsten" -> RTKContractAddresses = new String[]{"0x38332D8671961aE13d0BDe040d536eB336495eEA",
                        "0x9C72573A47b0d81Ef6048c320bF5563e1606A04C", "0x136A5c9B9965F3827fbB7A9e97E41232Df168B08",
                        "0xfB8C59fe95eB7e0a2fA067252661687df67d87b8", "0x99afe8FDEd0ef57845F126eEFa945d687CdC052d"};
                case "maticMainnet" -> RTKContractAddresses = new String[]{"0x38332D8671961aE13d0BDe040d536eB336495eEA",
                        "0x136A5c9B9965F3827fbB7A9e97E41232Df168B08", "0xfB8C59fe95eB7e0a2fA067252661687df67d87b8",
                        "0x99afe8FDEd0ef57845F126eEFa945d687CdC052d", "0x88dD15CEac31a828e06078c529F5C1ABB214b6E8"};
                case "maticMumbai" -> RTKContractAddresses = new String[] {"0x54320152Eb8889a9b28048280001549FAC3E26F5",
                        "0xc21af68636B79A9F12C11740B81558Ad27C038a6", "0x9D27dE8369fc977a3BcD728868984CEb19cF7F66",
                        "0xc21EE7D6eF734dc00B3c535dB658Ef85720948d3", "0x39b892Cf8238736c038B833d88B8C91B1D5C8158"};
            }

            if (shouldRunGame) {
                undisposedGameCount++;
                messageSendingExecutor.scheduleWithFixedDelay(new MessageSender(), 0, 750, TimeUnit.MILLISECONDS);
                switch (EthNetworkType) {
                    case "mainnet", "maticMainnet" -> {
                        LastBountyHunterGame newLastBountyHunterGame = new LastBountyHunterGame(this, actualGameChatId, EthNetworkType, shotWallet, RTKContractAddresses, shotCost);
                        currentlyActiveGames.put(actualGameChatId, newLastBountyHunterGame);
                        gameRunningExecutorService.execute(newLastBountyHunterGame);
                    }
                    case "ropsten", "maticMumbai" -> {
                        LastBountyHunterGame newLastBountyHunterGame = new LastBountyHunterGame(this, testingChatId, EthNetworkType, shotWallet, RTKContractAddresses, shotCost);
                        currentlyActiveGames.put(testingChatId, newLastBountyHunterGame);
                        gameRunningExecutorService.execute(newLastBountyHunterGame);
                    }
                }
                lastGameEndTime = Instant.now();
            } else {
                lastGameEndTime = Instant.now().minus(1, ChronoUnit.MINUTES);
            }
        } catch (Exception e) {
            e.printStackTrace(logsPrintStream);
        }
    }

    @SuppressWarnings("SpellCheckingInspection")
    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && isAdmin(update.getMessage().getChatId()) && update.getMessage().hasText()) {
            String chatId = update.getMessage().getChatId().toString();
            String text = update.getMessage().getText();
            logsPrintStream.println("Incoming Message :\n" + text);
            try {
                if (!shouldRunGame && text.equalsIgnoreCase("runBot")) {
                    if(!(Math.abs(Duration.between(Instant.now(), lastGameEndTime).toSeconds()) > 45)) {
                        sendMessage(chatId, "Please wait at least 40 Seconds between stopBot / runBot Commands");
                        return;
                    }

                    try {
                        if ((EthNetworkType.equals("mainnet") || EthNetworkType.equals("maticMainnet")) && !currentlyActiveGames.containsKey(actualGameChatId)) {
                            LastBountyHunterGame newLastBountyHunterGame = new LastBountyHunterGame(this, actualGameChatId, EthNetworkType, shotWallet, RTKContractAddresses, shotCost);
                            currentlyActiveGames.put(actualGameChatId, newLastBountyHunterGame);
                            if (!gameRunningExecutorService.isShutdown()) {
                                gameRunningExecutorService.shutdownNow();
                            }
                            gameRunningExecutorService = Executors.newCachedThreadPool();
                            gameRunningExecutorService.execute(newLastBountyHunterGame);
                        }
                        else if ((EthNetworkType.equals("ropsten") || EthNetworkType.equals("maticMumbai")) && !currentlyActiveGames.containsKey(testingChatId)) {
                            LastBountyHunterGame newLastBountyHunterGame = new LastBountyHunterGame(this, testingChatId, EthNetworkType, shotWallet, RTKContractAddresses, shotCost);
                            currentlyActiveGames.put(testingChatId, newLastBountyHunterGame);
                            if (!gameRunningExecutorService.isShutdown()) {
                                gameRunningExecutorService.shutdownNow();
                            }
                            gameRunningExecutorService = Executors.newCachedThreadPool();
                            gameRunningExecutorService.execute(newLastBountyHunterGame);
                        }
                        else {
                            throw new Exception("Operation Unsuccessful. Currently a game is running. Let the game finish before starting" +
                                    " the bot.");
                        }
                        undisposedGameCount++;
                        lastSendStatus = -1;
                        if (!messageSendingExecutor.isShutdown()) {
                            messageSendingExecutor.shutdownNow();
                        }
                        messageSendingExecutor = Executors.newSingleThreadScheduledExecutor();
                        messageSendingExecutor.scheduleWithFixedDelay(new MessageSender(), 0, 750, TimeUnit.MILLISECONDS);
                        shouldRunGame = true;
                        Document botNameDoc = new Document("botName", botName);
                        Document foundBotNameDoc = (Document) botControlCollection.find(botNameDoc).first();
                        Bson updatedAddyDoc = new Document("shouldRunGame", shouldRunGame);
                        Bson updateAddyDocOperation = new Document("$set", updatedAddyDoc);
                        assert foundBotNameDoc != null;
                        botControlCollection.updateOne(foundBotNameDoc, updateAddyDocOperation);
                        sendMessage(chatId, "Operation Successful");
                    } catch (Exception e) {
                        e.printStackTrace(logsPrintStream);
                        sendMessage(chatId, e.getMessage());
                    }
                }
                else if (shouldRunGame && text.equalsIgnoreCase("stopBot")) {
                    shouldRunGame = false;
                    Set<String> keys = currentlyActiveGames.keySet();
                    for (String key : keys) {
                        currentlyActiveGames.get(key).setShouldContinueGame(false);
                    }
                    Document botNameDoc = new Document("botName", botName);
                    Document foundBotNameDoc = (Document) botControlCollection.find(botNameDoc).first();
                    Bson updatedAddyDoc = new Document("shouldRunGame", shouldRunGame);
                    Bson updateAddyDocOperation = new Document("$set", updatedAddyDoc);
                    assert foundBotNameDoc != null;
                    botControlCollection.updateOne(foundBotNameDoc, updateAddyDocOperation);
                    sendMessage(chatId, "Operation Successful. Please keep an eye on Active Processes before using modification commands");
                }
                else if (text.equalsIgnoreCase("ActiveProcesses")) {
                    Set<String> keys = currentlyActiveGames.keySet();
                    int count = 0;
                    for (String key : keys) {
                        if (currentlyActiveGames.get(key).isGameRunning) {
                            count++;
                        }
                    }
                    sendMessage(chatId, "Chats with Active Games  :  " + currentlyActiveGames.size() + "\n\nChats with ongoing Round  :  "
                            + count + "\n\n\n----------------------------\nFor Dev Use Only (Ignore \uD83D\uDC47)\n\nUndisposed Game Count : "
                            + undisposedGameCount);
                }
                else if (text.equalsIgnoreCase("Switch to mainnet")) {
                    switchNetworks(chatId, EthNetworkType, "mainnet");
                }
                else if (text.equalsIgnoreCase("Switch to ropsten")) {
                    switchNetworks(chatId, EthNetworkType, "ropsten");
                }
                else if (text.equalsIgnoreCase("Switch to maticMainnet")) {
                    switchNetworks(chatId, EthNetworkType, "maticMainnet");
                }
                else if (text.equalsIgnoreCase("Switch to maticMumbai")) {
                    switchNetworks(chatId, EthNetworkType, "maticMumbai");
                }
                else if (text.toLowerCase().startsWith("setpot")) {
                    Set<String> keys = currentlyActiveGames.keySet();
                    boolean isAnyGameRunning = false;
                    for (String key : keys) {
                        isAnyGameRunning = isAnyGameRunning || currentlyActiveGames.get(key).isGameRunning;
                    }
                    if (!isAnyGameRunning) {
                        try {
                            BigInteger amount = new BigInteger(text.split(" ")[1]);
                            BigInteger diffBalance = amount.subtract(new BigInteger(getTotalRTKForPoolInWallet()));
                            if (keys.size() == 0) {
                                setTotalRTKForPoolInWallet(amount.toString());
                                return;
                            }
                            boolean shouldUpdate;
                            for (String key : keys) {
                                LastBountyHunterGame lastBountyHunterGame = currentlyActiveGames.get(key);
                                shouldUpdate = lastBountyHunterGame.addRTKToPot(diffBalance, "Override");
                                if(shouldUpdate) {
                                    setTotalRTKForPoolInWallet(amount.toString());
                                    lastBountyHunterGame.sendBountyUpdateMessage(diffBalance);
                                } else {
                                    sendMessage(chatId, "LastBountyHunterGame Wallet RTK Balance not enough to support this operation.");
                                }
                            }
                        } catch (Exception e) {
                            e.printStackTrace(logsPrintStream);
                            sendMessage(chatId, "Correct Format :- setPot amount\namount has to be BigInteger");
                        }
                    } else {
                        sendMessage(chatId, "Maybe a Round is active in at least on of the chat. Wait for all rounds to end");
                    }
                }
                else if (text.equalsIgnoreCase("getPot")) {
                    sendMessage(chatId, getTotalRTKForPoolInWallet());
                }
                else if (text.toLowerCase().startsWith("setshotcost")) {
                    try {
                        if (!shouldRunGame && currentlyActiveGames.size() == 0) {
                            Document botNameDoc = new Document("botName", botName);
                            Document foundBotNameDoc = (Document) botControlCollection.find(botNameDoc).first();
                            assert foundBotNameDoc != null;
                            shotCost = new BigInteger(text.trim().split(" ")[1]);
                            Bson updatedAddyDoc = new Document("shotCost", shotCost.toString());
                            Bson updateAddyDocOperation = new Document("$set", updatedAddyDoc);
                            botControlCollection.updateOne(foundBotNameDoc, updateAddyDocOperation);
                        } else {
                            throw new Exception();
                        }
                    } catch (Exception e) {
                        sendMessage(chatId, "Either the amount is invalid or there is at least one game that is still running.");
                    }
                }
                else if (text.equalsIgnoreCase("getShotCost")) {
                    Document botNameDoc = new Document("botName", botName);
                    Document foundBotNameDoc = (Document) botControlCollection.find(botNameDoc).first();
                    assert foundBotNameDoc != null;
                    sendMessage(chatId, (String) foundBotNameDoc.get("shotCost"));
                }
                else if (text.toLowerCase().startsWith("amountpulledoutfromfeesbalance")) {
                    if (!shouldRunGame && currentlyActiveGames.size() == 0) {
                        try {
                            String amount = text.split(" ")[1];
                            if (amount.contains("-")) {
                                throw new Exception("Value Cannot be negative");
                            }
                            addAmountToWalletFeesBalance("-" + amount);
                        } catch (Exception e) {
                            sendMessage(chatId, "Correct Format :- amountPulledOutFromFeesBalance amount\n" +
                                    "amount has to be a POSITIVE BigInteger");
                        }
                    } else {
                        sendMessage(chatId, "Maybe a Round is active in at least on of the chat. Also maker sure that bot is stopped before using " +
                                "this command");
                    }
                }
                else if (text.equalsIgnoreCase("getFeesBalance")) {
                    sendMessage(chatId, getWalletFeesBalance());
                }
                else if (text.equalsIgnoreCase("rebuildAdmins")) {
                    allAdmins = new ArrayList<>();
                    Document walletDetailDoc = new Document("identifier", "adminDetails");
                    Document foundWalletDetailDoc = (Document) walletDistributionCollection.find(walletDetailDoc).first();
                    assert foundWalletDetailDoc != null;
                    if (foundWalletDetailDoc.get("adminID") instanceof List) {
                        for (int i = 0; i < (((List<?>) foundWalletDetailDoc.get("adminID")).size()); i++) {
                            Object item = ((List<?>) foundWalletDetailDoc.get("adminID")).get(i);
                            if (item instanceof Long) {
                                allAdmins.add((Long) item);
                            }
                        }
                    }
                }
                else if (text.equalsIgnoreCase("rebuildWebSocketUrls")) {
                    Document botIndependentDoc = new Document("botName", "Bot Independent Data");
                    Document foundBotIndependentDoc = (Document) botControlCollection.find(botIndependentDoc).first();
                    assert foundBotIndependentDoc != null;
                    String[] linkCounts = ((String) foundBotIndependentDoc.get("urlCounts")).trim().split(" ");
                    int maticCount = Integer.parseInt(linkCounts[0]);
                    int QuickNodeCount = Integer.parseInt(linkCounts[1]);
                    if (foundBotIndependentDoc.get("urlList") instanceof List) {
                        for (int i = 0; i < (((List<?>) foundBotIndependentDoc.get("urlList")).size()); i++) {
                            Object item = ((List<?>) foundBotIndependentDoc.get("urlList")).get(i);
                            if (item instanceof String) {
                                if(i == 0) {
                                    maticPrefix = (String) item;
                                } else if(i == 1) {
                                    etherPrefix = (String) item;
                                } else if(i < maticCount + 2) {
                                    maticWebSocketUrls.add((String) item);
                                } else if(i < maticCount + QuickNodeCount + 2) {
                                    quickNodeWebSocketUrls.add((String) item);
                                } else {
                                    etherWebSocketUrls.add((String) item);
                                }
                            }
                        }
                    }
                    sendMessage(chatId, "Operation Successful");
                }
                else if (text.toLowerCase().startsWith("settopupwallet")) {
                    try {
                        Document document = new Document("identifier", "adminDetails");
                        Document foundDocument = (Document) walletDistributionCollection.find(document).first();
                        assert foundDocument != null;
                        topUpWalletAddress = text.split(" ")[1];
                        Bson updateDocument = new Document("topUpWalletAddress", topUpWalletAddress);
                        Bson updateDocumentOp = new Document("$set", updateDocument);
                        walletDistributionCollection.updateOne(foundDocument, updateDocumentOp);
                    } catch (Exception e) {
                        sendMessage(chatId, "Invalid Format. Proper Format : setTopUpWallet walletAddress");
                    }
                }
                else if (text.equalsIgnoreCase("resetWebSocketConnection")) {
                    Set<String> keys = currentlyActiveGames.keySet();
                    for (String key : keys) {
                        currentlyActiveGames.get(key).setShouldTryToEstablishConnection();
                    }
                }
                else if (text.toLowerCase().startsWith("setmessageflow to ")) {
                    if(!shouldRunGame) {
                        String[] input = text.toLowerCase().trim().split(" ");
                        shouldAllowMessageFlow = Boolean.parseBoolean(input[2]);
                    } else {
                        SendMessage sendMessage = new SendMessage();
                        sendMessage.setChatId(chatId);
                        sendMessage.setText("Stop All Games Before Changing the Value.");
                        try {
                            super.execute(sendMessage);
                        } catch (Exception e) {
                            e.printStackTrace(logsPrintStream);
                        }
                    }
                }
                else if (text.toLowerCase().startsWith("setshoulduseproxy to ")) {
                    Document botNameDoc = new Document("botName", botName);
                    Document foundBotNameDoc = (Document) botControlCollection.find(botNameDoc).first();
                    assert foundBotNameDoc != null;
                    shouldUseProxy = Boolean.parseBoolean(text.trim().split(" ")[2]);
                    Bson updatedAddyDoc = new Document("shouldUseProxy", shouldUseProxy);
                    Bson updateAddyDocOperation = new Document("$set", updatedAddyDoc);
                    botControlCollection.updateOne(foundBotNameDoc, updateAddyDocOperation);
                }
                else if (text.toLowerCase().startsWith("setshouldusequicknode to ")) {
                    Document botNameDoc = new Document("botName", botName);
                    Document foundBotNameDoc = (Document) botControlCollection.find(botNameDoc).first();
                    assert foundBotNameDoc != null;
                    shouldUseQuickNode = Boolean.parseBoolean(text.trim().split(" ")[2]);
                    Bson updatedAddyDoc = new Document("shouldUseQuickNode", shouldUseQuickNode);
                    Bson updateAddyDocOperation = new Document("$set", updatedAddyDoc);
                    botControlCollection.updateOne(foundBotNameDoc, updateAddyDocOperation);
                }
                else if (text.equalsIgnoreCase("getLogs")) {
                    sendLogs(chatId);
                }
                else if (text.equalsIgnoreCase("clearLogs")) {
                    if(logsPrintStream != null) {
                        logsPrintStream.flush();
                    }
                    try {
                        fileOutputStream = new FileOutputStream("LBH_OutPutLogs.txt");
                    } catch (FileNotFoundException e) {
                        e.printStackTrace();
                    }
                    logsPrintStream = new PrintStream(fileOutputStream) {

                        @Override
                        public void println(@Nullable String x) {
                            super.println("----------------------------- (Open)");
                            super.println(x);
                            super.println("----------------------------- (Close)\n\n");
                        }

                        @Override
                        public void close() {
                            try {
                                fileOutputStream.close();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                            super.close();
                        }
                    };
                }
                else if (text.toLowerCase().startsWith("getcsv")) {
                    String[] msg = text.trim().split(" ");
                    if((msg.length == 3)) {
                        Set<String> keys = currentlyActiveGames.keySet();
                        if (keys.size() == 0) {
                            sendMessage(chatId, "No Active Games. Use this command when a game is running");
                            return;
                        }
                        for(String s : keys) {
                            LastBountyHunterGame lastBountyHunterGame = currentlyActiveGames.get(s);
                            lastBountyHunterGame.getAllPreviousTrx(chatId, Integer.parseInt(msg[1]), msg[2]);
                            break;
                        }
                    } else {
                        sendMessage(chatId, "Invalid Format");
                    }
                }
                else if (text.toLowerCase().startsWith("convertrtklxintortk")) {
                    String[] msg = text.trim().split(" ");
                    if((msg.length == 3)) {
                        Set<String> keys = currentlyActiveGames.keySet();
                        if (keys.size() == 0) {
                            sendMessage(chatId, "No Active Games. Use this command when a game is running");
                            return;
                        }
                        int X = Integer.parseInt(msg[1]);
                        if (X >= 1 && X <= 5) {
                            for(String s : keys) {
                                LastBountyHunterGame lastBountyHunterGame = currentlyActiveGames.get(s);
                                lastBountyHunterGame.convertRTKLXIntoRTK(X, msg[2], chatId);
                                break;
                            }
                        } else {
                            sendMessage(chatId, "Invalid Value of X");
                        }
                    } else {
                        sendMessage(chatId, "Invalid Format");
                    }
                }
                else if (text.equalsIgnoreCase("Commands")) {
                    sendMessage(chatId, """
                            runBot
                            stopBot
                            ActiveProcesses
                            Switch to mainnet
                            Switch to ropsten
                            Switch to maticMainnet
                            Switch to maticMumbai
                            setPot amount
                            getPot
                            setShotCost amount
                            getShotCost
                            amountPulledOutFromFeesBalance amount
                            getFeesBalance
                            rebuildAdmins
                            rebuildWebSocketUrls
                            setTopUpWallet walletAddress
                            resetWebSocketConnection
                            setMessageFlow to boolean
                            setShouldUseProxy to boolean
                            setShouldUseQuickNode to boolean
                            getLogs
                            clearLogs
                            getCSV X startBlock
                            convertRTKLXIntoRTK X amount (amount can be a value or "Max")
                            Commands

                            (amount has to be bigInteger including 18 decimal eth precision)""");
                }

                else {
                    sendMessage(chatId, "Such command does not exists. BaaaaaaaaaKa");
                }
            } catch (Exception e) {
                e.printStackTrace(logsPrintStream);
            }
            sendMessage(chatId, "shouldRunGame : " + shouldRunGame + "\nEthNetworkType : " + EthNetworkType +
                    "\nshouldAllowMessageFlow : " + shouldAllowMessageFlow + "\nshouldUseProxy : " + shouldUseProxy +
                    "\nshouldUseQuickNode : " + shouldUseQuickNode + "\ntopUpWalletAddress : " + topUpWalletAddress +
                    "\nshotWallet : " + shotWallet +
                    "\nRTKContractAddresses :\n" + Arrays.toString(RTKContractAddresses));
        }
        else if (update.hasMessage() && update.getMessage().hasText()) {
            String chatId = update.getMessage().getChatId().toString();
            String text = update.getMessage().getText();

            if (text.equalsIgnoreCase("/checkForTrx") || text.equalsIgnoreCase("/checkForTrx@" + getBotUsername())) {
                String msg = "Faliure.";

                if (Instant.now().compareTo(lastMomentFromCommandUse.plus(2, ChronoUnit.HOURS)) >= 0) {
                    try {
                        Thread.sleep(500);
                    } catch (Exception e) {
                        e.printStackTrace(logsPrintStream);
                    }
                    if (Instant.now().compareTo(lastMomentWhenTrxWasRead.plus(30, ChronoUnit.MINUTES)) >= 0) {
                        Set<String> keys = currentlyActiveGames.keySet();
                        if (keys.size() != 0) {
                            for (String key : keys) {
                                LastBountyHunterGame lastBountyHunterGame = currentlyActiveGames.get(key);
                                lastBountyHunterGame.setShouldTryToEstablishConnection();
                            }
                            msg = "Success";
                        } else {
                            msg += " No active Games";
                        }
                    } else {
                        msg += " It seems the bot the reading the transactions. If you still think this is an error, please contact " +
                                "any administrator to look into the issue.";
                    }
                    lastMomentFromCommandUse = Instant.now();
                } else {
                    msg += " This command can only be used once every 90 minutes. If there are any issues with the games, please " +
                            "contact any administrator to look into the issue.";
                }
                enqueueMessageForSend(chatId, msg, -2, null);
            }
        }
    }

    @Override
    public String getBotUsername() {
        return "Last_Bounty_Hunter_Bot";
    }

    @Override
    public String getBotToken() {
        return (System.getenv("lastBountyHunterBotTokenA") + ":" + System.getenv("lastBountyHunterBotTokenB"));
    }

    @Override
    public <T extends Serializable, Method extends BotApiMethod<T>> T execute(Method method) throws TelegramApiException {
        if(shouldAllowMessageFlow) {
            return super.execute(method);
        } else if(isAdmin(Long.parseLong(((SendMessage) method).getChatId()))) {
            return super.execute(method);
        } else {
            return null;
        }
    }

    @Override
    public Message execute(SendAnimation sendAnimation) throws TelegramApiException {
        if(shouldAllowMessageFlow) {
            return super.execute(sendAnimation);
        } else {
            return null;
        }
    }


    @SuppressWarnings("SpellCheckingInspection")
    private void switchNetworks(String chatId, String from, String to) {
        if (from.equals(to)) {
            return;
        }
        try {
            if (EthNetworkType.equals(from) && currentlyActiveGames.size() == 0) {
                clientSession.startTransaction();
                Document walletDetailDoc = new Document("identifier", "walletBalanceDistribution");
                Document foundWalletDetailDoc1 = (Document) walletDistributionCollection.find(walletDetailDoc).first();
                walletDetailDoc = new Document("identifier", to + "Backup");
                Document foundWalletDetailDoc2 = (Document) walletDistributionCollection.find(walletDetailDoc).first();
                walletDetailDoc = new Document("identifier", from + "Backup");
                Document foundWalletDetailDoc3 = (Document) walletDistributionCollection.find(walletDetailDoc).first();
                assert foundWalletDetailDoc1 != null;
                assert foundWalletDetailDoc2 != null;
                assert foundWalletDetailDoc3 != null;


                Bson updateWalletDoc = walletDetailDoc;
                Bson updateWalletDocOperation = new Document("$set", updateWalletDoc);
                walletDistributionCollection.updateOne(clientSession, foundWalletDetailDoc1, updateWalletDocOperation);
                updateWalletDoc = new Document("identifier", "walletBalanceDistribution")
                        .append("totalRTKBalanceForPool", foundWalletDetailDoc2.get("totalRTKBalanceForPool"))
                        .append("lastCheckedBlockNumber", foundWalletDetailDoc2.get("lastCheckedBlockNumber"))
                        .append("lastCheckedTransactionIndex", foundWalletDetailDoc2.get("lastCheckedTransactionIndex"))
                        .append("balanceCollectedAsFees", foundWalletDetailDoc2.get("balanceCollectedAsFees"));
                updateWalletDocOperation = new Document("$set", updateWalletDoc);
                walletDistributionCollection.updateOne(clientSession, foundWalletDetailDoc3, updateWalletDocOperation);

                switch (to) {
                    case "mainnet" -> RTKContractAddresses = new String[]{"0x1F6DEADcb526c4710Cf941872b86dcdfBbBD9211",
                            "0x66bc87412a2d92a2829137ae9dd0ee063cd0f201", "0xb0f87621a43f50c3b7a0d9f58cc804f0cdc5c267",
                            "0x4a1c95097473c54619cb0e22c6913206b88b9a1a", "0x63b9713df102ea2b484196a95cdec5d8af278a60"};
                    case "ropsten" -> RTKContractAddresses = new String[]{"0x38332D8671961aE13d0BDe040d536eB336495eEA",
                            "0x9C72573A47b0d81Ef6048c320bF5563e1606A04C", "0x136A5c9B9965F3827fbB7A9e97E41232Df168B08",
                            "0xfB8C59fe95eB7e0a2fA067252661687df67d87b8", "0x99afe8FDEd0ef57845F126eEFa945d687CdC052d"};
                    case "maticMainnet" -> RTKContractAddresses = new String[]{"0x38332D8671961aE13d0BDe040d536eB336495eEA",
                            "0x136A5c9B9965F3827fbB7A9e97E41232Df168B08", "0xfB8C59fe95eB7e0a2fA067252661687df67d87b8",
                            "0x99afe8FDEd0ef57845F126eEFa945d687CdC052d", "0x88dD15CEac31a828e06078c529F5C1ABB214b6E8"};
                    default -> RTKContractAddresses = new String[] {"0x54320152Eb8889a9b28048280001549FAC3E26F5",
                            "0xc21af68636B79A9F12C11740B81558Ad27C038a6", "0x9D27dE8369fc977a3BcD728868984CEb19cF7F66",
                            "0xc21EE7D6eF734dc00B3c535dB658Ef85720948d3", "0x39b892Cf8238736c038B833d88B8C91B1D5C8158"};
                }
                EthNetworkType = to;

                Document botNameDoc = new Document("botName", botName);
                Document foundBotNameDoc = (Document) botControlCollection.find(botNameDoc).first();
                Bson updatedAddyDoc = new Document("EthNetworkType", EthNetworkType);
                Bson updateAddyDocOperation = new Document("$set", updatedAddyDoc);
                assert foundBotNameDoc != null;
                botControlCollection.updateOne(clientSession, foundBotNameDoc, updateAddyDocOperation);
                clientSession.commitTransaction();
                sendMessage(chatId, "Operation Successful");
            } else {
                if (!EthNetworkType.equals(from)) {
                    throw new Exception("Bot is already running on " + to + "...\nSwitch Unsuccessful");
                } else {
                    throw new Exception("Operation Unsuccessful. Currently a game is running. Let the game finish " +
                            "before switching the network");
                }
            }
        } catch (Exception e) {
            if (clientSession.hasActiveTransaction()) {
                clientSession.abortTransaction();
            }
            sendMessage(chatId, "Operation Unsuccessful : " + e.getMessage());
        }
    }

    public void sendMessage(String chat_id, String msg, String... url) {
        if (url.length == 0) {
            SendMessage sendMessage = new SendMessage();
            sendMessage.setText(msg);
            sendMessage.setChatId(chat_id);
            try {
                execute(sendMessage);
            } catch (Exception e) {
                e.printStackTrace(logsPrintStream);
            }
        } else {
            SendAnimation sendAnimation = new SendAnimation();
            sendAnimation.setAnimation(new InputFile().setMedia(url[(int) (Math.random() * (url.length))]));
            sendAnimation.setCaption(msg);
            sendAnimation.setChatId(chat_id);
            try {
                execute(sendAnimation);
            } catch (Exception e) {
                e.printStackTrace(logsPrintStream);
            }
        }
    }

    public void enqueueMessageForSend(String chat_id, String msg, int sendStatus, TransactionData transactionData, String... url) {
        try {
            if (url.length == 0) {
                SendMessage sendMessage = new SendMessage();
                sendMessage.setText(msg);
                sendMessage.setChatId(chat_id);
                if(transactionData == null) {
                    allPendingMessages.putLast(new TelegramMessage(sendMessage, sendStatus));
                } else {
                    allPendingMessages.putLast(new TelegramMessage(sendMessage, sendStatus, transactionData));
                }
            } else {
                SendAnimation sendAnimation = new SendAnimation();
                sendAnimation.setAnimation(new InputFile().setMedia(url[(int) (Math.random() * (url.length))]));
                sendAnimation.setCaption(msg);
                sendAnimation.setChatId(chat_id);
                if(transactionData == null) {
                    allPendingMessages.putLast(new TelegramMessage(sendAnimation, sendStatus));
                } else {
                    allPendingMessages.putLast(new TelegramMessage(sendAnimation, sendStatus, transactionData));
                }
            }
        } catch (Exception e) {
            e.printStackTrace(logsPrintStream);
        }
    }

    private void sendLogs(String chatId) {
        SendDocument sendDocument = new SendDocument();
        sendDocument.setChatId(chatId);
        logsPrintStream.flush();
        sendDocument.setDocument(new InputFile().setMedia(new File("LBH_OutPutLogs.txt")));
        sendDocument.setCaption("Latest Logs");
        try {
            execute(sendDocument);
        } catch (Exception e) {
            e.printStackTrace(logsPrintStream);
        }
    }

    public void sendFile(String chatId, String fileName) {
        SendDocument sendDocument = new SendDocument();
        sendDocument.setChatId(chatId);
        sendDocument.setDocument(new InputFile().setMedia(new File(fileName)));
        sendDocument.setCaption(fileName);
        try {
            execute(sendDocument);
        } catch (Exception e) {
            e.printStackTrace(logsPrintStream);
        }
    }

    public boolean deleteGame(String chat_id, LastBountyHunterGame lastBountyHunterGame, String deleterId, boolean closedByAdmin) {
        logsPrintStream.println("\n...Request made to delete the Game...\n(By Admin : " + closedByAdmin + ")");
        if (allPendingMessages.size() != 0) {
            if(!messageSendingExecutor.isShutdown()) {
                logsPrintStream.println("Request to close the game with message sender shutdown and pending message > 0");
            }
            return false;
        }
        if (!messageSendingExecutor.isShutdown()) {
            messageSendingExecutor.shutdownNow();
        }
        new Thread() {
            @Override
            public void run() {
                super.run();
                while (!lastBountyHunterGame.hasGameClosed) {
                    try {
                        sleep(1000);
                    } catch (Exception e) {
                        e.printStackTrace(logsPrintStream);
                    }
                }
                gameRunningExecutorService.shutdown();
                logsPrintStream.println("Game ExecutorService end successful.");
            }
        }.start();
        logsPrintStream.println("Game Deletion from ID : " + deleterId);
        currentlyActiveGames.remove(chat_id);
        Document botNameDoc = new Document("botName", botName);
        Document foundBotNameDoc = (Document) botControlCollection.find(botNameDoc).first();
        Document intermediate = new Document("wasGameEndMessageSent", true);
        if(shouldRunGame && currentlyActiveGames.size() == 0) {
            shouldRunGame = false;
            intermediate.append("shouldRunGame", false);
        }
        Bson updateAddyDocOperation = new Document("$set", intermediate);
        assert foundBotNameDoc != null;
        botControlCollection.updateOne(foundBotNameDoc, updateAddyDocOperation);
        lastSendStatus = -1;
        lastGameEndTime = Instant.now().plus(1500, ChronoUnit.MILLIS);
        return true;
    }

    public void decreaseUndisposedGameCount() {
        undisposedGameCount--;
    }

    public boolean isAdmin(long id) {
        return allAdmins.contains(id);
    }

    public void setTotalRTKForPoolInWallet(String amount) {
        Bson updateWalletDoc = new Document("totalRTKBalanceForPool", amount);
        Bson updateWalletDocOperation = new Document("$set", updateWalletDoc);
        Document walletDetailDoc = new Document("identifier", "walletBalanceDistribution");
        Document foundWalletDetailDoc = (Document) walletDistributionCollection.find(walletDetailDoc).first();
        assert foundWalletDetailDoc != null;
        walletDistributionCollection.updateOne(foundWalletDetailDoc, updateWalletDocOperation);
        try {
            Thread.sleep(500);
        } catch (Exception e) {
            e.printStackTrace(logsPrintStream);
        }
        walletDistributionCollection.updateOne(foundWalletDetailDoc, updateWalletDocOperation);
    }

    public String getTotalRTKForPoolInWallet() {
        Document walletDetailDoc = new Document("identifier", "walletBalanceDistribution");
        Document foundWalletDetailDoc = (Document) walletDistributionCollection.find(walletDetailDoc).first();
        assert foundWalletDetailDoc != null;
        return (String) foundWalletDetailDoc.get("totalRTKBalanceForPool");
    }

    public ProxyIP getProxyIP() {
        Collections.shuffle(allProxies);
        return allProxies.get(0);
    }

    public void addAmountToWalletFeesBalance(String amount) {
        if(!amount.equals("0")) {
            Document walletDetailDoc = new Document("identifier", "walletBalanceDistribution");
            Document foundWalletDetailDoc = (Document) walletDistributionCollection.find(walletDetailDoc).first();
            assert foundWalletDetailDoc != null;
            BigInteger balance = new BigInteger((String) foundWalletDetailDoc.get("balanceCollectedAsFees"));
            balance = balance.add(new BigInteger(amount));
            Bson updateWalletDoc = new Document("balanceCollectedAsFees", balance.toString());
            Bson updateWalletDocOperation = new Document("$set", updateWalletDoc);
            walletDistributionCollection.updateOne(foundWalletDetailDoc, updateWalletDocOperation);
        }
    }

    public String getWalletFeesBalance() {
        Document walletDetailDoc = new Document("identifier", "walletBalanceDistribution");
        Document foundWalletDetailDoc = (Document) walletDistributionCollection.find(walletDetailDoc).first();
        assert foundWalletDetailDoc != null;
        return (String) foundWalletDetailDoc.get("balanceCollectedAsFees");
    }

    public void resetWasGameEndMessageSent() {
        Bson updatedAddyDoc = new Document("wasGameEndMessageSent", false);
        Bson updateAddyDocOperation = new Document("$set", updatedAddyDoc);
        Document botNameDoc = new Document("botName", botName);
        Document foundBotNameDoc = (Document) botControlCollection.find(botNameDoc).first();
        assert foundBotNameDoc != null;
        botControlCollection.updateOne(foundBotNameDoc, updateAddyDocOperation);
    }

    public boolean getWasGameEndMessageSent() {
        Document botNameDoc = new Document("botName", botName);
        Document foundBotNameDoc = (Document) botControlCollection.find(botNameDoc).first();
        assert foundBotNameDoc != null;
        return (boolean) foundBotNameDoc.get("wasGameEndMessageSent");
    }

    public TransactionData getLastCheckedTransactionDetails() {
        TransactionData transactionData = new TransactionData();
        Document walletDetailDoc = new Document("identifier", "walletBalanceDistribution");
        Document foundWalletDetailDoc = (Document) walletDistributionCollection.find(walletDetailDoc).first();
        assert foundWalletDetailDoc != null;
        transactionData.blockNumber = new BigInteger((String) foundWalletDetailDoc.get("lastCheckedBlockNumber"));
        transactionData.trxIndex = new BigInteger((String) foundWalletDetailDoc.get("lastCheckedTransactionIndex"));
        return transactionData;
    }

    public LBH_LastGameState getLastGameState() {
        Document botNameDoc = new Document("botName", botName);
        Document foundBotNameDoc = (Document) botControlCollection.find(botNameDoc).first();
        assert foundBotNameDoc != null;
        TransactionData transactionData = new TransactionData();
        transactionData.trxHash = (String) foundBotNameDoc.get("trxHash");
        transactionData.fromAddress = (String) foundBotNameDoc.get("from");
        transactionData.toAddress = (String) foundBotNameDoc.get("to");
        transactionData.X = (int) foundBotNameDoc.get("X");
        transactionData.didBurn = (boolean) foundBotNameDoc.get("didBurn");
        transactionData.blockNumber = new BigInteger((String) foundBotNameDoc.get("block"));
        transactionData.trxIndex = new BigInteger((String) foundBotNameDoc.get("trxIndex"));
        transactionData.value = new BigInteger((String) foundBotNameDoc.get("value"));
        ArrayList<String> arrayList = new ArrayList<>();
        arrayList.add((String) foundBotNameDoc.get("lastCountedHash0"));
        arrayList.add((String) foundBotNameDoc.get("lastCountedHash1"));
        arrayList.add((String) foundBotNameDoc.get("lastCountedHash2"));
        String endTime = (String) foundBotNameDoc.get("endTime");
        LBH_LastGameState lbh_lastGameState;
        try {
            lbh_lastGameState = new LBH_LastGameState(transactionData, Instant.parse(endTime), arrayList);
        } catch (Exception e) {
            lbh_lastGameState = new LBH_LastGameState(transactionData, null, arrayList);
        }
        String msg = "\nPrevious State read :- \nTrxData --> ";
        if(lbh_lastGameState.lastCheckedTransactionData != null) {
            msg += lbh_lastGameState.lastCheckedTransactionData.toString();
            msg += ", Last 3 Trx Hash : " + lbh_lastGameState.last3CountedHash;
        } else {
            msg += "null";
        }
        msg += "\nEnd Time --> ";
        if(lbh_lastGameState.lastGameEndTime != null) {
            msg += lbh_lastGameState.lastGameEndTime.toString();
        } else {
            msg += "null";
        }
        logsPrintStream.println(msg);
        lastSavedStateTransactionData = lbh_lastGameState.lastCheckedTransactionData;
        return lbh_lastGameState;
    }

    public void setLastCheckedTransactionDetails(TransactionData transactionData) {
        Bson updateWalletDoc = new Document("lastCheckedBlockNumber", transactionData.blockNumber.toString())
                .append("lastCheckedTransactionIndex", transactionData.trxIndex.toString());
        Bson updateWalletDocOperation = new Document("$set", updateWalletDoc);
        Document walletDetailDoc = new Document("identifier", "walletBalanceDistribution");
        Document foundWalletDetailDoc = (Document) walletDistributionCollection.find(walletDetailDoc).first();
        assert foundWalletDetailDoc != null;
        walletDistributionCollection.updateOne(foundWalletDetailDoc, updateWalletDocOperation);
    }
}
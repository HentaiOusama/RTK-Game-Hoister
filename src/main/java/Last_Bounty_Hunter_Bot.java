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

@SuppressWarnings("SpellCheckingInspection")
public class Last_Bounty_Hunter_Bot extends TelegramLongPollingBot {

    private class MessageSender implements Runnable {
        @Override
        public void run() {
            try {
                TelegramMessage currentMessage = allPendingMessages.take();
                lastSendStatus = currentMessage.sendStatus;
                if(makeChecks) {
                    boolean shouldReturn = true;
                    if(currentMessage.hasTransactionData) {
                        if(lastSavedStateTransactionData != null) {
                            makeChecks = currentMessage.transactionData.compareTo(lastSavedStateTransactionData) < 0;
                        } else {
                            shouldReturn = false;
                            makeChecks = false;
                        }
                        if(!makeChecks) {
                            Set<String> keys = currentlyActiveGames.keySet();
                            for(String key : keys) {
                                currentlyActiveGames.get(key).shouldRecoverFromAbruptInterruption = false;
                            }
                        }
                    }
                    if(shouldReturn) {
                        return;
                    }
                }
                if ((currentMessage.isMessage)) {
                    execute(currentMessage.sendMessage);
                } else {
                    execute(currentMessage.sendAnimation);
                }
                if(currentMessage.hasTransactionData) {
                    lastSavedStateTransactionData = currentMessage.transactionData;
                }
            } catch (Exception e) {
                e.printStackTrace(logsPrintStream);
            }
        }
    }

    private class LogClearer implements Runnable {
        @Override
        public void run() {
            if(logsPrintStream != null) {
                logsPrintStream.flush();
            }
            try {
                fileOutputStream = new FileOutputStream("CustomLogsOutput.txt");
            } catch (FileNotFoundException e) {
                e.printStackTrace(logsPrintStream);
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
    }

    // Game manager variable
    private boolean shouldRunGame;
    private ArrayList<Long> allAdmins = new ArrayList<>();
    private final String testingChatId = "-1001477389485", actualGameChatId = "-1001275436629", mainRuletkaChatID = "-1001303208172";
    private boolean shouldAllowMessageFlow = true;
    volatile String topUpWalletAddress;
    volatile boolean makeChecks = false;
    volatile TransactionData lastSavedStateTransactionData = null;
    volatile int lastSendStatus = -1;
    FileOutputStream fileOutputStream;
    PrintStream logsPrintStream;
    int undisposedGameCount = 0;
    final String proxyUsername, proxyPassword;
    boolean shouldUseProxy;
    private final ArrayList<ProxyIP> allProxies = new ArrayList<>();
    private Instant lastGameEndTime;

    // Blockchain Related Stuff
    private String EthNetworkType;
    private final String shotWallet;
    private String[] RTKContractAddresses;
    private BigInteger shotCost;
    String maticPrefix;
    String etherPrefix;
    ArrayList<String> maticWebSocketUrls = new ArrayList<>();
    ArrayList<String> etherWebSocketUrls = new ArrayList<>();
    

    // MongoDB Related Stuff
    private final String botName = "Last Bounty Hunter Bot";
    private final ClientSession clientSession;
    private final MongoCollection botControlCollection, walletDistributionCollection;

    // All Data Holders
    private final HashMap<String, Game> currentlyActiveGames = new HashMap<>();
    private final LinkedBlockingDeque<TelegramMessage> allPendingMessages = new LinkedBlockingDeque<>();
    private ExecutorService gameRunningExecutorService = Executors.newCachedThreadPool();
    private ScheduledExecutorService messageSendingExecutor = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService logClearingExecutor = Executors.newSingleThreadScheduledExecutor();


    Last_Bounty_Hunter_Bot(String shotWallet) {
        this.shotWallet = shotWallet;
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                super.run();
                logsPrintStream.println("\n...Shutdown Handler Called...\n...Initiating Graceful Shutdown...\n");
                gameRunningExecutorService.shutdownNow();
                messageSendingExecutor.shutdownNow();
                logClearingExecutor.shutdownNow();
                Set<String> keys = currentlyActiveGames.keySet();
                for(String key : keys) {
                    Game game = currentlyActiveGames.get(key);
                    try {
                        logsPrintStream.println("Checking File Path : " + new File(".").getCanonicalPath());
                        logsPrintStream.println("Does basic file exist : " + new File("./PreservedState.bps").exists());
                        FileOutputStream fileOutputStream = new FileOutputStream("./PreservedState.bps");
                        ObjectOutputStream objectOutputStream = new ObjectOutputStream(fileOutputStream);
                        LastGameState lastGameState = new LastGameState(lastSavedStateTransactionData, game.getCurrentRoundEndTime());
                        logsPrintStream.println("\nSaved Game State :-\nTrxData --> " + ((lastSavedStateTransactionData == null) ?
                                "null" : lastSavedStateTransactionData.toString()) + "\nEndTime --> " + ((lastGameState.lastGameEndTime == null) ?
                                "null" : lastGameState.lastGameEndTime.toString()));
                        objectOutputStream.writeObject(lastGameState);
                        objectOutputStream.close();
                        fileOutputStream.close();
                    } catch (IOException e) {
                        e.printStackTrace(logsPrintStream);
                    }
                }
                sendLogs(allAdmins.get(0).toString());
                logsPrintStream.println("\n...Graceful Shutddown Successful...\n");
                logsPrintStream.flush();
                logsPrintStream.close();
            }
        });

        logClearingExecutor.scheduleWithFixedDelay(new LogClearer(), 0, 3, TimeUnit.DAYS);

        // Mongo Stuff
        ConnectionString connectionString = new ConnectionString(
                "mongodb+srv://" + System.getenv("lastBountyHunterMonoID") + ":" +
                        System.getenv("lastBountyHunterMonoPass") + "@hellgatesbotcluster.zm0r5.mongodb.net/test" +
                        "?keepAlive=true&poolSize=30&autoReconnect=true&socketTimeoutMS=360000&connectTimeoutMS=360000"
        );
        MongoClientSettings mongoClientSettings = MongoClientSettings.builder()
                .applyConnectionString(connectionString).retryWrites(true).build();
        MongoClient mongoClient = MongoClients.create(mongoClientSettings);
        clientSession = mongoClient.startSession();
        botControlCollection = mongoClient.getDatabase("All-Bots-Command-Centre").getCollection("MemberValues");
        walletDistributionCollection = mongoClient.getDatabase("Last-Bounty-Hunter-Bot-Database").getCollection("ManagingData");

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

        Document botNameDoc = new Document("botName", botName);
        Document foundBotNameDoc = (Document) botControlCollection.find(botNameDoc).first();
        assert foundBotNameDoc != null;
        shouldRunGame = (boolean) foundBotNameDoc.get("shouldRunGame");
        this.EthNetworkType = (String) foundBotNameDoc.get("EthNetworkType");
        this.shotCost = new BigInteger((String) foundBotNameDoc.get("shotCost"));
        proxyUsername = (String) foundBotNameDoc.get("proxyUsername");
        proxyPassword = (String) foundBotNameDoc.get("proxyPassword");
        if (foundBotNameDoc.get("proxyIP") instanceof List) {
            for (int i = 0; i < (((List<?>) foundBotNameDoc.get("proxyIP")).size()); i++) {
                Object item = ((List<?>) foundBotNameDoc.get("proxyIP")).get(i);
                if (item instanceof String) {
                    allProxies.add(new ProxyIP(((String) item).trim().split(":")));
                }
            }
        }
        int maticCount = Integer.parseInt(((String) foundBotNameDoc.get("urlCounts")).trim().split(" ")[0]);
        if (foundBotNameDoc.get("urlList") instanceof List) {
            for (int i = 0; i < (((List<?>) foundBotNameDoc.get("urlList")).size()); i++) {
                Object item = ((List<?>) foundBotNameDoc.get("urlList")).get(i);
                if (item instanceof String) {
                    if(i == 0) {
                        maticPrefix = (String) item;
                    } else if(i == 1) {
                        etherPrefix = (String) item;
                    } else if(i < maticCount + 2) {
                        maticWebSocketUrls.add((String) item);
                    } else {
                        etherWebSocketUrls.add((String) item);
                    }
                }
            }
        }
        shouldUseProxy = (boolean) foundBotNameDoc.get("shouldUseProxy");

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
            messageSendingExecutor.scheduleWithFixedDelay(new MessageSender(), 0, 1200, TimeUnit.MILLISECONDS);
            switch (EthNetworkType) {
                case "mainnet", "maticMainnet" -> {
                    Game newGame = new Game(this, actualGameChatId, EthNetworkType, shotWallet, RTKContractAddresses, shotCost);
                    currentlyActiveGames.put(actualGameChatId, newGame);
                    gameRunningExecutorService.execute(newGame);
                }
                case "ropsten", "maticMumbai" -> {
                    Game newGame = new Game(this, testingChatId, EthNetworkType, shotWallet, RTKContractAddresses, shotCost);
                    currentlyActiveGames.put(testingChatId, newGame);
                    gameRunningExecutorService.execute(newGame);
                }
            }
        }
        lastGameEndTime = Instant.now();
    }

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
                            Game newGame = new Game(this, actualGameChatId, EthNetworkType, shotWallet, RTKContractAddresses, shotCost);
                            currentlyActiveGames.put(actualGameChatId, newGame);
                            if (!gameRunningExecutorService.isShutdown()) {
                                gameRunningExecutorService.shutdownNow();
                            }
                            gameRunningExecutorService = Executors.newCachedThreadPool();
                            gameRunningExecutorService.execute(newGame);
                        }
                        else if ((EthNetworkType.equals("ropsten") || EthNetworkType.equals("maticMumbai")) && !currentlyActiveGames.containsKey(testingChatId)) {
                            Game newGame = new Game(this, testingChatId, EthNetworkType, shotWallet, RTKContractAddresses, shotCost);
                            currentlyActiveGames.put(testingChatId, newGame);
                            if (!gameRunningExecutorService.isShutdown()) {
                                gameRunningExecutorService.shutdownNow();
                            }
                            gameRunningExecutorService = Executors.newCachedThreadPool();
                            gameRunningExecutorService.execute(newGame);
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
                        messageSendingExecutor.scheduleWithFixedDelay(new MessageSender(), 0, 1200, TimeUnit.MILLISECONDS);
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
                            setTotalRTKForPoolInWallet(amount.toString());
                            for (String key : keys) {
                                Game game = currentlyActiveGames.get(key);
                                game.addRTKToPot(diffBalance, "Override");
                                game.sendBountyUpdateMessage(diffBalance);
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
                    Document botNameDoc = new Document("botName", botName);
                    Document foundBotNameDoc = (Document) botControlCollection.find(botNameDoc).first();
                    assert foundBotNameDoc != null;
                    int maticCount = Integer.parseInt(((String) foundBotNameDoc.get("urlCounts")).trim().split(" ")[0]);
                    maticWebSocketUrls = new ArrayList<>();
                    etherWebSocketUrls = new ArrayList<>();
                    if (foundBotNameDoc.get("urlList") instanceof List) {
                        for (int i = 0; i < (((List<?>) foundBotNameDoc.get("urlList")).size()); i++) {
                            Object item = ((List<?>) foundBotNameDoc.get("urlList")).get(i);
                            if (item instanceof String) {
                                if(i == 0) {
                                    maticPrefix = (String) item;
                                } else if(i == 1) {
                                    etherPrefix = (String) item;
                                } else if(i < maticCount + 2) {
                                    maticWebSocketUrls.add((String) item);
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
                    try {
                        if (!shouldRunGame && currentlyActiveGames.size() == 0) {
                            Document botNameDoc = new Document("botName", botName);
                            Document foundBotNameDoc = (Document) botControlCollection.find(botNameDoc).first();
                            assert foundBotNameDoc != null;
                            shouldUseProxy = Boolean.parseBoolean(text.trim().split(" ")[2]);
                            Bson updatedAddyDoc = new Document("shouldUseProxy", shouldUseProxy);
                            Bson updateAddyDocOperation = new Document("$set", updatedAddyDoc);
                            botControlCollection.updateOne(foundBotNameDoc, updateAddyDocOperation);
                        } else {
                            throw new Exception();
                        }
                    } catch (Exception e) {
                        sendMessage(chatId, "Either the amount is invalid or there is at least one game that is still running.");
                    }
                }
                else if (text.equalsIgnoreCase("getLogs")) {
                    sendLogs(chatId);
                }
                else if (text.equalsIgnoreCase("clearLogs")) {
                    new LogClearer().run();
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
                                getLogs
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
                    "\nRTKContractAddresses :\n" + Arrays.toString(RTKContractAddresses));
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
        sendDocument.setDocument(new InputFile().setMedia(new File("CustomLogsOutput.txt")));
        sendDocument.setCaption("Lastest Logs");
        try {
            execute(sendDocument);
        } catch (Exception e) {
            e.printStackTrace(logsPrintStream);
        }
    }

    public boolean deleteGame(String chat_id, Game game) {
        logsPrintStream.println("\n...Request made to delete the Game...");
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
                while (!game.hasGameClosed) {
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
        Document walletDetailDoc = new Document("identifier", "walletBalanceDistribution");
        Document foundWalletDetailDoc = (Document) walletDistributionCollection.find(walletDetailDoc).first();
        Bson updateWalletDoc = new Document("totalRTKBalanceForPool", amount);
        Bson updateWalletDocOperation = new Document("$set", updateWalletDoc);
        assert foundWalletDetailDoc != null;
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
        Document walletDetailDoc = new Document("identifier", "walletBalanceDistribution");
        Document foundWalletDetailDoc = (Document) walletDistributionCollection.find(walletDetailDoc).first();
        assert foundWalletDetailDoc != null;
        BigInteger balance = new BigInteger((String) foundWalletDetailDoc.get("balanceCollectedAsFees"));
        balance = balance.add(new BigInteger(amount));
        Bson updateWalletDoc = new Document("balanceCollectedAsFees", balance.toString());
        Bson updateWalletDocOperation = new Document("$set", updateWalletDoc);
        walletDistributionCollection.updateOne(foundWalletDetailDoc, updateWalletDocOperation);
    }

    public String getWalletFeesBalance() {
        Document walletDetailDoc = new Document("identifier", "walletBalanceDistribution");
        Document foundWalletDetailDoc = (Document) walletDistributionCollection.find(walletDetailDoc).first();
        assert foundWalletDetailDoc != null;
        return (String) foundWalletDetailDoc.get("balanceCollectedAsFees");
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

    public void resetWasGameEndMessageSent() {
        Document botNameDoc = new Document("botName", botName);
        Document foundBotNameDoc = (Document) botControlCollection.find(botNameDoc).first();
        Bson updatedAddyDoc = new Document("wasGameEndMessageSent", false);
        Bson updateAddyDocOperation = new Document("$set", updatedAddyDoc);
        assert foundBotNameDoc != null;
        botControlCollection.updateOne(foundBotNameDoc, updateAddyDocOperation);
    }

    public boolean getWasGameEndMessageSent() {
        Document botNameDoc = new Document("botName", botName);
        Document foundBotNameDoc = (Document) botControlCollection.find(botNameDoc).first();
        assert foundBotNameDoc != null;
        return (boolean) foundBotNameDoc.get("wasGameEndMessageSent");
    }

    public LastGameState getLastGameState() {
        try {
            FileInputStream fileInputStream = new FileInputStream("./PreservedState.bps");
            ObjectInputStream objectInputStream = new ObjectInputStream(fileInputStream);
            LastGameState lastGameState = (LastGameState) objectInputStream.readObject();
            String msg = "\nPrevious State read :- \nTrxData -->";
            if(lastGameState.lastCheckedTransactionData != null) {
                msg += lastGameState.lastCheckedTransactionData.toString();
            } else {
                msg += "null";
            }
            msg += "\nEnd Time --> ";
            if(lastGameState.lastGameEndTime != null) {
                msg += lastGameState.lastGameEndTime.toString();
            } else {
                msg += "null";
            }
            logsPrintStream.println(msg);
            lastSavedStateTransactionData = lastGameState.lastCheckedTransactionData;
            objectInputStream.close();
            fileInputStream.close();
            return lastGameState;
        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace(logsPrintStream);
            return null;
        }
    }

    public void setLastCheckedTransactionDetails(TransactionData transactionData) {
        Document walletDetailDoc = new Document("identifier", "walletBalanceDistribution");
        Document foundWalletDetailDoc = (Document) walletDistributionCollection.find(walletDetailDoc).first();
        assert foundWalletDetailDoc != null;
        Bson updateWalletDoc = new Document("lastCheckedBlockNumber", transactionData.blockNumber.toString())
                .append("lastCheckedTransactionIndex", transactionData.trxIndex.toString());
        Bson updateWalletDocOperation = new Document("$set", updateWalletDoc);
        walletDistributionCollection.updateOne(foundWalletDetailDoc, updateWalletDocOperation);
    }
}
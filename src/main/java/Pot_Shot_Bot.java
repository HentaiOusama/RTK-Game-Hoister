import Supporting_Classes.PSB_LastGameState;
import Supporting_Classes.ProxyIP;
import Supporting_Classes.TelegramMessage;
import Supporting_Classes.TransactionData;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.WriteConcern;
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

public class Pot_Shot_Bot extends TelegramLongPollingBot {

    private class MessageSender implements Runnable {
        @Override
        public void run() {
            try {
                TelegramMessage currentMessage = allPendingMessages.take();
                String msg = null;
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
    private final String testingChatId = "-1001477389485", actualGameChatId = "-1001470156156";
    private boolean shouldAllowMessageFlow = true;
    String topUpWalletAddress;
    volatile TransactionData lastSavedStateTransactionData = null;
    FileOutputStream fileOutputStream;
    PrintStream logsPrintStream;
    int undisposedGameCount = 0;
    String proxyUsername, proxyPassword;
    boolean shouldUseProxy, shouldUseQuickNode, shouldUseFigment;
    private final ArrayList<ProxyIP> allProxies = new ArrayList<>();
    private Instant lastGameEndTime = Instant.now();
    volatile Instant lastMomentWhenTrxWasRead = Instant.now(), lastMomentFromCommandUse = Instant.now().minus(55, ChronoUnit.MINUTES);

    // Blockchain Related Stuff
    private String EthNetworkType;
    private final String shotWallet;
    private String[] RTKContractAddresses;
    private BigInteger shotCost;
    String maticPrefix;
    String etherPrefix;
    ArrayList<String> maticVigilWebSocketUrls = new ArrayList<>();
    ArrayList<String> quickNodeWebSocketUrls = new ArrayList<>();
    ArrayList<String> etherWebSocketUrls = new ArrayList<>();
    ArrayList<String> figmentWebSocketUrls = new ArrayList<>();

    // MongoDB Related Stuff
    final String botType;
    private final String botName;
    private final ClientSession clientSession;
    private final MongoCollection<Document> botControlCollection, walletDistributionCollection;

    // All Data Holders
    private final HashMap<String, PotShotBotGame> currentlyActiveGames = new HashMap<>();
    private final LinkedBlockingDeque<TelegramMessage> allPendingMessages = new LinkedBlockingDeque<>();
    private ExecutorService gameRunningExecutorService = Executors.newCachedThreadPool();
    private ScheduledExecutorService messageSendingExecutor = Executors.newSingleThreadScheduledExecutor();


    @SuppressWarnings("SpellCheckingInspection")
    Pot_Shot_Bot(String botType, String shotWallet) {
        this.botType = botType;
        botName = "Pot Shot " + botType + " Bot";
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
                    for(String key : keys) {
                        currentlyActiveGames.get(key).tryToSaveState();
                    }
                } else {
                    logsPrintStream.println("Last Saved State Trx Data is NULL. Retaining old value.");
                }
                logsPrintStream.println("...Graceful Shutdown Successful...");
                logsPrintStream.flush();
                logsPrintStream.close();
                sendLogs(allAdmins.get(0).toString());
                System.out.println("\n...Graceful Shutdown Successful...\n");
                try {
                    Thread.sleep(8000);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });

        if(logsPrintStream != null) {
            logsPrintStream.flush();
        }
        try {
            fileOutputStream = new FileOutputStream("PS" + botType + "_OutPutLogs.txt");
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
                .applyConnectionString(connectionString).retryWrites(true).writeConcern(WriteConcern.MAJORITY).build();
        MongoClient mongoClient = MongoClients.create(mongoClientSettings);
        clientSession = mongoClient.startSession();
        botControlCollection = mongoClient.getDatabase("All-Bots-Command-Centre").getCollection("MemberValues");
        walletDistributionCollection = mongoClient.getDatabase("Pot-Shot-" + botType + "-Bot-Database").getCollection("ManagingData");

        try {
            Document walletDetailDoc = new Document("identifier", "adminDetails");
            Document foundWalletDetailDoc = walletDistributionCollection.find(walletDetailDoc).first();
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
            Document foundBotIndependentDoc = botControlCollection.find(botIndependentDoc).first();
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
            try {
                String[] linkCounts = ((String) foundBotIndependentDoc.get("urlCounts")).trim().split(" ");
                int maticVigilCount = Integer.parseInt(linkCounts[0]);
                int QuickNodeCount = Integer.parseInt(linkCounts[1]) + maticVigilCount;
                int etherCount = Integer.parseInt(linkCounts[2]) + QuickNodeCount;
                int figmentCount = Integer.parseInt(linkCounts[3]) + etherCount;
                if (foundBotIndependentDoc.get("urlList") instanceof List) {
                    for (int i = 0; i < (((List<?>) foundBotIndependentDoc.get("urlList")).size()); i++) {
                        Object item = ((List<?>) foundBotIndependentDoc.get("urlList")).get(i);
                        if (item instanceof String) {
                            if(i == 0) {
                                maticPrefix = (String) item;
                            } else if(i == 1) {
                                etherPrefix = (String) item;
                            } else if(i < maticVigilCount + 2) {
                                maticVigilWebSocketUrls.add((String) item);
                            } else if(i < QuickNodeCount + 2) {
                                quickNodeWebSocketUrls.add((String) item);
                            } else if (i < etherCount + 2) {
                                etherWebSocketUrls.add((String) item);
                            } else if (i < figmentCount + 2) {
                                figmentWebSocketUrls.add((String) item);
                            }
                        }
                    }
                }
                logsPrintStream.println("Read Urls : \n" + maticVigilWebSocketUrls + "\n" + quickNodeWebSocketUrls +
                        "\n" + etherWebSocketUrls + "\n" + figmentWebSocketUrls);
            } catch (Exception e) {
                e.printStackTrace(logsPrintStream);
            }

            Document botNameDoc = new Document("botName", botName);
            Document foundBotNameDoc = botControlCollection.find(botNameDoc).first();
            assert foundBotNameDoc != null;
            shouldRunGame = (boolean) foundBotNameDoc.get("shouldRunGame");
            this.EthNetworkType = (String) foundBotNameDoc.get("EthNetworkType");
            this.shotCost = new BigInteger((String) foundBotNameDoc.get("shotCost"));
            shouldUseProxy = (boolean) foundBotNameDoc.get("shouldUseProxy");
            shouldUseQuickNode = (boolean) foundBotNameDoc.get("shouldUseQuickNode");
            shouldUseFigment = (boolean) foundBotNameDoc.get("shouldUseFigment");

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
                messageSendingExecutor.scheduleWithFixedDelay(new Pot_Shot_Bot.MessageSender(), 0, 750, TimeUnit.MILLISECONDS);
                switch (EthNetworkType) {
                    case "mainnet", "maticMainnet" -> {
                        PotShotBotGame newPotShotBotGame = new PotShotBotGame(this, actualGameChatId, botType, EthNetworkType,
                                shotWallet, RTKContractAddresses, shotCost);
                        currentlyActiveGames.put(actualGameChatId, newPotShotBotGame);
                        gameRunningExecutorService.execute(newPotShotBotGame);
                    }
                    case "ropsten", "maticMumbai" -> {
                        PotShotBotGame newPotShotBotGame = new PotShotBotGame(this, testingChatId, botType, EthNetworkType,
                                shotWallet, RTKContractAddresses, shotCost);
                        currentlyActiveGames.put(testingChatId, newPotShotBotGame);
                        gameRunningExecutorService.execute(newPotShotBotGame);
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
                            PotShotBotGame newPotShotBotGame = new PotShotBotGame(this, actualGameChatId, botType, EthNetworkType,
                                    shotWallet, RTKContractAddresses, shotCost);
                            currentlyActiveGames.put(actualGameChatId, newPotShotBotGame);
                            if (!gameRunningExecutorService.isShutdown()) {
                                gameRunningExecutorService.shutdownNow();
                            }
                            gameRunningExecutorService = Executors.newCachedThreadPool();
                            gameRunningExecutorService.execute(newPotShotBotGame);
                        }
                        else if ((EthNetworkType.equals("ropsten") || EthNetworkType.equals("maticMumbai")) && !currentlyActiveGames.containsKey(testingChatId)) {
                            PotShotBotGame newPotShotBotGame = new PotShotBotGame(this, testingChatId, botType, EthNetworkType,
                                    shotWallet, RTKContractAddresses, shotCost);
                            currentlyActiveGames.put(testingChatId, newPotShotBotGame);
                            if (!gameRunningExecutorService.isShutdown()) {
                                gameRunningExecutorService.shutdownNow();
                            }
                            gameRunningExecutorService = Executors.newCachedThreadPool();
                            gameRunningExecutorService.execute(newPotShotBotGame);
                        }
                        else {
                            throw new Exception("Operation Unsuccessful. Currently a game is running. Let the game finish before starting" +
                                    " the bot.");
                        }
                        undisposedGameCount++;
                        if (!messageSendingExecutor.isShutdown()) {
                            messageSendingExecutor.shutdownNow();
                        }
                        messageSendingExecutor = Executors.newSingleThreadScheduledExecutor();
                        messageSendingExecutor.scheduleWithFixedDelay(new Pot_Shot_Bot.MessageSender(), 0, 750, TimeUnit.MILLISECONDS);
                        shouldRunGame = true;
                        Document botNameDoc = new Document("botName", botName);
                        Document foundBotNameDoc = botControlCollection.find(botNameDoc).first();
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
                        currentlyActiveGames.get(key).shouldContinueGame = false;
                    }
                    Document botNameDoc = new Document("botName", botName);
                    Document foundBotNameDoc = botControlCollection.find(botNameDoc).first();
                    Bson updatedAddyDoc = new Document("shouldRunGame", shouldRunGame);
                    Bson updateAddyDocOperation = new Document("$set", updatedAddyDoc);
                    assert foundBotNameDoc != null;
                    botControlCollection.updateOne(foundBotNameDoc, updateAddyDocOperation);
                    sendMessage(chatId, "Operation Successful. Please keep an eye on Active Processes before using modification commands");
                }
                else if (text.equalsIgnoreCase("ActiveProcesses")) {
                    sendMessage(chatId, "Chats with Active Games  :  " + currentlyActiveGames.size()
                            + "\n\n\n----------------------------\nFor Dev Use Only (Ignore \uD83D\uDC47)\n\nUndisposed Game Count : "
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
                    try {
                        BigInteger amount = new BigInteger(text.split(" ")[1]);
                        BigInteger diffBalance = amount.subtract(new BigInteger(getTotalRTKForPoolInWallet()));
                        boolean shouldUpdate;
                        Set<String> keys = currentlyActiveGames.keySet();
                        for (String key : keys) {
                            PotShotBotGame potShotBotGame = currentlyActiveGames.get(key);
                            shouldUpdate = potShotBotGame.addRTKToPot(diffBalance, "Override");
                            if(shouldUpdate) {
                                potShotBotGame.sendBountyUpdateMessage(diffBalance);
                                setTotalRTKForPoolInWallet(amount.toString());
                            } else {
                                sendMessage(chatId, "PotShotBotGame Wallet RTK Balance not enough to support this operation.");
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace(logsPrintStream);
                        sendMessage(chatId, "Correct Format :- setPot amount\namount has to be BigInteger");
                    }
                }
                else if (text.equalsIgnoreCase("getPot")) {
                    sendMessage(chatId, getTotalRTKForPoolInWallet());
                }
                else if (text.toLowerCase().startsWith("setshotcost")) {
                    try {
                        if (!shouldRunGame && currentlyActiveGames.size() == 0) {
                            Document botNameDoc = new Document("botName", botName);
                            Document foundBotNameDoc = botControlCollection.find(botNameDoc).first();
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
                    Document foundBotNameDoc = botControlCollection.find(botNameDoc).first();
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
                    Document foundWalletDetailDoc = walletDistributionCollection.find(walletDetailDoc).first();
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
                    Document foundBotIndependentDoc = botControlCollection.find(botIndependentDoc).first();
                    assert foundBotIndependentDoc != null;
                    maticVigilWebSocketUrls = new ArrayList<>();
                    quickNodeWebSocketUrls = new ArrayList<>();
                    figmentWebSocketUrls = new ArrayList<>();
                    etherWebSocketUrls = new ArrayList<>();

                    try {
                        String[] linkCounts = ((String) foundBotIndependentDoc.get("urlCounts")).trim().split(" ");
                        int maticVigilCount = Integer.parseInt(linkCounts[0]);
                        int QuickNodeCount = Integer.parseInt(linkCounts[1]) + maticVigilCount;
                        int etherCount = Integer.parseInt(linkCounts[2]) + QuickNodeCount;
                        int figmentCount = Integer.parseInt(linkCounts[3]) + etherCount;
                        if (foundBotIndependentDoc.get("urlList") instanceof List) {
                            for (int i = 0; i < (((List<?>) foundBotIndependentDoc.get("urlList")).size()); i++) {
                                Object item = ((List<?>) foundBotIndependentDoc.get("urlList")).get(i);
                                if (item instanceof String) {
                                    if(i == 0) {
                                        maticPrefix = (String) item;
                                    } else if(i == 1) {
                                        etherPrefix = (String) item;
                                    } else if(i < maticVigilCount + 2) {
                                        maticVigilWebSocketUrls.add((String) item);
                                    } else if(i < QuickNodeCount + 2) {
                                        quickNodeWebSocketUrls.add((String) item);
                                    } else if (i < etherCount + 2) {
                                        etherWebSocketUrls.add((String) item);
                                    } else if (i < figmentCount + 2) {
                                        figmentWebSocketUrls.add((String) item);
                                    }
                                }
                            }
                        }
                        logsPrintStream.println("Read Urls : \n" + maticVigilWebSocketUrls + "\n" + quickNodeWebSocketUrls +
                                "\n" + etherWebSocketUrls + "\n" + figmentWebSocketUrls);
                        sendMessage(chatId, "Operation Successful");
                    } catch (Exception e) {
                        e.printStackTrace(logsPrintStream);
                        sendMessage(chatId, "Operation Unsuccessful");
                    }
                }
                else if (text.toLowerCase().startsWith("settopupwallet")) {
                    try {
                        Document document = new Document("identifier", "adminDetails");
                        Document foundDocument = walletDistributionCollection.find(document).first();
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
                    Document foundBotNameDoc = botControlCollection.find(botNameDoc).first();
                    assert foundBotNameDoc != null;
                    shouldUseProxy = Boolean.parseBoolean(text.trim().split(" ")[2]);
                    Bson updatedAddyDoc = new Document("shouldUseProxy", shouldUseProxy);
                    Bson updateAddyDocOperation = new Document("$set", updatedAddyDoc);
                    botControlCollection.updateOne(foundBotNameDoc, updateAddyDocOperation);
                }
                else if (text.toLowerCase().startsWith("setshouldusequicknode to ")) {
                    shouldUseQuickNode = Boolean.parseBoolean(text.trim().split(" ")[2]);
                    Document updatedAddyDoc = new Document("shouldUseQuickNode", shouldUseQuickNode);
                    if (shouldUseQuickNode) {
                        shouldUseFigment = false;
                        updatedAddyDoc.append("shouldUseFigment", false);
                    }
                    Bson updateAddyDocOperation = new Document("$set", updatedAddyDoc);
                    Document botNameDoc = new Document("botName", botName);
                    Document foundBotNameDoc = botControlCollection.find(botNameDoc).first();
                    assert foundBotNameDoc != null;
                    botControlCollection.updateOne(foundBotNameDoc, updateAddyDocOperation);
                }
                else if (text.toLowerCase().startsWith("setshouldusefigment to ")) {
                    shouldUseFigment = Boolean.parseBoolean(text.trim().split(" ")[2]);
                    Document updatedAddyDoc = new Document("shouldUseFigment", shouldUseFigment);
                    if (shouldUseFigment) {
                        shouldUseQuickNode = false;
                        updatedAddyDoc.append("shouldUseQuickNode", false);
                    }
                    Bson updateAddyDocOperation = new Document("$set", updatedAddyDoc);
                    Document botNameDoc = new Document("botName", botName);
                    Document foundBotNameDoc = botControlCollection.find(botNameDoc).first();
                    assert foundBotNameDoc != null;
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
                        fileOutputStream = new FileOutputStream("PS" + botType + "_OutPutLogs.txt");
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
                            setshouldusefigment to boolean
                            getLogs
                            clearLogs
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
                    "\nshouldUseQuickNode : " + shouldUseQuickNode + "\nshouldUseFigment : " + shouldUseFigment + "\ntopUpWalletAddress : "
                    + topUpWalletAddress + "\nshotWallet : " + shotWallet + "\nRTKContractAddresses :\n" + Arrays.toString(RTKContractAddresses));
        }
    } // Not yet complete

    @Override
    public String getBotUsername() {
        return "PotShot" + botType +"Bot";
    }

    @Override
    public String getBotToken() {
        return (System.getenv("potShot" + botType + "BotTokenA") + ":" + System.getenv("potShot" + botType + "BotTokenB"));
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
                Document foundWalletDetailDoc1 = walletDistributionCollection.find(walletDetailDoc).first();
                walletDetailDoc = new Document("identifier", to + "Backup");
                Document foundWalletDetailDoc2 = walletDistributionCollection.find(walletDetailDoc).first();
                walletDetailDoc = new Document("identifier", from + "Backup");
                Document foundWalletDetailDoc3 = walletDistributionCollection.find(walletDetailDoc).first();
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
                        .append("balanceCollectedAsFees", foundWalletDetailDoc2.get("balanceCollectedAsFees"))
                        .append("lastCheckedTrxHash", foundWalletDetailDoc2.get("lastCheckedTrxHash"))
                        .append("lastCountedHash0", foundWalletDetailDoc2.get("lastCountedHash0"))
                        .append("lastCountedHash1", foundWalletDetailDoc2.get("lastCountedHash1"))
                        .append("lastCountedHash2", foundWalletDetailDoc2.get("lastCountedHash2"));
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
                Document foundBotNameDoc = botControlCollection.find(botNameDoc).first();
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

    public void enqueueMessageForSend(String chat_id, String msg, TransactionData transactionData, String... url) {
        try {
            if (url.length == 0) {
                SendMessage sendMessage = new SendMessage();
                sendMessage.setText(msg);
                sendMessage.setChatId(chat_id);
                if(transactionData == null) {
                    allPendingMessages.putLast(new TelegramMessage(sendMessage, 0));
                } else {
                    allPendingMessages.putLast(new TelegramMessage(sendMessage, 0, transactionData));
                }
            } else {
                SendAnimation sendAnimation = new SendAnimation();
                sendAnimation.setAnimation(new InputFile().setMedia(url[(int) (Math.random() * (url.length))]));
                sendAnimation.setCaption(msg);
                sendAnimation.setChatId(chat_id);
                if(transactionData == null) {
                    allPendingMessages.putLast(new TelegramMessage(sendAnimation, 0));
                } else {
                    allPendingMessages.putLast(new TelegramMessage(sendAnimation, 0, transactionData));
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
        sendDocument.setDocument(new InputFile().setMedia(new File("PS" + botType + "_OutPutLogs.txt")));
        sendDocument.setCaption("Latest Logs");
        try {
            execute(sendDocument);
        } catch (Exception e) {
            sendMessage(chatId, "Error in sending Logs\n" + Arrays.toString(e.getStackTrace()));
            e.printStackTrace(logsPrintStream);
        }
    }

    public boolean deleteGame(String chat_id, PotShotBotGame potShotBotGame, String deleterId) {
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
                while (!potShotBotGame.hasGameClosed) {
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
        Document foundBotNameDoc = botControlCollection.find(botNameDoc).first();
        Document intermediate = new Document("wasGameEndMessageSent", true);
        if(shouldRunGame && currentlyActiveGames.size() == 0) {
            shouldRunGame = false;
            intermediate.append("shouldRunGame", false);
        }
        Bson updateAddyDocOperation = new Document("$set", intermediate);
        assert foundBotNameDoc != null;
        botControlCollection.updateOne(foundBotNameDoc, updateAddyDocOperation);
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
        Document foundWalletDetailDoc = walletDistributionCollection.find(walletDetailDoc).first();
        Bson updateWalletDoc = new Document("totalRTKBalanceForPool", amount);
        Bson updateWalletDocOperation = new Document("$set", updateWalletDoc);
        assert foundWalletDetailDoc != null;
        walletDistributionCollection.updateOne(foundWalletDetailDoc, updateWalletDocOperation);
    }

    public String getTotalRTKForPoolInWallet() {
        Document walletDetailDoc = new Document("identifier", "walletBalanceDistribution");
        Document foundWalletDetailDoc = walletDistributionCollection.find(walletDetailDoc).first();
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
            Document foundWalletDetailDoc = walletDistributionCollection.find(walletDetailDoc).first();
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
        Document foundWalletDetailDoc = walletDistributionCollection.find(walletDetailDoc).first();
        assert foundWalletDetailDoc != null;
        return (String) foundWalletDetailDoc.get("balanceCollectedAsFees");
    }

    public PSB_LastGameState getLastGameState() {
        Document walletDetailDoc = new Document("identifier", "walletBalanceDistribution");
        Document foundWalletDetailDoc = walletDistributionCollection.find(walletDetailDoc).first();
        assert foundWalletDetailDoc != null;
        TransactionData transactionData = new TransactionData();
        transactionData.blockNumber = new BigInteger((String) foundWalletDetailDoc.get("lastCheckedBlockNumber"));
        transactionData.trxIndex = new BigInteger((String) foundWalletDetailDoc.get("lastCheckedTransactionIndex"));
        transactionData.trxHash = (String) foundWalletDetailDoc.get("lastCheckedTrxHash");
        ArrayList<String> arrayList = new ArrayList<>();
        arrayList.add((String) foundWalletDetailDoc.get("lastCountedHash0"));
        arrayList.add((String) foundWalletDetailDoc.get("lastCountedHash1"));
        arrayList.add((String) foundWalletDetailDoc.get("lastCountedHash2"));
        PSB_LastGameState PSBLastGameState;
        try {
            PSBLastGameState = new PSB_LastGameState(transactionData, arrayList);
        } catch (Exception e) {
            PSBLastGameState = new PSB_LastGameState(transactionData, arrayList);
        }
        String msg = "\nPrevious State read :- \nTrxData -->";
        if(PSBLastGameState.lastCheckedTransactionData != null) {
            msg += PSBLastGameState.lastCheckedTransactionData.toString();
            msg += ", Last 3 Trx Hash : " + PSBLastGameState.last3CountedHash;
        } else {
            msg += "null";
        }
        logsPrintStream.println(msg);
        lastSavedStateTransactionData = PSBLastGameState.lastCheckedTransactionData;
        return PSBLastGameState;
    }

    public void setLastCheckedTransactionDetails(TransactionData transactionData, ArrayList<String> last3CountedHash) {
        Document walletDetailDoc = new Document("identifier", "walletBalanceDistribution");
        Document foundWalletDetailDoc = walletDistributionCollection.find(walletDetailDoc).first();
        assert foundWalletDetailDoc != null;
        Document tempDoc = new Document("lastCheckedBlockNumber", transactionData.blockNumber.toString())
                .append("lastCheckedTransactionIndex", transactionData.trxIndex.toString())
                .append("lastCheckedTrxHash", transactionData.trxHash);

        if (last3CountedHash != null) {
            for(int i = 0; i < 3; i++) {
                if(i < last3CountedHash.size())
                    tempDoc.append("lastCountedHash" + i, last3CountedHash.get(i));
                else
                    tempDoc.append("lastCountedHash" + i, "NULL");
            }
        }

        Bson updateWalletDocOperation = new Document("$set", tempDoc);
        walletDistributionCollection.updateOne(foundWalletDetailDoc, updateWalletDocOperation);
    }
}

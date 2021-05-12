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
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendAnimation;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.io.File;
import java.io.PrintStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.*;

public class Pot_Shot_Bot extends TelegramLongPollingBot {

    private class MessageSender implements Runnable {
        @Override
        public void run() {
            try {
                TelegramMessage currentMessage = allPendingMessages.take();
                if ((currentMessage.isMessage)) {
                    logsPrintStream.println("Msg Sender (Executor): " + currentMessage.sendMessage.getText());
                    execute(currentMessage.sendMessage);
                } else {
                    logsPrintStream.println("Msg Sender (Executor): " + currentMessage.sendAnimation.getCaption());
                    execute(currentMessage.sendAnimation);
                }
                if(currentMessage.hasTransactionData) {
                    lastSavedStateTransactionData = currentMessage.transactionData;
                } else {
                    logsPrintStream.println("(Message Sender) No Trx Data with the current Msg");
                }
            } catch (Exception e) {
                e.printStackTrace(logsPrintStream);
            }
        }
    }
    
    // Game manager variable
    private boolean shouldRunGame;
    private ArrayList<Long> allAdmins = new ArrayList<>();
    private final String testingChatId = "-1001477389485", actualGameChatId = ""; // Not yet Complete
    String topUpWalletAddress;
    volatile TransactionData lastSavedStateTransactionData = null;
    PrintStream logsPrintStream;
    int undisposedGameCount = 0;
    String proxyUsername, proxyPassword;
    boolean shouldUseProxy, shouldUseQuickNode;
    private final ArrayList<ProxyIP> allProxies = new ArrayList<>();

    // Blockchain Related Stuff
    private String EthNetworkType;
    private final String shotWallet;
    private String[] RTKContractAddresses;
    private BigInteger shotCost;
    String maticPrefix;
    String etherPrefix;
    ArrayList<String> maticWebSocketUrls = new ArrayList<>();
    ArrayList<String> quickNodeWebSocketUrls = new ArrayList<>();
    ArrayList<String> etherWebSocketUrls = new ArrayList<>();

    // MongoDB Related Stuff
    private final String botName;
    private final ClientSession clientSession;
    private final MongoCollection botControlCollection, walletDistributionCollection;

    // All Data Holders
    private final HashMap<String, PotShotBotGame> currentlyActiveGames = new HashMap<>();
    private final LinkedBlockingDeque<TelegramMessage> allPendingMessages = new LinkedBlockingDeque<>();
    private ExecutorService gameRunningExecutorService = Executors.newCachedThreadPool();
    private ScheduledExecutorService messageSendingExecutor = Executors.newSingleThreadScheduledExecutor();


    public Pot_Shot_Bot(String botType, String shotWallet) {
        botName = "Pot Shot " + botType + " Bot";
        this.shotWallet = shotWallet;
        
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
        walletDistributionCollection = mongoClient.getDatabase("Pot-Shot-" + botType + "-Bot-Database").getCollection("ManagingData");

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
                messageSendingExecutor.scheduleWithFixedDelay(new Pot_Shot_Bot.MessageSender(), 0, 1200, TimeUnit.MILLISECONDS);
                switch (EthNetworkType) {
                    case "mainnet", "maticMainnet" -> {
                        PotShotBotGame newPotShotBotGame = new PotShotBotGame(this, actualGameChatId, EthNetworkType, shotWallet, RTKContractAddresses, shotCost);
                        currentlyActiveGames.put(actualGameChatId, newPotShotBotGame);
                        gameRunningExecutorService.execute(newPotShotBotGame);
                    }
                    case "ropsten", "maticMumbai" -> {
                        PotShotBotGame newPotShotBotGame = new PotShotBotGame(this, testingChatId, EthNetworkType, shotWallet, RTKContractAddresses, shotCost);
                        currentlyActiveGames.put(testingChatId, newPotShotBotGame);
                        gameRunningExecutorService.execute(newPotShotBotGame);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace(logsPrintStream);
        }
    }


    @Override
    public void onUpdateReceived(Update update) {

    }

    @Override
    public String getBotUsername() {
        return null;
    }

    @Override
    public String getBotToken() {
        return null;
    }


    public ProxyIP getProxyIP() {
        Collections.shuffle(allProxies);
        return allProxies.get(0);
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
        sendDocument.setDocument(new InputFile().setMedia(new File("LBH_OutPutLogs.txt")));
        sendDocument.setCaption("Latest Logs");
        try {
            execute(sendDocument);
        } catch (Exception e) {
            e.printStackTrace(logsPrintStream);
        }
    }

    public boolean deleteGame(String chat_id, PotShotBotGame lastBountyHunterGame, String deleterId) {
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
        return true;
    }

    public void decreaseUndisposedGameCount() {
        undisposedGameCount--;
    }
}

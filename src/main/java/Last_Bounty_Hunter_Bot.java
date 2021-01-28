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
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;

@SuppressWarnings("SpellCheckingInspection")
public class Last_Bounty_Hunter_Bot extends TelegramLongPollingBot {

    private class messageSender implements Runnable {
        @Override
        public void run() {
            try {
                TelegramMessage currentMessage = allPendingMessages.take();
                if ((currentMessage.isMessage)) {
                    execute(currentMessage.sendMessage);
                } else {
                    execute(currentMessage.sendAnimation);
                }
                lastSendStatus = currentMessage.sendStatus;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    // Game manager variable
    private boolean shouldRunGame;
    private ArrayList<Long> allAdmins = new ArrayList<>();
    public volatile String topUpWalletAddress;
    private final long testingChatId = -1001477389485L, actualGameChatId = -1001275436629L;
    public volatile int lastSendStatus = -1;

    // Blockchain Related Stuff
    private String EthNetworkType;
    private final String shotWallet;
    private String[] RTKContractAddresses;
    private BigInteger shotCost;

    // MongoDB Related Stuff
    private final String botName = "Last Bounty Hunter Bot";
    private final ClientSession clientSession;
    private final MongoCollection botControlCollection, walletDistributionCollection;
    private Document botNameDoc, foundBotNameDoc, walletDetailDoc, foundWalletDetailDoc;

    // All Data Holders
    private final HashMap<Long, Game> currentlyActiveGames = new HashMap<>();
    private final LinkedBlockingDeque<TelegramMessage> allPendingMessages = new LinkedBlockingDeque<>();
    private ExecutorService executorService = Executors.newCachedThreadPool();
    private ScheduledExecutorService messageSendingExecuter = Executors.newSingleThreadScheduledExecutor();


    Last_Bounty_Hunter_Bot(String shotWallet) {
        this.shotWallet = shotWallet;

        // Mongo Stuff
        String mongoDBUri = "mongodb+srv://" + System.getenv("lastBountyHunterMonoID") + ":" +
                System.getenv("lastBountyHunterMonoPass") + "@hellgatesbotcluster.zm0r5.mongodb.net/test";
        ConnectionString connectionString = new ConnectionString(mongoDBUri);
        MongoClientSettings mongoClientSettings = MongoClientSettings.builder()
                .applyConnectionString(connectionString).retryWrites(true).build();
        MongoClient mongoClient = MongoClients.create(mongoClientSettings);
        clientSession = mongoClient.startSession();
        String botControlDatabaseName = "All-Bots-Command-Centre";
        botControlCollection = mongoClient.getDatabase(botControlDatabaseName).getCollection("MemberValues");
        walletDistributionCollection = mongoClient.getDatabase("Last-Bounty-Hunter-Bot-Database").getCollection("ManagingData");

        walletDetailDoc = new Document("identifier", "adminDetails");
        foundWalletDetailDoc = (Document) walletDistributionCollection.find(walletDetailDoc).first();
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
        System.out.println("TopUpWalletAddress = " + topUpWalletAddress + "\nAdmins = " + allAdmins);

        botNameDoc = new Document("botName", botName);
        foundBotNameDoc = (Document) botControlCollection.find(botNameDoc).first();
        assert foundBotNameDoc != null;
        shouldRunGame = (boolean) foundBotNameDoc.get("shouldRunGame");
        this.EthNetworkType = (String) foundBotNameDoc.get("EthNetworkType");
        this.shotCost = new BigInteger((String) foundBotNameDoc.get("shotCost"));

        if (EthNetworkType.equals("mainnet")) {
            RTKContractAddresses = new String[]{"0x1F6DEADcb526c4710Cf941872b86dcdfBbBD9211",
                    "0x66bc87412a2d92a2829137ae9dd0ee063cd0f201", "0xb0f87621a43f50c3b7a0d9f58cc804f0cdc5c267",
                    "0x4a1c95097473c54619cb0e22c6913206b88b9a1a", "0x63b9713df102ea2b484196a95cdec5d8af278a60"};
        } else if (EthNetworkType.equals("ropsten")) {
            RTKContractAddresses = new String[]{"0x38332D8671961aE13d0BDe040d536eB336495eEA",
                    "0x9C72573A47b0d81Ef6048c320bF5563e1606A04C", "0x136A5c9B9965F3827fbB7A9e97E41232Df168B08",
                    "0xfB8C59fe95eB7e0a2fA067252661687df67d87b8", "0x99afe8FDEd0ef57845F126eEFa945d687CdC052d"};
        }

        if (shouldRunGame) {
            messageSendingExecuter.scheduleWithFixedDelay(new messageSender(), 0, 800, TimeUnit.MILLISECONDS);
            if (EthNetworkType.equals("mainnet")) {
                Game newGame = new Game(this, actualGameChatId, EthNetworkType, shotWallet, RTKContractAddresses, shotCost);
                currentlyActiveGames.put(actualGameChatId, newGame);
                executorService.execute(newGame);
            } else if (EthNetworkType.equals("ropsten")) {
                Game newGame = new Game(this, testingChatId, EthNetworkType, shotWallet, RTKContractAddresses, shotCost);
                currentlyActiveGames.put(testingChatId, newGame);
                executorService.execute(newGame);
            }
        }
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage()) {
            if (isAdmin(update.getMessage().getChatId())) {
                if (update.getMessage().hasText()) {
                    long chatId = update.getMessage().getChatId();
                    String text = update.getMessage().getText();
                    if (!shouldRunGame && text.equalsIgnoreCase("run")) {
                        try {
                            if (EthNetworkType.equals("mainnet") && !currentlyActiveGames.containsKey(actualGameChatId)) {
                                Game newGame = new Game(this, actualGameChatId, EthNetworkType, shotWallet, RTKContractAddresses, shotCost);
                                currentlyActiveGames.put(actualGameChatId, newGame);
                                if(!executorService.isShutdown()) {
                                    executorService.shutdownNow();
                                }
                                executorService = Executors.newCachedThreadPool();
                                executorService.execute(newGame);
                            } else if (EthNetworkType.equals("ropsten") && !currentlyActiveGames.containsKey(testingChatId)) {
                                Game newGame = new Game(this, testingChatId, EthNetworkType, shotWallet, RTKContractAddresses, shotCost);
                                currentlyActiveGames.put(testingChatId, newGame);
                                if(!executorService.isShutdown()) {
                                    executorService.shutdownNow();
                                }
                                executorService = Executors.newCachedThreadPool();
                                executorService.execute(newGame);
                            } else {
                                throw new Exception("Operation Unsuccessful. Currently a game is running. Let the game finish before starting" +
                                        " the bot.");
                            }
                            lastSendStatus = -1;
                            if (!messageSendingExecuter.isShutdown()) {
                                messageSendingExecuter.shutdownNow();
                            }
                            messageSendingExecuter = Executors.newSingleThreadScheduledExecutor();
                            messageSendingExecuter.scheduleWithFixedDelay(new messageSender(), 0, 800, TimeUnit.MILLISECONDS);
                            shouldRunGame = true;
                            Document botNameDoc = new Document("botName", botName);
                            Document foundBotNameDoc = (Document) botControlCollection.find(botNameDoc).first();
                            Bson updatedAddyDoc = new Document("shouldRunGame", shouldRunGame);
                            Bson updateAddyDocOperation = new Document("$set", updatedAddyDoc);
                            assert foundBotNameDoc != null;
                            botControlCollection.updateOne(foundBotNameDoc, updateAddyDocOperation);
                            sendMessage(chatId, "Operation Successful");
                        } catch (Exception e) {
                            e.printStackTrace();
                            sendMessage(chatId, e.getMessage());
                        }
                    }
                    else if (text.equalsIgnoreCase("Switch to mainnet")) {
                        switchNetworks(chatId, "ropsten", "mainnet");
                    }
                    else if (text.equalsIgnoreCase("Switch to ropsten")) {
                        switchNetworks(chatId, "mainnet", "ropsten");
                    }
                    else if (shouldRunGame && text.equalsIgnoreCase("stopBot")) {
                        shouldRunGame = false;
                        Set<Long> keys = currentlyActiveGames.keySet();
                        for (long chat_Id : keys) {
                            currentlyActiveGames.get(chat_Id).setShouldContinueGame(false);
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
                        Set<Long> keys = currentlyActiveGames.keySet();
                        int count = 0;
                        for (long chat_Id : keys) {
                            if (currentlyActiveGames.get(chat_Id).isGameRunning) {
                                count++;
                            }
                        }
                        sendMessage(chatId, "Chats with Active Games  :  " + currentlyActiveGames.size() +
                                "\n\nChats with ongoing Round  :  " + count);
                    }
                    else if (text.toLowerCase().startsWith("setpot")) {
                        Set<Long> keys = currentlyActiveGames.keySet();
                        boolean isAnyGameRunning = false;
                        for (long key : keys) {
                            isAnyGameRunning = isAnyGameRunning || currentlyActiveGames.get(key).isGameRunning;
                        }
                        if (!isAnyGameRunning && !shouldRunGame && currentlyActiveGames.size() == 0) {
                            try {
                                BigInteger amount = new BigInteger(text.split(" ")[1]);
                                setTotalRTKForPoolInWallet(amount.toString());
                            } catch (Exception e) {
                                e.printStackTrace();
                                sendMessage(update.getMessage().getChatId(), "Correct Format :- setPot amount\namount has to be BigInteger");
                            }
                        } else {
                            sendMessage(chatId, "Maybe a Round is active in at least on of the chat. Also maker sure that bot is stopped before using " +
                                    "this command");
                        }
                    }
                    else if (text.equalsIgnoreCase("getPot")) {
                        sendMessage(chatId, getTotalRTKForPoolInWallet());
                    }
                    else if (text.toLowerCase().startsWith("setshotcost")) {
                        try {
                            if(!shouldRunGame && currentlyActiveGames.size() == 0) {
                                botNameDoc = new Document("botName", botName);
                                foundBotNameDoc = (Document) botControlCollection.find(botNameDoc).first();
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
                        botNameDoc = new Document("botName", botName);
                        foundBotNameDoc = (Document) botControlCollection.find(botNameDoc).first();
                        assert foundBotNameDoc != null;
                        sendMessage(chatId, (String) foundBotNameDoc.get("shotCost"));
                    }
                    else if (text.toLowerCase().startsWith("amountpulledoutfromfeesbalance")) {
                        Set<Long> keys = currentlyActiveGames.keySet();
                        boolean isAnyGameRunning = false;
                        for (long key : keys) {
                            isAnyGameRunning = isAnyGameRunning || currentlyActiveGames.get(key).isGameRunning;
                        }
                        if (!isAnyGameRunning && !shouldRunGame && currentlyActiveGames.size() == 0) {
                            try {
                                String amount = text.split(" ")[1];
                                if (amount.contains("-")) {
                                    throw new Exception("Value Cannot be negative");
                                }
                                addAmountToWalletFeesBalance("-" + amount);
                            } catch (Exception e) {
                                sendMessage(update.getMessage().getChatId(), "Correct Format :- amountPulledOutFromFeesBalance amount\n" +
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
                        Set<Long> keys = currentlyActiveGames.keySet();
                        for (long chat_Id : keys) {
                            currentlyActiveGames.get(chat_Id).setShouldTryToEstablishConnection();
                        }
                    }
                    else if (text.equalsIgnoreCase("Commands")) {
                        sendMessage(update.getMessage().getChatId(), """
                                Run
                                Switch to mainnet
                                Switch to ropsten
                                StopBot
                                ActiveProcesses
                                setPot amount
                                getPot
                                setShotCost amount
                                getShotCost
                                amountPulledOutFromFeesBalance amount
                                getFeesBalance
                                rebuildAdmins
                                setTopUpWallet walletAddress
                                resetWebSocketConnection
                                Commands

                                (amount has to be bigInteger including 18 decimal eth precision)""");
                    }

                    else {
                        sendMessage(update.getMessage().getChatId(), "Such command does not exists. BaaaaaaaaaKa");
                    }
                    sendMessage(update.getMessage().getChatId(), "shouldRunGame = " + shouldRunGame + "\nEthNetworkType = "
                            + EthNetworkType + "\nRTKContractAddresses = " + Arrays.toString(RTKContractAddresses));
                }
                // Can add special operation for admin here
                return;
            }

            long chat_id = update.getMessage().getChatId();
            String[] inputMsg = update.getMessage().getText().trim().split(" ");
            if (!shouldRunGame) {
                sendMessage(chat_id, "Bot Under Maintainance. Please try again Later...");
            }
            switch (inputMsg[0]) {
                case "/rules", "/rules@Last_Bounty_Hunter_Bot" -> {
                    SendMessage sendMessage = new SendMessage();
                    if (!update.getMessage().getChat().isUserChat()) {
                        sendMessage.setText("Please use this command in private chat @" + getBotUsername());
                    } else {
                        sendMessage.setText("Not yet Complete...");
                    }
                    sendMessage.setChatId(chat_id);
                    try {
                        execute(sendMessage);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
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

    private void switchNetworks(long chatId, String from, String to) {
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

                EthNetworkType = to;
                if(EthNetworkType.equals("mainnet")) {
                    RTKContractAddresses = new String[]{"0x1F6DEADcb526c4710Cf941872b86dcdfBbBD9211",
                            "0x66bc87412a2d92a2829137ae9dd0ee063cd0f201", "0xb0f87621a43f50c3b7a0d9f58cc804f0cdc5c267",
                            "0x4a1c95097473c54619cb0e22c6913206b88b9a1a", "0x63b9713df102ea2b484196a95cdec5d8af278a60"};
                } else {
                    RTKContractAddresses = new String[]{"0x38332D8671961aE13d0BDe040d536eB336495eEA",
                            "0x9C72573A47b0d81Ef6048c320bF5563e1606A04C", "0x136A5c9B9965F3827fbB7A9e97E41232Df168B08",
                            "0xfB8C59fe95eB7e0a2fA067252661687df67d87b8", "0x99afe8FDEd0ef57845F126eEFa945d687CdC052d"};
                }

                botNameDoc = new Document("botName", botName);
                foundBotNameDoc = (Document) botControlCollection.find(botNameDoc).first();
                Bson updatedAddyDoc = new Document("EthNetworkType", EthNetworkType);
                Bson updateAddyDocOperation = new Document("$set", updatedAddyDoc);
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
            if(clientSession.hasActiveTransaction()) {
                clientSession.abortTransaction();
            }
            sendMessage(chatId, "Operation Unsuccessful : " + e.getMessage());
        }
    }

    public void sendMessage(long chat_id, String msg, String... url) {
        if (url.length == 0) {
            SendMessage sendMessage = new SendMessage();
            sendMessage.setText(msg);
            sendMessage.setChatId(chat_id);
            try {
                execute(sendMessage);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            SendAnimation sendAnimation = new SendAnimation();
            sendAnimation.setAnimation(url[(int) (Math.random() * (url.length))]);
            sendAnimation.setCaption(msg);
            sendAnimation.setChatId(chat_id);
            try {
                execute(sendAnimation);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void enqueueMessageForSend(long chat_id, String msg, int sendStatus, String... url) {
        try {
            if (url.length == 0) {
                SendMessage sendMessage = new SendMessage();
                sendMessage.setText(msg);
                sendMessage.setChatId(chat_id);
                allPendingMessages.putLast(new TelegramMessage(sendMessage, sendStatus));
            } else {
                SendAnimation sendAnimation = new SendAnimation();
                sendAnimation.setAnimation(url[(int) (Math.random() * (url.length))]);
                sendAnimation.setCaption(msg);
                sendAnimation.setChatId(chat_id);
                allPendingMessages.putLast(new TelegramMessage(sendAnimation, sendStatus));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public boolean deleteGame(long chat_id, Game game) {
        if (allPendingMessages.size() != 0) {
            return false;
        }
        if (!messageSendingExecuter.isShutdown()) {
            messageSendingExecuter.shutdownNow();
        }
        new Thread() {
            @Override
            public void run() {
                super.run();
                while(!game.hasGameClosed) {
                    try {
                        sleep(1000);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                executorService.shutdown();
                System.out.println("Game end successful.");
            }
        }.start();
        currentlyActiveGames.remove(chat_id);
        lastSendStatus = -1;
        return true;
    }

    public boolean isAdmin(long id) {
        return allAdmins.contains(id);
    }

    public void setTotalRTKForPoolInWallet(String amount) {
        walletDetailDoc = new Document("identifier", "walletBalanceDistribution");
        foundWalletDetailDoc = (Document) walletDistributionCollection.find(walletDetailDoc).first();
        Bson updateWalletDoc = new Document("totalRTKBalanceForPool", amount);
        Bson updateWalletDocOperation = new Document("$set", updateWalletDoc);
        walletDistributionCollection.updateOne(foundWalletDetailDoc, updateWalletDocOperation);
    }

    public String getTotalRTKForPoolInWallet() {
        walletDetailDoc = new Document("identifier", "walletBalanceDistribution");
        foundWalletDetailDoc = (Document) walletDistributionCollection.find(walletDetailDoc).first();
        assert foundWalletDetailDoc != null;
        return (String) foundWalletDetailDoc.get("totalRTKBalanceForPool");
    }

    public void addAmountToWalletFeesBalance(String amount) {
        walletDetailDoc = new Document("identifier", "walletBalanceDistribution");
        foundWalletDetailDoc = (Document) walletDistributionCollection.find(walletDetailDoc).first();
        assert foundWalletDetailDoc != null;
        BigInteger balance = new BigInteger((String) foundWalletDetailDoc.get("balanceCollectedAsFees"));
        balance = balance.add(new BigInteger(amount));
        Bson updateWalletDoc = new Document("balanceCollectedAsFees", balance.toString());
        Bson updateWalletDocOperation = new Document("$set", updateWalletDoc);
        walletDistributionCollection.updateOne(foundWalletDetailDoc, updateWalletDocOperation);
    }

    public String getWalletFeesBalance() {
        walletDetailDoc = new Document("identifier", "walletBalanceDistribution");
        foundWalletDetailDoc = (Document) walletDistributionCollection.find(walletDetailDoc).first();
        assert foundWalletDetailDoc != null;
        return (String) foundWalletDetailDoc.get("balanceCollectedAsFees");
    }

    public TransactionData getLastCheckedTransactionDetails() {
        TransactionData transactionData = new TransactionData();
        walletDetailDoc = new Document("identifier", "walletBalanceDistribution");
        foundWalletDetailDoc = (Document) walletDistributionCollection.find(walletDetailDoc).first();
        assert foundWalletDetailDoc != null;
        transactionData.blockNumber = new BigInteger((String) foundWalletDetailDoc.get("lastCheckedBlockNumber"));
        transactionData.trxIndex = new BigInteger((String) foundWalletDetailDoc.get("lastCheckedTransactionIndex"));
        return transactionData;
    }

    public void setLastCheckedTransactionDetails(TransactionData transactionData) {
        walletDetailDoc = new Document("identifier", "walletBalanceDistribution");
        foundWalletDetailDoc = (Document) walletDistributionCollection.find(walletDetailDoc).first();
        assert foundWalletDetailDoc != null;
        Bson updateWalletDoc = new Document("lastCheckedBlockNumber", transactionData.blockNumber.toString())
                .append("lastCheckedTransactionIndex", transactionData.trxIndex.toString());
        Bson updateWalletDocOperation = new Document("$set", updateWalletDoc);
        walletDistributionCollection.updateOne(foundWalletDetailDoc, updateWalletDocOperation);
    }
}
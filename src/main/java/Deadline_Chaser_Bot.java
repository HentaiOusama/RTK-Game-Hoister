import com.mongodb.MongoClient;
import com.mongodb.MongoClientURI;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendAnimation;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressWarnings("SpellCheckingInspection")
public class Deadline_Chaser_Bot extends TelegramLongPollingBot {

    // Game manager variable
    private boolean shouldRunGame;
    private boolean waitingToSwitchServers = false;
    private ArrayList<Long> allAdmins = new ArrayList<>();
    public volatile String topUpWalletAddress;

    // Blockchain Related Stuff
    private String EthNetworkType;
    private final String shotWallet;
    private String[] RTKContractAddresses;
    private final BigInteger shotCost;

    // MongoDB Related Stuff
    private final String botName = "Deadline Chaser Bot";
    private final MongoCollection botControlCollection, walletDistributionCollection;
    private Document botNameDoc, foundBotNameDoc, walletDetailDoc, foundWalletDetailDoc;

    // All Games Status
    private final HashMap<Long, Game> currentlyActiveGames = new HashMap<>();
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    Deadline_Chaser_Bot(String EthNetworkType, String shotWallet, String[] RTKContractAddresses, BigInteger shotCost) {
        this.EthNetworkType = EthNetworkType;
        this.shotWallet = shotWallet;
        this.RTKContractAddresses = RTKContractAddresses;
        this.shotCost = shotCost;
        // Mongo Stuff
        String mongoDBUri = "mongodb+srv://" + System.getenv("deadlineChaserMonoID") + ":" +
                System.getenv("deadlineChaserMonoPass") + "@hellgatesbotcluster.zm0r5.mongodb.net/test";
        MongoClient mongoClient = new MongoClient(new MongoClientURI(mongoDBUri));
        String botControlDatabaseName = "All-Bots-Command-Centre";
        MongoDatabase botControlDatabase = mongoClient.getDatabase(botControlDatabaseName);
        botControlCollection = botControlDatabase.getCollection("MemberValues");
        walletDistributionCollection = mongoClient.getDatabase("Deadline-Chaser-Bot-Database").getCollection("WalletBalanceDistribution");
        walletDetailDoc = new Document("identifier", "adminDetails");
        foundWalletDetailDoc = (Document) walletDistributionCollection.find(walletDetailDoc).first();
        assert foundWalletDetailDoc != null;
        topUpWalletAddress = (String) foundWalletDetailDoc.get("topUpWalletAddress");
        if (foundWalletDetailDoc.get("adminID") instanceof List) {
            for(int i = 0; i < (((List<?>) foundWalletDetailDoc.get("adminID")).size()); i++) {
                Object item = ((List<?>) foundWalletDetailDoc.get("adminID")).get(i);
                if(item instanceof Long) {
                    allAdmins.add((Long) item);
                }
            }
        }
        System.out.println("TopUpWalletAddress = " + topUpWalletAddress + "\nAdmins = " + allAdmins);
        botNameDoc = new Document("botName", botName);
        foundBotNameDoc = (Document) botControlCollection.find(botNameDoc).first();
        assert foundBotNameDoc != null;
        shouldRunGame = (boolean) foundBotNameDoc.get("shouldRunGame");
    }

    // Not yet complete. Add option for admin to reset the data of walletDoc on mongoDB. For this, we need to update backup Doc on mongoDB as well.
    @Override
    public void onUpdateReceived(Update update) {
        if(update.hasMessage()) {
            if(isAdmin(update.getMessage().getChatId())) {
                if(update.getMessage().hasText()) {
                    long chatId = update.getMessage().getChatId();
                    String text = update.getMessage().getText();
                    if(!shouldRunGame && text.equalsIgnoreCase("run")) {
                        shouldRunGame = true;
                        Set<Long> keys = currentlyActiveGames.keySet();
                        for(long chat_Id : keys) {
                            currentlyActiveGames.get(chat_Id).setShouldContinueGame(true);
                        }
                        botNameDoc = new Document("botName", botName);
                        foundBotNameDoc = (Document) botControlCollection.find(botNameDoc).first();
                        Bson updatedAddyDoc = new Document("shouldRunGame", shouldRunGame);
                        Bson updateAddyDocOperation = new Document("$set", updatedAddyDoc);
                        botControlCollection.updateOne(foundBotNameDoc, updateAddyDocOperation);
                    } else if(text.equalsIgnoreCase("Switch to mainnet")) {
                        EthNetworkType = "mainnet";
                        RTKContractAddresses = new String[]{"0x1F6DEADcb526c4710Cf941872b86dcdfBbBD9211",
                                "0x66bc87412a2d92a2829137ae9dd0ee063cd0f201", "0xb0f87621a43f50c3b7a0d9f58cc804f0cdc5c267",
                                "0x4a1c95097473c54619cb0e22c6913206b88b9a1a", "0x63b9713df102ea2b484196a95cdec5d8af278a60"};
                    }
                    else if (text.equalsIgnoreCase("Switch to ropsten")) {
                        EthNetworkType = "ropsten";
                        RTKContractAddresses = new String[]{"0x38332D8671961aE13d0BDe040d536eB336495eEA",
                                "0x9C72573A47b0d81Ef6048c320bF5563e1606A04C", "0x136A5c9B9965F3827fbB7A9e97E41232Df168B08",
                                "0xfB8C59fe95eB7e0a2fA067252661687df67d87b8", "0x99afe8FDEd0ef57845F126eEFa945d687CdC052d"};
                    } else if(shouldRunGame && text.equalsIgnoreCase("stopBot")) {
                        shouldRunGame = false;
                        Set<Long> keys = currentlyActiveGames.keySet();
                        for(long chat_Id : keys) {
                            currentlyActiveGames.get(chat_Id).setShouldContinueGame(false);
                        }
                        botNameDoc = new Document("botName", botName);
                        foundBotNameDoc = (Document) botControlCollection.find(botNameDoc).first();
                        Bson updatedAddyDoc = new Document("shouldRunGame", shouldRunGame);
                        Bson updateAddyDocOperation = new Document("$set", updatedAddyDoc);
                        botControlCollection.updateOne(foundBotNameDoc, updateAddyDocOperation);
                    }
                    else if(text.equalsIgnoreCase("StartServerSwitchProcess")) {
                        waitingToSwitchServers = true;
                        sendMessage(chatId, "From now, the bot won't accept new games or Ticket buy requests. Please use \"ActiveProcesses\" command " +
                                "to see how many games are active and then switch when there are no games active games and ticket buyers.");
                    } else if(text.equalsIgnoreCase("ActiveProcesses")) {
                        Set<Long> keys = currentlyActiveGames.keySet();
                        int count = 0;
                        for(long chat_Id : keys) {
                            if(currentlyActiveGames.get(chat_Id).isGameRunning) {
                                count++;
                            }
                        }
                        sendMessage(chatId, "Chats with Active Games : " + currentlyActiveGames.size() +
                                "\n\nChats with ongoing Round = " + count);
                    }
                    else if(text.toLowerCase().startsWith("setpot")) {
                        Set<Long> keys = currentlyActiveGames.keySet();
                        boolean isAnyGameRunning = false;
                        for(long key : keys) {
                            isAnyGameRunning = isAnyGameRunning || currentlyActiveGames.get(key).isGameRunning;
                        }
                        if(!isAnyGameRunning && !shouldRunGame) {
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
                    } else if(text.equalsIgnoreCase("getPot")) {
                        sendMessage(chatId, getTotalRTKForPoolInWallet());
                    }
                    else if(text.toLowerCase().startsWith("amountpulledoutfromfeesbalance")) {
                        Set<Long> keys = currentlyActiveGames.keySet();
                        boolean isAnyGameRunning = false;
                        for(long key : keys) {
                            isAnyGameRunning = isAnyGameRunning || currentlyActiveGames.get(key).isGameRunning;
                        }
                        if(!isAnyGameRunning && !shouldRunGame) {
                            try {
                                String amount = text.split(" ")[1];
                                if(amount.contains("-")) {
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
                    } else if(text.equalsIgnoreCase("getFeesBalance")) {
                        sendMessage(chatId, getWalletFeesBalance());
                    }
                    else if(text.equalsIgnoreCase("rebuildAdmins")) {
                        allAdmins = new ArrayList<>();
                        Document walletDetailDoc = new Document("identifier", "adminDetails");
                        Document foundWalletDetailDoc = (Document) walletDistributionCollection.find(walletDetailDoc).first();
                        assert foundWalletDetailDoc != null;
                        if (foundWalletDetailDoc.get("adminID") instanceof List) {
                            for(int i = 0; i < (((List<?>) foundWalletDetailDoc.get("adminID")).size()); i++) {
                                Object item = ((List<?>) foundWalletDetailDoc.get("adminID")).get(i);
                                if(item instanceof Long) {
                                    allAdmins.add((Long) item);
                                }
                            }
                        }
                    } else if(text.toLowerCase().startsWith("settopupwallet")) {
                        try {
                            Document document = new Document("identifier", "adminDetails");
                            Document foundDocument = (Document) walletDistributionCollection.find(document).first();
                            assert foundDocument != null;
                            topUpWalletAddress = text.split(" ")[1];
                            Bson updateDocument = new Document("topUpWalletAddress", topUpWalletAddress);
                            Bson updateDocumentOp = new Document("$set" , updateDocument);
                            walletDistributionCollection.updateOne(foundDocument, updateDocumentOp);
                        } catch (Exception e) {
                            sendMessage(chatId, "Invalid Format. Proper Format : setTopUpWallet walletAddress");
                        }
                    }

                    else if(text.equalsIgnoreCase("Commands")) {
                        sendMessage(update.getMessage().getChatId(), "Run\nSwitch to mainnet\nSwitch to ropsten\nStopBot\nStartServerSwitchProcess\n" +
                                "ActiveProcesses\nsetPot amount\ngetPot\namountPulledOutFromFeesBalance amount\ngetFeesBalance\nrebuildAdmins" +
                                "\nsetTopUpWallet walletAddress" +
                                "\nCommands\n\n(amount has to be bigInteger including 18 decimal eth precision)");
                    } else {
                        sendMessage(update.getMessage().getChatId(), "Such command does not exists. RETARD");
                    }
                    sendMessage(update.getMessage().getChatId(), "shouldRunGame = " + shouldRunGame + "\nEthNetworkType = " + EthNetworkType +
                            "\nRTKContractAddresses = " + Arrays.toString(RTKContractAddresses) + "\n" + "WaitingToSwitchServers = " + waitingToSwitchServers);
                }
                // Can add special operation for admin here
            }
        }
        if(!shouldRunGame) {
            sendMessage(update.getMessage().getChatId(), "Bot under maintenance.. Please try again later.");
            return;
        }

        if(update.hasMessage()) {
            long chat_id = update.getMessage().getChatId();
            String[] inputMsg = update.getMessage().getText().trim().split(" ");
            if(waitingToSwitchServers) {
                sendMessage(chat_id, "The bot is not accepting any commands at the moment. The bot will be changing the servers soon. So a buffer time has been " +
                        "provided to complete all active games and Ticket purchases. This won't take much long. Please expect a 15-30 minute delay. This process has to be" +
                        "done after every 15 days.");
                return;
            }
            switch (inputMsg[0]) {
                case "/startgame", "/startgame@Deadline_Chaser_Bot" -> {
                    SendMessage sendMessage = new SendMessage();
                    boolean shouldSend = true;
                    if (update.getMessage().getChat().isGroupChat() || update.getMessage().getChat().isSuperGroupChat()) {
                        if (currentlyActiveGames.containsKey(chat_id)) {
                            sendMessage.setText("A game is already running. Participate in the current game. Cannot start a new game.");
                        } else {
                            long testingChatId = -1001477389485L;
                            if (!(chat_id == -1001474429395L || chat_id == testingChatId)) {
                                sendMessage(chat_id, "This bot cannot be used in this group!! It is built for exclusive groups only.");
                                return;
                            }
                            Game newGame;
                            try {
                                sendMessage(chat_id, "Initiating a new Game!!!");
                                newGame = new Game(this, chat_id, EthNetworkType, shotWallet, RTKContractAddresses, shotCost);
                                currentlyActiveGames.put(chat_id, newGame);
                                executorService.execute(newGame);
                                SendAnimation sendAnimation = new SendAnimation();
                                sendAnimation.setAnimation("https://media.giphy.com/media/3ov9jYd9chmwCNQl8c/giphy.gif");
                                sendAnimation.setCaption("New game has been created.");
                                sendAnimation.setChatId(chat_id);
                                execute(sendAnimation);
                                shouldSend = false;
                            } catch (Exception e) {
                                e.printStackTrace();
                                shouldSend = false;
                            }
                        }
                    } else {
                        sendMessage.setText("This command can only be run in a group!!!");
                    }
                    sendMessage.setChatId(chat_id);
                    if (shouldSend) {
                        try {
                            execute(sendMessage);
                        } catch (TelegramApiException e) {
                            e.printStackTrace();
                        }
                    }
                }
                case "/rules", "/rules@Deadline_Chaser_Bot" -> {
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
        return "Deadline_Chaser_Bot";
    }

    @Override
    public String getBotToken() {
        return (System.getenv("deadlineChaserBotTokenA") + ":" + System.getenv("deadlineChaserBotTokenB"));
    }

    public void sendMessage(long chat_id, String msg, String... url) {
        if(url.length == 0) {
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
            sendAnimation.setAnimation(url[(int)(Math.random()*(url.length))]);
            sendAnimation.setCaption(msg);
            sendAnimation.setChatId(chat_id);
            try {
                execute(sendAnimation);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void deleteGame(long chat_id) {
        currentlyActiveGames.remove(chat_id);
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
        Bson updateWalletDoc = new Document("balanceCollectedAsFees", balance);
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
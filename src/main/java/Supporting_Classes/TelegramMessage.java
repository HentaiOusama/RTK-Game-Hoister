package Supporting_Classes;

import org.telegram.telegrambots.meta.api.methods.send.SendAnimation;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

public class TelegramMessage {
    public int sendStatus;
    public SendMessage sendMessage;
    public SendAnimation sendAnimation;
    public boolean isMessage, hasTransactionData;
    public TransactionData transactionData;

    public TelegramMessage(SendMessage sendMessage, int sendStatus) {
        this.sendMessage = sendMessage;
        this.sendStatus = sendStatus;
        isMessage = true;
        hasTransactionData = false;
    }

    public TelegramMessage(SendMessage sendMessage, int sendStatus, TransactionData transactionData) {
        this.sendMessage = sendMessage;
        this.sendStatus = sendStatus;
        this.transactionData = transactionData;
        isMessage = true;
        hasTransactionData = true;
    }

    public TelegramMessage(SendAnimation sendAnimation, int sendStatus) {
        this.sendAnimation = sendAnimation;
        this.sendStatus = sendStatus;
        isMessage = false;
        hasTransactionData = false;
    }

    public TelegramMessage(SendAnimation sendAnimation, int sendStatus, TransactionData transactionData) {
        this.sendAnimation = sendAnimation;
        this.sendStatus = sendStatus;
        this.transactionData = transactionData;
        isMessage = false;
        hasTransactionData = true;
    }
}

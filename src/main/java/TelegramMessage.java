import org.telegram.telegrambots.meta.api.methods.send.SendAnimation;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

public class TelegramMessage {
    int sendStatus;
    SendMessage sendMessage;
    SendAnimation sendAnimation;
    boolean isMessage, hasTransactionData;
    TransactionData transactionData;

    TelegramMessage(SendMessage sendMessage, int sendStatus) {
        this.sendMessage = sendMessage;
        this.sendStatus = sendStatus;
        isMessage = true;
        hasTransactionData = false;
    }

    TelegramMessage(SendMessage sendMessage, int sendStatus, TransactionData transactionData) {
        this.sendMessage = sendMessage;
        this.sendStatus = sendStatus;
        this.transactionData = transactionData;
        isMessage = true;
        hasTransactionData = true;
    }

    TelegramMessage(SendAnimation sendAnimation, int sendStatus) {
        this.sendAnimation = sendAnimation;
        this.sendStatus = sendStatus;
        isMessage = false;
        hasTransactionData = false;
    }

    TelegramMessage(SendAnimation sendAnimation, int sendStatus, TransactionData transactionData) {
        this.sendAnimation = sendAnimation;
        this.sendStatus = sendStatus;
        this.transactionData = transactionData;
        isMessage = false;
        hasTransactionData = true;
    }
}

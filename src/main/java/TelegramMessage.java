import org.telegram.telegrambots.meta.api.methods.send.SendAnimation;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

public class TelegramMessage {
    int sendStatus;
    SendMessage sendMessage;
    SendAnimation sendAnimation;
    boolean isMessage;

    TelegramMessage(SendMessage sendMessage, int sendStatus) {
        this.sendMessage = sendMessage;
        this.sendStatus = sendStatus;
        isMessage = true;
    }

    TelegramMessage(SendAnimation sendAnimation, int sendStatus) {
        this.sendAnimation = sendAnimation;
        this.sendStatus = sendStatus;
        isMessage = false;
    }
}

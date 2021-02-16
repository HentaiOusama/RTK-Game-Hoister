import java.io.Serializable;
import java.time.Instant;

public class LastGameState implements Serializable {
    TransactionData lastCheckedTransactionData;
    Instant lastGameEndTime;

    LastGameState(TransactionData lastCheckedTransactionData, Instant lastGameEndTime) {
        this.lastCheckedTransactionData = lastCheckedTransactionData;
        this.lastGameEndTime = lastGameEndTime;
    }
}

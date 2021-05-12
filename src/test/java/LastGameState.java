import Supporting_Classes.TransactionData;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;

public class LastGameState implements Serializable {
    TransactionData lastCheckedTransactionData;
    Instant lastGameEndTime;
    ArrayList<String> last3CountedHash;

    LastGameState(TransactionData lastCheckedTransactionData, Instant lastGameEndTime, ArrayList<String> last3CountedHash) {
        this.lastCheckedTransactionData = lastCheckedTransactionData;
        this.lastGameEndTime = lastGameEndTime;
        this.last3CountedHash = last3CountedHash;
    }
}

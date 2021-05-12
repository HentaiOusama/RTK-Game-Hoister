package Supporting_Classes;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;

public class LBH_LastGameState implements Serializable {
    public TransactionData lastCheckedTransactionData;
    public Instant lastGameEndTime;
    public ArrayList<String> last3CountedHash;

    public LBH_LastGameState(TransactionData lastCheckedTransactionData, Instant lastGameEndTime, ArrayList<String> last3CountedHash) {
        this.lastCheckedTransactionData = lastCheckedTransactionData;
        this.lastGameEndTime = lastGameEndTime;
        this.last3CountedHash = last3CountedHash;
    }
}

package Supporting_Classes;

import java.io.Serializable;
import java.util.ArrayList;

public class PSB_LastGameState implements Serializable {
    public TransactionData lastCheckedTransactionData;
    public ArrayList<String> last3CountedHash;

    public PSB_LastGameState(TransactionData lastCheckedTransactionData, ArrayList<String> last3CountedHash) {
        this.lastCheckedTransactionData = lastCheckedTransactionData;
        this.last3CountedHash = last3CountedHash;
    }
}

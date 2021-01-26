import java.math.BigInteger;

public class TransactionData implements Comparable<TransactionData>{
    String trxHash;
    String methodName;
    String fromAddress;
    String toAddress;
    BigInteger value;
    boolean didBurn;
    BigInteger blockNumber;
    BigInteger trxIndex;
    int X;  // X in RTKLX

    @Override
    public int compareTo(TransactionData o) {
        if(blockNumber.compareTo(o.blockNumber) != 0) {
            return blockNumber.compareTo(o.blockNumber);
        } else {
            return trxIndex.compareTo(o.trxIndex);
        }
    }

    public int compareBlock(BigInteger blk) {
        return blockNumber.compareTo(blk);
    }

    @Override
    public int hashCode() {
        return trxHash.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if(obj instanceof TransactionData) {
            return trxHash.equals(((TransactionData) obj).trxHash);
        } else {
            return false;
        }
    }

    @Override
    public String toString() {
        return "TrxHash : " + trxHash + ", from : " + fromAddress + ", to : " + toAddress + ", X : " + X + ", DidBurn : " + didBurn + ", Block : "
                + blockNumber + ", TrxIndex : " + trxIndex;
    }
}
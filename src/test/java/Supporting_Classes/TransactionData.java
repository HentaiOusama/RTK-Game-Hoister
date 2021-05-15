package Supporting_Classes;

import java.io.Serializable;
import java.math.BigInteger;
import java.time.Instant;

public class TransactionData implements Comparable<TransactionData>, Serializable {
    public String trxHash;
    public String methodName;
    public String fromAddress;
    public String toAddress;
    public BigInteger value;
    public boolean didBurn;
    public BigInteger blockNumber;
    public BigInteger trxIndex;
    public Instant blockTimeStamp;
    public int X;  // X in RTKLX
    public boolean containsBuildError = false;

    @Override
    public int compareTo(TransactionData o) {
        if (blockNumber.compareTo(o.blockNumber) != 0) {
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
        if (obj instanceof TransactionData) {
            return trxHash.equals(((TransactionData) obj).trxHash);
        } else {
            return false;
        }
    }

    @Override
    public String toString() {
        return "(Method : " + methodName + ")" + ", TrxHash : " + trxHash + ", from : " + fromAddress + ", to : " + toAddress + ", X : " + X +
                ", Value : " + value + ", DidBurn : " + didBurn + ", Block : " + blockNumber + ", TrxIndex : " + trxIndex + ", TimeStamp : " +
                blockTimeStamp + ", HasBuildError : " + containsBuildError;
    }
}
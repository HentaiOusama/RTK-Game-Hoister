import java.math.BigInteger;

public class TempTest2 {
    public static void main(String[] args) {
        TransactionData transactionData1 = new TransactionData(), transactionData2 = new TransactionData();
        transactionData1.blockNumber = new BigInteger("1000");
        transactionData2.blockNumber = new BigInteger("1000");
        transactionData1.trxIndex = new BigInteger("2");
        transactionData2.trxIndex = new BigInteger("2");
        System.out.println("Compare : " + transactionData2.compareTo(transactionData1));
    }
}

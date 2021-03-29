import java.io.*;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;

public class TempTest {
    public static void main(String[] args) throws IOException, ClassNotFoundException {
        System.out.println("Writing :-");
        FileOutputStream fileOutputStream = new FileOutputStream("./PreservedState.bps");
        ObjectOutputStream objectOutputStream = new ObjectOutputStream(fileOutputStream);
        TransactionData transactionData = new TransactionData();
        ArrayList<String> arrayList = new ArrayList<>();
        transactionData.trxHash = "0x733b7d574c2987fb1e23a80f6b088a818bcca8204d349487a9c59faaa7407c65";
        transactionData.X = 4;
        transactionData.toAddress = "0xdccf6ee3977903d541b47f31d5bfd3aed3511c62";
        transactionData.fromAddress = "0xeb5472754882c7f6662728daa655321f2d28f5ac";
        transactionData.didBurn = false;
        transactionData.blockNumber = new BigInteger("12610375");
        transactionData.trxIndex = new BigInteger("5");
        transactionData.value = new BigInteger("250000000000000000000");
        arrayList.add("Trx 1");
        arrayList.add("Trx 2");
        arrayList.add("Trx 3");
        arrayList.add("Trx 4");
        arrayList.add("Trx 5");
        LastGameState lastGameState = new LastGameState(transactionData, Instant.parse("2021-03-29T11:53:51.472155033Z"),
                arrayList);
        objectOutputStream.writeObject(lastGameState);
        objectOutputStream.close();
        fileOutputStream.close();

        System.out.println("Reading :-");
        FileInputStream fileInputStream = new FileInputStream("./PreservedState.bps");
        ObjectInputStream objectInputStream = new ObjectInputStream(fileInputStream);
        lastGameState = (LastGameState) objectInputStream.readObject();
        String msg = "\nPrevious State read :- \nTrxData -->";
        if(lastGameState.lastCheckedTransactionData != null) {
            msg += lastGameState.lastCheckedTransactionData.toString();
            msg += ", Last 3 Trx : " + lastGameState.last3CountedHash;
        } else {
            msg += "null";
        }
        msg += "\nEnd Time --> ";
        if(lastGameState.lastGameEndTime != null) {
            msg += lastGameState.lastGameEndTime.toString();
        } else {
            msg += "null";
        }
        System.out.println(msg);
    }
}

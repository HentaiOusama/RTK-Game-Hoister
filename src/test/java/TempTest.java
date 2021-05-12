import Supporting_Classes.LBH_LastGameState;
import Supporting_Classes.TransactionData;

import java.io.*;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;

public class TempTest {
    public static void main(String[] args) throws IOException, ClassNotFoundException {
        System.out.println(Instant.parse("null"));
    }

    public static void byteArrayMaker() {
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
        LBH_LastGameState LBHLastGameState = new LBH_LastGameState(transactionData, Instant.parse("2021-03-29T11:53:51.472155033Z"),
                arrayList);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutputStream out;
        try {
            out = new ObjectOutputStream(System.out);
            out.writeObject(LBHLastGameState);
            out.flush();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void writerFunction() throws IOException {
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
        LBH_LastGameState LBHLastGameState = new LBH_LastGameState(transactionData, Instant.parse("2021-03-29T11:53:51.472155033Z"),
                arrayList);
        objectOutputStream.writeObject(LBHLastGameState);
        objectOutputStream.close();
        fileOutputStream.close();
    }

    public static void readerFunction() throws IOException, ClassNotFoundException {
        System.out.println("Reading :-");
        FileInputStream fileInputStream = new FileInputStream("./PreservedState.bps");
        ObjectInputStream objectInputStream = new ObjectInputStream(fileInputStream);
        LBH_LastGameState LBHLastGameState = (LBH_LastGameState) objectInputStream.readObject();
        String msg = "\nPrevious State read :- \nTrxData -->";
        if(LBHLastGameState.lastCheckedTransactionData != null) {
            msg += LBHLastGameState.lastCheckedTransactionData.toString();
            msg += ", Last 3 Trx : " + LBHLastGameState.last3CountedHash;
        } else {
            msg += "null";
        }
        msg += "\nEnd Time --> ";
        if(LBHLastGameState.lastGameEndTime != null) {
            msg += LBHLastGameState.lastGameEndTime.toString();
        } else {
            msg += "null";
        }
        System.out.println(msg);
    }
}

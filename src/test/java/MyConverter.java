import org.web3j.exceptions.MessageDecodingException;

import java.math.BigInteger;

public class MyConverter {
    public static BigInteger getFilterId(String result) {
        return decodeQuantity(result);
    }

    public static BigInteger decodeQuantity(String value) {
        if (isLongValue(value)) {
            return BigInteger.valueOf(Long.parseLong(value));
        } else if (!isValidHexQuantity(value)) {
            throw new MessageDecodingException("Value must be in format 0x[1-9]+[0-9]* or 0x0");
        } else {
            try {
                return new BigInteger(value.substring(2), 16);
            } catch (NumberFormatException var2) {
                throw new MessageDecodingException("Negative ", var2);
            }
        }
    }

    private static boolean isLongValue(String value) {
        try {
            Long.parseLong(value);
            return true;
        } catch (NumberFormatException var2) {
            return false;
        }
    }

    private static boolean isValidHexQuantity(String value) {
        System.out.println("Input Quantity : " + value);
        if (value == null) {
            return false;
        } else if (value.length() < 3) {
            return false;
        } else {
            return value.startsWith("0x");
        }
    }
}

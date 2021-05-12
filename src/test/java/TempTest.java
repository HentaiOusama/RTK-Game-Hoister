import java.time.Duration;
import java.time.Instant;

public class TempTest {
    public static void main(String[] args) {
        System.out.println(Math.abs(Duration.between(Instant.now(), null).toSeconds()));
    }
}

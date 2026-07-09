import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class DecryptTest {
    public static void main(String[] args) throws Exception {
        String SECRET_KEY = "ChurchGenius2024";
        String token = "qZJuLSOwOzOVWb-SAIg2QaXv3H9NGxBiAHFq8vzxaIqIFBXRtd1NlMuwAZQpOlAQ";
        SecretKeySpec keySpec = new SecretKeySpec(SECRET_KEY.getBytes(StandardCharsets.UTF_8), "AES");
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, keySpec);
        byte[] decoded   = Base64.getUrlDecoder().decode(token);
        byte[] decrypted = cipher.doFinal(decoded);
        String result = new String(decrypted, StandardCharsets.UTF_8);
        System.out.println("Decrypted clientId: [" + result + "]");
        System.out.println("Length: " + result.length());
    }
}

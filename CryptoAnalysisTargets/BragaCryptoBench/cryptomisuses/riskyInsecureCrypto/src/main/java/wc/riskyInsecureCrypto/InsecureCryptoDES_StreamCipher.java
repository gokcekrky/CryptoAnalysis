package wc.riskyInsecureCrypto;

import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.security.spec.*;
import javax.crypto.*;
import javax.crypto.spec.*;

public final class InsecureCryptoDES_StreamCipher {

    public static void main(String[] a) {
        try {
            KeyGenerator kg = KeyGenerator.getInstance("DES","SunJCE");
            kg.init(56);
            byte[] iv = new byte[8];
            (new SecureRandom()).nextBytes(iv);
            Key k = kg.generateKey();
            byte[] msg = "alexmbraga".getBytes();
            
            Cipher cf = Cipher.getInstance("DES/CFB8/NoPadding","SunJCE");
            AlgorithmParameterSpec aps = new IvParameterSpec(iv);
            cf.init(Cipher.ENCRYPT_MODE, k, aps);
            byte[] ct = cf.doFinal(msg);
            cf.init(Cipher.DECRYPT_MODE, k, aps);
            byte[] pt = cf.doFinal(ct);
            System.out.println("Clear text : " + new String(pt));

            iv = new byte[8];
            (new SecureRandom()).nextBytes(iv);
            k = kg.generateKey();
            
            msg = "Alexandre Melo Braga1234".getBytes();
            
            cf = Cipher.getInstance("DES/CFB64/NoPadding","SunJCE");
            aps = new IvParameterSpec(iv);
            cf.init(Cipher.ENCRYPT_MODE, k, aps);
            ct = cf.doFinal(msg);
            cf.init(Cipher.DECRYPT_MODE, k, aps);
            pt = cf.doFinal(ct);
            System.out.println("Clear text : " + new String(pt));

            iv = new byte[8];
            (new SecureRandom()).nextBytes(iv);
            k = kg.generateKey();
            msg = "alexmbraga".getBytes();
            
            cf = Cipher.getInstance("DES/OFB8/NoPadding","SunJCE");
            aps = new IvParameterSpec(iv);
            cf.init(Cipher.ENCRYPT_MODE, k, aps);
            ct = cf.doFinal(msg);
            
            cf.init(Cipher.DECRYPT_MODE, k, aps);
            pt = cf.doFinal(ct);
            System.out.println("Clear text : " + new String(pt));

            iv = new byte[8];
            (new SecureRandom()).nextBytes(iv);
            k = kg.generateKey();
            msg = "Alexandre Melo Braga1234".getBytes();
            
            cf = Cipher.getInstance("DES/OFB64/NoPadding","SunJCE");
            aps = new IvParameterSpec(iv);
            cf.init(Cipher.ENCRYPT_MODE, k, aps);
            ct = cf.doFinal(msg);
            
            cf.init(Cipher.DECRYPT_MODE, k, aps);
            pt = cf.doFinal(ct);
            System.out.println("Clear text : " + new String(pt));
        } catch (NoSuchAlgorithmException | NoSuchPaddingException | 
                 InvalidKeyException | InvalidAlgorithmParameterException |
                 IllegalBlockSizeException | BadPaddingException |
                NoSuchProviderException e) {
            System.out.println(e);
        }
    }
}


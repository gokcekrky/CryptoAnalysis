package example;

import org.alexmbraga.utils.U;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import org.bouncycastle.util.Arrays;

/*
ECDSA signatures are randomized: each signature consists of two values (r; s): 
the value r is derived from an ephemeral public key kG generated using a random 
per-message nonce k, and a signature value s that depends on k. It is essential 
for the security of ECDSA that signers use unpredictable and distinct values for
k for every signature, since predictable or repeated values allow an adversary 
to eciently compute the long-term private key from one or two signature values.
*/

public final class RandomMessageNonceECDSA {

    public static void main(String[] args) throws Exception {

      ECGenParameterSpec ecps = new ECGenParameterSpec("secp256r1");
      U.println("EC parameters "+ecps.getName());
      
      KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC","SunEC");
      kpg.initialize(ecps); 
      
        KeyPair kpAna = kpg.generateKeyPair();

        SecureRandom sr1 = SecureRandom.getInstanceStrong();
        byte[] seed = sr1.generateSeed(24);
        sr1.setSeed(seed);
        
        Signature signer1 = Signature.getInstance("SHA256withECDSA","SunEC");
        signer1.initSign(kpAna.getPrivate(), sr1);
        byte[] doc = U.cancaoDoExilio.getBytes();
        signer1.update(doc);
        byte[] sign1 = signer1.sign();
        
        SecureRandom sr2 =  SecureRandom.getInstanceStrong();
        sr2.setSeed(seed);
        
        Signature signer2 = Signature.getInstance("SHA256withECDSA","SunEC");
        signer2.initSign(kpAna.getPrivate(), sr2);
        doc = U.cancaoDoExilio.getBytes();
        signer2.update(doc);
        byte[] sign2 = signer2.sign();

        boolean ok = Arrays.areEqual(sign1, sign2);
        if (ok) {
            System.out.println("Nonce repeated! Signatures are equal!");
        } else {
            System.out.println("Signatures are different!");
        }
    }
}
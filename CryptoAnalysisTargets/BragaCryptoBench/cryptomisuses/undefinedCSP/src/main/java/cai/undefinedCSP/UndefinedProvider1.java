package cai.undefinedCSP;

import java.security.KeyPairGenerator;
import java.security.Signature;

public final class UndefinedProvider1 {

    public static void main(String[] args) throws Exception {

        // par de chaves de Ana e configurações do criptosistema
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("DSA");
        System.out.println("KeyPairGenerator "+kpg.getProvider().getName());
        
        Signature signerAna = Signature.getInstance("SHA256WithDSA");
        System.out.println("Signer "+signerAna.getProvider().getName());
        
        // Beto configura seu criptosistema
        Signature verifierBeto = Signature.getInstance("SHA256WithDSA");
        System.out.println("Verifier "+verifierBeto.getProvider().getName());
        
    }
}

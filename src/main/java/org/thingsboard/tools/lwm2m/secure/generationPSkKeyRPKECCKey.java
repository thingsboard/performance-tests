/**
 * Copyright © 2016-2018 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.tools.lwm2m.secure;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.leshan.core.util.Hex;
import org.thingsboard.tools.lwm2m.client.LwM2MSecurityMode;

import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;

@Slf4j
public class generationPSkKeyRPKECCKey {

    private static final String ALGORITM = "EC";
    private static final String  PROVIDER = "SunEC";
    private static final String  NAME_PARAMETER_SPEC = "secp256r1";

    public generationPSkKeyRPKECCKey(Integer dtlsMode) {
        switch (LwM2MSecurityMode.fromSecurityMode(dtlsMode)) {
            case PSK:
                generationPSkKey();
                break;
            case RPK:
                generationRPKECCKey();
        }
    }

    private void generationPSkKey() {
        /** PSK */
        int lenPSkKey = 32;
        /** Start PSK
         * Clients and Servers MUST support PSK keys of up to 64 bytes in length, as required by [RFC7925]
         * SecureRandom object must be unpredictable, and all SecureRandom output sequences must be cryptographically strong, as described in [RFC4086]
         * */
        SecureRandom randomPSK = new SecureRandom();
        byte bytesPSK[] = new byte[lenPSkKey];
        randomPSK.nextBytes(bytesPSK);
        log.info("\nCreating new PSK: \n for the next start PSK -> security key: [{}]", Hex.encodeHexString(bytesPSK));
    }

    private void generationRPKECCKey() {

        /** Start RPK
         * Elliptic Curve parameters  : [secp256r1 [NIST P-256, X9.62 prime256v1] (1.2.840.10045.3.1.7)]
         * */
        KeyPairGenerator kpg = null;
        try {
            kpg = KeyPairGenerator.getInstance(ALGORITM, PROVIDER);
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        } catch (NoSuchProviderException e) {
            e.printStackTrace();
        }
        ECGenParameterSpec ecsp = new ECGenParameterSpec(NAME_PARAMETER_SPEC);
        try {
            kpg.initialize(ecsp);
        } catch (InvalidAlgorithmParameterException e) {
            e.printStackTrace();
        }

        KeyPair kp = kpg.genKeyPair();
        PrivateKey privKey = kp.getPrivate();
        PublicKey pubKey = kp.getPublic();

        if (pubKey instanceof ECPublicKey) {
            ECPublicKey ecPublicKey = (ECPublicKey) pubKey;
            /** Get x coordinate */
            byte[] x = ecPublicKey.getW().getAffineX().toByteArray();
            if (x[0] == 0)
                x = Arrays.copyOfRange(x, 1, x.length);

            /** Get Y coordinate */
            byte[] y = ecPublicKey.getW().getAffineY().toByteArray();
            if (y[0] == 0)
                y = Arrays.copyOfRange(y, 1, y.length);

            /** Get Curves params */
            String privHex = Hex.encodeHexString(privKey.getEncoded());
            log.info("\nCreating new RPK for the next start... \n" +
                    " Elliptic Curve parameters  : [{}] \n" +
                    " public_x :  [{}] \n" +
                    " public_y :  [{}] \n" +
                    " private_s : [{}] \n" +
                    " Public Key (Hex): [{}]\n" +
                    " Private Key (Hex): [{}]",
                    ecPublicKey.getParams().toString(),
                    Hex.encodeHexString(x),
                    Hex.encodeHexString(y),
                    privHex.substring(privHex.length() - 64),
                    Hex.encodeHexString(pubKey.getEncoded()),
                    Hex.encodeHexString(privKey.getEncoded()));
        }
    }
}


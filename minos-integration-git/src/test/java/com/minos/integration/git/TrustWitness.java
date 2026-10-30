package com.minos.integration.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Filtre {@code clean} de test : crée le fichier témoin passé en argument, puis recopie son entrée
 * standard. Lancé par {@code java -cp ... TrustWitness <témoin>}, il vaut sous {@code sh -c} comme
 * sous {@code cmd.exe /c} ; sa seule trace est le fichier témoin.
 */
public final class TrustWitness {

    private TrustWitness() {
    }

    public static void main(String[] arguments) throws IOException {
        Files.writeString(Path.of(arguments[0]), "executed", StandardCharsets.UTF_8);
        System.in.transferTo(System.out);
        System.out.flush();
    }
}

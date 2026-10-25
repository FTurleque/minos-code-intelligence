## Intégrité des fichiers

L'installateur et les exécutables de cette release sont **signés Authenticode**, avec horodatage.

Chaque fichier publié porte aussi une attestation de provenance GitHub, et sa somme SHA-256 est publiée à côté. Pour vérifier un téléchargement :

```
gh attestation verify <fichier> --repo {repository}
```

La vérification nomme le dépôt, le workflow et le commit qui ont produit le fichier. Elle prouve l'origine, pas l'innocuité.

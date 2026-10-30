## Intégrité des fichiers

Cette release n'est **pas signée Authenticode** : Windows SmartScreen peut avertir d'un « éditeur inconnu » au premier lancement. C'est un risque accepté (R-28 du registre des risques du dépôt).

Chaque fichier publié porte une attestation de provenance GitHub, et sa somme SHA-256 est publiée à côté. Pour vérifier un téléchargement :

```
gh attestation verify <fichier> --repo {repository}
```

La vérification nomme le dépôt, le workflow et le commit qui ont produit le fichier. Elle prouve l'origine, pas l'innocuité.

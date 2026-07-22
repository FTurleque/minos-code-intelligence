# M0 — Stratégie de validation CI de la PR #4

Date : 22 juillet 2026

Statut : **BLOQUÉE AVANT EXÉCUTION DU PREMIER STEP**

Suivi : issue GitHub #5

## Objectif

Obtenir une preuve CI exploitable sans attribuer au code Java un échec qui se
produit avant le démarrage du job.

La PR #4 reste Draft et ne doit pas être fusionnée tant que la stratégie
primaire ci-dessous n'est pas satisfaite ou qu'une dérogation explicite n'est
pas décidée par le propriétaire du dépôt.

## Observation de référence

Head : `55df7f14b3a1936f539bdefe4ca3ebb43b29afc8`

```text
workflow     M0 Java CI
run          #212
run id       29954498349
job          Java 24 / Maven Wrapper verify
job id       89040022858
status       completed
conclusion   failure
steps        null ; endpoint direct = []
logs_url     null
job logs     404 BlobNotFound
artifacts    []
```

Le workflow est suffisamment valide pour créer le run et le job portant le nom
déclaré. En revanche, même le step implicite `Set up job` n'apparaît pas. Le
runner n'a donc exécuté ni `actions/checkout`, ni `actions/setup-java`, ni
Maven.

Le 22 juillet 2026, la page officielle GitHub Status indique Actions
opérationnel et aucun incident du jour. Les incidents de démarrage des 9, 13 et
20 juillet sont résolus et ne suffisent pas à expliquer les échecs répétés du
dépôt.

## Ce que les preuves excluent actuellement

- un échec de compilation MINOS ;
- un échec des 27 tests JUnit ;
- une incompatibilité Java 24 ou Maven 3.9.16 pendant le job ;
- une erreur produite par `actions/checkout`, `setup-java` ou
  `upload-artifact`, puisque leurs steps n'ont pas démarré ;
- un diagnostic par ajout de steps supplémentaires : aucun step existant n'est
  atteint.

Modifier le code Java, ajouter du logging au YAML ou changer de JDK serait donc
une correction sans preuve.

## Causes à vérifier dans l'interface GitHub

Le connecteur et l'API ne remontent pas le bandeau de diagnostic de l'interface
du run. Les causes ci-dessous restent des hypothèses à vérifier, pas des causes
déclarées :

1. Actions désactivé ou restreint au niveau du dépôt ou du compte ;
2. politique interdisant les actions GitHub ou exigeant des SHA complets ;
3. quota de minutes, budget, moyen de paiement ou verrouillage du compte ;
4. restriction GitHub interne nécessitant le support.

GitHub documente que les permissions sont configurées dans
`Settings > Actions > General`. Une politique limitée peut bloquer les actions
`actions/*`, et une politique de pinning peut exiger des SHA complets. GitHub
documente également des quotas de minutes pour les dépôts privés.

## Vérification manuelle minimale

### 1. Ouvrir le run de référence ou le dernier run équivalent

```text
https://github.com/FTurleque/minos-code-intelligence/actions/runs/29954498349
```

Relever textuellement le bandeau ou l'annotation affiché au niveau du run ou du
job. Ne pas relancer avant d'avoir conservé ce message dans #5.

### 2. Vérifier la politique Actions

Dans le dépôt :

```text
Settings > Actions > General > Actions permissions
```

Vérifier :

- GitHub Actions activé ;
- actions créées par GitHub autorisées ;
- éventuelle exigence de pinning par SHA complet.

Si le pinning est exigé, la correction sera de remplacer les tags
`actions/checkout@v7`, `actions/setup-java@v5` et `actions/upload-artifact@v6`
par leurs SHA officiels vérifiés. Cette modification ne doit être faite qu'après
confirmation de la politique.

### 3. Vérifier usage et facturation

Dans les paramètres du compte propriétaire, vérifier l'usage GitHub Actions,
le budget, les minutes incluses et l'absence d'alerte de paiement. Les minutes
des dépôts privés sont imputées au propriétaire du dépôt.

### 4. Vérifier un éventuel verrouillage

Si GitHub affiche qu'Actions est désactivé pour le compte indépendamment des
réglages du dépôt, la documentation officielle demande de contacter GitHub
Support.

## Stratégie primaire de validation

La porte CI M0 est satisfaite seulement lorsqu'un run du head courant ou d'un
descendant strict remplit :

```text
job.steps non vide
logs téléchargeables
checkout success
setup Java 24 success
Maven Wrapper verify success
27 tests ou total ultérieur success
artefact de diagnostic publié ou absence justifiée car tout est vert
```

Après obtention de cette preuve :

1. mettre #5 à jour avec run, job, commit et résultats ;
2. fermer #5 uniquement si le comportement est compris ou durablement corrigé ;
3. réévaluer le statut Draft de la PR #4 ;
4. ne fusionner qu'après revue explicite.

## Stratégie de repli

Le build local reproductible reste une preuve technique, pas une validation
GitHub Actions :

```text
Windows 10
OpenJDK 24.0.1
Maven Wrapper 3.3.4
Maven 3.9.16
35 sources main
14 sources test
27 tests réussis
```

Une fusion fondée uniquement sur cette preuve constituerait une dérogation.
Elle exige une décision explicite du propriétaire dans #5 et dans la PR #4 ;
elle ne doit jamais être appliquée automatiquement par le runner ou par MINOS.

## Sources officielles

- paramètres Actions :
  <https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/enabling-features-for-your-repository/managing-github-actions-settings-for-a-repository> ;
- facturation et minutes Actions :
  <https://docs.github.com/en/billing/concepts/product-billing/github-actions> ;
- endpoint des logs de job :
  <https://docs.github.com/en/rest/actions/workflow-jobs> ;
- état du service : <https://www.githubstatus.com/>.

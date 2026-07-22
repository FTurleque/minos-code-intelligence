# M0 — Stratégie de validation CI de la PR #4

Date : 22 juillet 2026

Statut : **CI LOCALE MANUELLE — GITHUB ACTIONS EN PAUSE**

Suivi de l'anomalie historique : issue GitHub #5

## Décision

La validation de MINOS est locale et déclenchée manuellement jusqu'à nouvelle
décision explicite du propriétaire du dépôt.

Le workflow `.github/workflows/m0-java-ci.yml` n'écoute plus les événements
`push` ou `pull_request`. Il conserve uniquement `workflow_dispatch` afin de ne
jamais consommer GitHub Actions ni créer un run à la suite d'un push. Sa
présence ne constitue pas une preuve CI et il ne doit pas être lancé sans
demande explicite.

La PR #4 reste Draft. La politique de validation manuelle ne donne aucune
autorisation implicite de la fusionner ou de démarrer M1.

## Porte locale normative

Depuis un commit propre de la branche à valider :

```powershell
.\scripts\m0\validate-local-ci.ps1
```

Le runner vérifie et conserve :

```text
worktree Git propre
branche et commit exacts
Java 24
Maven Wrapper / Maven 3.9.16
mvnw.cmd clean verify
mvnw.cmd -f fixtures/java/java-24-smoke/pom.xml clean verify
codes de sortie, durées et logs
```

Les preuves sont créées transactionnellement sous :

```text
.minos-m0/validation/manual-ci/<date>-<commit>/
  environment.txt
  minos-clean-verify.txt
  java-24-smoke-clean-verify.txt
  result.txt
```

`latest.txt` pointe vers le dernier run. Ces fichiers restent locaux et sont
ignorés par Git. `-AllowDirty` existe uniquement pour un diagnostic de travail :
un tel run ne vaut pas validation d'un commit livrable.

## Observation GitHub Actions conservée

Head de référence : `55df7f14b3a1936f539bdefe4ca3ebb43b29afc8`

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

Le workflow était suffisamment valide pour créer le run et le job portant le
nom déclaré. Même le step implicite `Set up job` n'apparaissait pas. Le runner
n'avait donc exécuté ni `actions/checkout`, ni `actions/setup-java`, ni Maven.

Ces preuves excluent un échec de compilation MINOS, des tests JUnit, de Java 24
ou de Maven pendant le job. Le problème reste une anomalie d'infrastructure
distincte, suivie dans #5 et mise en pause.

## Réactivation éventuelle de GitHub Actions

La réactivation n'est pas une tâche active. Elle exige une demande explicite.
À ce moment-là seulement :

1. relever le bandeau exact du dernier run dans l'interface GitHub ;
2. vérifier `Settings > Actions > General`, les politiques d'actions et de
   pinning par SHA ;
3. vérifier les minutes, budgets et alertes de facturation du compte ;
4. contacter GitHub Support si Actions reste désactivé indépendamment des
   réglages du dépôt ;
5. déclencher manuellement `workflow_dispatch`, jamais un déclenchement
   automatique.

Un futur run GitHub exploitable devra fournir :

```text
job.steps non vide
logs téléchargeables
checkout success
setup Java 24 success
Maven Wrapper verify success
27 tests ou total ultérieur success
```

Après obtention de cette preuve, mettre #5 à jour et réévaluer séparément son
statut. La revue, la sortie de Draft et la fusion de la PR #4 restent des
actions distinctes qui exigent une demande explicite.

## Preuve locale déjà acquise

Avant la formalisation du runner manuel, la validation locale avait fourni :

```text
Windows 10
OpenJDK 24.0.1
Maven Wrapper 3.3.4
Maven 3.9.16
35 sources main
14 sources test
27 tests réussis
```

Cette preuve ne doit jamais être présentée comme une validation GitHub
Actions. Le runner manuel la rend désormais reproductible et rattachable à un
commit propre.

## Sources officielles conservées pour une reprise

- paramètres Actions :
  <https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/enabling-features-for-your-repository/managing-github-actions-settings-for-a-repository> ;
- facturation et minutes Actions :
  <https://docs.github.com/en/billing/concepts/product-billing/github-actions> ;
- endpoint des logs de job :
  <https://docs.github.com/en/rest/actions/workflow-jobs> ;
- état du service : <https://www.githubstatus.com/>.

# Décision M12 — Multi-dépôts et intelligence Git

Statut : **VERDICT PRÉPARÉ — porte locale finale à acquérir**

Issue : #35. PR : #36.

## Question

> MINOS peut-il raisonner factuellement sur plusieurs dépôts d’un même workspace et enrichir la Code Intelligence avec l’historique Git, sans inventer de relations inter-dépôts ni confondre activité Git et importance architecturale ?

## Verdict préparé

> **OUI, à condition de séparer strictement les faits Git des signaux architecturaux et de ne promouvoir une relation cross-repository que sur une identité fournisseur exacte, unique et traçable.**

## Raisons

### Multi-repository

M12 réutilise les workspaces persistants introduits en M1. Il ne crée pas un second modèle de regroupement de projets.

La résolution cross-repository est une vue dérivée des snapshots actifs. Une relation non résolue n’est reliée à un autre projet que lorsque le couple `providerId + externalId` correspond exactement à une référence fournisseur d’un symbole local et qu’une seule cible existe.

Le nom et le `qualifiedName` ne sont pas utilisés comme preuve de résolution.

### Git

L’historique est lu localement avec JGit. M12 mesure uniquement des faits observés : commits, chemins modifiés, auteurs distincts, dernière modification et agrégats de zones.

Une forte fréquence de modification n’implique ni criticité, ni centralité, ni importance métier.

### Bornes et honnêteté

Les fenêtres et volumes sont bornés. Les clones shallow, HEAD détachées, historiques tronqués et projets sans snapshot sont explicitement signalés.

## Critères d’acceptation

- [x] workspaces M1 exposés sans duplication de modèle ;
- [x] résolution cross-repository exacte et unique ;
- [x] absence de résolution name-only ;
- [x] métriques Git factuelles et bornées ;
- [x] lecture Git Java pure ;
- [x] limitations explicites ;
- [x] contrat public M12 sans fuite JGit/interne ;
- [x] tests Git synthétiques ;
- [x] test cross-repository ;
- [x] test d’intégration API ;
- [ ] `./mvnw clean verify` sur le head exact final sous Java 24 ;
- [ ] passage Ready et fusion après autorisation explicite.

## Porte finale attendue

```text
158 sources main
83 sources test
221/221 tests PASS
BUILD SUCCESS
```

Le verdict ne devient définitif qu’après acquisition de cette porte sur le head exact final.

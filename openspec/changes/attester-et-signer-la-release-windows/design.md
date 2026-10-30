# Design

## Context

Sprint 5 de l'audit du 10 octobre 2026 : **AUD-DEP-06**. HEAD analysé : `2d1cc214` (`develop`). Dépôt public (`gh repo view --json visibility`).

## État vérifié au HEAD

### AUD-DEP-06 — confirmé

- Aucune occurrence de `sign-windows-artifact`, `attest-build-provenance`, `id-token` ni `attestations` dans `.github/workflows/`.
- `MINOS_REQUIRE_SIGNED_RELEASE` : lu par `scripts/history/m21/run-s5.ps1:90,92` seulement ; cité par `docs/developer/supply-chain.md:87`.
- `docs/developer/supply-chain.md:97` cite `.\scripts\m21\run-s5.ps1`, chemin inexistant (le script est sous `scripts/history/m21/`).
- `release-windows.yml:173-175` et `:249-251` : publication de l'installateur et du zip avec leurs `.sha256`. `:185-198` : zip « lite », SBOM « lite » et notices sont publiés aussi.
- **Entrées de `scripts/release/sign-windows-artifact.ps1`** : `-Artifact`, `-CertificateThumbprint` (40 caractères hexadécimaux, passé à `signtool /sha1` : le certificat doit donc **déjà se trouver dans un magasin de la machine**), `-TimestampUrl` (facultatif), `-SignToolPath`. Le script ne lit aucune variable d'environnement, aucun fichier PFX, aucun secret.
- Aucun ADR ne traite de signature ni de provenance (l'ADR 0040 traite des empreintes des outils embarqués).

**Non vérifié** : l'existence d'un certificat de signature de code au nom du propriétaire ; aucun workflow exécuté.

### Écarts avec l'action proposée par l'audit

1. **Le script de signature n'est pas branchable tel quel sur un runner hébergé.** Il suppose un certificat installé dans un magasin de la machine. Les certificats de signature de code sont aujourd'hui délivrés sur matériel ou par un service (clé non exportable) : un runner GitHub n'en a pas. « Brancher `sign-windows-artifact.ps1` » exige d'abord de choisir un **mode** de signature (D2).
2. **Un horodatage facultatif produit une signature qui expire avec le certificat.** Quand la signature est exigée, l'horodatage l'est aussi.
3. **L'ordre compte.** Signer modifie le fichier : la signature doit précéder le calcul des `.sha256`, du manifeste de release et la qualification (`-ValidateOnly`), sinon les sommes publiées sont fausses.
4. **L'action oublie des artefacts** : zip « lite », SBOM « lite », notices, et l'archive du plugin IntelliJ (publiée par le changement `publier-le-plugin-avec-la-release`). L'attestation les couvre tous.
5. **Les exécutables à l'intérieur de l'installateur et du zip** (`minos.exe` produit par `jpackage`, lanceurs) ne sont pas mentionnés : signer seulement l'installateur laisse le contenu extrait non signé. À inclure quand la signature est active.
6. **Permissions.** `id-token: write` et `attestations: write` vont dans le job `publish` seulement, pas au niveau du workflow.

## Goals / Non-Goals

**Goals** : un utilisateur peut vérifier qu'un artefact vient de ce dépôt, de ce workflow et de ce commit ; la signature, quand elle est exigée, ne peut pas être sautée en silence ; l'état réel (signé ou non) est écrit dans la release.

**Non-Goals** : fournir un certificat ; garantir l'absence d'avertissement SmartScreen ; signer des images Docker.

## Decisions

### D1. Attestation de provenance d'abord, indépendante de la signature

Le job `publish` appelle `actions/attest-build-provenance` (épinglée par SHA) sur tous les artefacts qu'il publie, avec `id-token: write` et `attestations: write` limités à ce job. La documentation donne la commande de vérification (`gh attestation verify <fichier> --repo <dépôt>`). C'est une preuve d'origine indépendante de l'hébergement des sommes, sans coût ni secret.

Livrable **seule** : le lot 1 des tâches ne dépend pas de D2.

### D2. Mode de signature — **tranchée le 2026-10-24 : option C, définitive jusqu'à révision du risque**

Décision du propriétaire : la release n'est pas signée. Aucun service de signature n'est accessible à un mainteneur individuel établi en France : Azure Artifact Signing n'est ouvert qu'aux particuliers des États-Unis et du Canada et aux organisations, SignPath Foundation exige une licence OSI pour tout le projet, et un certificat OV demande un module matériel (option B). Le risque est accepté au registre (R-28 de `docs/architecture/risks/register.md`), avec ses conditions de révision : création d'une structure juridique dans l'Union européenne, utilisateurs bloqués par SmartScreen, au plus tard le 2027-10-24. L'attestation de provenance D1 et les sommes SHA-256 publiées sont la preuve d'origine ; le texte de la release le dit (`Get-ReleaseIntegrityNotes`, `scripts/release/release-integrity-notes-unsigned.md`). Le garde-fou D3 reste en place : à la révision, brancher un mode sera l'ajout de ce mode à `$script:MinosSigningModes` de `scripts/release/release-signature.ps1`, avec son ADR, puis un réglage de variable.

**Écart d'implémentation.** Le test du garde-fou est un script PowerShell (`scripts/release/test-release-signature.ps1`, sur le modèle de `test-iscc-provenance.ps1`) et non `test_release_signature_gate.py` : les fonctions du garde-fou se chargent sans effet de bord et la signature est décrite par un lecteur injecté, ce qui les éprouve aussi sous Linux ; un cas lit la vraie signature d'un fichier non signé sous Windows. La qualification recalculait **déjà** les sommes dans les deux modes (`Verify-Sha256`, `publish-windows-release.ps1`) : le test exécute cette fonction réelle, extraite par l'analyseur PowerShell, au lieu d'en ajouter une seconde.

- **Option A : service de signature** (Azure Trusted Signing ou équivalent), appelé depuis le job `build` par une identité fédérée (OIDC), sans secret de longue durée. Demande un compte, une validation d'identité et un ADR.
- **Option B : certificat sur matériel, runner auto-hébergé.** `sign-windows-artifact.ps1` fonctionne alors tel quel ; demande d'exploiter un runner de confiance.
- **Option C (retenue) : pas de signature.** La release reste non signée et **le dit** (D4) ; l'attestation D1 est la seule preuve d'origine. Le garde-fou fail-closed (D3) est livré quand même, pour que l'activation future soit un réglage et non un chantier.

### D3. Garde-fou fail-closed

Une variable de dépôt `MINOS_REQUIRE_SIGNED_RELEASE` (valeur `1` : exigée) est lue par le workflow de release. Exigée : le job échoue si le mode de signature n'est pas configuré, si un exécutable publié (installateur, exécutables qu'il contient) n'a pas une signature Authenticode valide **et horodatée** (`Get-AuthenticodeSignature`, statut `Valid`, horodatage présent), ou si la vérification ne peut pas être faite. Non exigée : aucune signature n'est tentée à moitié ; l'état « non signé » est enregistré.

Un test Windows (extraction des définitions réelles, modèle `scripts/lib/test_minos_exit_code.py`) couvre la fonction de contrôle : fichier non signé + exigence → échec ; signature de test auto-signée sans horodatage + exigence → échec ; exigence absente → succès avec état « non signé ».

### D4. L'état de signature est écrit, jamais présumé

Le manifeste de release et les notes de version portent l'état : signé (émetteur, horodatage) ou non signé. La documentation utilisateur ne dit pas « installateur signé » tant que ce n'est pas vrai (capability-honest) ; `supply-chain.md` est corrigé (chemin du script, état réel).

### D5. Signature, puis sommes, puis qualification

`build-windows-distribution.ps1` et `publish-windows-release.ps1` sont ordonnés : exécutables internes signés avant l'assemblage, installateur signé après sa construction, **puis** calcul des `.sha256` et du manifeste, puis qualification `-ValidateOnly` sur les fichiers finals. Un contrôle de la qualification recalcule les sommes des fichiers publiés et les compare aux `.sha256`.

### D6. Un gate statique

`check-ci-wiring.py` (ou le gate des workflows) exige que le job qui publie la release contienne l'étape d'attestation et les deux permissions, et que `supply-chain.md` ne cite aucun chemin de script inexistant.

## Direction des dépendances

Sans objet (aucun module).

## Qualification des capacités

- Provenance attestée : **non qualifiée** au HEAD ; qualifiée à la première release (tâche manuelle).
- Installateur signé : **non supportée** au HEAD et tant que D2 = C ; l'annoncer ainsi.

## Windows et Linux

La signature et son contrôle sont propres à Windows (`signtool`, `Get-AuthenticodeSignature`) : le test se déclare sauté sous Linux, avec sa raison, et s'exécute dans `Verify (windows-2022)`. L'attestation s'exécute sur le runner du job `publish`.

## Risks / Trade-offs

- Une attestation prouve l'origine, pas l'innocuité : à dire dans la documentation.
- Rendre la signature exigée sans mode configuré bloque toute release : c'est l'effet voulu, et la variable reste absente par défaut.
- Les workflows de release ne tournent pas sur une PR : preuve réelle au prochain candidat.
- Même fichier que `publier-le-plugin-avec-la-release` : fusion en série.

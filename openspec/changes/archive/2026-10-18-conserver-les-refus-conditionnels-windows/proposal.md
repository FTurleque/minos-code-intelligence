# Proposal

## Why

**AUD-SEC-06** (faible, sprint 8 de l'audit du 10 octobre 2026 ; reprise de S16 ; bloque AUD-ARC-05). Sous Windows, `PrivateLocalStorage` durcit un emplacement privé en relisant sa DACL par Java puis en la réécrivant (`minos-engine/src/main/java/com/minos/io/PrivateLocalStorage.java:381`, `:390`). Les refus ordinaires sont conservés (`:413`), mais Java ne voit pas les entrées **conditionnelles** : la réécriture efface donc un refus conditionnel (XD) posé par un administrateur. La limite est écrite dans la Javadoc (`:71-72` : « the rewrite drops it »). C'est contraire à la règle établie pour les refus ordinaires : ne jamais lever une restriction d'administrateur.

Second volet : deux composants décident des droits. Le backend AppContainer lance `icacls` lui-même (`WindowsAppContainerWorkerSandboxBackend.java:620`, `:703`, par `runIcacls` en `:720-727`), sans délai et sans vérifier que `SystemRoot` est défini, alors que `PrivateLocalStorage` fait les deux (`:630-640`). Et quand les deux ne s'accordent pas, le durcissement suivant « emporte les octrois du bac à sable » (commentaire du backend, `:611-615`).

Le comportement réel d'un refus conditionnel pendant le durcissement n'a **pas été mesuré** : aucun test ne le couvre au HEAD `949e1569`.

## What Changes

- **Un refus conditionnel n'est plus retiré** : le durcissement lit la DACL complète et, en présence d'un refus conditionnel, le conserve ou échoue avant d'écrire. Une autorisation conditionnelle héritée continue d'être retirée (test existant).
- **Les octrois Java du bac à sable** passent par une primitive unique, additive, à identité unique, avec délai et répertoire Windows vérifié.
- **La limite documentée** dans la Javadoc de `PrivateLocalStorage` est remplacée par le comportement garanti.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `stockage-prive-et-secrets` : deux exigences ajoutées (un refus conditionnel n'est jamais retiré par le durcissement ; un octroi d'ACL du bac à sable est additif, borné et fail-closed).

## Hors périmètre

- **Retirer le lancement de processus de `minos-engine`** (AUD-ARC-05, sprint 10) : ce changement n'ajoute aucune classe lanceuse à `minos-engine` et place la primitive d'octroi là où AUD-ARC-05 n'aura pas à la déplacer.
- Les octrois faits **en PowerShell** par le lanceur du bac à sable (`windows-appcontainer-sandbox-v4.ps1.template:474,482,488`) : déjà additifs et regroupés en trois fonctions ; ils ne peuvent pas appeler une primitive Java.
- La règle de **registre** de `windows-fragments/appcontainer-private-storage.ps1frag:35` (ce n'est pas une ACE de fichier).
- Le plugin IntelliJ (`MinosStrongProcessLauncher.java:161`, `setAcl` à entrée unique) : hors reactor, sans accès aux primitives.
- `scripts/install/configure-runtime-settings.ps1:74` : la coupure d'héritage du fichier de secret est une exigence existante de la capacité.
- Linux : aucune ACL conditionnelle ; comportement inchangé.

## Impact

- **Modules du reactor** : `minos-engine` (`com.minos.io.PrivateLocalStorage`), `minos-runtime-local` (`sandbox/WindowsAppContainerWorkerSandboxBackend` et une classe d'octroi nouvelle du même package). Aucune dépendance entre modules ajoutée (ADR 0022).
- **Surfaces publiques** : une erreur d'usage nouvelle peut apparaître à la CLI et au MCP quand un emplacement privé porte un refus conditionnel (selon D3) ; message actionnable, sans chemin absolu. Aucun champ ni option ne change.
- **ADR** : aucun si la lecture se fait par `icacls` ; un ADR nouveau (prochain numéro libre de `docs/architecture/adr/README.md`) si l'appel natif FFM est retenu (D2), car il impose `--enable-native-access` à tous les lanceurs.
- **Gates à rejouer** : `python scripts/remediation/check-p0-p2.py` (`:139` lit `PrivateLocalStorage.java`), `python scripts/remediation/check-mne.py` (`:146`), `python scripts/remediation/check-mnd.py` (`:77`), `python scripts/remediation/check-post-mne.py` (`:144`), `python scripts/remediation/check-minos-01.py` (`:151`), `python scripts/remediation/check-post228-hardening.py` (`:42-53`), `python scripts/architecture/check-private-io.py`, `python scripts/architecture/check-module-boundaries.py` ; scope JaCoCo `provider-sandbox-windows` (`scripts/quality/check-jacoco.py`).
- **Plateformes** : Windows seul pour le comportement ; sous Linux les tests concernés comptent comme sautés.

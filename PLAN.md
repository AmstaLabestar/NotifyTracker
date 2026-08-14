# PLAN.md — Refacto NotifTracker

Plan de refonte complète. Mis à jour **à chaque étape** : cocher les cases,
compléter le journal en bas. Voir [CLAUDE.md](CLAUDE.md) pour l'architecture et les conventions.

**Statut global :** 🟢 Phase 0 en cours (build OK) → prochaine : lecture audio à valider sur appareil
**Dernière mise à jour :** 2026-08-14

---

## Motivation

Trois problèmes remontés par l'utilisateur :

1. **La lecture audio ne fonctionne pas.**
2. **L'UI / UX est minimale.**
3. Besoin d'une base saine pour ajouter la capture **photo**.

Cause probable du point 1 (à confirmer en Phase 0) : sur API 26–28 le constructeur
`FileObserver(File, mask)` (API 29+) lève `NoSuchMethodError`, donc `AudioObserver`
plante au démarrage et **aucun vocal n'est copié** → rien à lire. Sur API 29+, vérifier
que la copie se déclenche et que `MediaPlayer` lit bien les `.opus`.

## Architecture cible

```
com.notiftracker/
├── data/
│   ├── entity/      MessageEntity, MediaEntity (audio/photo/video)
│   ├── dao/         MessageDao, MediaDao (renvoient des Flow)
│   ├── AppDatabase  (Room, version 3, migrations réelles)
│   └── TrackerRepository
├── service/
│   ├── NotificationService   (listener : texte + onNotificationRemoved)
│   └── MediaCaptureService   (foreground service : héberge les observers)
├── capture/
│   ├── MediaObserver         (compat API 26+ ; audio + images + vidéo)
│   └── DeletionTracker       (corrélation suppression ↔ message)
├── ui/
│   ├── MainActivity          (bottom nav)
│   ├── messages/  (fragment + adapter : timeline texte)
│   ├── media/     (fragment + adapter : lecteur audio inline + vignettes)
│   └── settings/  (permissions, diagnostic, export)
└── util/
```

Principes : une seule `AppDatabase`, DAO renvoyant des `Flow` (UI réactive), aucun
`Thread.sleep` sur les threads de callback, foreground service pour la fiabilité,
médias reliés à leur conversation quand c'est possible.

---

## Phase 0 — Fiabilité & bugs bloquants  🔴 priorité

Objectif : que l'audio se copie ET se lise, sans crash, sur tout minSdk 26+.

- [x] Diagnostic : appareil API 29+ → le crash FileObserver n'est pas la cause. Cause probable retenue : `FileObserver` ne reçoit pas de façon fiable les écritures d'une autre app sur stockage scoped (Android 11+), donc les vocaux ne sont jamais copiés. Corrigé par un scan périodique.
- [x] Wrapper compat `FileObserver` : constructeur `File` (API 29+) sinon `String` (API < 29) — `AudioObserver.watchDirectory`
- [x] Suppression du `Thread.sleep(500)` : stabilité du fichier via l'âge de `lastModified` + scan périodique (3 s) hors thread de callback
- [x] `MediaCaptureService` foreground (notification persistante `dataSync`) héberge la surveillance ; démarré depuis `NotificationService.onCreate` et `MainActivity`
- [x] `AudioActivity` : `setOnErrorListener` sur `MediaPlayer`, message d'erreur localisé, reset de la position
- [x] `AudioActivity` : libération du player en `onPause`
- [x] Build `assembleDebug` OK
- [ ] **À valider sur appareil réel** : les vocaux se copient bien et se lisent (test manuel WhatsApp)

## Phase 1 — Couche données unifiée

- [ ] `MediaEntity` (type, chemin, taille, timestamp, conversation, lien message)
- [ ] Fusionner en une seule `AppDatabase` (version 3) avec `MessageDao` + `MediaDao`
- [ ] DAO en `Flow` ; supprimer les `getAll()` one-shot
- [ ] `TrackerRepository` comme point d'accès unique
- [ ] Migration Room réelle (ne plus jeter les données) OU documenter la migration destructive assumée

## Phase 2 — Capture améliorée

- [ ] `onNotificationRemoved` → vraie détection de suppression / disparition
- [ ] Corréler médias ↔ conversation (par fenêtre temporelle avec les notifs)
- [ ] Capture **photo** : observer `WhatsApp Images` / `WhatsApp Video` (mêmes limites : ne marche que si l'auto-téléchargement WhatsApp est actif)
- [ ] Copier les médias dans Room + dossier privé avec métadonnées

## Phase 3 — Refonte UI / UX

- [ ] `MainActivity` avec bottom navigation (Messages · Médias · Réglages)
- [ ] Timeline messages : regroupement par conversation, badge « supprimé »
- [ ] Écran Médias : vignettes photo, lecteur audio **inline** avec seekbar + durée + état lecture/pause fiable
- [ ] Thème Material 3 propre (palette cohérente, dark mode, couleur dynamique si dispo)
- [ ] Écrans d'onboarding permissions (au lieu de demander `MANAGE_EXTERNAL_STORAGE` d'office)
- [ ] États vides et messages d'erreur soignés

## Phase 4 — Finitions

- [ ] Diagnostic permissions clair et actionnable
- [ ] Export (texte + éventuellement médias) amélioré
- [ ] Passe `./gradlew lint` propre
- [ ] Mise à jour finale de [CLAUDE.md](CLAUDE.md)

---

## Journal des étapes

> Une ligne par étape réalisée : date · phase · ce qui a changé · fichiers touchés.

- 2026-08-14 · Init · Création de `PLAN.md` et `CLAUDE.md`, audit complet du code existant.
- 2026-08-14 · Phase 0 · Fiabilisation capture + lecture audio. Fichiers : `AudioObserver.kt` (réécrit : compat FileObserver + scan périodique + dédup, sans `Thread.sleep`), `MediaCaptureService.kt` (nouveau foreground service), `NotificationService.kt` (démarre le service), `MainActivity.kt` (démarre le service + permission notifications), `AudioActivity.kt` (error listener + release onPause), `AndroidManifest.xml` (service + permissions FGS/notifications), `strings.xml` (libellés service + erreur). Build `assembleDebug` validé.

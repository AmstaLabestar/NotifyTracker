# PLAN.md — Refacto NotifTracker

Plan de refonte complète. Mis à jour **à chaque étape** : cocher les cases,
compléter le journal en bas. Voir [CLAUDE.md](CLAUDE.md) pour l'architecture et les conventions.

**Statut global :** 🟢 Phases 0-3 OK + release auto → prochaine : Phase 4 (finitions)
**Dernière mise à jour :** 2026-08-14

**Livraison continue :** chaque push met à jour la release GitHub « latest ».
APK toujours téléchargeable ici →
`https://github.com/AmstaLabestar/NotifyTracker/releases/download/latest/NotifTracker-latest.apk`

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

## Phase 1 — Couche données unifiée  ✅

- [x] `MediaEntity` + enum `MediaType` (type, chemin, taille, timestamp, conversation, sender, lien message) — `data/MediaEntity.kt`
- [x] `AppDatabase` (version 3) avec `MessageDao` + `MediaDao` + `Converters` — `data/AppDatabase.kt` (remplace `MessageDatabase`)
- [x] DAO en `Flow` (`observeAll`, `observeByType`) ; `MainActivity` observe via `repeatOnLifecycle`, plus de `getAll()` one-shot
- [x] `TrackerRepository` = point d'accès unique — `data/TrackerRepository.kt`
- [x] `Message` déplacé dans le package `com.notiftracker.data`
- [x] Migration destructive assumée et documentée (app perso) — cf. commentaire dans `AppDatabase`
- [x] Build `assembleDebug` OK
- [ ] Peupler `media` en base (repoussé en Phase 2, avec la capture photo + corrélation)

## Phase 2 — Capture améliorée  ✅

- [x] Capture **photo + vidéo** : `MediaObserver` observe `WhatsApp Images` / `WhatsApp Video` (+ vocaux), pour WhatsApp et WhatsApp Business. Limite connue : ne marche que si l'auto-téléchargement WhatsApp est actif (sinon le fichier n'atterrit jamais sur le disque).
- [x] Médias enregistrés dans Room (`MediaEntity`) + dossier privé `captured_media/<type>/`, avec métadonnées (type, taille, horodatage, nom source).
- [x] Corrélation média ↔ conversation par fenêtre temporelle (±120 s) avec les notifications (`findNearest`) → renseigne `conversation` / `sender` / `linkedMessageId`.
- [x] `AudioActivity` lit désormais depuis la base (`mediaOfType(AUDIO)`), affiche l'expéditeur corrélé et lit depuis `localPath`.
- [x] Build `assembleDebug` OK
- [~] `onNotificationRemoved` : **écarté après analyse**. La disparition d'une notif WhatsApp est ambiguë (lecture du chat ≈ suppression, même code `reason`) → trop de faux positifs. La détection fiable reste le marqueur texte « Ce message a été supprimé » déjà capté par `onNotificationPosted`. Surtout : le modèle « on sauvegarde tout à l'arrivée » préserve déjà le contenu même si le message est effacé ensuite, donc détecter la suppression n'est pas nécessaire pour l'objectif.

## Phase 3 — Refonte UI / UX  ✅

- [x] `MainActivity` = hôte avec top bar + **bottom navigation** (Messages · Médias · Réglages), architecture à fragments (`ui/`)
- [x] Écran **Médias** : vignettes photo + frame vidéo (Coil), lecteur audio **inline**, filtres par type (chips Tout/Photos/Vidéos/Vocaux), plein écran photo (dialog) et vidéo (`VideoView`)
- [x] Thème **Material 3** propre (palette teal cohérente) + **dark mode** (`values-night`)
- [x] Onboarding permissions déplacé dans l'onglet **Réglages** (plus de `MANAGE_EXTERNAL_STORAGE` demandé d'office au lancement)
- [x] États vides soignés (messages + médias)
- [x] Build `assembleDebug` OK (ajout Coil + fragment-ktx)
- [ ] Timeline messages regroupée par conversation + badge « supprimé » : reporté (liste simple pour l'instant, largement suffisante)
- [ ] Seekbar / durée sur le lecteur audio : reporté (play/pause fiable en place)

## Phase 4 — Finitions

- [ ] Diagnostic permissions clair et actionnable
- [ ] Export (texte + éventuellement médias) amélioré
- [ ] Passe `./gradlew lint` propre
- [ ] Mise à jour finale de [CLAUDE.md](CLAUDE.md)

---

## Journal des étapes

> Une ligne par étape réalisée : date · phase · ce qui a changé · fichiers touchés.

- 2026-08-14 · Init · Création de `PLAN.md` et `CLAUDE.md`, audit complet du code existant.
- 2026-08-14 · CI · Release APK automatique. Correctif clé : `gradlew` n'avait pas le bit exécutable (`git update-index --chmod=+x`) → la CI échouait sur Linux (exit 126) depuis toujours. Workflow étendu : build + upload artifact + release « latest » avec APK. `build_cli/` ajouté au `.gitignore`. Release vérifiée (HTTP 200).
- 2026-08-14 · Phase 3 · Refonte UI/UX. `MainActivity` = hôte bottom-nav ; nouveaux fragments `ui/` : `MessagesFragment`, `MediaFragment` (+ `MediaAdapter`, vignettes Coil, audio inline, plein écran photo/vidéo), `SettingsFragment` (permissions, diagnostic, export, purge). Thème Material 3 + dark mode (`values-night`), palette teal. `AudioActivity`/`activity_audio`/`item_audio` supprimés. Deps ajoutées : Coil, coil-video, fragment-ktx, activity-ktx. Nouveaux layouts (fragments, item_media, bottom_nav, icônes). Build OK.
- 2026-08-14 · Phase 2 · Capture multi-médias. `AudioObserver` → `MediaObserver` (audio + photo + vidéo, WhatsApp + Business), copie dans `captured_media/<type>/` + insertion `MediaEntity` en base avec corrélation conversation (±120 s, `findNearest`). `AudioActivity`/`AudioAdapter` réécrits sur la base (`mediaOfType(AUDIO)`, lecture depuis `localPath`, expéditeur affiché). `MediaCaptureService`/`MainActivity` mis à jour. `onNotificationRemoved` écarté (ambigu, documenté). Build OK.
- 2026-08-14 · Phase 1 · Couche données unifiée. Nouveau package `com.notiftracker.data` : `Message` (déplacé), `MediaEntity` + `MediaType`, `AppDatabase` v3 (Message + Media, DAO en `Flow`, `Converters`), `TrackerRepository`. Suppression de `MessageDatabase.kt` et `Message.kt` (racine). `MainActivity` observe les messages via `Flow` + `repeatOnLifecycle` (plus de reload manuel) ; `NotificationService` et `MessageAdapter` mis à jour. Build OK.
- 2026-08-14 · Phase 0 · Fiabilisation capture + lecture audio. Fichiers : `AudioObserver.kt` (réécrit : compat FileObserver + scan périodique + dédup, sans `Thread.sleep`), `MediaCaptureService.kt` (nouveau foreground service), `NotificationService.kt` (démarre le service), `MainActivity.kt` (démarre le service + permission notifications), `AudioActivity.kt` (error listener + release onPause), `AndroidManifest.xml` (service + permissions FGS/notifications), `strings.xml` (libellés service + erreur). Build `assembleDebug` validé.

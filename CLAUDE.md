# CLAUDE.md — NotifTracker

Guide de référence pour travailler dans ce dépôt. À lire avant toute modification.
Voir [PLAN.md](PLAN.md) pour l'état d'avancement du refacto et les prochaines étapes.

## 1. Objectif du projet

App Android qui écoute les notifications WhatsApp (et WhatsApp Business) afin de
**conserver les messages avant qu'ils soient supprimés** par l'expéditeur :

- **Texte** — capturé via les notifications (`NotificationListenerService`).
- **Audio (vocaux)** — copié depuis le dossier média de WhatsApp vers un dossier privé de l'app.
- **Photo / vidéo** — objectif du refacto (pas encore implémenté).

> Usage strictement personnel / éducatif. On ne fait aucune exfiltration réseau :
> tout reste local sur l'appareil.

## 2. Stack technique

| Élément        | Valeur |
|----------------|--------|
| Langage        | Kotlin |
| UI             | Vues XML + Material 3 (`Theme.Material3.DayNight`) |
| minSdk         | 26 |
| targetSdk / compileSdk | 34 |
| JVM target     | 17 |
| Persistance    | Room (`androidx.room` 2.6.1, kapt) |
| Async          | Coroutines (`Dispatchers.IO`, `lifecycleScope`) |
| Build          | Gradle (wrapper `gradle-8.2`), `viewBinding` activé |

## 3. Commandes

```bash
./gradlew assembleDebug        # build APK debug
./gradlew installDebug         # installe sur l'appareil branché (adb)
./gradlew lint                 # analyse lint Android
./gradlew clean                # nettoyage
```

> Windows : utiliser `gradlew.bat`. Le wrapper est versionné à la racine.

## 4. Architecture actuelle

```
app/src/main/kotlin/com/notiftracker/
├── data/                    # couche données (Phase 1)
│   ├── Message.kt           # entité Room (messages)
│   ├── MediaEntity.kt       # entité Room (audio/photo/vidéo) + enum MediaType
│   ├── AppDatabase.kt       # Room v3 : MessageDao + MediaDao (Flow) + Converters
│   └── TrackerRepository.kt # point d'accès unique, expose des Flow
├── NotificationService.kt   # NotificationListenerService : parse + insert via repo
├── MediaCaptureService.kt   # foreground service : héberge MediaObserver
├── MediaObserver.kt         # FileObserver compat + scan périodique → copie + MediaEntity
├── MessageAdapter.kt        # liste des messages (MainActivity)
├── MainActivity.kt          # écran principal : observe les messages via Flow
├── AudioActivity.kt         # écran audios : observe mediaOfType(AUDIO), MediaPlayer
└── res/                     # layouts, strings, colors, themes
```

Flux messages : `NotificationService.onNotificationPosted` → `extractPayload` →
`Message` (empreinte SHA-256 anti-doublon) → `TrackerRepository.insertMessage`.
`MainActivity` observe `repository.messages` (Flow) et se met à jour toute seule.

Flux médias : `MediaCaptureService` (foreground) fait tourner `MediaObserver`, qui
surveille les dossiers WhatsApp (Voice Notes, Audio, Images, Video ; WhatsApp +
Business), copie chaque nouveau fichier dans `captured_media/<type>/` et insère une
`MediaEntity` en base — en corrélant à la conversation via le message le plus proche
dans le temps (`findNearest`, ±120 s). `AudioActivity` observe `mediaOfType(AUDIO)`.

> Photos/vidéos sont capturées en base mais pas encore affichées : l'écran médias
> (vignettes, lecteur inline) arrive en Phase 3 (refonte UI).
> Rappel : la capture de fichiers dépend de l'auto-téléchargement WhatsApp.

## 5. Conventions

- **Langue** : commentaires, libellés UI et strings en **français**. Pas d'accents dans
  les constantes de détection (`deletedMarkers`) pour éviter les soucis d'encodage.
- **Strings** : jamais de texte en dur dans le code UI → passer par `res/values/strings.xml`.
- **Couleurs / thème** : utiliser les attributs Material 3 (`?attr/colorSurface`, etc.).
  Couleurs de statut nommées dans `res/values/colors.xml`.
- **Room** : incrémenter `version` à chaque changement de schéma. `fallbackToDestructiveMigration`
  est actif aujourd'hui (données jetées à chaque migration) — à remplacer par de vraies
  migrations quand les données comptent (voir PLAN.md).
- **Async** : jamais de `Thread.sleep` sur un thread de callback (FileObserver, main).
- **Compat** : `minSdk = 26` → vérifier la disponibilité des API. Ex. `FileObserver(File, mask)`
  n'existe qu'à partir de l'API 29.

## 6. Pièges connus (à traiter dans le refacto)

1. `FileObserver(File, mask)` — API 29+ seulement ; crash `NoSuchMethodError` sur API 26–28.
2. Aucun foreground service lancé alors que `FOREGROUND_SERVICE` est déclarée → la
   surveillance s'arrête quand le process est tué.
3. `Thread.sleep(500)` dans `AudioObserver.onEvent` bloque le thread d'événements.
4. Détection de suppression basée sur du texte marketing rare ; `onNotificationRemoved`
   n'est pas implémenté.
5. Audios copiés sans lien vers l'expéditeur / la conversation, et hors de Room.
6. Permission `MANAGE_EXTERNAL_STORAGE` demandée d'office au lancement (UX brutale + risque
   Play Store).

## 7. Permissions

- `BIND_NOTIFICATION_LISTENER_SERVICE` — cœur de la capture texte.
- Accès stockage (`MANAGE_EXTERNAL_STORAGE` / `READ_EXTERNAL_STORAGE`) — pour lire les
  dossiers média de WhatsApp.
- L'utilisateur doit activer manuellement l'accès aux notifications
  (`Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`).

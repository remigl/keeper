# Keeper

Application Android native (Kotlin, aucune bibliothèque tierce) qui **journalise les notifications reçues** dans des fichiers texte locaux. Passive, hors ligne, sans permission déclarée.

## Architecture

```
MainActivity               écran d'état / configuration (facultatif après la mise en route)
ViewerActivity             lecture des logs : jour actuel ou précédent, filtre par app, tri et plage horaire
AppIcons                   nom et icône des apps (affichage uniquement, cache en mémoire)
NotificationLoggerService  NotificationListenerService : observe, extrait, transmet l'entrée
Keeper                     singleton du processus → un seul LogStore (un verrou, un thread d'écriture)
log/LogStore               dossier, rotation quotidienne, écritures sérialisées + fsync, file d'attente en cas d'erreur
log/LogFormat              format texte d'une entrée (Kotlin pur, testé)
log/LogParser              relecture tolérante de ce format (Kotlin pur, testé)
LogProvider                ContentProvider non exporté, lecture seule, pour « Ouvrir le log »
```

Pas de base de données, pas de SharedPreferences, pas de WorkManager, pas de service au premier plan.

## Permissions

**Aucune `<uses-permission>`.** `INTERNET` est même retirée explicitement du manifest fusionné (`tools:node="remove"`).

- L'accès aux notifications est accordé par l'utilisateur dans *Paramètres → Accès aux notifications*. `BIND_NOTIFICATION_LISTENER_SERVICE` figure sur le service : c'est une protection détenue par le système seul, pas une permission demandée par l'app.
- Pas de `RECEIVE_BOOT_COMPLETED` : Android relie lui-même le listener après chaque démarrage.
- Pas de `POST_NOTIFICATIONS` : l'app n'affiche aucune notification.
- Pas de permission de stockage : les logs sont dans le stockage interne privé.
- Une déclaration `<queries>` (ce n'est pas une permission) rend visibles les apps qui ont une icône dans le lanceur, pour afficher leur nom et leur logo dans la visionneuse. Les autres (certains composants système) apparaissent avec leur nom de package et une pastille à initiale.

## Réception des notifications

`NotificationListenerService` (API officielle). Android lie le service quand l'accès est accordé, puis appelle `onNotificationPosted()` à chaque notification. Le service ne fait que lire `extras` : il n'appelle jamais `cancelNotification`, `snoozeNotification`, `setNotificationsShown`, ni aucune action.

Extraction : `EXTRA_TITLE` (sinon `EXTRA_TITLE_BIG`), `EXTRA_BIG_TEXT` (sinon `EXTRA_TEXT`), puis les lignes `EXTRA_TEXT_LINES` (style « boîte de réception ») s'il y en a.

Deux comportements ajoutés pour la fiabilité :

- **Re-publications identiques ignorées.** Les applis republient souvent la même notification (progression, lecteur média). Si titre et texte sont identiques à la dernière entrée de cette notification (même clé), rien n'est écrit. Seul un hash est gardé en mémoire (256 clés au plus), jamais le contenu. Une notification supprimée puis republiée est de nouveau journalisée.
- **Rattrapage à la connexion.** Quand Android (re)lie le service (après un démarrage, une mise à jour, l'arrêt du processus), les notifications encore affichées et publiées après la dernière écriture sont journalisées une fois.

## Stockage

`/data/data/io.github.remigl.keeper/files/NotificationLogger/` (`Context.filesDir`), créé au premier lancement avec le fichier du jour.

- Privé à l'application : aucune autre app n'y a accès, aucune permission n'est nécessaire.
- Sauvegarde désactivée (`allowBackup="false"`) et `data_extraction_rules.xml` exclut tout, sauvegarde cloud **et** transfert d'appareil à appareil.
- Le bouton « Fichier brut » de la visionneuse passe par un `ContentProvider` **non exporté** avec une autorisation de lecture temporaire, limitée au fichier choisi (`FLAG_GRANT_READ_URI_PERMISSION`).
- En développement : `adb exec-out run-as io.github.remigl.keeper cat files/NotificationLogger/2026-11-17.log` (build debug uniquement).

### Format

```text
[2026-11-17 14:32:08 +01:00]
Package: com.example.app
Title: Nouveau message
Text: Bonjour, tu as reçu un nouveau message.
  deuxième ligne du message
----------------------------------------
```

- Heure = `postTime` de la notification, dans le fuseau du téléphone, **avec le décalage UTC** : deux entrées à 02:30 lors du passage à l'heure d'hiver restent distinctes.
- Une ligne qui commence en colonne 0 est toujours structurelle (horodatage, `Clé: `, séparateur). Les lignes suivantes d'un champ sur plusieurs lignes sont indentées de deux espaces, donc le contenu d'une notification ne peut pas imiter un séparateur ou une nouvelle entrée.
- Titre ou texte absent → champ vide (la clé est toujours présente).
- Caractères de contrôle supprimés, retours à la ligne normalisés, Unicode conservé (UTF-8).
- Champ limité à 10 000 caractères, avec la mention `[…N caractères tronqués]`.

## Visionneuse

« Voir les notifications » lit directement le fichier du jour ou le précédent (onglets *Aujourd'hui* / *Hier*, ou la date du fichier si ce n'est pas la veille) :

- pastilles par application, avec logo, nom et nombre de notifications, triées de la plus bavarde à la moins bavarde ;
- tri *plus récentes* / *plus anciennes* ;
- plage horaire « de … à … » (une plage comme 22:00 – 06:00 passe minuit) ;
- un appui sur une notification déplie son texte ;
- « Fichier brut » ouvre le `.log` dans une autre app via une autorisation temporaire.

La lecture et la résolution des icônes se font hors du thread principal ; rien n'est écrit, rien n'est gardé après la fermeture de l'écran.

## Rotation

Faite **dans l'écriture**, au moment où une notification arrive :

1. date du jour = `ZonedDateTime.now()` dans le fuseau actuel (suit les changements de fuseau et d'heure) ;
2. si c'est le même jour que la dernière vérification et que le fichier existe → écriture directe (un seul `stat`) ;
3. sinon : créer le dossier et le fichier du jour, lister les `yyyy-MM-dd.log`, garder **le fichier du jour + le plus récent des autres**, supprimer le reste.

En usage normal, c'est « hier + aujourd'hui ». Après 5 jours téléphone éteint, on garde le dernier jour enregistré et le jour actuel. Aucun fichier vide n'est inventé pour les jours manquants, et il n'y a jamais plus de deux fichiers. Si l'horloge recule, on ne dépasse pas non plus deux fichiers. Les fichiers autres que `.log` ne sont jamais touchés.

## Batterie

- Aucun timer, alarme, wakelock, job périodique, service au premier plan ni récepteur de démarrage.
- Le code ne s'exécute que lorsqu'Android appelle le listener.
- Les écritures passent par **un seul thread, créé à la demande et arrêté après 10 s d'inactivité** : rien ne tourne tant qu'aucune notification n'arrive.
- Par notification : extraction des extras, un `stat`, un `open/write/fsync/close`. La rotation (listing du dossier) a lieu au plus une fois par jour et par processus.

## Sécurité et confidentialité

- Aucune connexion réseau possible (pas de permission `INTERNET`), pas d'analytics, pas de SDK, pas de dépendance d'exécution, pas de bloc de métadonnées des dépendances dans l'APK.
- Logs dans le stockage privé, exclus de toute sauvegarde ou transfert.
- Rien dans Logcat en release : R8 supprime tous les appels `android.util.Log`, et même en debug les erreurs ne contiennent que le type d'exception, jamais le contenu.
- `FLAG_SECURE` sur les écrans de l'app : pas de capture d'écran ni de miniature dans les apps récentes.
- Écriture : l'entrée complète est écrite en un seul `write()` puis `fsync`. Si le stockage est indisponible, jusqu'à 200 entrées restent en mémoire et sont écrites à la notification suivante.

## Limites d'Android (pas de contournement)

- **Avant le premier déverrouillage** qui suit un redémarrage, le stockage chiffré n'est pas disponible et Android ne lie pas le listener. Les notifications encore affichées à ce moment sont rattrapées ; celles déjà disparues sont perdues.
- **Android 13+, app installée hors Play Store** : l'interrupteur d'accès aux notifications est grisé (« paramètre restreint »). *Infos de l'application → ⋮ → Autoriser les paramètres restreints*, puis activer l'accès.
- **Android 15+** : le système masque aux listeners non privilégiés le contenu de certaines notifications sensibles (codes à usage unique). Elles sont journalisées telles qu'Android les transmet.
- Certains constructeurs (Xiaomi, Huawei…) tuent agressivement les apps. Le listener est en général épargné ; sinon Android le relie au prochain événement et le rattrapage s'applique. L'app ne demande pas l'exemption d'optimisation de la batterie.
- La sécurité du stockage privé repose sur le sandbox Android : un téléphone rooté ou une sauvegarde `adb` d'un build debug peuvent y accéder.

## Compilation

Android Studio (Ladybug ou plus récent), JDK 17+. AGP 8.7.3, Kotlin 2.0.21, Gradle 8.11.1 (wrapper inclus), `compileSdk`/`targetSdk` 35, `minSdk` 27 (Android 8.1).

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # tests JVM : rotation, format, concurrence, erreurs
./gradlew assembleRelease        # R8 + shrinkResources ; configurer une signature
```

Tester sans attendre une vraie notification :

```bash
adb shell cmd notification post -S bigtext -t "Titre test" tag1 "Texte de test"
```

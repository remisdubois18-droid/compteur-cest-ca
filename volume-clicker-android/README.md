# Volume Clicker — Android (version sans autorisation d'accessibilité)

Application Android indépendante, dans le dossier `volume-clicker-android/` de ce dépôt. Les autres projets du dépôt ne sont pas modifiés.

## Fonctionnement
- Comptage séparé des boutons physiques **Volume +**, **Volume −** et total
- Comptage en premier plan via les événements de touches Android
- **Mode expérimental** pour les autres applications et l'écran verrouillé : `MediaSession` active, `VolumeProvider` de type remote, service de premier plan avec notification persistante et bouton Arrêter
- Sauvegarde locale des compteurs, remise à zéro avec confirmation
- Aucune permission d'accessibilité, pas d'accès Internet, pas de compte, pas de collecte de données
- Instructions d'optimisation batterie pour OPPO/ColorOS

### Restrictions importantes
Cette méthode **n'est pas garantie** pour tous les modèles Android. Avec écran éteint, ColorOS peut consommer la pression ou arrêter le service malgré une notification. Une session multimédia active peut également **modifier le routage des commandes de volume** et perturber la gestion du volume d'autres applications. Les répétitions de touches pressées longuement peuvent être comptées plusieurs fois. Cette application n'est pas un lecteur multimédia ; le mode `MediaSession` sert uniquement au test du comportement des boutons et doit être arrêté lorsqu'il n'est pas utilisé.

Le blocage Play Protect précédent était cohérent avec la présence du service d'accessibilité dans l'APK distribué depuis Internet. Cette nouvelle version ne déclare **plus** ce service, mais cela ne garantit pas l'absence de toute alerte Play Protect : n'en désactive pas les protections.

## Génération de l'APK
1. Ouvrir **Actions → Volume Clicker - Android APK** dans GitHub
2. Choisir le dernier build vert, puis l'artefact **VolumeClicker-APK**
3. Décompresser l'archive ; installer `app-debug.apk`
4. Sur OPPO, depuis les informations de l'app, autoriser l'arrière-plan et adapter les réglages batterie si disponibles
5. Ouvrir l'app et cliquer **Démarrer le comptage**

Le `app-debug.apk` est signé par la clé **debug** du runner GitHub. À chaque build, cette clé peut changer : désinstaller l'ancienne version avant l'installation si Android signale une signature incompatible.

## Tests essentiels sur appareil réel
1. 10 pressions Volume +, 10 Volume −, sans pression longue ; vérifier 10/10.
2. Afficher une autre application, répéter le test.
3. Verrouiller sans éteindre l'écran si l'appareil le permet, puis tester.
4. Éteindre l'écran par verrouillage, tester 10 pressions supplémentaires, rallumer et vérifier.
5. Essayer pendant une lecture audio pour observer une éventuelle interférence avec le volume.
6. Redémarrer l'application ; vérifier la persistance du compteur.

**Aucune validation sur un OPPO réel n'a encore été effectuée.**

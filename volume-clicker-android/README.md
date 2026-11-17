# Volume Clicker (Android)

Application native Android indépendante des autres projets de ce dépôt.

## Compilation
Dans GitHub : **Actions → Volume Clicker - Android APK → Run workflow**. Ouvrir le run terminé, puis télécharger l'artefact **VolumeClicker-APK** et extraire `app-debug.apk`.

## Utilisation
Installer l'APK, ouvrir l'application, démarrer le comptage, puis activer manuellement **Volume Clicker** dans **Paramètres Android → Accessibilité**.
Le compteur fonctionne dans d'autres applications lorsque le service reçoit les touches physiques.
Les compteurs sont stockés localement. Une pression prolongée compte une fois ; les boutons de volume sont interceptés pendant le comptage.

**Limite :** écran verrouillé/éteint non garanti : certains téléphones interceptent les boutons au niveau du système. Le service d'accessibilité ne peut pas contourner cette limitation. Aucun accès Internet, aucune collecte du contenu de l'écran.

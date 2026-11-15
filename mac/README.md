# Livre de voyage – application Mac

Version Mac du livre de voyage : on choisit les photos **dans Photos**, l'application rédige
les légendes et coche les **personnes déjà nommées dans Photos**, puis fabrique un **PDF**
à partager (AirDrop, Messages, Mail, WhatsApp…).

## Installer

1. Sur GitHub, onglet **Actions** › « Construire l'application Mac » › dernière exécution réussie,
   fichier **LivreDeVoyage-Mac** (ou onglet **Releases** une fois fusionné sur `main`).
2. Ouvrez le .zip, glissez **Livre de voyage** dans le dossier **Applications**.
3. Premier lancement : l'application n'est pas signée par Apple. macOS refuse d'abord de l'ouvrir ;
   allez dans **Réglages Système › Confidentialité et sécurité**, en bas : **« Ouvrir quand même »**.

## Autorisations demandées

- **Photos** : pour choisir les photos et lire leur date et leur position GPS.
- **Automatisation › Photos** : pour « Importer la sélection de Photos ».
- **Accès complet au disque** (à donner soi-même) : pour lire les noms des **Personnes**.
  Réglages Système › Confidentialité et sécurité › Accès complet au disque › **+** › Livre de voyage.
  Photos ne donne pas ces noms aux autres applications : la base de la photothèque est lue
  en lecture seule, sur une copie, sans rien modifier.

Après chaque mise à jour de l'application, macOS peut redemander ces autorisations.

## Utilisation

1. **Nouvelle histoire** (⌘N) : titre, région, qui était là.
2. **Ajouter des photos** : depuis la photothèque, ou sélectionnez-les dans Photos puis
   **Sélection de Photos**.
3. **Personnes** : coche sur chaque photo les visages nommés dans Photos.
4. **Légendes** : sources libres (Wikipédia, gratuit) ou Claude (clé API dans les Réglages, ⌘,).
   La légende part du GPS ; sans GPS, indiquez le lieu ou le nom du cliché.
5. Cliquez une photo pour corriger sa légende ; ⌘← / ⌘→ pour la précédente / suivante.
   ⌘-clic pour en sélectionner plusieurs, ⌫ pour les retirer.
6. **Feuilleter**, puis **PDF et partage** (haute qualité ou léger).

Les histoires sont gardées dans `~/Library/Application Support/Livre de voyage/`.
Les photos restent dans Photos : le livre ne garde que leurs références.

## Technique

SwiftUI, PhotoKit, macOS 13 ou plus récent. Projet généré par XcodeGen (`mac/project.yml`),
compilé par GitHub Actions (`.github/workflows/mac.yml`).

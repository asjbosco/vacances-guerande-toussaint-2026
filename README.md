# Livre de voyage – Guérande, Toussaint 2026

Application Android qui transforme les plus belles photos du voyage en **livre numérique** :
chaque photo reçoit le **lieu**, un **court texte historique ou géographique** et les
**participants** présents, puis le livre se **partage** sur WhatsApp ou Facebook.

## Comment ça marche

1. **Choisir les photos** – l'application montre les photos de la galerie prises pendant les
   dates du voyage ; on coche les plus intéressantes.
2. **Rédiger les légendes** – pour chaque photo :
   - la position GPS enregistrée dans la photo donne l'adresse (ex. « Rue de Saillé, Guérande ») ;
   - l'application cherche les articles **Wikipédia** les plus proches de cet endroit ;
   - **Claude** regarde la photo, choisit l'article qui correspond et écrit 2 à 3 phrases
     sur l'intérêt du lieu. Sans clé API, le début de l'article Wikipédia est repris tel quel.
3. **Relire et compléter** – chaque légende est modifiable ; on coche les participants
   présents sur la photo (bouton « Tout le monde » pour aller vite).
4. **Feuilleter** – le livre se lit page par page, avec une couverture.
5. **Partager** – WhatsApp, Facebook (profil ou Page) ou toute autre application.
   Chaque photo est envoyée avec sa légende **incrustée en dessous**, pour que le texte
   reste visible même là où l'application ne reprend pas les légendes (Facebook).

## Réglages au premier lancement (⚙️)

- **Le voyage** : titre, région visitée, dates (pré-remplis pour Guérande, Toussaint 2026).
- **Participants** : Emmanuel, Gaëlle, Albéric, Maylis, Victor, Juliette et Charlotte sont déjà enregistrés ; on peut en ajouter ou en retirer.
- **Clé API Claude** : à créer sur <https://console.anthropic.com> (rubrique *API Keys*).
  Elle reste enregistrée uniquement sur le téléphone. Coût indicatif : environ 1 centime par photo.

## Bon à savoir

- Pour que le lieu soit trouvé, les photos doivent avoir été prises avec la **localisation
  activée** dans l'appareil photo. Sinon, Claude devine le lieu d'après l'image et la région.
- Les photos du livre sont copiées dans l'application (réduites à 2048 px) : les originaux
  de la galerie ne sont jamais modifiés.
- Facebook n'autorise pas une application à pré-remplir le texte d'une publication : c'est
  pour cela que les légendes sont incrustées dans les images.

## Technique

Kotlin, Jetpack Compose, SDK Java officiel d'Anthropic (modèle `claude-opus-5-5`),
API Wikipédia, Geocoder Android. Android 8.0 minimum.

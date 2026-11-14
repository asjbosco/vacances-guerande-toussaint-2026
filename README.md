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
   - **Photos voisines** : quand plusieurs photos sont prises au même endroit (moins de 300 m
     d'écart, réglable ; ou moins de 20 minutes d'écart pour les photos sans GPS), l'histoire du
     lieu n'est racontée qu'une fois. Les photos suivantes reçoivent une phrase sur ce qu'elles
     ajoutent (un détail, un autre point de vue).
3. **Reconnaître les visages** – l'application repère les visages sur les photos et
   reconnaît les personnes déjà nommées. Pour les visages inconnus, elle les regroupe et
   demande une seule fois par personne « Qui est-ce ? » (un proche de la liste, ou un nouveau
   nom ajouté à la liste). Elle coche ensuite elle-même les participants sur chaque photo ;
   il suffit de décocher les erreurs (l'application ne se servira plus de ce visage).
   Tout se fait **sur le téléphone** : aucune photo de visage n'est envoyée sur Internet.
   Les personnes nommées restent connues pour les voyages suivants.
4. **Relire et compléter** – chaque légende est modifiable ; on coche les participants
   présents sur la photo (bouton « Tout le monde » pour aller vite).
5. **Feuilleter** – le livre se lit page par page, avec une couverture.
6. **Partager** – WhatsApp, Facebook (profil ou Page) ou toute autre application.
   Chaque photo est envoyée avec sa légende **incrustée en dessous**, pour que le texte
   reste visible même là où l'application ne reprend pas les légendes (Facebook).
7. **Livre PDF imprimable** – dans « Partager », le bouton *Livre PDF imprimable* fabrique
   tout le livre au format A4 : une couverture (titre, dates, participants, première photo),
   puis une page par photo avec le lieu, la date, le texte et « Avec … ». On peut ensuite
   l'enregistrer (Drive, Fichiers), l'envoyer ou l'imprimer.

## Installer l'application sur le téléphone

À chaque fusion sur `main`, GitHub construit automatiquement l'application
(et vérifie aussi que chaque pull request compile) :

1. Sur GitHub, onglet **Releases** : ouvrez la dernière version (le plus simple depuis le téléphone).
   À défaut, onglet **Actions**, dernière exécution réussie (✅), fichier **LivreDeVoyage** (un .zip à ouvrir).
2. Téléchargez **LivreDeVoyage.apk**.
3. Ouvrez le fichier sur le téléphone Android et acceptez l'installation
   « depuis une source inconnue » quand Android le demande.

## Réglages au premier lancement (⚙️)

- **Le voyage** : titre, région visitée, dates (pré-remplis pour Guérande, Toussaint 2026).
- **Famille et proches** : Emmanuel, Gaëlle, Albéric, Maylis, Victor, Juliette et Charlotte sont déjà enregistrés
  comme participants du voyage. On peut ajouter d'autres proches (case « Du voyage » décochée) :
  ils ne figurent pas sur la couverture mais peuvent être reconnus sur les photos.
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
API Wikipédia, Geocoder Android, ML Kit (détection des visages) et modèle FaceNet en
TensorFlow Lite (empreinte des visages). Android 8.0 minimum.

Le modèle `facenet.tflite` provient du projet
[FaceRecognition_With_FaceNet_Android](https://github.com/shubham0204/FaceRecognition_With_FaceNet_Android)
(licence Apache 2.0), converti du FaceNet de David Sandberg (licence MIT). Il est téléchargé
automatiquement à la compilation ; pour compiler sur votre ordinateur, placez-le dans
`app/src/main/assets/`.

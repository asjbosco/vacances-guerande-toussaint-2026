# Livre de voyage – Guérande, Toussaint 2026

Application Android qui transforme les plus belles photos du voyage en **livre numérique** :
chaque photo reçoit le **lieu**, un **court texte historique ou géographique** et les
**participants** présents, puis le livre se **partage** sur WhatsApp ou Facebook.

## Comment ça marche

**Plusieurs histoires.** L'écran d'accueil liste vos histoires (un voyage, une fête…), chacune
avec son propre livre, gardé en mémoire. On peut en créer une nouvelle (qui part de zéro), en
rouvrir une pour la modifier plus tard, la **dupliquer** ou la supprimer (menu ⋮).

Dans une histoire :

1. **Choisir les photos** – toute la galerie, regroupée par jour (« Tout le jour » pour cocher
   une journée entière). Les photos déjà dans le livre sont grisées.
2. **Légendes** – l'application demande d'où elles doivent venir :
   - **Sources libres (Wikipédia)** : gratuit, texte repris de l'article le plus proche et raccourci ;
   - **Claude (Anthropic)** : la photo et les articles du lieu sont envoyés à Claude, qui rédige
     un texte court (clé API nécessaire, ~1 centime par photo).

   Dans les deux cas, la légende part de la **position GPS** de la photo. Sans GPS, l'application
   ne devine pas : elle vous demande d'indiquer le **lieu ou le nom du cliché** (icône 📍 barrée).
   Format de chaque légende :
   - **le lieu en gras** (le site précis s'il est connu, sinon la ville ou le village) ;
   - un texte de **2 lignes au plus, en style télégraphique** ;
   - la **date** et les **personnes** présentes.

   Photos voisines (moins de 300 m d'écart, réglable) : l'histoire du lieu n'est racontée qu'une
   fois ; les suivantes reçoivent un texte sur un autre aspect.
3. **Visages** – l'application repère les visages et reconnaît les proches déjà nommés. Pour les
   visages inconnus, elle les regroupe et demande une seule fois par personne « Qui est-ce ? ».
   Elle coche ensuite les personnes sur chaque photo ; il suffit de décocher les erreurs.
   Tout se fait **sur le téléphone**. Les proches nommés sont reconnus d'une histoire à l'autre.
4. **Relire** – touchez une photo pour corriger sa légende ; les boutons **Précédente / Suivante**
   passent directement à la photo d'à côté, et la liste revient sur la dernière photo ouverte.
   **Appui long** sur une photo pour en sélectionner plusieurs et les retirer d'un coup.
5. **Feuilleter** – le livre se lit page par page, avec une couverture.
6. **Partager** – WhatsApp, Facebook, **Quick Share** (vers un téléphone à côté) ou une autre
   application, chaque photo avec sa légende incrustée ; ou le **livre en PDF** (A4) :
   - *haute qualité* (~300 dpi, pour imprimer ou zoomer) ou *léger* (envoi rapide) ;
   - les photos sont reprises **depuis l'original de la galerie** et intégrées en JPEG, sans
     perte supplémentaire. Envoyé comme document, WhatsApp ne recompresse pas le PDF ;
     pour les photos seules, choisir « HD » dans WhatsApp avant l'envoi.

## Installer l'application sur le téléphone

À chaque fusion sur `main`, GitHub construit automatiquement l'application
(et vérifie aussi que chaque pull request compile) :

1. Sur GitHub, onglet **Releases** : ouvrez la dernière version (le plus simple depuis le téléphone).
   À défaut, onglet **Actions**, dernière exécution réussie (✅), fichier **LivreDeVoyage** (un .zip à ouvrir).
2. Téléchargez **LivreDeVoyage.apk**.
3. Ouvrez le fichier sur le téléphone Android et acceptez l'installation
   « depuis une source inconnue » quand Android le demande.

## Réglages (⚙️ sur l'écran d'accueil)

- **Famille et proches** : Emmanuel, Gaëlle, Albéric, Maylis, Victor, Juliette et Charlotte sont
  enregistrés au départ ; la liste s'agrandit à volonté et sert à toutes les histoires.
  Dans chaque histoire, l'icône ✎ indique qui était du voyage (affiché sur la couverture).
- **Clé API Claude** : à créer sur <https://console.anthropic.com> (rubrique *API Keys*).
  Elle reste enregistrée uniquement sur le téléphone.

## Bon à savoir

- Pour que le lieu soit trouvé automatiquement, les photos doivent avoir été prises avec la
  **localisation activée** dans l'appareil photo. Sinon, indiquez le lieu à la main.
- Le livre de la première version de l'application est repris automatiquement comme
  première histoire.
- Les photos du livre sont copiées dans l'application (réduites à 2048 px) : les originaux
  de la galerie ne sont jamais modifiés.
- Facebook n'autorise pas une application à pré-remplir le texte d'une publication : c'est
  pour cela que les légendes sont incrustées dans les images.

## Technique

Kotlin, Jetpack Compose, API Claude d'Anthropic en appel HTTPS direct (modèle `claude-opus-5-5`),
API Wikipédia, Geocoder Android, ML Kit (détection des visages) et modèle FaceNet en
TensorFlow Lite (empreinte des visages). Android 8.0 minimum.

Le modèle `facenet.tflite` provient du projet
[FaceRecognition_With_FaceNet_Android](https://github.com/shubham0204/FaceRecognition_With_FaceNet_Android)
(licence Apache 2.0), converti du FaceNet de David Sandberg (licence MIT). Il est téléchargé
automatiquement à la compilation ; pour compiler sur votre ordinateur, placez-le dans
`app/src/main/assets/`.

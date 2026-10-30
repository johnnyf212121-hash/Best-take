# Ověření verze 0.3.0

Datum: 18. 10. 2026 (Europe/Prague).

## Změny

- Viditelná ikona zvětšení i návratu do mřížky je o třetinu menší a má 65% neprůhlednost. Oblast pro klepnutí zůstává původní velikosti.
- Nevybrané položky mřížky nemají viditelný checkbox. Klepnutí na fotku přepíná výběr a zaškrtnutí. Ve zvětšeném náhledu zůstává checkbox stále viditelný.
- Text „Načítám náhled“ je odstraněn. Při otevírání se nejdříve použije dostupný malý náhled, pak kvalitnější dekódovaný snímek. Chyby otevření se stále zobrazují.
- Mřížka se při zvětšení skryje pomocí INVISIBLE, zůstává měřená a ponechává si buňky i jejich bitmapy. Výběr nemění dataset. Obnovení galerie nevymazává cache dostupných fotek; LRU zůstává omezená na 24 MiB, viditelné buňky drží vlastní reference. Nejde o neomezené načítání celé knihovny do paměti.
- V základním zvětšení přepne vodorovný tah snímek doleva/doprava. U přiblížené fotky tah posouvá výřez. Pinch, svislý ani zrušený tah nepřepínají snímek. Na hranici seznamu se nepřeskakuje na opačný konec. Šipky nadále fungují při jakémkoliv zoomu.
- Popisek původní fotografie se vykresluje poloprůhledně přes její viditelný spodní okraj. Nepřidává řádek do rozvržení a „Před / Po“ zachovává velikost, zoom i posun snímku.
- Aktualizovaná nápověda a český návod.

## Výsledný balíček

- `artifacts/Lepsi-moment-0.3.0.apk`, 22 440 970 bajtů.
- `cz.honza.lepsimoment`, versionCode 3, versionName 0.3.0; Android API 29+, target API 35, ARM64.
- SHA-256: `a31fae164b622ccbda57a3db84a8f499b87677e1e22d8d673e5b41fbcd61f596`.
- Podpis ověřen nástrojem apksigner a porovnán s 0.2.0; stejný certifikát `fa807f3f09b5fcf1ff780c2ef5eba8c8147bbdef232832ee6d8009426eb9d661`. Instalace aktualizuje předchozí verzi.
- Manifest stále neobsahuje INTERNET.

## Kontroly

Sestavení `assembleDebug`, `assembleDebugAndroidTest`, `testDebugUnitTest` a `lintDebug` dokončeno. Lint: 0 chyb, 19 upozornění (stejné kategorie jako v předchozí verzi). 10 jednotkových testů úspěšných.

8 integračních testů úspěšných na ARM64 emulátoru Android 15 / API 35, 1080 × 2400, 420 dpi. Běh trval 21,306 s. Protokol: `.tooling/instrumentation-03.log`.

- Regrese: detekce, skládání, export, EXIF, vracení změn, obnova Activity, živé doladění.
- Galerie: výběr klepnutím, skrytý checkbox nevybrané fotky, stále viditelný checkbox ve fullscreen, swipe oběma směry, ochrana před přepnutím při pinch/pan/svislém či zrušeném tahu, zachování výběru po obnově Activity, limit 6 fotek.
- Návrat do mřížky po dokončení nového snímku rozvržení zachová identické buňky i identické objekty dekódovaných bitmap.
- Opakované přepínání Před / Po při základním i přiblíženém a posunutém náhledu zachová shodné rozměry view, obrazový obdélník, zoom a posun. Popisek není samostatný řádek.

Vizuálně zkontrolovány `artifacts/gallery-grid-03.png`, `artifacts/gallery-fullscreen-03.png`, `artifacts/before-overlay-03.png`. Testovací fotografie jsou syntetická koláž portrétu NASA. Verze 0.3.0 zatím nebyla otestována na fyzickém Samsungu Galaxy S24.

## Předání

APK a aktualizovaný český návod nahrány na https://www.transfernow.net/dl/20261018dXNEZQ5J. Na stránce pro příjemce ověřena dostupnost obou souborů, platnost do 25. 10. 2026 19:56:39 SEČ. Potvrzení je v `artifacts/transfernow-0.3.0.png`; kontrolní součty v `artifacts/SHA256SUMS-0.3.txt`.

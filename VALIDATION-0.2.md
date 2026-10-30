# Ověření verze 0.2.0

Datum: 18. 10. 2026 (Europe/Prague).

## Změny

- Vlastní výběr 2–6 fotek s checkboxy a ikonou zvětšení v každé buňce. Zvětšený snímek nahradí jen oblast mřížky, podporuje pinch zoom, posun, výběr checkboxem, návrat do mřížky a přechod na sousední fotku.
- Čtení fotek respektuje plný i částečný přístup Androidu. Alternativou je import přes původní systémový Photo Picker.
- Rámečky obličejů lze skrýt nezávisle na výběru osoby. Hlavní fotka podporuje pinch zoom až 16×, posun a zachování zvětšení při změně výběru či rámečků.
- Doladění má horní polovinu pro obličej s okolím a spodní polovinu pro posuvníky. Obsah spodní poloviny lze posouvat. Větší rozměr obličeje zaujímá přibližně polovinu náhledového rámu; výřez je vystředěn na vybranou tvář.
- Živý náhled s cache zdrojových výřezů a sloučením rychlých požadavků; úpravy se během posouvání nekumulují. Použít vytvoří jediný krok historie, Zrušit ponechá předchozí potvrzené parametry.
- Nová velikost masky provádí erozi/dilataci hranice pomocí vzdálenostní transformace; geometrie samotného obličeje zůstává samostatná. Parametr se ukládá do session, staré session používají nulu.

## Výsledný balíček

- `artifacts/Lepsi-moment-0.2.0.apk`, 22 438 666 bajtů (~22,4 MB).
- Balíček `cz.honza.lepsimoment`, versionCode 2, versionName 0.2.0.
- Android API 29+, target API 35, ARM64.
- SHA-256: `1cddc1cad5ddbc87e9840d94cf58551f7de733dd0c8d17c18648643da6505362`.
- APK v2 podpis ověřen. SHA-256 podpisového certifikátu `fa807f3f09b5fcf1ff780c2ef5eba8c8147bbdef232832ee6d8009426eb9d661` je shodný s dodaným APK 0.1.0; instalace je aktualizací stejného balíčku.
- Manifest nadále neobsahuje INTERNET. Obsahuje čtení médií (READ_MEDIA_IMAGES, READ_MEDIA_VISUAL_USER_SELECTED, READ_EXTERNAL_STORAGE jen do API 32) a vlastní interní oprávnění AndroidX.

## Automatické kontroly

`assembleDebug`, `assembleDebugAndroidTest`, `testDebugUnitTest` a `lintDebug` úspěšně dokončeny. Lint: 0 chyb, 20 upozornění (novější knihovny, ARM64 bez ChromeOS x86, české řetězce bez lokalizace a záměrné levé/pravé umístění tlačítek v galerii).

**10 jednotkových testů: 10 úspěšných.** Původní geometrie a prolínání doplněny o erozi/dilataci, neměnnou geometrii uvnitř masky a omezení dostupností zdrojových pixelů.

**7 integračních testů: 7 úspěšných.** Finální běh na ARM64 emulátoru Android 15 / API 35, 1080 × 2400, 420 dpi, trval 15,935 s. To není údaj o rychlosti na telefonu uživatele.

- Původní lokální detekce více osob, samostatné kontury, výměna, nedotčená druhá osoba a okolí, export, EXIF orientace, vrácení a obnova Activity.
- Živý náhled se po opakovaných změnách nekumuluje a v kontrolním scénáři odpovídá finálnímu renderu pixel po pixelu; zohledňuje i další upravenou osobu.
- Skutečné události se dvěma prsty přiblíží hlavní snímek i zvětšenou fotku v galerii. Gesto nevybírá obličej. Klepnutí vybere obličej i při vypnutých rámečcích.
- Posuvníky mění náhled před potvrzením a neovlivní potvrzený editor ani historii. Náhled a spodní panel mají stejnou výšku. Obnovení Activity zachová otevřené doladění a parametry. Použít uloží masku do session a jeden krok historie; Undo a Cancel vrátí očekávaný stav.
- Galerie: zvětšení nevybírá fotku, checkbox vybere přímo v náhledu, zvětšení zůstává v oblasti mřížky, výběr přežije obnovu Activity a návrat do mřížky. Limit 6 fotek je vynucen.

## Vizuální kontrola a omezení

Snímky z emulátoru: `artifacts/gallery-grid-02.png`, `artifacts/gallery-fullscreen-02.png`, `artifacts/tune-live-02.png`. Testovací koláž obsahuje veřejně dostupný portrét NASA, nikoli reálnou skupinu s různými výrazy.

Uživatel potvrdil dobré fungování 0.1.0 na svém telefonu. Verze 0.2.0 zatím nebyla ověřena na fyzickém Samsungu S24. Chování oprávnění a výběru cloudových fotek v konkrétní One UI, rozsáhlá uživatelská galerie a vzhled výsledků na reálných sériích vyžadují vyzkoušení v telefonu. Výsledné JPG má stejné limity rozlišení a SDR jako 0.1.0.

## Předání

APK a český návod byly 18. 10. 2026 nahrány na TransferNow: https://www.transfernow.net/dl/20261018CAyxlGmM. Stránka pro stažení ověřena: oba soubory jsou dostupné, konec platnosti 25. 10. 2026 v 19:09:53 SEČ. Snímek potvrzení: `artifacts/transfernow-0.2.0.png`. Kontrolní součty předaných souborů jsou v `artifacts/SHA256SUMS-0.2.txt`.

Po plném běhu testů byl samostatně zopakován test galerie kvůli pořízení snímku po dokončení animace: úspěšný, 5,429 s. Na snímku je ověřeno skutečné přiblížení fotografie v oblasti mřížky. Produkční APK se tím nezměnilo.

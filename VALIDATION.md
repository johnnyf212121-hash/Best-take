# Ověření verze 0.1.0

Datum: 17. 10. 2026 (Europe/Prague).

## Výsledný balíček

- Soubor: `artifacts/Lepsi-moment-0.1.0.apk`
- Velikost: 22 259 072 bajtů (přibližně 22,3 MB).
- Balíček: `cz.honza.lepsimoment`, versionCode 1.
- Android API 29+, cílové API 35, pouze ARM64 (`arm64-v8a`).
- SHA-256: `b8445665ef3b1b1f6edf4c8a9add03dabd49fffd53094641627daf3a5753f275`.
- Podpis: ověřen nástrojem `apksigner`, APK Signature Scheme v2, lokální vývojový certifikát Android Debug. Nejde o produkční podpis pro obchod.
- Finální sloučený manifest neobsahuje oprávnění INTERNET, přístup k celé galerii ani zápis do úložiště. Jediné deklarované uses-permission je vlastní oprávnění AndroidX pro interní přijímače.

## Automatické kontroly

`assembleDebug`, `assembleDebugAndroidTest`, `testDebugUnitTest` a `lintDebug` úspěšně dokončeny. Pro sestavování v tomto adresáři použijte `sh scripts/build-local.sh`, který sjednocuje umístění lokálního vývojového podpisu.

**8 jednotkových testů: 8 úspěšných, 0 chyb.**

- Identická náhrada zachová všechny pixely.
- Vnitřek masky dostane zdrojové pixely, okolí se nezmění a okraj se prolne.
- Zarovnání zvládá známý posun, rotaci a změnu velikosti.
- Ruční doladění se provádí kolem středu očí.
- Korekce barvy odstraní jednotný barevný posun v testovací ploše.
- Oříznutý zdroj nezpůsobí čtení mimo obraz ani přepsání nepokrytých pixelů.
- Bez obou očí se zarovnání odmítne.
- Normalizovaná geometrie se správně přepočítá do rozlišení fotografie.

**3 integrační testy na Androidu: 3 úspěšné, 0 chyb.**

Emulátor ARM64, Android 15 / API 35. Poslední běh trval přibližně 9,3 s; není to údaj o rychlosti zpracování na Samsungu.

- Detekce dvou tváří, samostatné kontury obou tváří, lokální výměna jedné, nedotčená druhá tvář, export JPG přes MediaStore, zachování původního souboru a absence oprávnění INTERNET.
- Načtení do editoru, výměna, vrácení změny a zachování stavu při znovuvytvoření Activity.
- JPEG s fyzicky otočenými pixely a EXIF orientací: načtení do správné orientace, správné rozměry a detekce obou tváří.

Testy používají portrét NASA v syntetické koláži a umělou barevnou značku jako kontrolní změnu. V aplikaci pro uživatele tento testovací obrázek není.

## Ruční kontrola v emulátoru

- Instalace a spuštění výsledného APK.
- Vizuální kontrola úvodu a editoru při 1080 × 2400 / 420 dpi a při kompaktním nastavení 1080 × 2340 / 480 dpi.
- Hlavní tlačítka na úvodu zůstávají dostupná i při menší výšce; horní obsah lze posouvat.
- Úplné zastavení aplikace, nové spuštění a „Pokračovat v poslední úpravě“ obnovily fotky i výměnu obličeje.
- Tlačítko „Uložit novou fotku“ v takto obnoveném editoru zobrazilo potvrzení o uložení nového JPG 1024 × 512 px.

Snímky z kontrol: `artifacts/home.png`, `artifacts/editor-test.png`, `artifacts/restored-editor.png`. Editor obsahuje syntetickou testovací koláž; nejde o ukázku reálné série s různými výrazy.

Lint: **0 chyb**, 12 upozornění: 6 na dostupné novější verze knihoven, 1 na úmyslně omezenou architekturu ARM64 (ChromeOS), 3 na drobné alokace při kreslení a 2 na české řetězce bez lokalizačních resources. Žádné upozornění na chybějící pravidla zálohování.

## Dosud neověřeno

- Skutečný Samsung Galaxy S24 ani konkrétní verze jeho One UI.
- Přirozenost nahrazení výrazů na uživatelových reálných skupinových fotografiích.
- Extrémní osvětlení, zakrytí obličeje, HEIC/Ultra HDR a velké 50MP snímky jako samostatné testovací scénáře.
- Záruka kvality srovnatelné s proprietární funkcí Google Best Take.

Před běžným používáním je potřeba zkusit několik vlastních sérií a zkontrolovat výsledky ve zvětšení. Omezení exportu a návod jsou v README.md.
